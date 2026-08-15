package io.reascale.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 引擎来源 —— 内置 vs 用户导入
 * 对应 §4.1 / §20
 */
@Serializable
enum class EngineSource {
    @SerialName("builtin") BUILTIN,
    @SerialName("user") USER
}

/**
 * 引擎作用域 —— 真人 / 动漫 / 人脸 / 通用
 * 对应 §5 内置引擎清单
 */
@Serializable
enum class EngineDomain {
    @SerialName("photo") PHOTO,
    @SerialName("anime") ANIME,
    @SerialName("face") FACE,
    @SerialName("general") GENERAL
}

/**
 * 引擎能力描述
 * 对应 §20.4 Auto-Probe 启发式推断
 */
@Serializable
data class EngineCapabilities(
    /** 模型原生放大倍数，例如 2 / 4 */
    val baseScale: Int,
    /** 模型原生支持的最大输入边长（像素）。0 = 不限 */
    val maxInputEdge: Int = 0,
    /** 模型原生支持的最大输入像素（宽*高）。0 = 不限 */
    val maxInputPixels: Long = 0L,
    /** 输入张量格式: NCHW / NHWC */
    val inputLayout: String = "NCHW",
    /** 输入张量数据类型 */
    val inputDtype: String = "float32",
    /** 0..1 浮点输入归一化均值 */
    val mean: Float = 0.0f,
    /** 0..1 浮点输入归一化标准差 */
    val std: Float = 1.0f,
    /** 输入张量是否固定尺寸（true 则必须 tile 到该尺寸） */
    val fixedSize: Boolean = false,
    /** 通道数（RGB=3，灰度=1） */
    val channels: Int = 3
)

/**
 * 引擎档案 = profile.json
 * 对应 §20.5 + §24 引擎面板
 * 由 Auto-Probe 推断生成，用户可在 EngineEditor 中编辑覆盖
 */
@Serializable
data class EngineProfile(
    /** 引擎全局唯一 ID（UUID） */
    val id: String,
    /** 显示名（用户可改） */
    var displayName: String,
    /** 引擎来源 */
    val source: EngineSource,
    /** 模型文件 URI 字符串（用户导入）或内置资产名 */
    val modelUri: String,
    /** 引擎作用域 */
    val domain: EngineDomain,
    /** 能力描述 */
    val capabilities: EngineCapabilities,
    /** 用户备注 */
    val note: String = "",
    /** 创建时间（epoch millis） */
    val createdAt: Long = System.currentTimeMillis(),
    /** 最后一次 Dry-Run 通过时间（0 = 未通过） */
    val lastDryRunAt: Long = 0L,
    /** Dry-Run 是否通过 */
    val dryRunPassed: Boolean = false,
    /** 模型文件 SHA-256（用于去重 / 完整性校验） */
    val sha256: String = ""
)

/**
 * 引擎面板的目标放大倍数（用户可调）
 * 对应 §24 三路径放大
 */
@Serializable
data class UpscalePlan(
    /** 用户请求的目标放大倍数（1 - 16） */
    val targetScale: Int,
    /** 是否允许基础+下采样路径（C 路径：4x 模型做 2x） */
    val allowBasePlusDownsample: Boolean = true
) {
    init {
        require(targetScale in 1..16) { "targetScale 必须在 1..16 范围" }
    }
}

/**
 * 输出格式
 * 对应 §19.3 默认 7 种
 */
@Serializable
enum class OutputFormat {
    @SerialName("jpeg") JPEG,
    @SerialName("png") PNG,
    @SerialName("webp") WEBP,
    @SerialName("heic") HEIC,
    @SerialName("heif") HEIF,
    @SerialName("avif") AVIF,
    @SerialName("jxl") JXL
}

/**
 * 输出编码选项
 * 对应 §19.2 EncodeOptions
 * 注意：quality 对不同格式有非线性映射
 *  - JPEG: 0-100 线性
 *  - PNG: compression 0-9（与 quality 反向，这里 100 = 无压缩）
 *  - WebP: 0-100 质量
 *  - HEIC/HEIF: CRF 18-51（quality 100 → CRF 18；0 → CRF 51）
 *  - AVIF: aom CQ 0-63（quality 100 → CQ 18；0 → CQ 63）
 *  - JXL: distance 0.0-15.0（quality 100 → 0.0；0 → 15.0）
 */
