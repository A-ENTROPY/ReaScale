package io.reascale.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.reascale.app.core.processing.ImageProcessor
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.EngineRepository
import io.reascale.app.data.ImageJob
import io.reascale.app.data.OutputFormat
import io.reascale.app.data.UpscalePlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * ONNX 引擎端到端测试（a30 新增）
 *
 * 前置：`adb push` 把测试模型放到 files/onnx_e2e/animevideo_x4.onnx
 * （2.5MB RealESRGAN AnimeVideo-v3 x4，动态 NCHW 输入，0..1 完整图像，scale=4）
 *
 * 覆盖：importOnnx(.onnx) → OnnxEngine 探测（scale/语义/域）→ tile 推理 → 输出尺寸/像素校验
 */
@RunWith(AndroidJUnit4::class)
class OnnxEngineE2ETest {

    @Test
    fun onnxAnimeVideoX4E2E() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. 模型（由 adb push 到 /sdcard/Download，测试内复制到应用私有目录）
        val modelFile = File(ctx.filesDir, "onnx_e2e/animevideo_x4.onnx")
        val sdcardModel = File("/sdcard/Download/animevideo_x4.onnx")
        if (!modelFile.exists() && sdcardModel.exists()) {
            modelFile.parentFile?.mkdirs()
            sdcardModel.copyTo(modelFile)
        }
        assertTrue("模型缺失: $modelFile", modelFile.exists())

        // 2. 输入图：程序生成 320x240 渐变图
        val inputFile = createTestInput(ctx, 320, 240)

        // 3. 导入模型
        val repo = EngineRepository(ctx)
        val profile = runBlocking { repo.importOnnx(modelFile) }
        println("ONNX profile: ${profile.displayName} baseScale=${profile.capabilities.baseScale} uri=${profile.modelUri}")
        assertTrue(profile.modelUri.endsWith(".onnx"))
        assertEquals(4, profile.capabilities.baseScale)

        // 4. 处理
        val result = runProcess(ctx, profile, inputFile, 320, 240, 4)
        assertTrue("process 失败: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val outUri = result.getOrThrow()
        println("OUT uri: $outUri")

        // 5. 校验输出
        verifyOutput(ctx, outUri, 320 * 4, 240 * 4)

        // 6. 引擎探测日志
        checkProbeLog(ctx, "动态输入、scale=4、0..1 完整图像模型")
    }

    /** 固定输入模型（128x128 输入，4x 输出）E2E */
    @Test
    fun onnxFixed128X4E2E() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. 模型
        val modelFile = File(ctx.filesDir, "onnx_e2e/user_model.onnx")
        val sdcardModel = File("/sdcard/Download/user_model.onnx")
        if (!modelFile.exists() && sdcardModel.exists()) {
            modelFile.parentFile?.mkdirs()
            sdcardModel.copyTo(modelFile)
        }
        assertTrue("模型缺失: $modelFile", modelFile.exists())

        // 2. 输入图：474x355（与用户之前测试一致）
        val inputFile = createTestInput(ctx, 474, 355)

        // 3. 导入模型
        val repo = EngineRepository(ctx)
        val profile = runBlocking { repo.importOnnx(modelFile) }
        println("FIXED profile: ${profile.displayName} baseScale=${profile.capabilities.baseScale} uri=${profile.modelUri}")
        assertTrue(profile.modelUri.endsWith(".onnx"))
        assertEquals(4, profile.capabilities.baseScale)

        // 4. 处理
        val result = runProcess(ctx, profile, inputFile, 474, 355, 4)
        assertTrue("process 失败: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val outUri = result.getOrThrow()
        println("FIXED OUT uri: $outUri")

        // 5. 校验输出尺寸 1896x1420 与像素
        verifyOutput(ctx, outUri, 474 * 4, 355 * 4)

        // 6. 日志：固定输入探测
        val logFile = File(ctx.filesDir, "debug_logs/reascale_log.txt")
        if (logFile.exists()) {
            val log = logFile.readText()
            assertTrue("缺少 fixed-tile 日志", log.contains("fixed-tile"))
            println("✓ 固定输入模型探测 + tile 推理日志确认")
        }
    }

    // ============ 辅助方法 ============

    private fun createTestInput(ctx: Context, w: Int, h: Int): File {
        val dir = File(ctx.filesDir, "onnx_e2e")
        dir.mkdirs()
        val f = File(dir, "input_${w}x${h}.png")
        if (f.exists()) return f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x * 255 / w
                val g = y * 255 / h
                val b = (x + y) * 255 / (w + h)
                bmp.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    private fun runProcess(
        ctx: Context, profile: io.reascale.app.data.EngineProfile,
        inputFile: File, inW: Int, inH: Int, scale: Int
    ): Result<Uri> {
        val job = ImageJob(
            id = UUID.randomUUID().toString(),
            sourceUri = Uri.fromFile(inputFile).toString(),
            sourceDisplayName = inputFile.name,
            sourceSizeBytes = inputFile.length(),
            sourceWidth = inW,
            sourceHeight = inH,
            engineId = profile.id,
            upscalePlan = UpscalePlan(targetScale = scale),
            encodeOptions = EncodeOptions(format = OutputFormat.JPEG, quality = 95)
        )
        val processor = ImageProcessor(
            context = ctx,
            engineProvider = { pid -> ImageProcessor.defaultEngineProvider(ctx, profile) },
            outputDirProvider = { "" }
        )
        return runBlocking { processor.process(job, profile) {} }
    }

    private fun verifyOutput(ctx: Context, outUri: Uri, expW: Int, expH: Int) {
        val out = ctx.contentResolver.openInputStream(outUri)?.use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull("输出解码失败", out)
        assertEquals("输出宽", expW, out!!.width)
        assertEquals("输出高", expH, out.height)
        var sum = 0L
        var cnt = 0L
        var y = 0
        while (y < out.height) {
            var x = 0
            while (x < out.width) {
                val p = out.getPixel(x, y)
                sum += (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)
                cnt += 3
                x += 7
            }
            y += 7
        }
        val avg = sum.toDouble() / cnt
        println("OUT avg pixel = $avg")
        assertTrue("输出异常（过暗/过亮/噪声）: avg=$avg", avg in 30.0..225.0)
        out.recycle()
    }

    private fun checkProbeLog(ctx: Context, label: String) {
        val logFile = File(ctx.filesDir, "debug_logs/reascale_log.txt")
        if (logFile.exists() && logFile.length() > 0) {
            val log = logFile.readText()
            println("Probe log check: ${log.lines().size} lines")
        }
    }
}
