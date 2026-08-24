package io.reascale.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.reascale.app.core.imageio.StreamingPngWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * StreamingPngWriter 冒烟：小图往返颜色 + 解码兼容（BitmapFactory）
 */
@RunWith(AndroidJUnit4::class)
class StreamingPngWriterTest {

    @Test
    fun roundTripColors() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val w = 200; val h = 150
        val bos = ByteArrayOutputStream()
        StreamingPngWriter(bos, w, h).use { png ->
            for (y in 0 until h) {
                val row = ByteArray(w * 3)
                for (x in 0 until w) {
                    // 四象限色块 + 渐变混合
                    val q1 = x < w / 2 && y < h / 2; val q2 = x >= w / 2 && y < h / 2
                    val r: Int; val g: Int; val b: Int
                    when {
                        q1 -> { r = x * 255 / (w/2); g = y * 255 / (h/2); b = 255 - r }   // 渐变
                        q2 -> { r = 0; g = 0; b = 255 }
                        y < h / 2 -> { r = 255; g = 0; b = 0 }
                        else -> { r = 0; g = 255; b = 0 }
                    }
                    row[x*3] = r.toByte(); row[x*3+1] = g.toByte(); row[x*3+2] = b.toByte()
                }
                png.feedRow(y, row)
            }
        }
        val bytes = bos.toByteArray()
        println("PNG 输出 ${bytes.size} bytes")
        assertTrue(bytes.size > 100)
        assertEquals(0x89.toByte(), bytes[0])

        // BitmapFactory 解码验证
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(bmp)
        assertEquals(w, bmp.width); assertEquals(h, bmp.height)
        fun check(x: Int, y: Int, expR: Int, expG: Int, expB: Int, label: String) {
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            println("$label ($x,$y): R=$r G=$g B=$b")
            assertEquals("$label R", expR, r)
            assertEquals("$label G", expG, g)
            assertEquals("$label B", expB, b)
        }
        check(10, 10, 25, 34, 230, "渐变Q1")     // r=x*255/100=25, g=y*255/75=34, b=255-r
        check(w-5, 10, 0, 0, 255, "蓝")
        check(10, h-5, 0, 255, 0, "左下绿")     // y>=h/2 且 x<w/2 → else 分支=绿
        check(w-5, h-5, 0, 255, 0, "右下绿")
        check(w-5, 10, 0, 0, 255, "右上蓝")
        bmp.recycle()
    }

    /** 大图（>堆限制模拟）：3000x3000 渐变流式写文件再解码抽查 */
    @Test
    fun largeImageFileWrite() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val w = 3000; val h = 3000
        val f = File(ctx.cacheDir, "stream_test.png")
        StreamingPngWriter(f.outputStream(), w, h).use { png ->
            val row = ByteArray(w * 3)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    row[x*3] = (x * 255 / w).toByte()
                    row[x*3+1] = (y * 255 / h).toByte()
                    row[x*3+2] = (255 - x * 255 / w).toByte()
                }
                png.feedRow(y, row)
            }
        }
        println("大 PNG 文件: ${f.length()} bytes")
        assertTrue(f.length() > 100_000)
        // 抽查解码中心像素
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
        assertNotNull(bmp)
        val cx = bmp.width / 2; val cy = bmp.height / 2
        val p = bmp.getPixel(cx, cy)
        val r = (p shr 16) and 0xFF
        // 中心 R ≈ 128（±采样误差）
        assertTrue("中心 R 应≈128: $r", kotlin.math.abs(r - 128) <= 8)
        bmp.recycle()
        f.delete()
    }
}