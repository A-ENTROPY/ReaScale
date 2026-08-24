package io.reascale.app

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.awxkee.jxlcoder.JxlCoder
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
 * 流式 JXL 端到端：3200x3000 输入（>32M pixel 走 processTiled）→ 用户模型 4x
 * → 输出 12800x12000 ≈ 1.9 亿像素（>200MB bitmap 阈值）→ 流式 JXL 直写 MediaStore。
 *
 * 验证：
 * 1. process 不抛"输出图片过大"
 * 2. 输出文件为合法 JXL（可 getSize）
 * 3. 输出尺寸 = 输入 × 模型倍数
 */
@RunWith(AndroidJUnit4::class)
class OnnxStreamingJxlE2ETest {

    @Test
    fun streamingJxlLargeOutput() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. 模型
        val modelsDir = File(ctx.filesDir, "engines/models")
        val modelFiles = modelsDir.listFiles { f -> f.name.endsWith(".onnx") }
            ?: throw AssertionError("models 目录不存在")
        val modelFile = modelFiles.firstOrNull { f -> f.length() > 1000000 }
            ?: (if (modelFiles.isNotEmpty()) modelFiles[0] else throw AssertionError("无模型"))
        println("模型: ${modelFile.name}")

        // 2. 输入 3200x3000（>32M pixel → processTiled 路径）
        val w = 3200; val h = 3000
        val inputFile = File(ctx.cacheDir, "stream_e2e_input.png")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x * 255 / w
                val g = y * 255 / h
                val b = 255 - x * 255 / w
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        inputFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        println("输入: ${inputFile.length()} bytes(${w}x$h)")

        // 3. 导入模型
        val repo = EngineRepository(ctx)
        val profile = runBlocking { repo.importOnnx(modelFile) }
        val sc = profile.capabilities.baseScale
        println("scale=$sc")

        // 4. 处理（JXL 质量 90）
        val job = ImageJob(
            id = UUID.randomUUID().toString(),
            sourceUri = android.net.Uri.fromFile(inputFile).toString(),
            sourceDisplayName = "stream_e2e.png",
            sourceSizeBytes = inputFile.length(),
            sourceWidth = w,
            sourceHeight = h,
            engineId = profile.id,
            upscalePlan = UpscalePlan(targetScale = sc),
            encodeOptions = EncodeOptions(format = OutputFormat.JXL, quality = 90)
        )
        val processor = ImageProcessor(
            context = ctx,
            engineProvider = { pid -> ImageProcessor.defaultEngineProvider(ctx, profile) },
            outputDirProvider = { "" }
        )
        val result = runBlocking { processor.process(job, profile) {} }
        assertTrue("process 失败: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val uri = result.getOrThrow()
        println("输出 uri: $uri")

        // 5. 验证输出
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw AssertionError("无法读取输出")
        println("输出字节: ${bytes.size}")
        assertTrue("输出过小: ${bytes.size}", bytes.size > 10_000)
        val size = JxlCoder.getSize(bytes)
        assertNotNull("输出不是合法 JXL", size)
        assertEquals("输出宽", w * sc, size!!.width)
        assertEquals("输出高", h * sc, size.height)
        println("✓ 流式 JXL E2E 通过: ${size.width}x${size.height}, ${bytes.size} bytes")
    }
}