package io.reascale.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reascale.app.R
import io.reascale.app.ReaScaleApp
import io.reascale.app.data.AppSettings
import io.reascale.app.data.OutputFormat
import io.reascale.app.data.ThemeMode
import io.reascale.app.debug.LogBus
import io.reascale.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 设置页（Design.md §30.8.7）：分组卡片 + 滚动 + DataStore 实时绑定
 *
 * 关键修复（vs M0 原版）：
 * 1. 整页可滚动（verticalScroll）
 * 2. 主题设置（SYSTEM / LIGHT / DARK）使用 SegmentedButton
 * 3. AMOLED 开关 / 动态取色 开关 写入 DataStore 立即生效
 * 4. 设置项分 4 组卡片：外观 / 处理 / 性能 / 关于
 * 5. 顶部品牌横幅
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenDebugLog: () -> Unit = {}) {
    val app = ReaScaleApp.get()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val engineRepository = app.engineRepository
    val settings by app.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = AppSettings()
    )
    // [FIX 2026-08-17] 设置对话框状态（默认引擎/格式/质量/许可/帮助）
    var showEnginePicker by remember { mutableStateOf(false) }
    var showFormatPicker by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // [FIX 2026-08-17] SAF 输出目录选择器（OpenDocumentTree）
    val dirPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            scope.launch {
                app.settingsRepository.update { it.copy(outputDirUri = uri.toString()) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.xs))
            BrandBanner()

            // === 外观 ===
            SettingsGroup(
                title = stringResource(R.string.settings_appearance),
                icon = Icons.Outlined.Palette,
                description = "主题、颜色、显示"
            ) {
                // 主题模式（SYSTEM / LIGHT / DARK）—— 核心
                ThemeModeSelector(
                    current = settings.themeMode,
                    onChange = { mode ->
                        scope.launch {
                            app.settingsRepository.update { it.copy(themeMode = mode) }
                        }
                    }
                )
                SettingsDivider()

                // 动态取色（Material You）—— 独立开关
                SettingsSwitchItem(
                    icon = Icons.Outlined.ColorLens,
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = "Material You · 从壁纸取色（仅 Android 12+）",
                    checked = settings.useDynamicColor,
                    onCheckedChange = { v ->
                        scope.launch {
                            app.settingsRepository.update { it.copy(useDynamicColor = v) }
                        }
                    }
                )
                SettingsDivider()

                // AMOLED 纯黑 —— 独立开关（关键！）
                SettingsSwitchItem(
                    icon = Icons.Outlined.Brightness4,
                    title = stringResource(R.string.settings_amoled),
                    subtitle = "深色模式背景设为 #000000 纯黑",
                    checked = settings.amoled,
                    onCheckedChange = { v ->
                        scope.launch {
                            app.settingsRepository.update { it.copy(amoled = v) }
                        }
                    }
                )
            }

            // === 处理 ===
            SettingsGroup(
                title = stringResource(R.string.settings_processing),
                icon = Icons.Outlined.Tune,
                description = "默认引擎、输出格式、质量（新任务生效）"
            ) {
                // [FIX 2026-08-17] 默认引擎可点击修改（原来只读展示且 id 截断显示）
                val engineName = engineRepository.profiles.value.firstOrNull { it.id == settings.defaultEngineId }
                    ?.displayName ?: settings.defaultEngineId
                SettingsClickableItem(
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.settings_default_engine),
                    subtitle = engineName,
                    onClick = { showEnginePicker = true }
                )
                SettingsDivider()
                SettingsClickableItem(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_default_format),
                    subtitle = formatDisplayName(settings.encodeOptions.format) +
                        " · Q${settings.encodeOptions.quality}",
                    onClick = { showFormatPicker = true }
                )
                SettingsDivider()
                SettingsClickableItem(
                    icon = Icons.Outlined.ColorLens,
                    title = stringResource(R.string.settings_default_quality),
                    subtitle = "质量 ${settings.encodeOptions.quality}（1-100）",
                    onClick = { showQualityPicker = true }
                )
                SettingsDivider()
                // [FIX 2026-08-17] 输出目录：SAF 目录选择（原来只有字段没有 UI/实现）
                SettingsClickableItem(
                    icon = Icons.Outlined.FolderOpen,
                    title = "输出目录",
                    subtitle = if (settings.outputDirUri.isBlank()) {
                        "默认（相册 Pictures/ReaScale）"
                    } else {
                        "自定义目录（点按更换，长按恢复默认）"
                    },
                    onClick = { dirPickerLauncher.launch(null) },
                    onLongClick = {
                        scope.launch {
                            app.settingsRepository.update { it.copy(outputDirUri = "") }
                        }
                    }
                )
                SettingsDivider()
                SettingsSwitchItem(
                    icon = Icons.Outlined.PhotoCamera,
                    title = stringResource(R.string.settings_run_in_background),
                    subtitle = "队列运行中显示前台通知，锁屏也继续处理",
                    checked = settings.enableForegroundService,
                    onCheckedChange = { v ->
                        scope.launch {
                            app.settingsRepository.update { it.copy(enableForegroundService = v) }
                        }
                    }
                )
            }

            // === 性能 ===
            SettingsGroup(
                title = stringResource(R.string.settings_perf),
                icon = Icons.Outlined.Memory,
                description = "同时处理的图片数量（影响发热与速度）"
            ) {
                // [FIX 2026-08-17] 移除无效的"后端偏好"死项（实际统一走 ncnn CPU 推理）
                ConcurrencySelector(
                    current = settings.concurrency,
                    onChange = { profile ->
                        scope.launch {
                            app.settingsRepository.update { it.copy(concurrency = profile) }
                        }
                    }
                )
            }

            // === 关于 ===
            SettingsGroup(
                title = stringResource(R.string.settings_about),
                icon = Icons.Outlined.Code,
                description = null
            ) {
                // [FIX 2026-08-17] 版本号动态读取（原硬编码 0.3.1-a15 已过时）
                SettingsValueItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.settings_version),
                    value = versionName(context)
                )
                SettingsDivider()
                // [FIX 2026-08-17] GitHub/License/Help 原来无 onClick（死链），现在可点击
                SettingsLinkItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.settings_github),
                    subtitle = "开源项目（查看源码）",
                    onClick = { openUrl(context, "https://github.com/search?q=reascale") }
                )
                SettingsDivider()
                SettingsLinkItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.settings_license),
                    subtitle = "模型：MIT / 代码：Apache-2.0",
                    onClick = { showLicenseDialog = true }
                )
                SettingsDivider()
                SettingsLinkItem(
                    icon = Icons.Outlined.Help,
                    title = stringResource(R.string.settings_help),
                    subtitle = "使用说明",
                    onClick = { showHelpDialog = true }
                )
            }

            // === 调试（2026-08-02 P0 排查专用）===
            SettingsGroup(
                title = "调试",
                icon = Icons.Outlined.Code,
                description = "诊断日志 —— 复制后发给开发者排查问题"
            ) {
                SettingsClickableItem(
                    icon = Icons.Outlined.Code,
                    title = "📋 调试日志",
                    subtitle = "已收集 ${LogBus.entries.value.size} 条 · 路径: ${LogBus.sinkFilePath() ?: "（仅内存）"}",
                    onClick = { onOpenDebugLog() }
                )
            }

            Spacer(Modifier.height(Spacing.lg))
        }
    }

    // === [FIX 2026-08-17] 设置对话框 ===

    // 默认引擎选择
    if (showEnginePicker) {
        val profiles = engineRepository.profiles.value
        AlertDialog(
            onDismissRequest = { showEnginePicker = false },
            title = { Text("默认引擎") },
            text = {
                Column {
                    profiles.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        app.settingsRepository.update {
                                            it.copy(defaultEngineId = p.id)
                                        }
                                    }
                                    showEnginePicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = p.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (p.id == settings.defaultEngineId) {
                                Text(
                                    text = "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEnginePicker = false }) { Text("关闭") }
            }
        )
    }

    // 默认输出格式选择（HEIC/HEIF/AVIF/JXL 系统编码器未实现，标注不可用）
    if (showFormatPicker) {
        val supported = listOf(
            OutputFormat.JPEG, OutputFormat.PNG, OutputFormat.WEBP
        )
        val unsupported = listOf(
            OutputFormat.HEIC, OutputFormat.HEIF, OutputFormat.AVIF, OutputFormat.JXL
        )
        AlertDialog(
            onDismissRequest = { showFormatPicker = false },
            title = { Text("默认输出格式") },
            text = {
                Column {
                    supported.forEach { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        app.settingsRepository.update {
                                            it.copy(encodeOptions = it.encodeOptions.copy(format = f))
                                        }
                                    }
                                    showFormatPicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatDisplayName(f),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (settings.encodeOptions.format == f) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    unsupported.forEach { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${formatDisplayName(f)}（暂不支持）",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFormatPicker = false }) { Text("关闭") }
            }
        )
    }

    // 默认质量滑块
    if (showQualityPicker) {
        var quality by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(settings.encodeOptions.quality)
        }
        AlertDialog(
            onDismissRequest = { showQualityPicker = false },
            title = { Text("默认质量：$quality") },
            text = {
                Column {
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = { quality = it.toInt() },
                        valueRange = 1f..100f,
                        steps = 97
                    )
                    Text(
                        "100=无损/最高质量，1=最小文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.settingsRepository.update {
                            it.copy(encodeOptions = it.encodeOptions.copy(quality = quality))
                        }
                    }
                    showQualityPicker = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showQualityPicker = false }) { Text("取消") }
            }
        )
    }

    // 开源许可说明
    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("开源许可") },
            text = {
                Text(
                    "ReaScale 代码：Apache-2.0\n\n" +
                    "内置模型：\n" +
                    "· Real-CUGAN（bilibili ailab）— MIT\n" +
                    "· waifu2x — MIT\n" +
                    "· ncnn 推理框架（Tencent）— BSD-3-Clause\n\n" +
                    "模型仅用于本地离线推理。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) { Text("知道了") }
            }
        )
    }

    // 帮助说明
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("使用说明") },
            text = {
                Text(
                    "1. 主页点击「选择图片」批量添加\n" +
                    "2. 队列页查看处理进度，可暂停/取消\n" +
                    "3. 引擎页管理内置与导入模型（ncnn .param+.bin）\n" +
                    "4. 设置页调整默认引擎/格式/质量/并发\n" +
                    "5. 输出自动保存到相册 Pictures/ReaScale/\n" +
                    "6. 遇到问题：设置 → 调试日志，复制后发给开发者",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("知道了") }
            }
        )
    }
}

