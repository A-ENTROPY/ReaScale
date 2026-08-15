package io.reascale.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 模型可调参数面板（§24 引擎面板 · Phase B · 2026-08-04）
 *
 * 通用性设计原则：
 * 1. 19 个参数**全部可选**（带 enabled 标志），UI 自动隐藏未启用项
 * 2. 每个参数都有 applicability（适用域）→ 不同模型自动显示不同子集
 * 3. 持久化独立文件 params.json（不污染 profile.json）
 * 4. 老 profile 没 params → 走 EngineCapabilities 的默认值
 * 5. 不破坏现有 EngineCapabilities / EngineProfile（向后兼容）
 *
 * 数据来源（ground truth）：
 * - realesrgan-ncnn-vulkan README（nihui/xinntao 官方）
 * - Real-ESRGAN inference_realesrgan.py（xinntao 官方 Python CLI）
 * - GFPGAN inference_gfpgan.py（TencentARC 官方）
 * - Real-CUGAN README（bilibili ailab）
 *
 * 注意：当前 ReaScale 后端（ORT 1.18 + XNNPACK CPU）只支持 Real-ESRGAN 系列；
 * GFPGAN / Real-CUGAN 等的 denoise_strength / face_enhance 已预留字段，
 * 等 M3+ 引擎管理接入后再启用。
 */

/** 参数适用域：决定 UI 是否显示该参数 */
@Serializable
enum class ParamApplicability {
    /** 所有引擎都显示（如：放大倍数、输出格式） */
    @SerialName("all") ALL,
    /** Real-ESRGAN 等 SR 模型通用（tile/fp32/tta） */
    @SerialName("sr_generic") SR_GENERIC,
    /** 仅 realesr-general-x4v3（带去噪权重对） */
    @SerialName("sr_general_x4v3") SR_GENERAL_X4V3,
    /** GFPGAN 系列（face_enhance / version） */
    @SerialName("face_gfpgan") FACE_GFPGAN,
    /** Real-CUGAN / RealSR 等 vintage 模型（noise/alpha 通道） */
    @SerialName("upscale_generic") UPSCALE_GENERIC,
}

/** 输出图像后缀（用户可见，不影响实际格式） */
@Serializable
data class OutputSuffixConfig(
    val enabled: Boolean = false,
    val value: String = "_4x",
)

/**
 * 19 个可调参数（全部带 enabled 标志 → 未启用 = 走默认值）
 *
 * 命名规则：
 * - "通用"参数：所有引擎都生效（outputFormat, suffix, targetScale, concurrency）
 * - "模型特性"参数：仅特定模型生效（denoiseStrength, faceEnhance）
 * - "性能"参数：tile / thread / fp32 / tta
 * - "高级"参数：mean/std/maxInputEdge（覆盖 Auto-Probe）
 */
