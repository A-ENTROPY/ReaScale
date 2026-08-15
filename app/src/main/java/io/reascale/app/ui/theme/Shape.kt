package io.reascale.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状（Design.md §30.4）。
 * 图像处理 App 角大一点（16-20dp），不抢占图像。
 */
val ReaScaleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),    // 标签
    small      = RoundedCornerShape(8.dp),    // 按钮 / 缩略图
    medium     = RoundedCornerShape(12.dp),   // 卡片
    large      = RoundedCornerShape(20.dp),   // 底部弹层
    extraLarge = RoundedCornerShape(28.dp),   // 全屏弹层
)

/** 间距（Design.md §30.5） */
object Spacing {
    val xxs = 2.dp
    val xs  = 4.dp
    val sm  = 8.dp
    val md  = 16.dp
    val lg  = 24.dp
    val xl  = 32.dp
    val xxl = 48.dp
}