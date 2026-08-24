package io.reascale.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlDecodingSpeed
import com.awxkee.jxlcoder.JxlEffort
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 验证 JXL 编码器是否能正确编码/解码已知颜色 bitmap
 * 排查 ONNX 引擎输出 JXL 文件颜色渐变的问题
 */
@RunWith(AndroidJUnit4::class)
class JxlColorTest {

    @Test
    fun jxlRoundTripPreservesColors() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. 创建已知颜色 bitmap (128x128, 4个色块)
        val w = 128; val h = 128
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // 左上=R(255,0,0) 右上=B(0,0,255) 左下=G(0,255,0) 右下=白(255,255,255)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val q1 = x < w / 2 && y < h / 2      // 左上
                val q2 = x >= w / 2 && y < h / 2     // 右上
                val q3 = x < w / 2 && y >= h / 2     // 左下
                val r = if (q1 || (!q2 && !q3)) 255 else 0  // 红: Q1+Q4
                val g = if (y >= h / 2) 255 else 0           // 绿: Q3+Q4
                val b = if (x >= w / 2) 255 else 0           // 蓝: Q2+Q4
                bmp.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        assertEquals("左上应为红", -0x10000, bmp.getPixel(0, 0))
        assertEquals("右上应为蓝", -0xFFFF01, bmp.getPixel(w-1, 0))
        assertEquals("左下应为绿", -0xFF0100, bmp.getPixel(0, h-1))
        assertEquals("右下应为白", -1, bmp.getPixel(w-1, h-1))
        println("原始 bitmap 颜色正确: hasAlpha=${bmp.hasAlpha()}")

        // 2a. 编码 JXL (RGB, 无损)
        testRoundTrip(bmp, JxlChannelsConfiguration.RGB, "RGB")

        // 2b. 编码 JXL (RGBA, 无损) —— 与 App 实际路径一致(hasAlpha=true 时)
        testRoundTrip(bmp, JxlChannelsConfiguration.RGBA, "RGBA")

        // 2c. 用 setPixels 写入的输出型 bitmap (模拟引擎输出)，测试 hasAlpha 行为
        val engineLike = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val q1 = x < w / 2 && y < h / 2
                val q2 = x >= w / 2 && y < h / 2
                val q3 = x < w / 2 && y >= h / 2
                val r = if (q1 || (!q2 && !q3)) 255 else 0
                val g = if (y >= h / 2) 255 else 0
                val b = if (x >= w / 2) 255 else 0
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        engineLike.setPixels(px, 0, w, 0, 0, w, h)
        println("engine-like bitmap (setPixels): hasAlpha=${engineLike.hasAlpha()}")
        testRoundTrip(engineLike, JxlChannelsConfiguration.RGBA, "engineLike-RGBA")

        bmp.recycle()
        engineLike.recycle()
    }

    private fun testRoundTrip(src: Bitmap, config: JxlChannelsConfiguration, label: String) {
        val w = src.width; val h = src.height
        val jxlBytes = JxlCoder.encode(
            src, config,
            JxlCompressionOption.LOSSLESS,
            JxlEffort.SQUIRREL, 100, JxlDecodingSpeed.SLOWEST
        )
        assertTrue("$label 编码结果不应为空", jxlBytes.isNotEmpty())
        val decoded = JxlCoder.decode(jxlBytes)
        assertNotNull("$label 解码结果不应为空", decoded)
        val d00 = decoded.getPixel(0, 0)
        val d10 = decoded.getPixel(w - 1, 0)
        val d01 = decoded.getPixel(0, h - 1)
        val d11 = decoded.getPixel(w - 1, h - 1)
        println("$label 解码后: 左上=0x%08X 右上=0x%08X 左下=0x%08X 右下=0x%08X".format(d00, d10, d01, d11))
        fun assertColorClose(expected: Int, actual: Int, msg: String) {
            val er = (expected shr 16) and 0xFF
            val eg = (expected shr 8) and 0xFF
            val eb = expected and 0xFF
            val ar = (actual shr 16) and 0xFF
            val ag = (actual shr 8) and 0xFF
            val ab = actual and 0xFF
            if (kotlin.math.abs(er - ar) > 2 || kotlin.math.abs(eg - ag) > 2 || kotlin.math.abs(eb - ab) > 2) {
                fail("$label/$msg: 期望 0x%08X, 实际 0x%08X".format(expected, actual))
            }
        }
        assertColorClose(-0x10000, d00, "左上")
        assertColorClose(-0xFFFF01, d10, "右上")
        assertColorClose(-0xFF0100, d01, "左下")
        assertColorClose(-1, d11, "右下")
        decoded.recycle()
        println("✓ $label 编解码颜色正确")
    }
}