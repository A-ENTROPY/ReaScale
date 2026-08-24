package io.reascale.app.core.imageio

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 流式 PNG 编码器（纯 Kotlin，零依赖）
 *
 * 用于超大图输出：逐行喂入 RGB 数据（行序严格递增、每行完整宽度），
 * 内部 zlib deflate 攒块写 IDAT —— 不驻留整图像素。
 *
 * PNG 结构：signature + IHDR + IDAT*(zlib(RGB扫描线,每行前置filter=0)) + IEND
 */
class StreamingPngWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    compressionLevel: Int = 4
) : java.io.Closeable {

    private val deflater = Deflater(compressionLevel.coerceIn(0, 9), false /*zlib wrap*/)
    private val crc = CRC32()
    private var finished = false

    // deflate 输入缓冲
    private var inBuf = ByteArray(128 * 1024)
    private var inLen = 0

    // IDAT 攒块缓冲（≥64KB 落盘一次）
    private var idat = ByteArray(64 * 1024)
    private var idatLen = 0

    init {
        require(width > 0 && height > 0) { "非法尺寸 ${width}x$height" }
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        writeInt(ihdr, 0, width)
        writeInt(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = 2   // color type: truecolor RGB
        ihdr[10] = 0  // compression: deflate
        ihdr[11] = 0  // filter: adaptive(逐行byte0)
        ihdr[12] = 0  // interlace: none
        writeChunk(TYPE_IHDR, ihdr, 0, ihdr.size)
    }

    /**
     * 喂入一行完整宽度的 RGB 数据（width*3 字节）。
     * y 必须从 0 开始严格递增（PNG 扫描线顺序）。
     */
    fun feedRow(y: Int, row: ByteArray) {
        check(!finished) { "已结束" }
        check(y < height) { "行 $y 超出高度 $height" }
        check(row.size >= width * 3) { "行数据过短 ${row.size} < ${width * 3}" }
        // 行前置 filter byte 0（None）
        appendToDeflater(FILTER_BYTE, 0, 1)
        appendToDeflater(row, 0, width * 3)
        pumpDeflate(false)
    }

    /** 结束：冲刷 deflate 尾部 + 最后 IDAT + IEND */
    override fun close() {
        if (finished) return
        finished = true
        deflater.finish()
        pumpDeflate(true)
        flushIdat()
        writeChunk(TYPE_IEND, EMPTY, 0, 0)
        deflater.end()
    }

    // ---------- 内部 ----------

    private fun appendToDeflater(src: ByteArray, off: Int, len: Int) {
        if (inLen + len > inBuf.size) {
            val nb = ByteArray(maxOf(inBuf.size * 2, inLen + len))
            System.arraycopy(inBuf, 0, nb, 0, inLen)
            inBuf = nb
        }
        System.arraycopy(src, off, inBuf, inLen, len)
        inLen += len
    }

    private fun pumpDeflate(finalDrain: Boolean) {
        if (finalDrain) {
            deflater.setInput(EMPTY, 0, 0)
        } else {
            if (inLen == 0) return
            deflater.setInput(inBuf, 0, inLen)
            inLen = 0
        }
        val out = ByteArray(64 * 1024)
        while (true) {
            val n = deflater.deflate(out, 0, out.size)
            if (n > 0) {
                accumIdat(out, 0, n)
                continue
            }
            // n == 0：needs input 或 needs output 或 finished
            if (deflater.finished()) break
            if (finalDrain && !deflater.finished()) continue
            break
        }
        if (idatLen >= FLUSH_THRESHOLD) flushIdat()
    }

    private fun accumIdat(src: ByteArray, off: Int, len: Int) {
        if (idatLen + len > idat.size) {
            val nb = ByteArray(maxOf(idat.size * 2, idatLen + len))
            System.arraycopy(idat, 0, nb, 0, idatLen)
            idat = nb
        }
        System.arraycopy(src, off, idat, idatLen, len)
        idatLen += len
    }

    private fun flushIdat() {
        if (idatLen == 0) return
        writeChunk(TYPE_IDAT, idat, 0, idatLen)
        idatLen = 0
    }

    private fun writeChunk(type: ByteArray, data: ByteArray, off: Int, len: Int) {
        val head = ByteArray(8)
        writeInt(head, 0, len)
        head[4] = type[0]; head[5] = type[1]; head[6] = type[2]; head[7] = type[3]
        out.write(head)
        crc.reset()
        crc.update(type, 0, 4)
        if (len > 0) crc.update(data, off, len)
        val cv = crc.value.toInt()
        if (len > 0) out.write(data, off, len)
        val tail = ByteArray(4)
        writeInt(tail, 0, cv)
        out.write(tail)
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    companion object {
        private val SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        private val TYPE_IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52) // IHDR
        private val TYPE_IDAT = byteArrayOf(0x49, 0x44, 0x41, 0x54) // IDAT
        private val TYPE_IEND = byteArrayOf(0x49, 0x45, 0x4E, 0x44) // IEND
        private val FILTER_BYTE = byteArrayOf(0)
        private val EMPTY = ByteArray(0)
        private const val FLUSH_THRESHOLD = 64 * 1024
    }
}