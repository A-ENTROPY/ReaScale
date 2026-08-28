package io.reascale.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reascale.app.R
import io.reascale.app.ReaScaleApp
import io.reascale.app.data.ImageJob
import io.reascale.app.data.JobStatus
import io.reascale.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/** [PERF 2026-08-26] 单次遍历分组结果 */
private class JobGroups(
    val running: List<ImageJob>,
    val pending: List<ImageJob>,
    val done: List<ImageJob>,
    val failed: List<ImageJob>
)

/**
 * 队列页（§30.8.6）
 *
 * UI 一对一实现：
 * 1. "+ 新建批处理" → 调 onPickImages 触发 PhotoPicker
 * 2. "全部暂停" / "全部开始" → 切换 QueueRunner.isRunning
 * 3. "清空已完成" → QueueManager.clearFinished
 * 4. 单图"停止"按钮 → QueueManager.cancel(id)
 * 5. 真实 jobs StateFlow 驱动（运行/等待/完成/失败四态分组）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    jobs: kotlinx.coroutines.flow.StateFlow<List<ImageJob>>,
    onPickImages: () -> Unit
) {
    val list by jobs.collectAsStateWithLifecycle()
    val app = ReaScaleApp.get()
    val scope = rememberCoroutineScope()

    // [PERF 2026-08-26] 单次遍历分组（旧实现 4 次全量 filter，万张时每次发射 4×N）
    val groups = remember(list) {
        val running = mutableListOf<ImageJob>()
        val pending = mutableListOf<ImageJob>()
        val done = mutableListOf<ImageJob>()
        val failed = mutableListOf<ImageJob>()
        for (j in list) {
            when (j.status) {
                JobStatus.RUNNING -> running.add(j)
                JobStatus.PENDING -> pending.add(j)
                JobStatus.COMPLETED -> done.add(j)
                else -> failed.add(j)
            }
        }
        JobGroups(running, pending, done, failed)
    }
    val running = groups.running
    val pending = groups.pending
    val done = groups.done
    val failed = groups.failed
    val isRunnerRunning by app.queueRunner.isRunning.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = onPickImages) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.queue_new))
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        if (list.isEmpty()) {
            EmptyQueue(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(Spacing.lg),
                onPickImages = onPickImages
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            contentPadding = PaddingValues(vertical = Spacing.md)
        ) {
            item {
                QueueSummaryCard(
                    total = list.size,
                    running = running.size,
                    pending = pending.size,
                    done = done.size
                )
            }

            if (running.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.queue_running), running.size) }
                items(running, key = { it.id }) { j -> RunningJobCard(j, onCancel = { id -> app.queueRunner.cancelJob(id) }) }
            }

            if (pending.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.queue_pending), pending.size) }
                items(pending, key = { it.id }) { j -> PendingJobCard(j) }
            }

            if (done.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.queue_completed), done.size) }
                items(done, key = { it.id }) { j -> DoneJobCard(j) }
            }

            if (failed.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.queue_failed), failed.size) }
                items(failed, key = { it.id }) { j -> FailedJobCard(j) }
            }

            item { Spacer(Modifier.height(Spacing.md)) }
            item {
                GlobalActions(
                    canPause = isRunnerRunning,
                    canStart = !isRunnerRunning && pending.isNotEmpty(),
                    onPause = { scope.launch { app.queueRunner.pause() } },
                    onStart = { app.queueRunner.start() },
                    onClear = { scope.launch { app.queueManager.clearFinished() } }
                )
            }
        }
    }
}

@Composable
private fun EmptyQueue(modifier: Modifier, onPickImages: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📥", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.queue_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.queue_empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.lg))
        FilledTonalButton(onClick = onPickImages) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.size(Spacing.sm))
            Text(stringResource(R.string.queue_new))
        }
    }
}

@Composable
private fun QueueSummaryCard(total: Int, running: Int, pending: Int, done: Int) {
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "总进度",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$done / $total",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("运行 $running", style = MaterialTheme.typography.labelSmall)
                        Text("等待 $pending", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { done.toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(MaterialTheme.shapes.small)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("($count)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RunningJobCard(j: ImageJob, onCancel: (String) -> Unit) {
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    StatusDot(MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(Spacing.sm))
                    Text(
                        text = j.sourceDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = { onCancel(j.id) }) {
                    Icon(Icons.Outlined.Stop, contentDescription = "停止")
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = { j.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.small)
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "${j.sourceWidth}×${j.sourceHeight} · ${j.upscalePlan.targetScale}× · ${(j.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PendingJobCard(j: ImageJob) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                StatusDot(MaterialTheme.colorScheme.outline)
                Spacer(Modifier.size(Spacing.sm))
                Text(j.sourceDisplayName, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.queue_waiting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DoneJobCard(j: ImageJob) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { /* TODO: 打开输出图 */ },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                StatusDot(MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.size(Spacing.sm))
                Text(j.sourceDisplayName, style = MaterialTheme.typography.bodyMedium)
            }
            Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun FailedJobCard(j: ImageJob) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(j.sourceDisplayName, style = MaterialTheme.typography.bodyMedium)
                if (j.lastError.isNotBlank()) {
                    Text(
                        j.lastError.take(50),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = CircleShape,
        color = color,
        modifier = Modifier.size(8.dp)
    ) {}
}

@Composable
private fun GlobalActions(
    canPause: Boolean,
    canStart: Boolean,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onClear: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
        if (canPause) {
            FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Pause, contentDescription = null)
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.queue_pause_all), style = MaterialTheme.typography.labelSmall)
            }
        } else if (canStart) {
            FilledTonalButton(onClick = onStart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(Spacing.xs))
                Text("全部开始", style = MaterialTheme.typography.labelSmall)
            }
        }
        FilledTonalButton(onClick = onClear, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
            Spacer(Modifier.size(Spacing.xs))
            Text(stringResource(R.string.queue_clear_done), style = MaterialTheme.typography.labelSmall)
        }
    }
}