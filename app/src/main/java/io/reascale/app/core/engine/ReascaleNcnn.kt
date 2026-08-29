package io.reascale.app.core.engine

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * ReascaleNcnn — 自己实现的 ncnn Vulkan JNI 桥接
 *
 * 对应 libreascale_ncnn.so（RegisterNatives 注册）
 * 包名 + 类名必须与 JNI_OnLoad 注册的类名一致
 */
class ReascaleNcnn {

    companion object {
        init {
            System.loadLibrary("reascale_ncnn")
        }
    }

    /**
     * C++ 推理进度回调（每完成一个 tile 回调一次，0..1）
     * [FIX 2026-08-17] tile 级实时进度，替代原先"一段段跳"
     */
    interface NcnnProgressListener {
        fun onProgress(p: Float)
    }

    // 原生句柄
    private var nativeHandle: Long = 0

    // ── JNI 接口（签名与 C++ gMethods 完全对齐）──

    /** gpuid: -1=CPU, >=0=GPU, ttaMode: true=启用, numThreads: >0 */
    private external fun nativeCreate(gpuid: Int, numThreads: Int, ttaMode: Boolean): Long
    /** [PERF 2026-08-29] 查询设备 GPU 数量（>0 表示可用 Vulkan） */
    private external fun nativeGpuCount(): Int
    /** 从 assets 加载 .param + ByteArray 加载 .bin */
    private external fun nativeLoadFromAssets(
        handle: Long,
        assetManager: AssetManager,
        paramPath: String,
        binData: ByteArray
    ): Boolean
    /** 从文件系统加载 .param + .bin（用户导入模型） */
    private external fun nativeLoadFromFile(
        handle: Long,
        paramPath: String,
        binPath: String
    ): Boolean
    /** 推理（listener 可为 null；每完成一个 tile 回调 onProgress(done/total)） */
    private external fun nativeProcess(
        handle: Long,
        input: Bitmap,
        output: Bitmap,
        scale: Int,
        noise: Int,
        tileSize: Int,
        prepadding: Int,
        listener: NcnnProgressListener?
    ): Boolean
    private external fun nativeSetScale(handle: Long, scale: Int)
    private external fun nativeSetTileSize(handle: Long, tile: Int)
    private external fun nativeGetTileSize(handle: Long): Int
    private external fun nativeSetPrepadding(handle: Long, pad: Int)
    private external fun nativeSetNumThreads(handle: Long, threads: Int)
    private external fun nativeDestroy(handle: Long)

    // ── 公开 API ──

    /** [PERF 2026-08-29] 设备可用 GPU 数（0 = 无 Vulkan，用 CPU） */
    fun gpuCount(): Int = runCatching { nativeGpuCount() }.getOrDefault(0)

    fun init(gpuid: Int = -1, ttaMode: Boolean = false, numThreads: Int = 4) {
        nativeHandle = nativeCreate(gpuid, numThreads, ttaMode)
    }

    fun loadFromAssets(assetManager: AssetManager, paramPath: String, binData: ByteArray): Boolean {
        return nativeLoadFromAssets(nativeHandle, assetManager, paramPath, binData)
    }

    /** 从文件系统加载模型（用户导入的 ncnn 模型） */
    fun loadFromFile(paramPath: String, binPath: String): Boolean {
        return nativeLoadFromFile(nativeHandle, paramPath, binPath)
    }

    fun process(
        input: Bitmap,
        scale: Int,
        noise: Int = -1,
        tileSize: Int = 0,
        prepadding: Int = 0,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap {
        // [FIX 2026-08-09] 必须先同步 native 的 s->scale，否则 native 尺寸校验
        // 用默认值 2 去比对实际输出（如 4x），返回 false → "ncnn process 失败"
        setScale(scale)
        val outW = (input.width * scale).coerceAtLeast(1)
        val outH = (input.height * scale).coerceAtLeast(1)
        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val listener = if (onProgress != null) {
            object : NcnnProgressListener {
                override fun onProgress(p: Float) { onProgress(p) }
            }
        } else null
        val ok = nativeProcess(nativeHandle, input, output, scale, noise, tileSize, prepadding, listener)
        if (!ok) throw IllegalStateException("ncnn process 失败")
        return output
    }

    fun setScale(scale: Int) = nativeSetScale(nativeHandle, scale)
    fun setTileSize(tile: Int) = nativeSetTileSize(nativeHandle, tile)
    fun getTileSize(): Int = nativeGetTileSize(nativeHandle)
    fun setPrepadding(pad: Int) = nativeSetPrepadding(nativeHandle, pad)
    fun setNumThreads(threads: Int) = nativeSetNumThreads(nativeHandle, threads)

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }
}