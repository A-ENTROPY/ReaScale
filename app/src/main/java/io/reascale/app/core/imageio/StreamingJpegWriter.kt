package io.reascale.app.core.imageio

import java.io.OutputStream

/**
 * Streaming JPEG baseline encoder (pure Kotlin, zero deps).
 *
 * - 4:4:4 (no chroma subsampling), one 8x8 MCU = Y,Cb,Cr
 * - ITU-T T.81 Annex K quant/Huffman tables, IJG quality scaling
 * - feedRow(y, row): rows strictly in order, full width RGB (width*3);
 *   internally buffers 8 rows per MCU row -- never holds the whole image
 *
 * For huge outputs (>200MB bitmap cannot go through Bitmap.compress).
 */
class StreamingJpegWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    quality: Int = 85
) : java.io.Closeable {

    // quant tables (quality-scaled, natural order)
    private val qLum = IntArray(64)
    private val qChr = IntArray(64)

    // huffman code tables
    private val dcLumCode = IntArray(16); private val dcLumLen = IntArray(16)
    private val acLumCode = IntArray(256); private val acLumLen = IntArray(256)
    private val dcChrCode = IntArray(16); private val dcChrLen = IntArray(16)
    private val acChrCode = IntArray(256); private val acChrLen = IntArray(256)

    // bit stream state
    private var bitAcc = 0
    private var bitCnt = 0

    // MCU row buffer (8 RGB rows)
    private val rowBuf = Array(8) { ByteArray(width * 3) }
    private var rowsFilled = 0
    private var yFed = 0
    private var predDC = IntArray(3)

    private val work = FloatArray(64)

    init {
        require(width > 0 && height > 0)
        buildQuantTables(quality.coerceIn(1, 100))

        out.write(0xFF); out.write(SOI and 0xFF)

        writeSegment(APP0, byteArrayOf(
            0x4A, 0x46, 0x49, 0x46, 0x00,
            0x01, 0x01,
            0x00,
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00
        ))

        val dqt = ByteArray(130)
        // PNG-style note: JPEG spec stores quant tables in ZIGZAG order;
        // our tables are natural order -> remap when writing
        dqt[0] = 0x00
        for (i in 0 until 64) dqt[1 + i] = qLum[zigzagBuf[i]].toByte()
        dqt[65] = 0x01
        for (i in 0 until 64) dqt[66 + i] = qChr[zigzagBuf[i]].toByte()
        writeSegment(DQT, dqt)

        val sof = ByteArray(15)
        sof[0] = 8
        sof[1] = (height shr 8).toByte(); sof[2] = height.toByte()
        sof[3] = (width shr 8).toByte(); sof[4] = width.toByte()
        sof[5] = 3
        for (c in 0 until 3) {
            sof[6 + c * 3] = (c + 1).toByte()
            sof[7 + c * 3] = 0x11
            sof[8 + c * 3] = if (c == 0) 0 else 1
        }
        writeSegment(SOF0, sof)

        writeDHT(0x00, DC_LUM_BITS, DC_LUM_VALS)
        writeDHT(0x10, AC_LUM_BITS, AC_LUM_VALS)
        writeDHT(0x01, DC_CHR_BITS, DC_CHR_VALS)
        writeDHT(0x11, AC_CHR_BITS, AC_CHR_VALS)

        writeSegment(SOS, byteArrayOf(
            3,
            1, 0x00,
            2, 0x11,
            3, 0x11,
            0, 63, 0
        ))
    }

    /** Feed one full-width RGB row; y must increase strictly from 0 */
    fun feedRow(y: Int, row: ByteArray) {
        check(y == yFed) { "row order: expect $yFed got $y" }
        check(row.size >= width * 3)
        System.arraycopy(row, 0, rowBuf[rowsFilled], 0, width * 3)
        rowsFilled++
        yFed++
        if (rowsFilled == 8 || yFed == height) {
            encodeMcuRow(padBottom = yFed == height && rowsFilled < 8)
            rowsFilled = 0
        }
    }

    override fun close() {
        check(yFed == height) { "rows not fed: $yFed/$height" }
        flushBits()
        out.write(0xFF); out.write(EOI and 0xFF)
        out.flush()
    }

    // ================= MCU row =================

    private fun encodeMcuRow(padBottom: Boolean) {
        val mcuCols = (width + 7) / 8
        if (padBottom) {
            for (r in rowsFilled until 8) {
                System.arraycopy(rowBuf[rowsFilled - 1], 0, rowBuf[r], 0, width * 3)
            }
        }
        for (mcuX in 0 until mcuCols) {
            predDC[0] = encodeBlock(mcuX, comp = 0, prevDC = predDC[0], qTab = qLum, dcTab = 0, acTab = 0)
            predDC[1] = encodeBlock(mcuX, comp = 1, prevDC = predDC[1], qTab = qChr, dcTab = 1, acTab = 1)
            predDC[2] = encodeBlock(mcuX, comp = 2, prevDC = predDC[2], qTab = qChr, dcTab = 1, acTab = 1)
        }
    }

    /** 8x8 block: color convert -> FDCT -> quantize -> huffman; returns new DC prediction */
    private fun encodeBlock(mcuX: Int, comp: Int, prevDC: Int, qTab: IntArray, dcTab: Int, acTab: Int): Int {
        val px = mcuX * 8
        for (i in 0 until 64) work[i] = 0f
        for (yy in 0 until 8) {
            // [FIX 2026-08-29] 右边缘不足 8 像素的块必须复制边缘像素填充（JPEG MCU 要求完整 8x8），
            // 之前 break 直接留黑 → 非 8 倍数宽度照片右侧出现竖条色偏伪影
            val rb = rowBuf[yy]
            for (xx in 0 until 8) {
                val sx = px + xx
                val o = (if (sx >= width) width - 1 else sx) * 3
                val r = rb[o].toInt() and 0xFF
                val g = rb[o + 1].toInt() and 0xFF
                val b = rb[o + 2].toInt() and 0xFF
                work[yy * 8 + xx] = when (comp) {
                    0 -> 0.299f * r + 0.587f * g + 0.114f * b - 128f
                    1 -> -0.168736f * r - 0.331264f * g + 0.5f * b
                    else -> 0.5f * r - 0.418688f * g - 0.081312f * b
                }
            }
        }
        fdct(work)
        val zz = zigzagBuf
        val q = IntArray(64) { idx ->
            val zpos = zz[idx]
            Math.round(work[zpos] / qTab[zpos])
        }
        // DC
        val dc = q[0]
        var diff = dc - prevDC
        val absDiff = if (diff < 0) -diff else diff
        var nbits = 0
        var t = absDiff
        while (t > 0) { nbits++; t = t shr 1 }
        if (dcTab == 0) putHuff(dcLumCode[nbits], dcLumLen[nbits])
        else putHuff(dcChrCode[nbits], dcChrLen[nbits])
        if (nbits > 0) {
            var v = diff
            if (diff < 0) v = diff + (1 shl nbits) - 1
            putBits(v, nbits)
        }
        // AC
        var run = 0
        for (k in 1 until 64) {
            val coef = q[k]
            if (coef == 0) { run++; continue }
            while (run > 15) {
                if (acTab == 0) putHuff(acLumCode[0xF0], acLumLen[0xF0])
                else putHuff(acChrCode[0xF0], acChrLen[0xF0])
                run -= 16
            }
            var abits = 0
            var t2 = if (coef < 0) -coef else coef
            while (t2 > 0) { abits++; t2 = t2 shr 1 }
            val rs = (run shl 4) or abits
            if (acTab == 0) putHuff(acLumCode[rs], acLumLen[rs])
            else putHuff(acChrCode[rs], acChrLen[rs])
            var v = coef
            if (coef < 0) v = coef + (1 shl abits) - 1
            putBits(v, abits)
            run = 0
        }
        if (run > 0) {
            if (acTab == 0) putHuff(acLumCode[0x00], acLumLen[0x00])
            else putHuff(acChrCode[0x00], acChrLen[0x00])
        }
        return dc
    }

    // ================= FDCT (direct formula + precomputed cosine, exact scaling) =================

    private fun fdct(d: FloatArray) {
        for (r in 0 until 8) {
            val o = r * 8
            for (u in 0 until 8) {
                var acc = 0f
                val ct = COS[u]
                for (x in 0 until 8) acc += d[o + x] * ct[x]
                TMP[u] = acc
            }
            for (u in 0 until 8) d[o + u] = CU[u] * TMP[u]
        }
        for (cc in 0 until 8) {
            for (u in 0 until 8) {
                var acc = 0f
                val ct = COS[u]
                for (x in 0 until 8) acc += d[8 * x + cc] * ct[x]
                TMP[u] = acc
            }
            for (u in 0 until 8) d[8 * u + cc] = CU[u] * TMP[u]
        }
    }

    // ================= bit stream =================

    private fun putBits(v: Int, len: Int) {
        var acc = bitAcc; var cnt = bitCnt
        for (i in len - 1 downTo 0) {
            acc = (acc shl 1) or ((v shr i) and 1)
            cnt++
            if (cnt == 8) {
                out.write(acc)
                if (acc == 0xFF) out.write(0x00)
                acc = 0; cnt = 0
            }
        }
        bitAcc = acc; bitCnt = cnt
    }

    private fun putHuff(code: Int, len: Int) = putBits(code, len)

    private fun flushBits() {
        while (bitCnt != 0) putBits(1, 1)
    }

    // ================= headers =================

    private fun writeMarker(m: Int) { out.write(0xFF); out.write(m and 0xFF) }

    private fun writeSegment(marker: Int, data: ByteArray) {
        writeMarker(marker)
        val len = data.size + 2
        out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
        out.write(data)
    }

    private fun writeDHT(classId: Int, bits: ByteArray, vals: ByteArray) {
        val data = ByteArray(17 + vals.size)
        data[0] = classId.toByte()
        for (i in 0 until 16) data[1 + i] = bits[i]
        System.arraycopy(vals, 0, data, 17, vals.size)
        writeSegment(DHT, data)
        buildCodes(classId, bits, vals)
    }

    private fun buildCodes(classId: Int, bits: ByteArray, vals: ByteArray) {
        val codes = IntArray(vals.size)
        var k = 0
        var code = 0
        for (len in 1..16) {
            repeat(bits[len - 1].toInt()) {
                codes[k] = code; k++; code++
            }
            code = code shl 1
        }
        val codeArr: IntArray; val lenArr: IntArray
        when (classId) {
            0x00 -> { codeArr = dcLumCode; lenArr = dcLumLen }
            0x10 -> { codeArr = acLumCode; lenArr = acLumLen }
            0x01 -> { codeArr = dcChrCode; lenArr = dcChrLen }
            else -> { codeArr = acChrCode; lenArr = acChrLen }
        }
        var idx = 0
        for (len in 1..16) {
            repeat(bits[len - 1].toInt()) {
                val v = vals[idx].toInt() and 0xFF
                codeArr[v] = codes[idx]; lenArr[v] = len
                idx++
            }
        }
    }

    private fun buildQuantTables(quality: Int) {
        val scale = if (quality < 50) 5000 / quality else 200 - quality * 2
        for (i in 0 until 64) {
            qLum[i] = ((BASE_LUM[i] * scale + 50) / 100).coerceIn(1, 255)
            qChr[i] = ((BASE_CHR[i] * scale + 50) / 100).coerceIn(1, 255)
        }
    }

    companion object {
        private const val SOI = 0xD8
        private const val EOI = 0xD9
        private const val APP0 = 0xE0
        private const val DQT = 0xDB
        private const val SOF0 = 0xC0
        private const val DHT = 0xC4
        private const val SOS = 0xDA

        private val zigzagBuf = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
        )

        private val COS = Array(8) { u ->
            FloatArray(8) { x ->
                Math.cos((2 * x + 1) * u * Math.PI / 16).toFloat()
            }
        }
        private val CU = FloatArray(8) { u ->
            0.5f * (if (u == 0) (1.0 / Math.sqrt(2.0)).toFloat() else 1f)
        }
        private val TMP = FloatArray(8)

        private val BASE_LUM = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99
        )
        private val BASE_CHR = intArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99,
            18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99,
            47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99
        )

        private fun jb(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

        private val DC_LUM_BITS = jb(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        private val DC_LUM_VALS = jb(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        private val DC_CHR_BITS = jb(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        private val DC_CHR_VALS = jb(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        private val AC_LUM_BITS = jb(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
        private val AC_CHR_BITS = jb(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)

        private val AC_LUM_VALS = jb(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
            0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
            0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
            0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
            0xF9, 0xFA
        )
        private val AC_CHR_VALS = jb(
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
            0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
            0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
            0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
            0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
            0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA,
            0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA
        )
    }
}
