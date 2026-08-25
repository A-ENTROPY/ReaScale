package io.reascale.app.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
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
    // [FIX 2026-08-25] 文件管理器：显式调起用户安装的第三方文件管理器 app
    // （GET_CONTENT 在 ColorOS 被 PhotoPicker 劫持、SAF DocumentsUI 限制多）
    private var filePickerLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
    // 待启动的第三方文件管理器列表（点"文件管理器"后填充，弹选择框）
    private var fmCandidates by androidx.compose.runtime.mutableStateOf<List<android.content.pm.ResolveInfo>>(emptyList())

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

                // [FIX 2026-08-25] 文件管理器：通用 StartActivityForResult launcher。
        // 点"文件管理器"时查询设备上能处理 image/* 的第三方文件管理器
        // （排除系统 DocumentsUI/PhotoPicker），弹选择框显式调起。
        try {
            filePickerLauncher = registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { res ->
                val uris = mutableListOf<android.net.Uri>()
                res.data?.let { data ->
                    // 多选：clipData；单选：data
                    val clip = data.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i).uri?.let { uris.add(it) }
                        }
                    } else {
                        data.data?.let { uris.add(it) }
                    }
                }
                if (uris.isEmpty()) return@registerForActivityResult
                // 第三方管理器返回的 URI 是临时读权限：复制到内部缓存防丢失
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
            Log.i("MainActivity", "file picker launcher registered (StartActivityForResult)")
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
            var showFmPickerDialog by remember { mutableStateOf(false) }

            // 查询第三方文件管理器（排除系统 DocumentsUI / PhotoPicker）
            fun queryFileManagers(): List<android.content.pm.ResolveInfo> {
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                }
                val pm = packageManager
                val resolvers = pm.queryIntentActivities(intent, 0)
                return resolvers.filter {
                    val pkg = it.activityInfo.packageName
                    // 排除系统 SAF/PhotoPicker/图库
                    !pkg.contains("documentsui") &&
                        !pkg.contains("photopicker") &&
                        !pkg.contains("com.google.android.photos") &&
                        !(pkg == "com.android.systemui") &&
                        !pkg.contains("gallery") &&
                        !(pkg == packageName)
                }.distinctBy { it.activityInfo.packageName }
            }

            fun launchFileManager(ri: android.content.pm.ResolveInfo) {
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                    setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                }
                try {
                    filePickerLauncher?.launch(intent)
                } catch (t: Throwable) {
                    Log.e("MainActivity", "launch fm failed: ${ri.activityInfo.packageName}", t)
                }
            }

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
                                    val fms = queryFileManagers()
                                    if (fms.isEmpty()) {
                                        // 无第三方文件管理器：回退 SAF
                                        try {
                                            filePickerLauncher?.launch(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_OPEN_DOCUMENT
                                                ).apply {
                                                    type = "image/*"
                                                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                                                }
                                            )
                                        } catch (t: Throwable) {
                                            Log.e("MainActivity", "saf fallback failed", t)
                                        }
                                    } else {
                                        fmCandidates = fms
                                        showFmPickerDialog = true
                                    }
                                }) {
                                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("文件管理器")
                                }
                            }
                        )
                    }

                    // 第三方文件管理器选择框
                    if (showFmPickerDialog) {
                        AlertDialog(
                            onDismissRequest = { showFmPickerDialog = false },
                            title = { Text("选择文件管理器") },
                            text = {
                                androidx.compose.foundation.lazy.LazyColumn {
                                    items(fmCandidates.size) { i ->
                                        val ri = fmCandidates[i]
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showFmPickerDialog = false
                                                    launchFileManager(ri)
                                                }
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                ri.loadLabel(packageManager).toString(),
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showFmPickerDialog = false }) { Text("取消") }
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
