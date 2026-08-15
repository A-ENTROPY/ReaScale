package io.reascale.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reascale.app.debug.LogBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志查看器（2026-08-02 P0 排查专用）
 *
 * 入口：Settings → "调试日志"
 *
 * 功能：
 * - 实时显示 LogBus 收集的所有日志（按时间倒序）
 * - 自动滚动到底部（除非用户向上滚）
 * - 顶部操作栏：复制全部 / 分享 / 清空 / 刷新
 * - 长按单条日志复制该条
 * - 日志等级染色：E 红 / W 黄 / I 蓝 / D 灰
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries by LogBus.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 倒序展示：最新在顶部
    val reversed by remember(entries) {
        derivedStateOf { entries.reversed() }
    }

    // 自动滚动到顶（最新的）—— 仅当用户仍停留在顶部附近时
    // [FIX] 原实现无条件 animateScrollToItem(0)：用户翻看历史日志时每次新日志都被拉回顶部
    // 现在检查 firstVisibleItemIndex：用户已向下翻看（index > 1）则不动
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("调试日志", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${entries.size} 条 · ${if (LogBus.sinkFilePath() != null) "已落盘" else "内存"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = LogBus.snapshotText()
                        copyToClipboard(ctx, text, "调试日志全部内容")
                        Toast.makeText(ctx, "已复制 ${text.length} 字符到剪贴板", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制全部")
                    }
                    IconButton(onClick = {
                        val text = LogBus.snapshotText()
                        shareText(ctx, text)
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = {
                        LogBus.clear()
                        Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "清空")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            // 操作提示卡
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "📋 把这里的内容复制后发给开发者",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "点击右上角 📋 复制全部，或长按单条复制该条。路径：" + (LogBus.sinkFilePath() ?: "（未落盘）"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (reversed.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📭", fontSize = 48.sp)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "暂无日志",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "选一张图并开始放大，日志会实时显示在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    reverseLayout = false
                ) {
                    // [FIX] key 改用索引：原 ts*31+hashCode 会碰撞（同毫秒多条相同消息）
                    // LogBus 是 FIFO 只增列表，索引在列表生命周期内稳定
                    itemsIndexed(reversed) { index, entry ->
                        LogRow(entry = entry, onLongClick = {
                            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.ts))
                            val text = "$ts ${entry.level.tag}/${entry.tag}: ${entry.message}"
                            copyToClipboard(ctx, text, "单条日志")
                            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                        })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(entry: LogBus.Entry, onLongClick: () -> Unit) {
    val color = when (entry.level) {
        LogBus.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogBus.Level.INFO -> MaterialTheme.colorScheme.onSurface
        LogBus.Level.WARN -> Color(0xFFF59E0B)
        LogBus.Level.ERROR -> Color(0xFFEF4444)
    }
    val ts = remember(entry.ts) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.ts))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .androidx_compose_foundation_clickable_no_indication(onLongClick),
            verticalAlignment = Alignment.Top
        ) {
            // 时间戳
            Text(
                text = ts,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(86.dp)
            )
            // 等级
            Text(
                text = entry.level.tag,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = color,
                modifier = Modifier.width(14.dp)
            )
            // tag
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(110.dp)
            )
            // 消息
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = color,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// 长按检测（不依赖 combinedClickable，更稳）
private fun Modifier.androidx_compose_foundation_clickable_no_indication(
    onLongClick: () -> Unit
): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onLongPress = { onLongClick() }
    )
}

private fun copyToClipboard(ctx: Context, text: String, label: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(ctx: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(intent, "分享日志").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}