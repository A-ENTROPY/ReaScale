package io.reascale.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.reascale.app.data.AppSettings
import io.reascale.app.data.ThemeMode

/**
 * ReaScale 主题（Design.md §30.2）。
 *
 * 三类用户可调项（来自 [AppSettings]）：
 * 1. [themeMode] —— SYSTEM / LIGHT / DARK
 * 2. [useDynamicColor] —— 是否用 Material You 取色（仅 API 31+）
 * 3. [amoled] —— 深色模式下是否用 #000000 纯黑（默认开）
 *
 * 关键：所有可调项都能立即生效——通过 [AppSettings] Flow 驱动，
 *      切换主题后整个 UI（包括主屏 / 队列 / 引擎 / 设置）即时重建。
 */
@Composable
fun ReaScaleTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
    }
    val useDynamic = settings.useDynamicColor
    val amoled = settings.amoled

    val colorScheme = when {
        // 动态取色（Material You）优先级最高
        useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme  ->
            dynamicDarkColorScheme(LocalContext.current)
        useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme ->
            dynamicLightColorScheme(LocalContext.current)
        // 深色 + AMOLED = 纯黑
        darkTheme && amoled -> reascaleAmoledColors()
        // 深色 + 关闭 AMOLED = 标准深灰
        darkTheme           -> darkColors()
        // 浅色
        else                -> lightColors()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = ReaScaleTypography,
        shapes      = ReaScaleShapes,
        content     = content
    )
}

/* ============== Light ============== */
@Composable
private fun lightColors() = lightColorScheme(
    primary            = LightPrimary,
    onPrimary          = LightOnPrimary,
    primaryContainer   = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary          = LightSecondary,
    onSecondary        = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary           = LightTertiary,
    onTertiary         = LightOnTertiary,
    tertiaryContainer  = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error              = LightError,
    onError            = LightOnError,
    errorContainer     = LightErrorContainer,
    onErrorContainer   = LightOnErrorContainer,
    background         = LightBackground,
    onBackground       = LightOnBackground,
    surface            = LightSurface,
    onSurface          = LightOnSurface,
    surfaceVariant     = LightSurfaceVariant,
    onSurfaceVariant   = LightOnSurfaceVariant,
    outline            = LightOutline,
    outlineVariant     = LightOutlineVariant,
)

/* ============== Dark (标准深灰) ============== */
@Composable
private fun darkColors() = darkColorScheme(
    primary            = DarkPrimary,
    onPrimary          = DarkOnPrimary,
    primaryContainer   = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary          = DarkSecondary,
    onSecondary        = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary           = DarkTertiary,
    onTertiary         = DarkOnTertiary,
    tertiaryContainer  = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error              = DarkError,
    onError            = DarkOnError,
    errorContainer     = DarkErrorContainer,
    onErrorContainer   = DarkOnErrorContainer,
    background         = DarkBackground,
    onBackground       = DarkOnBackground,
    surface            = DarkSurface,
    onSurface          = DarkOnSurface,
    surfaceVariant     = DarkSurfaceVariant,
    onSurfaceVariant   = DarkOnSurfaceVariant,
    outline            = DarkOutline,
    outlineVariant     = DarkOutlineVariant,
)

/* ============== Dark AMOLED (#000000) ==============
 * 关键：所有 surface 容器全设为 0xFF000000，
 *       卡片/底部弹层用 surfaceContainer* 系列（极深灰）做层次区分
 * 注意：用 @Composable 函数而非 class-level val，避免 Compose 内部依赖触发 init 异常
 */
@Composable
private fun reascaleAmoledColors() = darkColorScheme(
    primary            = DarkPrimary,
    onPrimary          = DarkOnPrimary,
    primaryContainer   = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary          = DarkSecondary,
    onSecondary        = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary           = DarkTertiary,
    onTertiary         = DarkOnTertiary,
    tertiaryContainer  = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error              = DarkError,
    onError            = DarkOnError,
    errorContainer     = DarkErrorContainer,
    onErrorContainer   = DarkOnErrorContainer,
    background         = AmoledBackground,            // #000000
    onBackground       = DarkOnBackground,
    surface            = AmoledSurface,               // #000000
    onSurface          = DarkOnSurface,
    surfaceVariant     = AmoledSurfaceVariant,        // #1A1A1C
    onSurfaceVariant   = DarkOnSurfaceVariant,
    surfaceContainer       = AmoledSurfaceContainer,        // #0A0A0B
    surfaceContainerHigh   = AmoledSurfaceContainerHigh,    // #141416
    surfaceContainerHighest = AmoledSurfaceContainerHighest, // #1E1E20
    outline            = DarkOutline,
    outlineVariant     = DarkOutlineVariant,
)