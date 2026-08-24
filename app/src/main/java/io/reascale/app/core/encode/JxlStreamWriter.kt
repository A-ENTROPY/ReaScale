package io.reascale.app.core.encode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * 流式 JPEG XL 编码器（JNI → dlopen libjxl.so 0.9.x）
 *
 * 与 [com.awxkee.jxlcoder.JxlCoder.encode] 的区别：
 * - JxlCoder.encode 需要整张 Bitmap（整帧驻留内存，>200MB 位图无法处理）
 * - 本类按输出行流式喂入 libjxl 的 chunked frame API，内存峰值 ≈ 编码器流水线深度
 *
 * 线程模型（[encodeStreaming] 内部）：
 * - nativeStart（JxlEncoderAddChunkedFrame）在独立协程执行：libjxl 0.9.x 会同步
 *   通过回调拉取像素数据（阻塞等待行就绪），喂行协程逐个投喂。
 * - 全部行喂完后 nativeFinishInput 唤醒编码侧，nativeDrain 循环取输出字节。
 *
 * 用法：
 * ```
 * JxlStreamWriter.encodeStreaming(w, h, quality, lossless,
 *     rowProvider = { y -> rgbRowOf(y) },        // 每行 width*3 字节
 *     sink = { bytes, len -> outputStream.write(bytes, 0, len) }
 * )
 * ```
 */
object JxlStreamWriter {

    init {
        System.loadLibrary("jxl_stream_writer")
    }

    /** 返回 libjxl 版本号（如 9002 = 0.9.2）；<0 表示加载失败 */
    external fun nativeVersion(): Int

    external fun nativeCreate(width: Int, height: Int, quality: Int, lossless: Boolean): Long

    /** 喂入一行 RGB（width*3 字节），协程安全 */
    external fun nativeFeedRow(handle: Long, y: Int, row: ByteArray)

    /** 喂入一行的一部分（xOffset 像素起），用于分块拼接输出行 */
    external fun nativeFeedRowAt(handle: Long, y: Int, xOffset: Int, row: ByteArray)

    /** 通知所有行已喂完 */
    external fun nativeFinishInput(handle: Long)

    /** 启动编码（AddChunkedFrame）。0=成功 -1=失败 */
    external fun nativeStart(handle: Long): Int

    /**
     * 驱动一次编码输出。
     * 返回：>0 已写字节数；0 队列取完；-1/-2 错误
     */
    external fun nativeDrain(handle: Long, out: ByteArray, cap: Int): Int

    /** 追加几次 FlushInput（求稳，大帧多轮产出） */
    external fun nativeFlushExtra(handle: Long): Int

    external fun nativeDestroy(handle: Long)

    /**
     * 流式编码：把 [height] 行 × [width] 像素的 RGB 源编码为 JPEG XL。
     *
     * @param rowProvider 第 i 行 RGB 数据（width*3 字节）
     * @param sink 输出字节块（bytes, 有效长度）
     */
    suspend fun encodeStreaming(
        width: Int,
        height: Int,
        quality: Int = 90,
        lossless: Boolean = false,
        rowProvider: (Int) -> ByteArray,
        sink: (ByteArray, Int) -> Unit
    ) {
        val handle = nativeCreate(width, height, quality, lossless)
        if (handle == 0L) throw IllegalStateException("JXL 流式编码器创建失败")
        try {
            // 编码线程：AddChunkedFrame 可能阻塞等待行就绪（0.9.x 同步拉取）
            val encScope = CoroutineScope(Dispatchers.Default)
            val encJob = encScope.async {
                val ret = nativeStart(handle)
                if (ret != 0) throw IllegalStateException("JXL 编码启动失败")
            }

            // 喂行（与编码并发；编码回调阻塞等待行就绪）
            withContext(Dispatchers.Default) {
                for (y in 0 until height) {
                    nativeFeedRow(handle, y, rowProvider(y))
                    if (y % 512 == 511) {
                        kotlinx.coroutines.yield()
                    }
                }
                nativeFinishInput(handle)
            }

            encJob.await()

            // 收集输出
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = nativeDrain(handle, buf, buf.size)
                if (n == -1) break      // 完成
                if (n == -2) throw IllegalStateException("JXL 编码失败")
                if (n == 0) break       // 防御：理论上不会
                sink(buf, n)
            }
            // 收尾：再一次 drain 确认无残留
            while (true) {
                val n = nativeDrain(handle, buf, buf.size)
                if (n <= 0) break
                sink(buf, n)
            }
        } finally {
            nativeDestroy(handle)
        }
    }
}