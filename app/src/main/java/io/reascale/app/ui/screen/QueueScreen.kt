package io.reascale.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.input.pointer.pointerInput
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

/** [OOM-FIX 2026-08-29] 每组最大渲染数（数千张队列下控制 Compose diff/分配压力） */
private const val RENDER_LIMIT = 500

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

        // [UX-FIX 2026-08-29] 快速定位滚动条（数千上万张队列快速滑动/定位）
        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
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
                items(running, key = { it.id }) { j ->
                    RunningJobCard(
                        j,
                        onCancel = { id -> app.queueRunner.cancelJob(id) },
                        onRemove = { id -> scope.launch { app.queueManager.remove(id) } }
                    )
                }
            }

            if (pending.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.queue_pending), pending.size) }
                // [OOM-FIX 2026-08-29] 超大队列（数千张）：每组最多渲染 RENDER_LIMIT 项，
                // 其余用计数行代替——LazyColumn 虚拟化只省布局，Compose 仍对全量 diff/分配
                items(pending.take(RENDER_LIMIT), key = { it.id }) { j ->
                    PendingJobCard(j, onRemove = { id -> scope.launch { app.queueManager.remove(id) } })
                }
                if (pending.size > RENDER_LIMIT) {
                    item { Text("… 还有 ${pending.size - RENDER_LIMIT} 个等待中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            if (done.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.queue_completed), done.size) }
                val recent = done.take(RENDER_LIMIT)
                items(recent, key = { it.id }) { j ->
                    DoneJobCard(j, onRemove = { id -> scope.launch { app.queueManager.remove(id) } })
                }
                if (done.size > RENDER_LIMIT) {
                    item { Text("… 已完成 ${done.size - RENDER_LIMIT} 个已折叠", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            if (failed.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.queue_failed), failed.size) }
                items(failed.take(RENDER_LIMIT), key = { it.id }) { j ->
                    FailedJobCard(
                        j,
                        onRetry = { id -> scope.launch { app.queueManager.retry(id) } },
                        onRemove = { id -> scope.launch { app.queueManager.remove(id) } }
                    )
                }
                if (failed.size > RENDER_LIMIT) {
                    item { Text("… 还有 ${failed.size - RENDER_LIMIT} 个失败已折叠", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            item { Spacer(Modifier.height(Spacing.md)) }
            item {
                GlobalActions(
                    canPause = isRunnerRunning,
                    canStart = !isRunnerRunning && pending.isNotEmpty(),
                    onPause = {
                        app.setProcessingEnabled(false)
                        scope.launch { app.queueRunner.pause() }
                    },
                    onStart = {
                        app.setProcessingEnabled(true)
                        app.queueRunner.start()
                    },
                    onClear = { scope.launch { app.queueManager.clearFinished() } },
                    onClearAll = { scope.launch { app.queueManager.clearAll() } }
                )
            }

            } // LazyColumn end
            // [UX-FIX 2026-08-29] 右侧快速定位滚动条（拖动跳转，支持上万条）
            FastScrollBar(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(14.dp)
            )
        } // Box end
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
private fun RunningJobCard(j: ImageJob, onCancel: (String) -> Unit, onRemove: (String) -> Unit) {
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
                IconButton(onClick = { onRemove(j.id) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "移除")
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
private fun PendingJobCard(j: ImageJob, onRemove: (String) -> Unit) {
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
            IconButton(onClick = { onRemove(j.id) }) {
                Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DoneJobCard(j: ImageJob, onRemove: (String) -> Unit) {
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
            IconButton(onClick = { onRemove(j.id) }) {
                Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FailedJobCard(j: ImageJob, onRetry: (String) -> Unit, onRemove: (String) -> Unit) {
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
            IconButton(onClick = { onRetry(j.id) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重试", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { onRemove(j.id) }) {
                Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(18.dp))
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
    onClear: () -> Unit,
    onClearAll: () -> Unit
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
        FilledTonalButton(onClick = onClearAll, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.DeleteForever, contentDescription = null)
            Spacer(Modifier.size(Spacing.xs))
            Text("清空队列", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * [UX-FIX 2026-08-29] 快速定位滚动条：拖动右侧条按比例跳转到对应列表位置。
 * 自研实现（Compose 1.10 已移除 built-in VerticalScrollbar），适用于上万条队列。
 */
@Composable
private fun FastScrollBar(listState: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    val thumbColor = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier) {
        val barH = constraints.maxHeight.toFloat()
        val totalItems = listState.layoutInfo.totalItemsCount.coerceAtLeast(1)
        // 估算每项高度 ~64dp → 总内容高度；thumb 高度 = 视口比例
        val itemH = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
        val contentH = totalItems * itemH
        val thumbH = (barH * (barH / contentH)).coerceIn(28f, barH)
        val track = barH - thumbH

        Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(totalItems, barH, thumbH, track) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val ratio = ((change.position.y - thumbH / 2f) / track).coerceIn(0f, 1f)
                            val target = (ratio * totalItems).toInt().coerceIn(0, totalItems - 1)
                            scope.launch { listState.scrollToItem(target) }
                        }
                    }
            ) {
                // 轨道
                drawRoundRect(color = barColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                // thumb：跟随当前首项位置
                val ratio = if (contentH > barH) {
                    (listState.firstVisibleItemIndex.toFloat() + listState.firstVisibleItemScrollOffset / itemH) / totalItems
                } else 0f
                val y = ratio * track
                drawRoundRect(
                    color = thumbColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                    size = androidx.compose.ui.geometry.Size(size.width, thumbH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
        }
    }