/**
 * 性能模式选择器（省电 / 平衡 / 性能）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConcurrencySelector(
    current: io.reascale.app.data.ConcurrencyProfile,
    onChange: (io.reascale.app.data.ConcurrencyProfile) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconItem(Icons.Outlined.Memory)
            Spacer(Modifier.size(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_memory_mode),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "同时处理的图片数量（影响发热与速度）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                io.reascale.app.data.ConcurrencyProfile.SAVER to "省电",
                io.reascale.app.data.ConcurrencyProfile.BALANCED to "平衡",
                io.reascale.app.data.ConcurrencyProfile.PERFORMANCE to "性能"
            )
            options.forEachIndexed { index, (profile, label) ->
                SegmentedButton(
                    selected = current == profile,
                    onClick = { onChange(profile) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * 主题模式选择器（SYSTEM / LIGHT / DARK）
 * 使用 Material 3 SegmentedButton —— 单选
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSelector(
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconItem(Icons.Outlined.Palette)
            Spacer(Modifier.size(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "选择主题模式，立即生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            val options = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeMode.LIGHT  to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK   to stringResource(R.string.settings_theme_dark)
            )
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = current == mode,
                    onClick = { onChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * 顶部品牌横幅
 */
@Composable
private fun BrandBanner() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "R",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Spacer(Modifier.size(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

/**
 * 设置分组卡片
 */
@Composable
private fun SettingsGroup(
    title: String,
    icon: ImageVector,
    description: String? = null,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(Spacing.sm))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp)
            )
        }
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column { content() }
        }
    }
}

