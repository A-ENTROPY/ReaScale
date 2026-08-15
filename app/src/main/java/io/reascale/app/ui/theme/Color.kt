package io.reascale.app.ui.theme

import androidx.compose.ui.graphics.Color

// === 浅色（默认） === 对应 Design.md §30.2.2
val LightPrimary            = Color(0xFF3B6FE0)
val LightOnPrimary          = Color(0xFFFFFFFF)
val LightPrimaryContainer   = Color(0xFFDDE6FF)
val LightOnPrimaryContainer = Color(0xFF001A41)
val LightSecondary          = Color(0xFF565E71)
val LightOnSecondary        = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDAE2F9)
val LightOnSecondaryContainer = Color(0xFF131C2B)
val LightTertiary           = Color(0xFFC77B30)
val LightOnTertiary         = Color(0xFFFFFFFF)
val LightTertiaryContainer  = Color(0xFFFFDDB7)
val LightOnTertiaryContainer = Color(0xFF2C1700)
val LightError              = Color(0xFFBA1A1A)
val LightOnError            = Color(0xFFFFFFFF)
val LightErrorContainer     = Color(0xFFFFDAD6)
val LightOnErrorContainer   = Color(0xFF410002)
val LightBackground         = Color(0xFFFDFBFF)
val LightOnBackground       = Color(0xFF1A1B1F)
val LightSurface            = Color(0xFFFDFBFF)
val LightOnSurface          = Color(0xFF1A1B1F)
val LightSurfaceVariant     = Color(0xFFE1E2EC)
val LightOnSurfaceVariant   = Color(0xFF44474F)
val LightOutline            = Color(0xFF74777F)
val LightOutlineVariant     = Color(0xFFC4C6D0)

// === 深色（标准深灰） === Material 3 派生色板
val DarkPrimary            = Color(0xFFAFC6FF)
val DarkOnPrimary          = Color(0xFF002E69)
val DarkPrimaryContainer   = Color(0xFF1F458F)
val DarkOnPrimaryContainer = Color(0xFFDDE6FF)
val DarkSecondary          = Color(0xFFBEC6DC)
val DarkOnSecondary        = Color(0xFF283041)
val DarkSecondaryContainer = Color(0xFF3E4759)
val DarkOnSecondaryContainer = Color(0xFFDAE2F9)
val DarkTertiary           = Color(0xFFFFB870)
val DarkOnTertiary         = Color(0xFF482900)
val DarkTertiaryContainer  = Color(0xFF693D00)
val DarkOnTertiaryContainer = Color(0xFFFFDDB7)
val DarkError              = Color(0xFFFFB4AB)
val DarkOnError            = Color(0xFF690005)
val DarkErrorContainer     = Color(0xFF93000A)
val DarkOnErrorContainer   = Color(0xFFFFDAD6)
val DarkBackground         = Color(0xFF121316)   // 标准深色背景
val DarkOnBackground       = Color(0xFFE3E2E6)
val DarkSurface            = Color(0xFF121316)
val DarkOnSurface          = Color(0xFFE3E2E6)
val DarkSurfaceVariant     = Color(0xFF44474F)
val DarkOnSurfaceVariant   = Color(0xFFC4C6D0)
val DarkOutline            = Color(0xFF8E9099)
val DarkOutlineVariant     = Color(0xFF44474F)

// === AMOLED 纯黑 === Design.md §30.2.2 强调：省电 + 凸显原图
// 关键：surface/background/surfaceContainer* 全部 = #000000，
// 卡片用 surfaceContainerHigh（极深灰 #0A0A0B）做层次
val AmoledBackground       = Color(0xFF000000)   // 纯黑
val AmoledSurface          = Color(0xFF000000)   // 纯黑
val AmoledSurfaceVariant   = Color(0xFF1A1A1C)   // 卡片/分隔
val AmoledSurfaceContainer = Color(0xFF0A0A0B)   // 容器底
val AmoledSurfaceContainerHigh = Color(0xFF141416) // 悬浮卡片
val AmoledSurfaceContainerHighest = Color(0xFF1E1E20) // 最顶卡片