@Serializable
data class ModelParameters(
    // ===== 通用（适用域 = ALL）=====
    /** 目标放大倍数 1.0 - 16.0（对应 Real-ESRGAN 的 -s） */
    val targetScale: IntParam = IntParam(
        enabled = true, value = 4, min = 1, max = 16, step = 1,
        applicability = ParamApplicability.ALL,
        label = "目标放大",
        description = "输出图像相对输入的放大倍数",
        unit = "×",
        defaultValue = 4,
    ),
    /** 输出文件名后缀（不改变实际格式，仅文件名添加） */
    val outputSuffix: OutputSuffixConfig = OutputSuffixConfig(
        enabled = false, value = "_upscaled",
    ),
    /** 并发档位（与全局 settings 同步时关闭） */
    val concurrencyOverride: IntParam = IntParam(
        enabled = false, value = 2, min = 1, max = 8,
        applicability = ParamApplicability.ALL,
        label = "推理并发数",
        description = "并行 tile 数（覆盖全局 settings）",
        unit = "线程",
        defaultValue = 2,
    ),

    // ===== 性能（适用域 = SR_GENERIC）=====
    /** Tile 大小 32-1024（0 = 自动，对应 realesrgan-ncnn-vulkan 的 -t） */
    val tileSize: IntParam = IntParam(
        enabled = false, value = 192, min = 32, max = 1024, step = 32,
        applicability = ParamApplicability.SR_GENERIC,
        label = "Tile 大小",
        description = "单次推理的 tile 边长。值越大越快但越占内存",
        unit = "px",
        defaultValue = 192,
    ),
    /** Tile padding（防止接缝，对应 --tile_pad） */
    val tilePad: IntParam = IntParam(
        // 2026-08-04 v3：改回 10（与 Real-ESRGAN 官方 ncnn-vulkan 默认值一致）
        // 之前的 v2 默认 32 反而引入了更多"训练-推理分布差异"
        // 因为模型训练时用的就是 10 像素 reflect padding
        enabled = true, value = 10, min = 0, max = 64,
        applicability = ParamApplicability.SR_GENERIC,
        label = "Tile 边缘填充",
        description = "相邻 tile 间的重叠像素（reflect 镜像），与官方 ncnn-vulkan 一致（推荐 10）",
        unit = "px",
        defaultValue = 10,
    ),
    /** Pre padding（对应 --pre_pad） */
    val prePad: IntParam = IntParam(
        enabled = false, value = 0, min = 0, max = 64,
        applicability = ParamApplicability.SR_GENERIC,
        label = "Pre Padding",
        description = "整图外扩像素，减少边缘失真",
        unit = "px",
        defaultValue = 0,
    ),
    /** FP32 精度（默认 fp16，对应 --fp32） */
    val useFp32: BoolParam = BoolParam(
        enabled = false, value = false,
        applicability = ParamApplicability.SR_GENERIC,
        label = "FP32 精度",
        description = "精度↑ 速度↓ 内存×2（CPU 默认 fp32，可忽略）",
        defaultValue = false,
    ),
    /** TTA 模式（Test-Time Augmentation，对应 -x） */
    val ttaMode: BoolParam = BoolParam(
        enabled = false, value = false,
        applicability = ParamApplicability.SR_GENERIC,
        label = "TTA 模式",
        description = "8x 推理 + 投票。极慢但精度最高",
        defaultValue = false,
    ),
    /** Load/Proc/Save 线程数（对应 -j 1:2:2） */
    val loadProcSaveThreads: StringParam = StringParam(
        enabled = false, value = "1:2:2",
        applicability = ParamApplicability.SR_GENERIC,
        label = "线程分配 (load:proc:save)",
        description = "图片加载/推理/编码 线程比例",
        defaultValue = "1:2:2",
    ),

    // ===== 增强（适用域 = SR_GENERAL_X4V3 / FACE_GFPGAN）=====
    /** 去噪强度 0-1（仅 realesr-general-x4v3，对应 --denoise_strength） */
    val denoiseStrength: FloatParam = FloatParam(
        enabled = false, value = 0.5f, min = 0.0f, max = 1.0f, step = 0.05f,
        applicability = ParamApplicability.SR_GENERAL_X4V3,
        label = "去噪强度",
        description = "0=保留噪点 1=最大去噪（仅 realesr-general-x4v3）",
        unit = "",
        defaultValue = 0.5f,
    ),
    /** GFPGAN 人脸增强（对应 --face_enhance） */
    val faceEnhance: BoolParam = BoolParam(
        enabled = false, value = false,
        applicability = ParamApplicability.FACE_GFPGAN,
        label = "人脸增强",
        description = "GFPGAN 联级（需 GFPGAN 模型可用，M3+ 启用）",
        defaultValue = false,
    ),
    /** GFPGAN 版本（1/1.2/1.3） */
    val gfpganVersion: StringParam = StringParam(
        enabled = false, value = "1.3",
        applicability = ParamApplicability.FACE_GFPGAN,
        label = "GFPGAN 版本",
        description = "1.3 默认（自然），1.2 更稳",
        defaultValue = "1.3",
    ),
    /** 只处理中心脸（对应 --only_center_face） */
    val onlyCenterFace: BoolParam = BoolParam(
        enabled = false, value = false,
        applicability = ParamApplicability.FACE_GFPGAN,
        label = "只处理中心脸",
        description = "跳过边缘的小脸（避免误识别）",
        defaultValue = false,
    ),

    // ===== Real-CUGAN / 高级（适用域 = UPSCALE_GENERIC）=====
    /** 噪点等级（Real-CUGAN：-1/0/1/2/3） */
    val noiseLevel: IntParam = IntParam(
        enabled = false, value = 0, min = -1, max = 3,
        applicability = ParamApplicability.UPSCALE_GENERIC,
        label = "噪点等级",
        description = "Real-CUGAN 专用：-1=无去噪 0=保守 1=denoise1x 2=denoise2x 3=denoise3x（官方 -n 参数）",
        unit = "",
        defaultValue = 0,
    ),
    /** Alpha 通道放大方式（realesrgan / bicubic） */
    val alphaUpsampler: StringParam = StringParam(
        enabled = false, value = "realesrgan",
        applicability = ParamApplicability.UPSCALE_GENERIC,
        label = "Alpha 通道放大",
        description = "PNG 透明通道放大算法（realesrgan 或 bicubic）",
        defaultValue = "realesrgan",
    ),

    // ===== 引擎能力覆盖（高级，适用域 = ALL）=====
    /** 输入张量归一化均值（覆盖 EngineCapabilities.mean） */
    val meanOverride: FloatParam = FloatParam(
        enabled = false, value = 0.0f, min = 0.0f, max = 1.0f, step = 0.05f,
        applicability = ParamApplicability.ALL,
        label = "归一化均值 (高级)",
        description = "覆盖 Auto-Probe 推断的 mean",
        unit = "",
        defaultValue = 0.0f,
    ),
    /** 输入张量归一化标准差 */
    val stdOverride: FloatParam = FloatParam(
        enabled = false, value = 1.0f, min = 0.1f, max = 2.0f, step = 0.05f,
        applicability = ParamApplicability.ALL,
        label = "归一化标准差 (高级)",
        description = "覆盖 Auto-Probe 推断的 std",
        unit = "",
        defaultValue = 1.0f,
    ),
    /** 最大输入边长（覆盖 EngineCapabilities.maxInputEdge） */
    val maxInputEdgeOverride: IntParam = IntParam(
        enabled = false, value = 192, min = 32, max = 2048, step = 32,
        applicability = ParamApplicability.ALL,
        label = "最大输入边长 (高级)",
        description = "覆盖 Auto-Probe 推断的 maxInputEdge",
        unit = "px",
        defaultValue = 192,
    ),
) {
    /** 合并出最终生效值（未启用的走 defaultValue） */
    fun effectiveTargetScale(): Int = targetScale.effective().toInt()
    fun effectiveTileSize(): Int = tileSize.effective()
    fun effectiveDenoiseStrength(): Float = denoiseStrength.effective()
    fun effectiveOutputSuffix(): String? = if (outputSuffix.enabled) outputSuffix.value else null
    fun effectiveMaxInputEdge(original: Int): Int =
        if (maxInputEdgeOverride.enabled) maxInputEdgeOverride.value else original
    fun effectiveMean(original: Float): Float =
        if (meanOverride.enabled) meanOverride.value else original
    fun effectiveStd(original: Float): Float =
        if (stdOverride.enabled) stdOverride.value else original
}

