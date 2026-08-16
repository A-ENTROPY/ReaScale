package io.reascale.app.debug

import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内日志总线 —— 用于把运行日志塞到 UI 界面，方便用户复制粘贴给开发者
 *
 * 设计目标（2026-08-02 P0 排查）：
 * - 用户没法用 adb logcat → 必须把诊断信息塞到 app 内可见界面
 * - 关键路径（ImageProcessor / OnnxEngine / StubEngine / App 启动）直接调 bus.log()
 * - UI 端 LogViewerScreen 通过 entries StateFlow 订阅，按时间倒序展示
 * - 长按 / 按钮一键复制全部日志
 *
 * 不依赖 Context，应用启动即可用（ReaScaleApp.onCreate 第一行就 init）。
 * 容量上限：MAX_ENTRIES 条，超过自动丢弃最老的（FIFO）
 */
object LogBus {

    enum class Level(val tag: String, val priority: Int) {
        DEBUG("D", Log.DEBUG),
        INFO("I", Log.INFO),
        WARN("W", Log.WARN),
        ERROR("E", Log.ERROR)
    }

    data class Entry(
        val ts: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 2000

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** 落盘目录（可空，setSinkDir 时设置） */
    @Volatile private var sinkDir: File? = null
    @Volatile private var sinkFile: File? = null

    private val lock = Any()

    private val logcatMirrorStarted = AtomicBoolean(false)
    private val crashHandlerInstalled = AtomicBoolean(false)
    private val mirroring = ThreadLocal.withInitial { false }

    /**
     * 安装全局崩溃捕获 + 启动 logcat 后台镜像。
     *
     * 为什么需要（2026-08-09 排查）：
     * - LogBus.log() 只能收显式调用，但 JNI/native 崩溃（SIGABRT/SIGSEGV）、
     *   System.loadLibrary 失败、未捕获异常都不走这里 → 日志界面啥都看不到
     * - 这里补三层：
     *   1. Thread.setDefaultUncaughtExceptionHandler —— 捕获未捕获 Java 异常
     *   2. LogcatCollectionThread —— 后台读 logcat 'AndroidRuntime'/'DEBUG'/'libc'
     *      镜像 native 崩溃的 abort message / backtrace
     *   3. 崩溃时同步落盘（append 到 sinkFile），避免进程死后内存日志丢失
     *
     * 在 ReaScaleApp.onCreate 第一行调用。
     */
    fun installCrashCapture() {
        synchronized(lock) {
            if (!crashHandlerInstalled.compareAndSet(false, true)) return
            // 1) 未捕获异常 handler（链式，不覆盖之前的）
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val full = Log.getStackTraceString(throwable)
                    log(Level.ERROR, "UncaughtException",
                        "线程 ${thread.name} 崩溃:\n$full")
                    flushSink()
                } catch (_: Throwable) {
                    // 崩溃 handler 里不能再抛
                }
                prev?.uncaughtException(thread, throwable)
                    ?: run {
                        // 无前置 handler：确保进程按系统默认行为退出
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
            }
        }
        // 2) logcat 后台镜像（native 崩溃也能抓到 abort message）
        if (logcatMirrorStarted.compareAndSet(false, true)) {
            val t = Thread({ logcatMirrorLoop() }, "LogBus-LogcatMirror")
            t.isDaemon = true
            t.start()
        }
    }

    /** 后台循环：读 logcat，把本进程相关的崩溃/错误镜像进 LogBus */
    private fun logcatMirrorLoop() {
        val myPid = Process.myPid()
        try {
            // 只读 AndroidRuntime / DEBUG / libc 三个 tag（崩溃都在这），避免刷屏
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", "-T", "1",
                    "AndroidRuntime:E", "libc:E", "DEBUG:E", "ncnn:V", "ReaScaleNcnn:V", "NcnnEngine:V")
            )
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            while (true) {
                val line = reader.readLine() ?: break
                // 排除自己镜像产生的日志，防递归
                if (line.contains("Logcat")) continue
                val lower = line.lowercase(Locale.US)
                // 只镜像本进程 + 崩溃关键字
                val mine = line.contains(" $myPid ") || line.contains("($myPid)")
                val crash = lower.contains("fatal") || lower.contains("unsatisfiedlink") ||
                    lower.contains("abort message") || lower.contains("sigsegv") ||
                    lower.contains("sigabrt") || lower.contains("jni detected") ||
                    lower.contains("jni") || lower.contains("dlopen") ||
                    lower.contains("process died") || lower.contains("was decommissioned")
                // [FIX 2026-08-17] ReaScaleNcnn 的 C++ 诊断日志（probe-*/tile@/process）也镜像进 LogBus，
                // 便于用户侧排查 ncnn 推理问题（探测/写回参数无法从 Kotlin 侧读取）
                val reascaleDiag = lower.contains("probe-") || lower.contains("tile@") ||
                    lower.contains("process:") || lower.contains("reascale")
                if (mine && (crash || reascaleDiag)) {
                    mirroring.set(true)
                    try {
                        val clean = if (line.length > 400) line.take(400) + "…" else line
                        log(Level.ERROR, "Logcat", clean)
                    } finally {
                        mirroring.set(false)
                    }
                }
            }
        } catch (_: Throwable) {
            // 镜像线程静默退出（logcat 不可用时不强求）
        }
    }

    /** 强制把内存日志落盘（崩溃前调用，防止丢失） */
    fun flushSink() {
        synchronized(lock) {
            val f = sinkFile ?: return
            runCatching {
                val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                _entries.value.forEach { e ->
                    f.appendText("${sdf.format(Date(e.ts))} ${e.level.tag}/${e.tag}: ${e.message}\n")
                }
            }
        }
    }

    /** 设置落盘目录（ReaScaleApp.onCreate 里调一次） */
    fun setSinkDir(dir: File) {
        synchronized(lock) {
            sinkDir = dir.apply { mkdirs() }
            sinkFile = File(dir, "reascale_log.txt")
        }
    }

    /** 主入口 */
    fun log(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            val list = _entries.value
            val newList = if (list.size >= MAX_ENTRIES) {
                list.drop(list.size - MAX_ENTRIES + 1) + entry
            } else {
                list + entry
            }
            _entries.value = newList
            // 落盘（可选）
            sinkFile?.let { f ->
                runCatching {
                    val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.ts))
                    f.appendText("$ts ${entry.level.tag}/${entry.tag}: ${entry.message}\n")
                }
            }
        }
        // 镜像线程的日志不再输出到 logcat（防递归死循环）
        if (mirroring.get() == true) return
        // 同时输出到 logcat 方便 adb 抓
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t == null) msg else "$msg\n${Log.getStackTraceString(t)}"
        log(Level.ERROR, tag, full)
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            sinkFile?.writeText("")
        }
    }

    /** 拼成可复制文本 */
    fun snapshotText(): String {
        val sb = StringBuilder()
        sb.appendLine("# ReaScale debug log")
        sb.appendLine("# captured at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("# entries: ${_entries.value.size}")
        sb.appendLine()
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        _entries.value.forEach { e ->
            sb.append(sdf.format(Date(e.ts))).append(' ')
            sb.append(e.level.tag).append('/').append(e.tag).append(": ")
            sb.appendLine(e.message)
        }
        return sb.toString()
    }

    /** 落盘文件路径（用于分享/导出） */
    fun sinkFilePath(): String? = sinkFile?.absolutePath
}