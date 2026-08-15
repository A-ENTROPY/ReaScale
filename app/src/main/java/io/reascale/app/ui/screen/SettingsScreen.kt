package io.reascale.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reascale.app.R
import io.reascale.app.ReaScaleApp
import io.reascale.app.data.AppSettings
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
    val settings by app.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = AppSettings()
    )

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
                description = "默认引擎、输出格式、质量"
            ) {
                SettingsValueItem(
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.settings_default_engine),
                    value = settings.defaultEngineId.substringAfter('_').take(24)
                )
                SettingsDivider()
                SettingsValueItem(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.settings_default_format),
                    value = "${settings.encodeOptions.format.name} · Q${settings.encodeOptions.quality}"
                )
                SettingsDivider()
                SettingsValueItem(
                    icon = Icons.Outlined.ColorLens,
                    title = stringResource(R.string.settings_default_quality),
                    value = "${settings.encodeOptions.quality}"
                )
                SettingsDivider()
                SettingsValueItem(
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.settings_tile_size),
                    // [FIX] 原实现误显示 maxQueueSize/1000；实际 tile 由引擎固定为 192
                    value = "192 px (内置固定)"
                )
                SettingsDivider()
                SettingsSwitchItem(
                    icon = Icons.Outlined.PhotoCamera,
                    title = stringResource(R.string.settings_run_in_background),
                    subtitle = "大队列时显示前台通知",
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
                description = "推理后端、内存策略"
            ) {
                SettingsValueItem(
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.settings_backend_pref),
                    value = "自动 · NNAPI / XNNPACK / CPU"
                )
                SettingsDivider()
                // 性能模式（SegmentedButton 单选）
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
                SettingsValueItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.settings_version),
                    value = "0.3.1-a15 (2026-08-17)"
                )
                SettingsDivider()
                SettingsLinkItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.settings_github),
                    subtitle = "github.com/reascale/app"
                )
                SettingsDivider()
                SettingsLinkItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.settings_license),
                    subtitle = "Apache-2.0"
                )
                SettingsDivider()
                SettingsLinkItem(
                    icon = Icons.Outlined.Help,
                    title = stringResource(R.string.settings_help),
                    subtitle = null
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

/** 可点击型设置项（带 ripple） */
@Composable
private fun SettingsClickableItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
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

/** 链接型设置项 */
@Composable
private fun SettingsLinkItem(
    icon: ImageVector,
    title: String,
    subtitle: String?
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
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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