/** 通用浮点参数（带 enabled 标志） */
@Serializable
data class FloatParam(
    val enabled: Boolean = false,
    val value: Float = 0f,
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0.05f,
    val applicability: ParamApplicability = ParamApplicability.ALL,
    val label: String = "",
    val description: String = "",
    val unit: String = "",
    val defaultValue: Float = 0f,
) {
    fun effective(): Float = if (enabled) value else defaultValue
}

/** 通用整数参数 */
@Serializable
data class IntParam(
    val enabled: Boolean = false,
    val value: Int = 0,
    val min: Int = 0,
    val max: Int = 100,
    val step: Int = 1,
    val applicability: ParamApplicability = ParamApplicability.ALL,
    val label: String = "",
    val description: String = "",
    val unit: String = "",
    val defaultValue: Int = 0,
) {
    fun effective(): Int = if (enabled) value else defaultValue
}

/** 通用布尔参数 */
@Serializable
data class BoolParam(
    val enabled: Boolean = false,
    val value: Boolean = false,
    val applicability: ParamApplicability = ParamApplicability.ALL,
    val label: String = "",
    val description: String = "",
    val defaultValue: Boolean = false,
) {
    fun effective(): Boolean = if (enabled) value else defaultValue
}

/** 通用字符串参数 */
@Serializable
data class StringParam(
    val enabled: Boolean = false,
    val value: String = "",
    val applicability: ParamApplicability = ParamApplicability.ALL,
    val label: String = "",
    val description: String = "",
    val defaultValue: String = "",
) {
    fun effective(): String = if (enabled) value else defaultValue
}

/**
 * 参数存储仓库（独立文件 engines/params.json）
 *
 * 设计：所有引擎的参数合并存到一个 JSON 文件，按 engineId 索引
 * - 加载：忽略未知 engineId（升级兼容）
 * - 保存：原子写（先写 .tmp，再 rename）
 * - 默认值：getOrPut 自动用 ModelParameters() 全默认
 */
class ParamsRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    private val _params = MutableStateFlow<Map<String, ModelParameters>>(emptyMap())
    val params: StateFlow<Map<String, ModelParameters>> = _params.asStateFlow()

    private val paramsFile: File
        get() = File(context.filesDir, "engines/params.json")

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded: Map<String, ModelParameters> = if (paramsFile.exists()) {
                runCatching {
                    val raw = paramsFile.readText()
                    if (raw.isBlank()) emptyMap()
                    else json.decodeFromString(
                        MapSerializer(String.serializer(), ModelParameters.serializer()),
                        raw
                    )
                }.getOrElse { emptyMap() }
            } else {
                emptyMap()
            }
            _params.value = loaded
        }
    }

    /** 获取某个引擎的参数（没有则返回默认值） */
    fun get(engineId: String): ModelParameters =
        _params.value[engineId] ?: ModelParameters()

    /** 获取并允许修改（mutable copy） */
    fun getOrDefault(engineId: String): ModelParameters =
        _params.value[engineId] ?: ModelParameters()

    /** 保存某个引擎的参数（覆盖式） */
    suspend fun save(engineId: String, params: ModelParameters) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = _params.value.toMutableMap()
                next[engineId] = params
                persist(next)
            }
        }

    /** 重置某个引擎的参数 */
    suspend fun reset(engineId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _params.value.toMutableMap()
            next.remove(engineId)
            persist(next)
        }
    }

    private fun persist(map: Map<String, ModelParameters>) {
        val parent = paramsFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        // 原子写：避免半写状态导致参数丢失
        val tmp = File(parent, "params.json.tmp")
        tmp.writeText(
            json.encodeToString(
                MapSerializer(String.serializer(), ModelParameters.serializer()),
                map
            )
        )
        if (paramsFile.exists()) paramsFile.delete()
        tmp.renameTo(paramsFile)
        _params.value = map
    }
}