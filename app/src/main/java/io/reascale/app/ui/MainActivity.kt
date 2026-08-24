package io.reascale.app.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.reascale.app.ReaScaleApp
import io.reascale.app.core.imageio.PhotoPicker
import io.reascale.app.data.AppSettings
import io.reascale.app.ui.theme.ReaScaleTheme
import kotlinx.coroutines.Dispatchers
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
    private var filePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        installCrashLogger()

        Log.i("MainActivity", "onCreate start")

        // [FIX 2026-08-17] Android 13+ 通知权限（前台服务通知需要）
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

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
                                targetScale = targetScale,
                                settings = settings
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

        // 文件管理器选图（ACTION_GET_CONTENT, image/*，原生文件管理器支持全选多选）
        try {
            filePickerLauncher = registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
            ) { uris ->
                if (uris.isEmpty()) return@registerForActivityResult
                // GET_CONTENT URI 无 persistable 权限，复制到内部缓存防丢失
                lifecycleScope.launch(Dispatchers.IO) {
                    val fileUris = uris.mapIndexedNotNull { i, uri ->
                        runCatching {
                            val mime = contentResolver.getType(uri)
                            val ext = when {
                                mime == null -> "jpg"
                                mime.contains("png", true) -> "png"
                                mime.contains("webp", true) -> "webp"
                                else -> "jpg"
                            }
                            val dest = java.io.File(cacheDir, "picked/${System.currentTimeMillis()}_$i.$ext").apply {
                                parentFile?.mkdirs()
                            }
                            contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
                            android.net.Uri.fromFile(dest)
                        }.getOrNull().also { if (it == null) Log.w("MainActivity", "copy failed for uri=$i") }
                    }
                    if (fileUris.isNotEmpty()) {
                        try {
                            val app = ReaScaleApp.get()
                            val settings = app.settingsRepository.settingsFlow.first()
                            val engineParams = app.paramsRepository.get(settings.defaultEngineId)
                            val targetScale = if (engineParams.targetScale.enabled) {
                                engineParams.targetScale.value
                            } else {
                                engineParams.targetScale.effective()
                            }
                            handlePickedImages(
                                context = this@MainActivity,
                                uris = fileUris,
                                engineId = settings.defaultEngineId,
                                targetScale = targetScale,
                                settings = settings
                            )
                        } catch (t: Throwable) {
                            Log.e("MainActivity", "file picker handler failed", t)
                        }
                    }
                }
            }
            Log.i("MainActivity", "file picker launcher registered (GetMultipleContents)")
        } catch (t: Throwable) {
            Log.e("MainActivity", "register file picker failed", t)
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
            var showSourceDialog by remember { mutableStateOf(false) }

            ReaScaleTheme(settings = settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReaScaleApp(
                        pickImages = { showSourceDialog = true }
                    )

                    // 来源选择对话框
                    if (showSourceDialog) {
                        AlertDialog(
                            onDismissRequest = { showSourceDialog = false },
                            title = { Text("选择图片来源") },
                            text = { Text("从相册批量选择，或通过文件管理器打开单张图片") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showSourceDialog = false
                                    try {
                                        pickImagesLauncher?.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        ) ?: Log.e("MainActivity", "launcher null!")
                                    } catch (t: Throwable) {
                                        Log.e("MainActivity", "picker launch failed", t)
                                    }
                                }) {
                                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("相册")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showSourceDialog = false
                                    try {
                                        filePickerLauncher?.launch("image/*")
                                    } catch (t: Throwable) {
                                        Log.e("MainActivity", "file picker launch failed", t)
                                    }
                                }) {
                                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("文件管理器")
                                }
                            }
                        )
                    }
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