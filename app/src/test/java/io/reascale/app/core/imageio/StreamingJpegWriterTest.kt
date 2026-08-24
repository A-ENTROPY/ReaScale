package io.reascale.app.core.imageio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PC JVM 单测：编码到固定文件，由 PowerShell System.Drawing 解码验证
 */
class StreamingJpegWriterTest {

    @Test
    fun decodeVerification() {
        val w = 160; val h = 120
        val bos = ByteArrayOutputStream()
        StreamingJpegWriter(bos, w, h, quality = 92).use { jpg ->
            for (y in 0 until h) {
                val row = ByteArray(w * 3)
                for (x in 0 until w) {
                    val q1 = x < w / 2 && y < h / 2; val q2 = x >= w / 2 && y < h / 2
                    val r: Int; val g: Int; val b: Int
                    when {
                        q1 -> { r = 255; g = 0; b = 0 }
                        q2 -> { r = 0; g = 0; b = 255 }
                        y < h / 2 -> { r = 0; g = 0; b = 0 }
                        else -> { r = 255; g = 255; b = 255 }
                    }
                    row[x*3] = r.toByte(); row[x*3+1] = g.toByte(); row[x*3+2] = b.toByte()
                }
                jpg.feedRow(y, row)
            }
        }
        val bytes = bos.toByteArray()
        val out = File("../build/jpeg_verify.jpg")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("written: ${out.absolutePath} ${bytes.size} bytes")
        assertTrue(bytes.size > 500)
    }

    @Test
    fun encodeSmall() {
        val w = 160; val h = 120
        val bos = ByteArrayOutputStream()
        StreamingJpegWriter(bos, w, h, quality = 92).use { jpg ->
            for (y in 0 until h) {
                val row = ByteArray(w * 3)
                for (x in 0 until w) {
                    val q1 = x < w / 2 && y < h / 2; val q2 = x >= w / 2 && y < h / 2
                    val r: Int; val g: Int; val b: Int
                    when {
                        q1 -> { r = 255; g = 0; b = 0 }
                        q2 -> { r = 0; g = 0; b = 255 }
                        y < h / 2 -> { r = 0; g = 0; b = 0 }
                        else -> { r = 255; g = 255; b = 255 }
                    }
                    row[x*3] = r.toByte(); row[x*3+1] = g.toByte(); row[x*3+2] = b.toByte()
                }
                jpg.feedRow(y, row)
            }
        }
        val bytes = bos.toByteArray()
        println("JPEG bytes=${bytes.size}")
        assertTrue(bytes.size > 500)

        // JPEG 结构检查
        org.junit.Assert.assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        org.junit.Assert.assertEquals(0xD8, bytes[1].toInt() and 0xFF) // SOI
        // 找 SOS 后扫描数据 + EOI
        var eoiFound = false
        var i = 2
        while (i < bytes.size - 1) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i+1].toInt() and 0xFF) == 0xD9) {
                eoiFound = true
                org.junit.Assert.assertEquals("EOI 应在文件尾", bytes.size - 2, i)
                break
            }
            i++
        }
        assertTrue("EOI 缺失", eoiFound)
        // 非零熵编码数据量合理
        assertTrue("数据量过小 ${bytes.size}", bytes.size > 1000)
    }

    @Test
    fun encodeGradientLarge() {
        val w = 2000; val h = 1500
        val bos = ByteArrayOutputStream()
        val t0 = System.currentTimeMillis()
        StreamingJpegWriter(bos, w, h, quality = 85).use { jpg ->
            val row = ByteArray(w * 3)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    row[x*3] = (x * 255 / w).toByte()
                    row[x*3+1] = (y * 255 / h).toByte()
                    row[x*3+2] = (255 - x * 255 / w).toByte()
                }
                jpg.feedRow(y, row)
            }
        }
        val dt = System.currentTimeMillis() - t0
        println("2000x1500 编码 ${dt}ms, ${bos.size()} bytes")
        assertTrue(bos.size() > 50_000)
    }
}