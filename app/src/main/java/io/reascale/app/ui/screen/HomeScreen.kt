package io.reascale.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reascale.app.R
import io.reascale.app.data.JobStatus
import io.reascale.app.ui.theme.Spacing

/**
 * 主页面（Design.md §30.8.2）
 * M1: 接入 PhotoPicker + QueueManager
 *
 * UI 升级（vs M0 原版）：
 * 1. 整页可滚动，避免内容溢出
 * 2. 顶部品牌横幅（渐变 + Logo + tagline）
 * 3. 大按钮 + 阴影 + 渐变背景（主操作）
 * 4. 队列进度卡 = 圆角卡 + 渐变背景
 * 5. 快捷操作用 ElevatedButton 而非裸 ListItem
 */
@Composable
fun HomeScreen(
    queueJobs: kotlinx.coroutines.flow.StateFlow<List<io.reascale.app.data.ImageJob>>,
    onPickImages: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEngines: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val jobs by queueJobs.collectAsStateWithLifecycle()
    val pending = jobs.count { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING }
    val total = jobs.size
    val completed = jobs.count { it.status == JobStatus.COMPLETED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Spacer(Modifier.height(Spacing.sm))

        // 品牌横幅
        BrandHero(
            pendingCount = pending,
            totalCount = total
        )

        // 主操作：选择图片
        PrimaryActionButton(
            onClick = onPickImages,
            title = stringResource(R.string.home_pick_image),
            subtitle = stringResource(R.string.home_pick_subtitle)
        )

        // 队列进度卡
        if (total > 0) {
            QueueProgressCard(
                total = total,
                completed = completed,
                pending = pending
            )
        }

        // 最近
        if (jobs.isNotEmpty()) {
            SectionTitle(stringResource(R.string.home_recent))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                contentPadding = PaddingValues(vertical = Spacing.xs)
            ) {
                // [FIX] 用 job.id 做稳定 key，任务重排/删除时缩略图不错位复用
                items(jobs.takeLast(8).reversed(), key = { it.id }) { job ->
                    RecentThumbnail(job.sourceDisplayName, job.status)
                }
            }
        }

        // 快捷操作
        SectionTitle(stringResource(R.string.home_quick_actions))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            QuickAction(
                icon = Icons.Outlined.QueuePlayNext,
                label = stringResource(R.string.home_action_batch),
                onClick = onOpenQueue,
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Outlined.Memory,
                label = stringResource(R.string.home_action_engines),
                onClick = onOpenEngines,
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Outlined.Settings,
                label = stringResource(R.string.home_action_settings),
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(Spacing.lg))
    }
}

/**
 * 顶部品牌区（Design.md §30.8.2 hero）
 * - 渐变背景（primary → tertiary）
 * - 大 Logo + 应用名 + tagline
 * - 右上角显示待处理数量徽章
 */
@Composable
private fun BrandHero(pendingCount: Int, totalCount: Int) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(Spacing.lg)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                }
                if (pendingCount > 0 || totalCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = "$totalCount",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 主操作按钮：渐变 + 阴影 + 大图标
 */
@Composable
private fun PrimaryActionButton(
    onClick: () -> Unit,
    title: String,
    subtitle: String
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.primary
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    )
                )
                .padding(Spacing.md)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 队列进度卡：圆角 + 渐变背景 + 进度条
 */
@Composable
private fun QueueProgressCard(total: Int, completed: Int, pending: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (pending > 0) stringResource(R.string.queue_running)
                               else stringResource(R.string.queue_idle),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$completed / $total",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${(completed.toFloat() / total.coerceAtLeast(1) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { completed.toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

/**
 * 最近任务缩略图（带状态点）
 */
@Composable
private fun RecentThumbnail(name: String, status: JobStatus) {
    val color = when (status) {
        JobStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
        JobStatus.RUNNING -> MaterialTheme.colorScheme.primary
        JobStatus.FAILED, JobStatus.CANCELLED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.size(80.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = name.take(6),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) {}
            }
        }
    }
}

/**
 * 快捷操作按钮：圆角 + 描边 + 居中图标文字
 */
@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md, horizontal = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 段落标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm)
    )
}

/** 段落标题（已在上方定义） */