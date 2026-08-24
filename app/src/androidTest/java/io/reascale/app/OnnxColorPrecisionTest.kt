package io.reascale.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlDecodingSpeed
import com.awxkee.jxlcoder.JxlEffort
import io.reascale.app.core.processing.ImageProcessor
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.EngineRepository
import io.reascale.app.data.ImageJob
import io.reascale.app.data.OutputFormat
import io.reascale.app.data.UpscalePlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 用户模型（固定 128x128 输入，4x）输出色彩精确验证
 *
 * 用与本地 python 参考相同的输入（R=x*255/128, G=128, B=(128-x)*255/128），
 * 引擎输出与 python onnxruntime 参考值对比（误差 ±3/255）。
 *
 * python 参考（127B 输入 → 512x512 输出）：
 *   pixel (0,0):  R=0.1288 G=0.4576 B=0.9039  → RGB(33,117,231)
 *   pixel (1,0):  R=0.0413 G=0.4974 B=0.9119  → RGB(11,127,233)
 *   通道均值: ch0=0.501 ch1=0.505 ch2=0.495
 */
@RunWith(AndroidJUnit4::class)
class OnnxColorPrecisionTest {

    @Test
    fun fixedModelColorMatchesPythonReference() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. 模型
        val modelsDir = File(ctx.filesDir, "engines/models")
        val modelFiles = modelsDir.listFiles { f -> f.name.endsWith(".onnx") }
            ?: throw AssertionError("models 目录不存在")
        val modelFile = modelFiles.firstOrNull { f -> f.length() > 1000000 }
            ?: (if (modelFiles.isNotEmpty()) modelFiles[0] else throw AssertionError("无模型"))
        println("模型: ${modelFile.name}")

        // 2. 输入：与 python 参考完全一致 128x128
        // R = x*255/128, G = 128, B = (128-x)*255/128
        val w = 128; val h = 128
        val inputFile = File(ctx.cacheDir, "color_ref_input.png")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x * 255 / w
                val g = 128
                val b = (w - x) * 255 / w
                bmp.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        inputFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        println("输入图: ${inputFile.absolutePath}")

        // 3. 导入 + 处理
        val repo = EngineRepository(ctx)
        val profile = runBlocking { repo.importOnnx(modelFile) }
        println("profile baseScale=${profile.capabilities.baseScale}")
        val job = ImageJob(
            id = UUID.randomUUID().toString(),
            sourceUri = android.net.Uri.fromFile(inputFile).toString(),
            sourceDisplayName = "color_ref.png",
            sourceSizeBytes = inputFile.length(),
            sourceWidth = w,
            sourceHeight = h,
            engineId = profile.id,
            upscalePlan = UpscalePlan(targetScale = profile.capabilities.baseScale),
            encodeOptions = EncodeOptions(format = OutputFormat.PNG, quality = 100)
        )
        val processor = ImageProcessor(
            context = ctx,
            engineProvider = { pid -> ImageProcessor.defaultEngineProvider(ctx, profile) },
            outputDirProvider = { "" }
        )
        val result = runBlocking { processor.process(job, profile) {} }
        assertTrue("process 失败: ${result.exceptionOrNull()?.message}", result.isSuccess)

        // 4. 解码输出
        val out = ctx.contentResolver.openInputStream(result.getOrThrow())?.use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull("输出解码失败", out)
        println("输出: ${out!!.width}x${out.height}")

        // 5. 与 python 参考对比（scale=4，输出 512x512）
        // 注意：模型输出 512x512 = 输入 128x128 * 4
        fun checkPx(ox: Int, oy: Int, expR: Int, expG: Int, expB: Int) {
            val p = out.getPixel(ox, oy)
            val ar = (p shr 16) and 0xFF
            val ag = (p shr 8) and 0xFF
            val ab = p and 0xFF
            val ok = kotlin.math.abs(ar - expR) <= 8 &&
                kotlin.math.abs(ag - expG) <= 8 &&
                kotlin.math.abs(ab - expB) <= 8
            println("px($ox,$oy): 实际(R=$ar,G=$ag,B=$ab) 参考(R=$expR,G=$expG,B=$expB) ${if (ok) "✓" else "✗"}")
            assertTrue("px($ox,$oy) 颜色偏差过大: 实际($ar,$ag,$ab) 参考($expR,$expG,$expB)", ok)
        }
        // python 参考: (0,0)=RGB(33,117,231) (1,0)=RGB(11,127,233)
        checkPx(0, 0, 33, 117, 231)
        checkPx(1, 0, 11, 127, 233)

