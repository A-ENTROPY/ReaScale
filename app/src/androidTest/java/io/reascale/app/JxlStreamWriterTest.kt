package io.reascale.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.awxkee.jxlcoder.JxlCoder
import io.reascale.app.core.encode.JxlStreamWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 流式 JXL 编码器冒烟测试：
 * 1. nativeVersion 正常加载 libjxl.so
 * 2. 小图（256x256 四色块）流式编码 → 解码后颜色一致
 * 3. 大图（3072x3072 渐变，模拟超大输出）流式编码 → 尺寸/趋势正确，内存峰值低
 */
@RunWith(AndroidJUnit4::class)
class JxlStreamWriterTest {

    @Test
    fun versionLoads() {
        val v = JxlStreamWriter.nativeVersion()
        println("libjxl version = $v")
        assertTrue("版本应 > 8000（0.8+）: $v", v >= 8000)
    }

    @Test
    fun smallImageRoundTrip() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val w = 256; val h = 256

        // 生成四色块源数据（RGB 行）
        val src = Array(h) { y -> ByteArray(w * 3) }
        for (y in 0 until h) {
            val row = src[y]
            for (x in 0 until w) {
                val i = x * 3
                val q1 = x < w / 2 && y < h / 2; val q2 = x >= w / 2 && y < h / 2
                val q3 = x < w / 2 && y >= h / 2
                val r = if (q1 || (!q2 && !q3)) 255 else 0
                val g = if (y >= h / 2) 255 else 0
                val b = if (x >= w / 2) 255 else 0
                row[i] = r.toByte(); row[i + 1] = g.toByte(); row[i + 2] = b.toByte()
            }
        }

        val jxlBytes = java.io.ByteArrayOutputStream()
        JxlStreamWriter.encodeStreaming(
            width = w, height = h, quality = 100, lossless = true,
            rowProvider = { src[it] },
            sink = { bytes, len -> jxlBytes.write(bytes, 0, len) }
        )
        val data = jxlBytes.toByteArray()
        println("流式编码输出 ${data.size} bytes")
        println("magic: " + data.take(16).joinToString(" ") { "%02X".format(it) })
        assertTrue("输出过小: ${data.size}", data.size > 40)

        // 解码回 256x256 bitmap
        val bmp = JxlCoder.decode(data)
        assertNotNull(bmp)
        println("解码: ${bmp.width}x${bmp.height}")
        assertEquals(w, bmp.width)
        assertEquals(h, bmp.height)

        fun px(x: Int, y: Int) = bmp.getPixel(x, y)
        fun check(x: Int, y: Int, expR: Int, expG: Int, expB: Int, label: String) {
            val p = px(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val ok = kotlin.math.abs(r - expR) <= 3 && kotlin.math.abs(g - expG) <= 3 && kotlin.math.abs(b - expB) <= 3
            println("$label px($x,$y): R=$r G=$g B=$b 期望 R=$expR G=$expG B=$expB ${if (ok) "✓" else "✗"}")
            assertTrue("$label 颜色错: ($r,$g,$b) vs ($expR,$expG,$expB)", ok)
        }
        check(0, 0, 255, 0, 0, "红")
        check(w - 1, 0, 0, 0, 255, "蓝")
        check(0, h - 1, 0, 255, 0, "绿")
        check(w - 1, h - 1, 255, 255, 255, "白")
        bmp.recycle()
    }

    @Test
    fun largeImageStreaming() = runBlocking {
        val w = 3072; val h = 3072  // ~36MB 输出 RGB 数据，流式不应驻留整图
        val jxlBytes = java.io.ByteArrayOutputStream()

        JxlStreamWriter.encodeStreaming(
            width = w, height = h, quality = 90, lossless = false,
            rowProvider = { y ->
                // 渐变色行：R 随 x 递增，B 递减（通道趋势验证）
                val row = ByteArray(w * 3)
                for (x in 0 until w) {
                    val i = x * 3
                    row[i] = (x * 255 / w).toByte()
                    row[i + 1] = (y * 255 / h).toByte()
                    row[i + 2] = (255 - x * 255 / w).toByte()
                }
                row
            },
            sink = { bytes, len -> jxlBytes.write(bytes, 0, len) }
        )
        val data = jxlBytes.toByteArray()
        println("3072x3072 流式编码输出 ${data.size} bytes")

        // 解码验证尺寸与通道趋势
        val bmp = JxlCoder.decode(data)
        assertNotNull(bmp)
        assertEquals(w, bmp.width)
        assertEquals(h, bmp.height)
        val midY = h / 2
        val p0 = bmp.getPixel(0, midY)
        val p1 = bmp.getPixel(w - 1, midY)
        val r0 = (p0 shr 16) and 0xFF; val b0 = p0 and 0xFF
        val r1 = (p1 shr 16) and 0xFF; val b1 = p1 and 0xFF
        println("中行左端 R=$r0 B=$b0  右端 R=$r1 B=$b1")
        assertTrue("R 应随 x 递增（$r0 -> $r1）", r1 > r0 + 100)
        assertTrue("B 应随 x 递减（$b0 -> $b1）", b0 > b1 + 100)
        bmp.recycle()
        println("✓ 大图像流式编码通过")
    }
}