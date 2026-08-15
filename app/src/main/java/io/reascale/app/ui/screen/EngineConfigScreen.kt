package io.reascale.app.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.reascale.app.ReaScaleApp
import io.reascale.app.data.BoolParam
import io.reascale.app.data.EngineDomain
import io.reascale.app.data.EngineProfile
import io.reascale.app.data.FloatParam
import io.reascale.app.data.IntParam
import io.reascale.app.data.ModelParameters
import io.reascale.app.data.ParamApplicability
import io.reascale.app.data.StringParam
import io.reascale.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 引擎参数配置面板（§24 引擎面板 · Phase B · 2026-08-04）
 *
 * 通用性实现：
 * - 4 个分组（基础/性能/增强/高级），每组通过 isApplicable 决定显示哪些参数
 * - 通用 ParamRow 自动适配 FloatParam / IntParam / BoolParam / StringParam
 * - 参数值以 mutableStateOf 跟踪；取消 = 丢弃；保存 = 持久化
 * - 重置按钮：删除该 engineId 的 params.json 条目（走默认）
 *
 * 注意：
 * - engineId 通过 route param 传入（避免顶层 state）
 * - 该 Screen 不修改 EngineProfile（参数独立存储）
 * - 返回时通过 navController.popBackStack()
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineConfigScreen(
    engineId: String,
    onBack: () -> Unit,
) {
    val app = ReaScaleApp.get()

    val profiles by app.engineRepository.profiles.collectAsState()
    val engine = profiles.firstOrNull { it.id == engineId }

    if (engine == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("引擎配置") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { inner ->
            Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center
            ) {
                Text("引擎不存在：$engineId")
            }
        }
        return
    }

    val savedParamsMap by app.paramsRepository.params.collectAsState()
    var workingParams by remember(engineId) {
        mutableStateOf(savedParamsMap[engineId] ?: ModelParameters())
    }
    // 2026-08-04 修复 3：保存后自动同步 workingParams，并刷新 EnginePicker
    // - engineId 变化 → 重新读
    // - savedParamsMap 变化（如保存后从其他位置）→ 重新读
    LaunchedEffect(engineId, savedParamsMap) {
        workingParams = savedParamsMap[engineId] ?: ModelParameters()
    }
    var showSavedHint by remember { mutableStateOf(false) }
    // [FIX] 保存落库走 app 级作用域：用户点保存后立刻返回（组合销毁）也不会丢数据。
    // 自动返回由 LaunchedEffect(savedTick) 驱动：屏幕已销毁则 effect 取消，不会双重返回。
    var savedTick by remember { mutableStateOf(0) }
    LaunchedEffect(savedTick) {
        if (savedTick > 0) {
            showSavedHint = true
            delay(400)  // 短暂展示"已保存"提示
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("引擎配置", style = MaterialTheme.typography.titleMedium)
                        Text(
                            engine.displayName,
                            style = MaterialTheme.typography.bodySmall,
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
                        workingParams = ModelParameters()
                        app.appScope.launch {
                            app.paramsRepository.reset(engineId)
                        }
                    }) {
                        Icon(Icons.Outlined.Restore, contentDescription = "重置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            // 捕获当前参数快照，用 app 级作用域落库（组合销毁不中断）
                            val params = workingParams
                            app.appScope.launch {
                                app.paramsRepository.save(engineId, params)
                                savedTick++
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.size(Spacing.xs))
                        Text("保存")
                    }
                }
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // 引擎摘要
            item { EngineHeader(engine) }

            // === 基础调参（精简：只留 3 个有效参数） ===
            item {
                ParamSection("参数", "放大倍数 · 降噪 · 并行推理") {
                    // 1. 放大倍数
                    IntParamSliderRow(
                        param = workingParams.targetScale,
                        onChange = { newParam ->
                            workingParams = workingParams.copy(targetScale = newParam)
                        },
                    )
                    HorizontalDivider()
                    // 2. 降噪等级（Real-CUGAN noise：-1=无 0=弱 3=强）
                    IntParamSliderRow(
                        param = workingParams.noiseLevel,
                        onChange = { newParam ->
                            workingParams = workingParams.copy(noiseLevel = newParam)
                        },
                    )
                    HorizontalDivider()
                    // 3. 并行推理数（线程数）
                    IntParamSliderRow(
                        param = workingParams.concurrencyOverride,
                        onChange = { newParam ->
                            workingParams = workingParams.copy(concurrencyOverride = newParam)
                        },
                    )
                }
            }

            // 底部留空（避免遮挡 BottomBar）
            item { Spacer(Modifier.height(80.dp)) }

            // 保存提示
            if (showSavedHint) {
                item {
                    LaunchedEffect(Unit) {
                        delay(2000)
                        showSavedHint = false
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(Modifier.size(Spacing.sm))
                            Text(
                                "已保存",
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 通用参数适配：根据 label 匹配到 ModelParameters 对应字段
 */
private fun applyIntParamUpdate(current: ModelParameters, old: IntParam, new: IntParam): ModelParameters = when (old.label) {
    "推理并发数" -> current.copy(concurrencyOverride = new)
    "Tile 大小" -> current.copy(tileSize = new)
    "Tile 边缘填充" -> current.copy(tilePad = new)
    "Pre Padding" -> current.copy(prePad = new)
    "噪点等级" -> current.copy(noiseLevel = new)
    "最大输入边长 (高级)" -> current.copy(maxInputEdgeOverride = new)
    else -> current
}

private fun applyBoolParamUpdate(current: ModelParameters, old: BoolParam, new: BoolParam): ModelParameters = when (old.label) {
    "FP32 精度" -> current.copy(useFp32 = new)
    "TTA 模式" -> current.copy(ttaMode = new)
    "人脸增强" -> current.copy(faceEnhance = new)
    "只处理中心脸" -> current.copy(onlyCenterFace = new)
    else -> current
}

private fun applyStringParamUpdate(current: ModelParameters, new: StringParam): ModelParameters = when (new.label) {
    "线程分配 (load:proc:save)" -> current.copy(loadProcSaveThreads = new)
    "GFPGAN 版本" -> current.copy(gfpganVersion = new)
    "Alpha 通道放大" -> current.copy(alphaUpsampler = new)
    else -> current
}

private fun updateFloatField(current: ModelParameters, new: FloatParam): ModelParameters = when (new.label) {
    "去噪强度" -> current.copy(denoiseStrength = new)
    else -> current
}

/**
 * 判断参数是否对该引擎适用
 */
private fun isApplicable(app: ParamApplicability, engine: EngineProfile): Boolean = when (app) {
    ParamApplicability.ALL -> true
    ParamApplicability.SR_GENERIC -> engine.domain != EngineDomain.FACE
    ParamApplicability.SR_GENERAL_X4V3 -> {
        val n = engine.displayName.lowercase() + " " + engine.modelUri.lowercase()
        "general" in n || "x4v3" in n
    }
    ParamApplicability.FACE_GFPGAN -> engine.domain == EngineDomain.FACE
    ParamApplicability.UPSCALE_GENERIC -> engine.domain != EngineDomain.ANIME
}

// ============================================================================
// 通用 UI 组件
// ============================================================================

@Composable
private fun EngineHeader(engine: EngineProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    engine.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "${engine.capabilities.baseScale}× · ${engine.domain.name} · ${engine.source.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            if (engine.note.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    engine.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ParamSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "折叠" else "展开"
                    )
                }
            }
            if (expanded) content()
        }
    }
}

