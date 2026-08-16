package io.reascale.app.ui.screen

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.reascale.app.data.EngineProfile
import io.reascale.app.data.EngineSource
import io.reascale.app.data.ModelParameters
import io.reascale.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 引擎选择页（§30.8.3）
 *
 * UI 一对一实现：
 * 1. 搜索引擎（顶栏搜索图标 / 输入框）
 * 2. 导入 ONNX（FAB → SAF → 复制到内部 → Auto-Probe → 写入 profile）
 * 3. 引擎详情/编辑（点击卡片 → ModalBottomSheet → 改名/删/设为默认）
 * 4. 删除用户导入引擎
 * 5. 设默认引擎（影响 settings.defaultEngineId）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginePickerScreen(
    onOpenConfig: (engineId: String) -> Unit = {},
) {
    val app = ReaScaleApp.get()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val profiles by app.engineRepository.profiles.collectAsStateWithLifecycle()
    val settings by app.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = io.reascale.app.data.AppSettings()
    )
    // 2026-08-04 修复 3：监听 params 变化，让卡片显示"已配置"角标
    val paramsMap by app.paramsRepository.params.collectAsStateWithLifecycle()

    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedEngine by remember { mutableStateOf<EngineProfile?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<EngineProfile?>(null) }

    val filtered = if (searchQuery.isBlank()) {
        profiles
    } else {
        profiles.filter { p ->
            p.displayName.contains(searchQuery, ignoreCase = true) ||
            p.domain.name.contains(searchQuery, ignoreCase = true)
        }
    }
    val builtin = filtered.filter { it.source == EngineSource.BUILTIN }
    val user = filtered.filter { it.source == EngineSource.USER }

    // SAF launcher：多选导入 ncnn 模型（.param + .bin 一起选，可单选 .param）
    // [FIX 2026-08-17] 原单选 OpenDocument 只接受 .param：用户选 .bin 就被拒（"不支持格式"）。
    // 现支持多选：识别 .param（结构）与 .bin（权重），两者都选或只选 .param 均可导入；
    // 只选 .bin 时给出明确指引而不是笼统报错。
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var tmpFiles: List<File> = emptyList()
            try {
                // 1. 全部 SAF Uri → cacheDir/imports/
                tmpFiles = uris.mapNotNull { uri ->
                    withContext(Dispatchers.IO) { copySafToCache(context, uri) }
                }
                if (tmpFiles.isEmpty()) {
                    snackbarHostState.showSnackbar("读取文件失败")
                    return@launch
                }
                val paramFile = tmpFiles.firstOrNull { it.name.endsWith(".param", ignoreCase = true) }
                val binFile = tmpFiles.firstOrNull { it.name.endsWith(".bin", ignoreCase = true) }
                if (paramFile == null) {
                    // 只有 .bin（或无法识别）：给出明确指引，不再笼统报"不支持"
                    val msg = if (binFile != null) {
                        "已选择 .bin 权重文件，还需要 .param 结构文件——请同时选中两者（可多选）"
                    } else {
                        "未识别到 .param 模型文件（ncnn 模型 = .param 结构 + .bin 权重，需成对）"
                    }
                    snackbarHostState.showSnackbar(msg)
                    return@launch
                }
                // 2. 导入（多选时把 .bin 一并交给 importOnnx，自动改名为 param 同名）
                val profile = app.engineRepository.importOnnx(paramFile, binOverride = binFile)
                snackbarHostState.showSnackbar("已导入：${profile.displayName}")
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar("导入失败：${t.message ?: "未知错误"}")
            } finally {
                // [FIX] 无论成功/失败都清理 cache 临时文件，避免残留累积
                tmpFiles.forEach { runCatching { if (it.exists()) it.delete() } }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.engines_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(stringResource(R.string.engines_title))
                    }
                },
                actions = {
                    if (searchOpen) {
                        IconButton(onClick = {
                            searchOpen = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭")
                        }
                    } else {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.engines_search))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.engines_import)) },
                icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                onClick = {
                    modelPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(inner).padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("⏳", style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.engines_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        if (filtered.isEmpty()) {
            // 搜索无结果
            Column(
                modifier = Modifier.fillMaxSize().padding(inner).padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🔍", style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "没有匹配的引擎",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            contentPadding = PaddingValues(vertical = Spacing.md)
        ) {
            if (builtin.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.engines_builtin), builtin.size)
                }
                items(builtin) { e ->
                    EngineCard(
                        engine = e,
                        isDefault = settings.defaultEngineId == e.id,
                        context = context,
                        // 2026-08-04 修复 3：是否已配置（任一参数 enabled 即视为已配置）
                        isConfigured = isConfigured(paramsMap[e.id]),
                        onClick = { selectedEngine = e }
                    )
                }
            }
            if (user.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.engines_user), user.size)
                }
                items(user) { e ->
                    EngineCard(
                        engine = e,
                        isDefault = settings.defaultEngineId == e.id,
                        context = context,
                        isConfigured = isConfigured(paramsMap[e.id]),
                        onClick = { selectedEngine = e }
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }  // FAB 留空
        }
    }

    // 详情/编辑底部弹层
    selectedEngine?.let { engine ->
        EngineEditorSheet(
            engine = engine,
            isDefault = settings.defaultEngineId == engine.id,
            onSetDefault = {
                scope.launch {
                    app.settingsRepository.update { it.copy(defaultEngineId = engine.id) }
                    snackbarHostState.showSnackbar("已设为默认引擎")
                }
            },
            onDelete = {
                showDeleteConfirm = engine
            },
            onOpenConfig = {
                onOpenConfig(engine.id)
            },
            onDismiss = { selectedEngine = null }
        )
    }

    // 删除确认对话框
    showDeleteConfirm?.let { engine ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除引擎") },
            text = { Text("确定要删除 ${engine.displayName} 吗？模型文件也会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = app.engineRepository.delete(engine.id)
                        showDeleteConfirm = null
                        selectedEngine = null
                        snackbarHostState.showSnackbar(
                            if (ok) "已删除" else "内置引擎不可删除"
                        )
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 复制 SAF Uri → cacheDir/imports/<name>
 * [FIX 2026-08-17] 追加时间戳后缀避免同名文件互相覆盖；
 * 文件名解析健壮化：DISPLAY_NAME 查询失败时解码 lastPathSegment
 * （content://.../document/primary:Download/model.param 之类），而不是直接拼原始段
 */
private fun copySafToCache(context: Context, uri: Uri): File? {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
        else null
    }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.let {
        android.net.Uri.decode(it)?.substringAfterLast('/')?.substringAfterLast(':')
    }?.takeIf { it.isNotBlank() } ?: "imported.param"
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dest = File(context.cacheDir, "imports/${System.currentTimeMillis()}_$safeName").apply {
        parentFile?.mkdirs()
    }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        dest
    }.getOrNull()
}

/** 分组 header */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            "($count)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 2026-08-04 修复 3：判断引擎是否已配置（任一参数 enabled 即视为已配置）
 * - 用于卡片右上角显示"已配置"角标
 */
private fun isConfigured(params: ModelParameters?): Boolean {
    if (params == null) return false
    // 至少有 1 个参数 enabled
    return params.targetScale.enabled ||
        params.outputSuffix.enabled ||
        params.concurrencyOverride.enabled ||
        params.tileSize.enabled ||
        params.tilePad.enabled ||
        params.prePad.enabled ||
        params.useFp32.enabled ||
        params.ttaMode.enabled ||
        params.loadProcSaveThreads.enabled ||
        params.denoiseStrength.enabled ||
        params.faceEnhance.enabled ||
        params.gfpganVersion.enabled ||
        params.onlyCenterFace.enabled ||
        params.noiseLevel.enabled ||
        params.alphaUpsampler.enabled ||
        params.meanOverride.enabled ||
        params.stdOverride.enabled ||
        params.maxInputEdgeOverride.enabled
}

/** 引擎卡片 */
@Composable
private fun EngineCard(
    engine: EngineProfile,
    isDefault: Boolean,
    context: android.content.Context,
    isConfigured: Boolean = false,
    onClick: () -> Unit
) {
    val scale = engine.capabilities.baseScale.toInt()
    val domainStr = when (engine.domain) {
        io.reascale.app.data.EngineDomain.PHOTO -> "真人"
        io.reascale.app.data.EngineDomain.ANIME -> "动漫"
        io.reascale.app.data.EngineDomain.FACE -> "人脸"
        io.reascale.app.data.EngineDomain.GENERAL -> "通用"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isDefault)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.size(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        engine.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    // 2026-08-04 修复 3：已配置角标（齿轮图标 + "已配置"文本）
                    if (isConfigured) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Tune,
                                    contentDescription = "已配置参数",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.size(2.dp))
                                Text(
                                    "已配置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.size(Spacing.xs))
                    }
                    if (isDefault) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "默认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    "${scale}× · $domainStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (engine.note.isNotBlank()) {
                    Text(
                        engine.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // [FIX] 原来无条件 true：改为按 modelUri 判断是否 NCNN 原生模型
                    // （内置 ncnn: 前缀 或 用户导入 .param 文件路径）
                    val hasNcnn = engine.modelUri.startsWith("ncnn:") ||
                        engine.modelUri.endsWith(".param", ignoreCase = true)
                    if (hasNcnn) {
                        AssistChip(
                            onClick = {},
                            label = { Text("NCNN 原生推理", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        )
                    }
                    if (engine.source == EngineSource.BUILTIN) {
                        AssistChip(
                            onClick = {},
                            label = { Text("内置", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 引擎详情/编辑底部弹层
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineEditorSheet(
    engine: EngineProfile,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onOpenConfig: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // [FIX] name 只是展示用（无改名输入框），直接用 engine.displayName，去掉冗余 state

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.lg)
        ) {
            // 头部：渐变 Banner
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        )
                        .padding(Spacing.md)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                        Spacer(Modifier.size(Spacing.md))
                        Column {
                            Text(
                                engine.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Text(
                                "${engine.capabilities.baseScale}× · ${engine.domain.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // 能力详情
            CapabilityRow("模型源", if (engine.source == EngineSource.BUILTIN) "内置" else "用户导入")
            CapabilityRow("基础放大", "${engine.capabilities.baseScale}×")
            CapabilityRow("作用域", engine.domain.name)
            CapabilityRow("最大输入边长", "${engine.capabilities.maxInputEdge}px")
            CapabilityRow("通道数", "${engine.capabilities.channels}")
            CapabilityRow("归一化均值", "${engine.capabilities.mean}")
            CapabilityRow("归一化标准差", "${engine.capabilities.std}")
            if (engine.note.isNotBlank()) {
                CapabilityRow("备注", engine.note)
            }

            Spacer(Modifier.height(Spacing.md))

            // 操作按钮
            // 参数配置（Phase B 2026-08-04）
            TextButton(
                onClick = {
                    onOpenConfig()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null)
                Spacer(Modifier.size(Spacing.sm))
                Text("参数配置")
            }
            if (!isDefault) {
                TextButton(
                    onClick = onSetDefault,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Star, contentDescription = null)
                    Spacer(Modifier.size(Spacing.sm))
                    Text("设为默认引擎")
                }
            }
            if (engine.source == EngineSource.USER) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.size(Spacing.sm))
                    Text("删除引擎", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}