package io.reascale.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.reascale.app.R
import io.reascale.app.ReaScaleApp
import io.reascale.app.core.imageio.ImageProbe
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.ImageJob
import io.reascale.app.data.UpscalePlan
import io.reascale.app.ui.screen.EngineConfigScreen
import io.reascale.app.ui.screen.EnginePickerScreen
import io.reascale.app.ui.screen.HomeScreen
import io.reascale.app.ui.screen.LogViewerScreen
import io.reascale.app.ui.screen.QueueScreen
import io.reascale.app.ui.screen.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 4 Tab 根容器（Design.md §30.8.2 / §30.16：底部导航永远可见）。
 * M1: 接入 PhotoPicker 真实回调，把选中的图批量入队。
 */
private enum class Tab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Home("home",     R.string.nav_home,     Icons.Outlined.Home),
    Queue("queue",    R.string.nav_queue,    Icons.Outlined.Queue),
    Engines("engines", R.string.nav_engines, Icons.Outlined.Memory),
    Settings("settings", R.string.nav_settings, Icons.Outlined.Settings),
}

@Composable
fun ReaScaleApp(
    pickImages: () -> Unit
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val app = ReaScaleApp.get()
    val queueJobs = app.queueManager.jobs
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Tab.entries.forEach { tab ->
                    val selected = currentRoute == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon  = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Tab.Home.route) {
                HomeScreen(
                    queueJobs = queueJobs,
                    onPickImages = pickImages,
                    onOpenQueue = { navController.navigate(Tab.Queue.route) },
                    onOpenEngines = { navController.navigate(Tab.Engines.route) },
                    onOpenSettings = { navController.navigate(Tab.Settings.route) }
                )
            }
            composable(Tab.Queue.route)    { QueueScreen(jobs = queueJobs, onPickImages = pickImages) }
            composable(Tab.Engines.route)  {
                EnginePickerScreen(
                    onOpenConfig = { engineId ->
                        navController.navigate("engine_config/$engineId")
                    }
                )
            }
            composable(
                route = "engine_config/{engineId}",
                arguments = listOf(navArgument("engineId") { type = NavType.StringType })
            ) { entry ->
                val engineId = entry.arguments?.getString("engineId") ?: ""
                EngineConfigScreen(
                    engineId = engineId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Tab.Settings.route) {
                SettingsScreen(
                    onOpenDebugLog = { navController.navigate("debuglog") }
                )
            }
            composable("debuglog") {
                LogViewerScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    // 让 lambda 在参数传入时实际触发 picker；这里不需要额外逻辑，
    // 因为 picker launcher 在 MainActivity 持有并通过 pickImages 触发。
    // 选完图后由 MainActivity 的回调把 uris 传给 handlePickedImages。
    // 提供一个隐式 side-effect-free 包装，调用方可直接传 lambda 进来。
    @Suppress("UNUSED_EXPRESSION") pickImages
}

/**
 * 处理 PhotoPicker 选中后的 Uri 列表
 * - 探测元数据
 * - 用默认设置构造 ImageJob
 * - 批量入队
 *
 * 闪退修复：
 * 1. 每张图单独 try-catch（SecurityException / IOException 不影响其他）
 * 2. displayName 安全取（避免 NPE）
 *
 * 暴露为顶层函数供 MainActivity 调用
 */
fun handlePickedImages(
    context: android.content.Context,
    uris: List<Uri>,
    engineId: String,
    targetScale: Int,
    settings: io.reascale.app.data.AppSettings = io.reascale.app.data.AppSettings()
) {
    try {
        val app = ReaScaleApp.get()
        val scope = app.appScope
        scope.launch(Dispatchers.IO) {
            try {
                val jobs = uris.mapNotNull { uri ->
                    try {
                        val meta = ImageProbe.probe(context, uri) ?: return@mapNotNull null
                        val displayName = runCatching {
                            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                                if (c.moveToFirst()) {
                                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (idx >= 0) c.getString(idx) else null
                                } else null
                            }
                        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
                        ImageJob(
                            id = "",
                            sourceUri = uri.toString(),
                            sourceDisplayName = displayName,
                            sourceSizeBytes = meta.fileSizeBytes,
                            sourceWidth = meta.width,
                            sourceHeight = meta.height,
                            engineId = engineId,
                            upscalePlan = UpscalePlan(targetScale = targetScale),
                            // [FIX 2026-08-17] 使用设置页配置的默认格式/质量
                            // （原固定 EncodeOptions()，设置页的默认格式/质量形同虚设）
                            encodeOptions = settings.encodeOptions
                        )
                    } catch (t: Throwable) {
                        android.util.Log.w("handlePickedImages", "skip uri=$uri", t)
                        null
                    }
                }
                if (jobs.isNotEmpty()) {
                    app.queueManager.enqueueAll(jobs)
                }
            } catch (t: Throwable) {
                android.util.Log.e("handlePickedImages", "batch failed", t)
            }
        }
    } catch (t: Throwable) {
        android.util.Log.e("handlePickedImages", "launch failed", t)
    }
}