/** 浮点参数行（Slider + Switch） */
@Composable
private fun SliderParamRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    step: Float,
    unit: String,
    description: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    var sliderValue by remember(value, enabled) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = sliderValue.coerceIn(min, max),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onValueChange(sliderValue) },
                valueRange = min..max,
                steps = ((max - min) / step).toInt().coerceAtLeast(0),
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "${formatFloat(sliderValue)}$unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 整数参数行（Slider + Switch） */
@Composable
private fun IntParamSliderRow(
    param: IntParam,
    onChange: (IntParam) -> Unit,
) {
    var v by remember(param.value, param.enabled) { mutableStateOf(param.value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(param.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    param.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = param.enabled, onCheckedChange = { onChange(param.copy(enabled = it)) })
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = v.coerceIn(param.min.toFloat(), param.max.toFloat()),
                onValueChange = { v = it },
                onValueChangeFinished = { onChange(param.copy(value = v.toInt())) },
                valueRange = param.min.toFloat()..param.max.toFloat(),
                steps = ((param.max - param.min) / param.step).coerceAtLeast(0),
                enabled = param.enabled,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "${v.toInt()}${param.unit}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 布尔参数行 */
@Composable
private fun BoolParamRow(param: BoolParam, onChange: (BoolParam) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(param.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                param.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = param.enabled && param.value,
            onCheckedChange = { onChange(param.copy(enabled = it, value = it)) }
        )
    }
}

/** 字符串参数行 */
@Composable
private fun StringParamRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    var text by remember(value, enabled) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onValueChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

private fun formatFloat(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString()
    else "%.2f".format(v).trimEnd('0').trimEnd('.')