package io.reascale.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.reascale.app.core.imageio.StreamingJpegWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * StreamingJpegWriter 冒烟：小图往返（BitmapFactory 能解、颜色近似）+ 大图文件
 */
@RunWith(AndroidJUnit4::class)
class StreamingJpegWriterTest {

    @Test
    fun roundTripColors() {
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
        println("JPEG 输出 ${bytes.size} bytes")
        assertTrue(bytes.size > 500)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)

        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("BitmapFactory 应能解码", bmp)
        assertEquals(w, bmp.width); assertEquals(h, bmp.height)

        fun check(x: Int, y: Int, expR: Int, expG: Int, expB: Int, label: String) {
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            println("$label ($x,$y): R=$r G=$g B=$b")
            // JPEG 有损，容差 ±12
            assertTrue("$label R: $r", kotlin.math.abs(r - expR) <= 12)
            assertTrue("$label G: $g", kotlin.math.abs(g - expG) <= 12)
            assertTrue("$label B: $b", kotlin.math.abs(b - expB) <= 12)
        }
        check(10, 10, 255, 0, 0, "红Q1")
        check(w-5, 10, 0, 0, 255, "蓝Q2")
        check(10, h-5, 255, 255, 255, "白Q3")
        check(w-5, h-5, 0, 0, 0, "黑Q4")
        bmp.recycle()
    }

    @Test
    fun largeImageFileWrite() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val w = 3000; val h = 3000
        val f = java.io.File(ctx.cacheDir, "stream_test.jpg")
        StreamingJpegWriter(f.outputStream(), w, h, quality = 85).use { jpg ->
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
        println("大 JPEG 文件: ${f.length()} bytes")
        assertTrue(f.length() > 50_000)
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
        assertNotNull(bmp)
        val cx = bmp.width / 2; val cy = bmp.height / 2
        val p = bmp.getPixel(cx, cy)
        val r = (p shr 16) and 0xFF
        assertTrue("中心 R 应≈128: $r", kotlin.math.abs(r - 128) <= 16)
        bmp.recycle()
        f.delete()
    }
}