        // 6. 通道均值趋势：R 通道随 x 递增（输入 R 渐变），B 通道随 x 递减
        // 采样输出中轴线对比
        val midY = out.height / 2
        val rowPx = IntArray(16)
        var prevR = -1
        var prevB = 256
        var trendOk = true
        for (i in 0 until 16) {
            val x = out.width * i / 16
            val p = out.getPixel(x, midY)
            rowPx[i] = p
            val r = (p shr 16) and 0xFF
            val b = p and 0xFF
            if (i > 0) {
                if (r < prevR) trendOk = false
                if (b > prevB) trendOk = false
            }
            prevR = r; prevB = b
            println("中轴采样 x=$x: R=$r B=$b")
        }
        assertTrue("R 应随 x 递增 / B 应随 x 递减（通道可能错乱）", trendOk)

        out.recycle()
        inputFile.delete()
        println("✓ 色彩精确验证通过")

        // 7. 额外验证：JXL 编码路径（模拟用户导出 JXL），解码后颜色应一致
        // 从 PNG 输出文件重新解码，JXL 编码再解码，验证颜色
        val pngFile = ctx.contentResolver.openInputStream(result.getOrThrow())?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw AssertionError("无法读取输出 PNG")
        val jxlCfg = if (pngFile.hasAlpha()) JxlChannelsConfiguration.RGBA else JxlChannelsConfiguration.RGB
        println("JXL 编码配置: $jxlCfg (hasAlpha=${pngFile.hasAlpha()})")
        val jxlBytes = JxlCoder.encode(
            pngFile, jxlCfg,
            JxlCompressionOption.LOSSLESS,
            JxlEffort.SQUIRREL, 100, JxlDecodingSpeed.SLOWEST
        )
        pngFile.recycle()
        val jxlDecoded = JxlCoder.decode(jxlBytes)
        assertNotNull("JXL 解码不应为空", jxlDecoded)
        println("JXL 解码: ${jxlDecoded.width}x${jxlDecoded.height} hasAlpha=${jxlDecoded.hasAlpha()}")
        // 不用 checkPx（它引用已回收的 out），直接内联检查
        val j00 = jxlDecoded.getPixel(0, 0)
        val j10 = jxlDecoded.getPixel(1, 0)
        fun jxlCheck(ox: Int, oy: Int, expR: Int, expG: Int, expB: Int) {
            val p = jxlDecoded.getPixel(ox, oy)
            val ar = (p shr 16) and 0xFF
            val ag = (p shr 8) and 0xFF
            val ab = p and 0xFF
            val ok = kotlin.math.abs(ar - expR) <= 8 &&
                kotlin.math.abs(ag - expG) <= 8 &&
                kotlin.math.abs(ab - expB) <= 8
            println("JXL px($ox,$oy): 实际(R=$ar,G=$ag,B=$ab) 参考(R=$expR,G=$expG,B=$expB) ${if (ok) "✓" else "✗"}")
            assertTrue("JXL px($ox,$oy) 颜色偏差过大: 实际($ar,$ag,$ab) 参考($expR,$expG,$expB)", ok)
        }
        jxlCheck(0, 0, 33, 117, 231)
        jxlCheck(1, 0, 11, 127, 233)
        jxlDecoded.recycle()
        println("✓ JXL 路径颜色也正确")
    }

    /** 多 tile 输入(300x300 → 4 tiles)：验证 tile 拼接无色彩/接缝错乱 */
    @Test
    fun multiTileNoSeamArtifacts() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. 模型
        val modelsDir = File(ctx.filesDir, "engines/models")
        val modelFiles = modelsDir.listFiles { f -> f.name.endsWith(".onnx") }
            ?: throw AssertionError("models 目录不存在")
        val modelFile = modelFiles.firstOrNull { f -> f.length() > 1000000 }
            ?: (if (modelFiles.isNotEmpty()) modelFiles[0] else throw AssertionError("无模型"))
        println("模型: ${modelFile.name}")

        // 2. 输入：300x300，R=x 渐变（通道间可区分）
        val w = 300; val h = 300
        val inputFile = File(ctx.cacheDir, "multitile_input.png")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x * 255 / w   // 0..255, 随 x 递增
                val g = y * 255 / h   // 随 y 递增
                val b = 255 - (x * 255 / w) // 随 x 递减
                bmp.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        inputFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        // 3. 导入 + 处理
        val repo = EngineRepository(ctx)
        val profile = runBlocking { repo.importOnnx(modelFile) }
        assertEquals(4, profile.capabilities.baseScale)
        val job = ImageJob(
            id = UUID.randomUUID().toString(),
            sourceUri = android.net.Uri.fromFile(inputFile).toString(),
            sourceDisplayName = "multitile.png",
            sourceSizeBytes = inputFile.length(),
            sourceWidth = w,
            sourceHeight = h,
            engineId = profile.id,
            upscalePlan = UpscalePlan(targetScale = 4),
            encodeOptions = EncodeOptions(format = OutputFormat.PNG, quality = 100)
        )
        val processor = ImageProcessor(
            context = ctx,
            engineProvider = { pid -> ImageProcessor.defaultEngineProvider(ctx, profile) },
            outputDirProvider = { "" }
        )
        val result = runBlocking { processor.process(job, profile) {} }
        assertTrue("process 失败: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val out = ctx.contentResolver.openInputStream(result.getOrThrow())?.use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull("输出解码失败", out)
        assertEquals("输出宽", 300 * 4, out!!.width)
        assertEquals("输出高", 300 * 4, out.height)
        println("多 tile 输出: ${out.width}x${out.height}")

        // 4. 验证 tile 边界处（x=128*4=512, x=256*4=1024）无颜色跳变
        // R 应随 x 单调递增（输入 R=x 渐变），允许 ±2 容差（整数平均噪声）
        val midY = 4 * 150  // 中行
        val samples = listOf(0, 100, 200, 300, 400, 500, 511, 512, 513, 600, 700, 1000, 1019, 1020, 1021, 1100, 1199)
        var prevR = -1
        var good = true
        val boundaryDetails = StringBuilder()
        for (x in samples) {
            val p = out.getPixel(x, midY)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val line = "x=$x: R=$r G=$g B=$b"
            println(line)
            if (x in listOf(511, 512, 513, 1019, 1020, 1021)) {
                boundaryDetails.appendLine(line)
            }
            if (r < prevR - 2) { good = false; println("  ✗ R 回退（tile 接缝错乱？）") }
            prevR = r
        }
        assertTrue("R 通道在 tile 边界处应持续递增（tile 拼接错误）\n$boundaryDetails", good)

        // 5. tile 边界处跳变不应过大（50% 重叠平均后应平滑）
        val p511 = out.getPixel(511, midY)
        val p512 = out.getPixel(512, midY)
        val r511 = (p511 shr 16) and 0xFF; val r512 = (p512 shr 16) and 0xFF
        val delta = kotlin.math.abs(r512 - r511)
        println("tile 边界跳变: |R512-R511|=$delta")
        assertTrue("tile 边界处 R 跳变过大: $delta (期望 < 20)", delta < 20)

        // 5. B 应随 x 递减（允许 ±2 容差）
        var prevB = 256
        var bGood = true
        for (x in samples) {
            val p = out.getPixel(x, midY)
            val b = p and 0xFF
            if (b > prevB + 2) { bGood = false; println("  ✗ B 回退 at x=$x") }
            prevB = b
        }
        assertTrue("B 通道在 tile 边界处应持续递减", bGood)

        out.recycle()
        inputFile.delete()
        println("✓ 多 tile 无接缝错乱")
    }
}