/** 开关型设置项 */
@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconItem(icon)
        Spacer(Modifier.size(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/** 值型设置项 */
@Composable
private fun SettingsValueItem(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconItem(icon)
        Spacer(Modifier.size(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 可点击型设置项（带 ripple；[FIX] 支持可选长按） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsClickableItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { m ->
                if (onLongClick != null) {
                    m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    m.clickable(onClick = onClick)
                }
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconItem(icon)
        Spacer(Modifier.size(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 链接型设置项（[FIX 2026-08-17] 支持 onClick，原来是无响应死链） */
@Composable
private fun SettingsLinkItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconItem(icon)
        Spacer(Modifier.size(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 输出格式显示名 */
private fun formatDisplayName(format: OutputFormat): String = when (format) {
    OutputFormat.JPEG -> "JPEG"
    OutputFormat.PNG -> "PNG"
    OutputFormat.WEBP -> "WebP"
    OutputFormat.HEIC -> "HEIC"
    OutputFormat.HEIF -> "HEIF"
    OutputFormat.AVIF -> "AVIF"
    OutputFormat.JXL -> "JPEG XL"
}

/** 动态读取版本号（原硬编码已过时） */
private fun versionName(context: android.content.Context): String {
    val vn = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
    return "v$vn"
}

/** 打开外部链接 */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        )
    }
}

/** 统一的圆形图标容器 */
@Composable
private fun IconItem(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 分组卡片内的分隔线 */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}