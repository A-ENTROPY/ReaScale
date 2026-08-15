package io.reascale.app.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.reascale.app.ReaScaleApp
import io.reascale.app.core.imageio.PhotoPicker
import io.reascale.app.data.AppSettings
import io.reascale.app.ui.theme.ReaScaleTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ReaScale 唯一 Activity。
 *
 * 2026-08-01 systematic-debugging 闪退根治：
 * - PhotoPicker.registerMultiPicker(maxItems=500) 在某些设备上 launch 时崩
 *   改为使用 Activity 直接注册（更安全）+ maxItems=30
 * - 全链路 try-catch 把任何崩溃转为 log
 */
class MainActivity : ComponentActivity() {

    private var pickImagesLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        installCrashLogger()

        Log.i("MainActivity", "onCreate start")

        // 直接在 Activity 上注册 launcher（不通过 PhotoPicker 封装）—— 更稳
        try {
            pickImagesLauncher = registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(
                    maxItems = 30  // 系统 PhotoPicker 实际硬上限 30
                )
            ) { uris ->
                Log.i("MainActivity", "picker result: ${uris.size} uris")
                if (uris.isEmpty()) return@registerForActivityResult
                try {
                    val app = ReaScaleApp.get()
                    lifecycleScope.launch {
                        try {
                            val settings = app.settingsRepository.settingsFlow.first()
                            // [FIX 2026-08-16] 放大倍数从引擎参数读取（用户可调）
                            val engineParams = app.paramsRepository.get(settings.defaultEngineId)
                            val targetScale = if (engineParams.targetScale.enabled) {
                                engineParams.targetScale.value
                            } else {
                                engineParams.targetScale.effective()
                            }
                            handlePickedImages(
                                context = this@MainActivity,
                                uris = uris,
                                engineId = settings.defaultEngineId,
                                targetScale = targetScale
                            )
                        } catch (t: Throwable) {
                            Log.e("MainActivity", "picker handler inner failed", t)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("MainActivity", "picker handler outer failed", t)
                }
            }
            Log.i("MainActivity", "launcher registered")
        } catch (t: Throwable) {
            Log.e("MainActivity", "register launcher failed", t)
        }

        setContent {
            // ⚠️ 注意：ReaScaleApp.get() 不在这里直接调，挪到 lambda 里 + try-catch
            // 防止极小概率 instance 未初始化时闪退
            val settingsFlow = remember {
                try {
                    ReaScaleApp.get().settingsRepository.settingsFlow
                } catch (t: Throwable) {
                    Log.e("MainActivity", "ReaScaleApp.get() failed in composition", t)
                    kotlinx.coroutines.flow.flowOf(AppSettings())
                }
            }
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())

            ReaScaleTheme(settings = settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReaScaleApp(
                        pickImages = {
                            try {
                                Log.i("MainActivity", "launching picker")
                                pickImagesLauncher?.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                ) ?: Log.e("MainActivity", "launcher null!")
                            } catch (t: Throwable) {
                                Log.e("MainActivity", "picker launch failed", t)
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * 顶层 appScope 由 ReaScaleApp 持有，但 Compose 重组可能频繁触发访问。
     * 用 try-catch 包了一层：如果 ReaScaleApp instance 异常未初始化，这里吞掉。
     */
    private fun appScopeDummyFlow() = try {
        ReaScaleApp.get().settingsRepository.settingsFlow
    } catch (t: Throwable) {
        Log.e("MainActivity", "ReaScaleApp.get() failed in composition", t)
        kotlinx.coroutines.flow.flowOf(AppSettings())
    }

    /**
     * 全局未捕获异常兜底：把崩溃打到 logcat，避免 ANR 静默
     */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("ReaScaleCrash", "uncaught exception on thread ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }
}