@Serializable
data class EncodeOptions(
    val format: OutputFormat = OutputFormat.JPEG,
    /** 1-100 通用质量条 */
    val quality: Int = 95,
    /** 是否保留 EXIF（默认 true；某些场景如 JXL 可能丢失） */
    val keepExif: Boolean = true,
    /** 是否保留 ICC 色彩配置（默认 true） */
    val keepIccProfile: Boolean = true,
    /** PNG 专用：0-9（仅当 format = PNG 时生效；quality=100 → compression=0） */
    val pngCompression: Int = 3,
    /** WebP 专用：是否使用无损模式（quality=100 时自动启用） */
    val webpLossless: Boolean = false
)

/**
 * 并发档位
 * 对应 §18 ConcurrencyGate
 */
@Serializable
enum class ConcurrencyProfile {
    @SerialName("saver") SAVER,     // 省电：1-2 线程，温和
    @SerialName("balanced") BALANCED, // 平衡：2-3 线程
    @SerialName("performance") PERFORMANCE // 性能：3-4 线程 + Vulkan
}

/**
 * 主题模式（用户显式选择）
 * 关键：与 isSystemInDarkTheme 解耦 —— 用户选 LIGHT/DARK 时强制覆盖系统设置
 */
@Serializable
enum class ThemeMode {
    @SerialName("system") SYSTEM,  // 跟随系统
    @SerialName("light") LIGHT,    // 强制浅色
    @SerialName("dark") DARK       // 强制深色（与 amoled 配合可纯黑）
}

/**
 * 应用全局设置
 * 对应 §18.10 + §19.6 + §27 性能 SLA + §30 主题
 */
@Serializable
data class AppSettings(
    val concurrency: ConcurrencyProfile = ConcurrencyProfile.BALANCED,
    val encodeOptions: EncodeOptions = EncodeOptions(),
    val defaultEngineId: String = "builtin_realesrgan_x2plus",
    val outputDirUri: String = "", // 用户选择的 SAF 目录
    val keepOriginalOnConflict: Boolean = true, // 同名文件是否覆盖
    val enableForegroundService: Boolean = true, // 大队列时显示前台通知
    val maxQueueSize: Int = 5000, // 队列上限（§18 限流）
    val enableAutoProbe: Boolean = true, // 导入 ONNX 时自动探测
    val showPerformanceHud: Boolean = false, // 调试用 HUD
    // === 主题（§30.2） ===
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = false,       // 默认关：保持 ReaScale 品牌色
    val amoled: Boolean = true                  // 默认开：深色模式背景 = 纯黑 #000000
)

/**
 * 队列任务状态
 * 对应 §18.3 Job 状态机
 */
@Serializable
enum class JobStatus {
    @SerialName("pending") PENDING,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("retry_wait") RETRY_WAIT
}

/**
 * 队列中的一项任务
 * 对应 §18 + §26 Room schema（M7 启用 Room 后转 @Entity）
 * 现在 M1 阶段先用内存版 + DataStore 持久化最简版
 */
@Serializable
data class ImageJob(
    val id: String,                    // UUID
    val sourceUri: String,             // SAF Uri 字符串
    val sourceDisplayName: String,     // 用户可见文件名
    val sourceSizeBytes: Long,         // 原文件大小
    val sourceWidth: Int,              // 原图宽（解码后回填）
    val sourceHeight: Int,             // 原图高
    val engineId: String,              // 使用的引擎
    val upscalePlan: UpscalePlan,      // 目标放大
    val encodeOptions: EncodeOptions,   // 输出格式
    val status: JobStatus = JobStatus.PENDING,
    val progress: Float = 0f,          // 0..1
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val retryCount: Int = 0,
    val lastError: String = "",
    val outputUri: String = ""         // 完成后填入
)