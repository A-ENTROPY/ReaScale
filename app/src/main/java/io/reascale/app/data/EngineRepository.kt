package io.reascale.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 引擎档案仓储
 * 对应 §20.5 profile.json 持久化
 *
 * 存储位置：filesDir/engines/profiles.json
 * 每个 EngineProfile + 用户选择的 .onnx 复制到 filesDir/engines/models/<id>.onnx
 *
 * M1 阶段：纯内存 + JSON 文件
 * M7 阶段：可平滑迁移到 Room
 */
class EngineRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<EngineProfile>>(emptyList())
    val profiles: StateFlow<List<EngineProfile>> = _profiles.asStateFlow()

    private val profilesFile: File
        get() = File(context.filesDir, "engines/profiles.json")

    private val modelsDir: File
        get() = File(context.filesDir, "engines/models").apply { mkdirs() }

    /** 内置引擎清单（NCNN 原生路径）
     *
     *  关键（2026-08-09 迁移）：id 名保持 `builtin_realesrgan_x*` 不变，
     *  因为老用户 `profiles.json` 里有这些 id；只把 modelUri 切到
     *  assets 里实际存在的 NCNN 模型 (realcugan ncnn-vulkan)。
     *  这样 initialize() 的合并逻辑会自动用新 seed 覆盖老 profile 的 modelUri。
     */
    private val builtinSeeds: List<EngineProfile> = listOf(
        EngineProfile(
            id = "builtin_realesrgan_x2plus",
            displayName = "Real-CUGAN 2x (无去噪)",
            source = EngineSource.BUILTIN,
            modelUri = "ncnn:realcugan/models-se/up2x-no-denoise",
            domain = EngineDomain.GENERAL,
            capabilities = EngineCapabilities(
                baseScale = 2,
                maxInputEdge = 1024,
                maxInputPixels = 1024L * 1024L,
                inputLayout = "NCHW",
                inputDtype = "float32",
                mean = 0.0f,
                std = 1.0f,
                fixedSize = false,
                channels = 3
            ),
            note = "内置：Real-CUGAN 2x no-denoise（NCNN + Vulkan），MIT · 由 RealESRGAN 切到 RealCUGAN (匹配 assets 模型)"
        ),
        EngineProfile(
            id = "builtin_realesrgan_x4plus",
            displayName = "Real-CUGAN 4x (保守)",
            source = EngineSource.BUILTIN,
            modelUri = "ncnn:realcugan/models-se/up4x-conservative",
            domain = EngineDomain.PHOTO,
            capabilities = EngineCapabilities(
                baseScale = 4,
                maxInputEdge = 512,
                maxInputPixels = 512L * 512L,
                inputLayout = "NCHW",
                inputDtype = "float32",
                mean = 0.0f,
                std = 1.0f,
                fixedSize = false,
                channels = 3
            ),
            note = "内置：Real-CUGAN 4x conservative（NCNN + Vulkan），MIT · 由 RealESRGAN 切到 RealCUGAN (匹配 assets 模型)"
        ),
        EngineProfile(
            id = "builtin_realesrgan_x4plus_anime_6b",
            displayName = "Real-CUGAN 3x (无去噪)",
            source = EngineSource.BUILTIN,
            modelUri = "ncnn:realcugan/models-se/up3x-no-denoise",
            domain = EngineDomain.ANIME,
            capabilities = EngineCapabilities(
                baseScale = 3,
                maxInputEdge = 768,
                maxInputPixels = 768L * 768L,
                inputLayout = "NCHW",
                inputDtype = "float32",
                mean = 0.0f,
                std = 1.0f,
                fixedSize = false,
                channels = 3
            ),
            note = "内置：Real-CUGAN 3x no-denoise（NCNN + Vulkan），MIT · 由 anime 切到 RealCUGAN 3x"
        ),
        EngineProfile(
            id = "builtin_gfpgan_v14",
            displayName = "GFPGAN v1.4 (人脸修复)",
            source = EngineSource.BUILTIN,
            modelUri = "asset:models/gfpgan-v1.4.onnx",   // M5 才补
            domain = EngineDomain.FACE,
            capabilities = EngineCapabilities(
                baseScale = 1,
                maxInputEdge = 512,
                maxInputPixels = 512L * 512L,
                inputLayout = "NCHW",
                inputDtype = "float32",
                mean = 0.5f,
                std = 0.5f,
                fixedSize = true,
                channels = 3
            ),
            note = "M5 待补：Tencent ARC GFPGAN v1.4；当前 assets 缺失，使用时会自动回退到 StubEngine"
        )
    )

    /** 初始化：首次启动时写入种子；后续从文件加载
     *
     * 关键修复（2026-08-02 真 bug）：
     * 早期版本 BUILTIN profile 的 modelUri 路径布局不同（如
     * `asset:realesrgan_x4plus_anime_6b.onnx`），后来把模型文件挪到
     * `assets/models/` 目录，更新为 `asset:models/realesrgan-x4plus-anime.onnx`。
     * 但旧用户的 `profiles.json` 还停留在旧路径 → 加载失败 → 误 fallback 到 StubEngine。
     *
     * 解决方案：每次加载后，把 builtinSeeds 按 id 匹配，**用当前 seed 覆盖**
     * 持久化数据中同 id 的 BUILTIN profile（只覆盖 modelUri/displayName/capabilities/note，
     * 保留用户可能改过的自定义字段）。USER 引擎完全不动。
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded: List<EngineProfile> = if (profilesFile.exists()) {
                runCatching {
                    val raw = profilesFile.readText()
                    if (raw.isBlank()) emptyList()
                    else json.decodeFromString(
                        ListSerializer(EngineProfile.serializer()),
                        raw
                    )
                }.getOrElse { builtinSeeds }
            } else {
                profilesFile.parentFile?.mkdirs()
                profilesFile.writeText(
                    json.encodeToString(
                        ListSerializer(EngineProfile.serializer()),
                        builtinSeeds
                    )
                )
                builtinSeeds
            }
            // 合并：builtinSeeds 覆盖 loaded 中同 id 的 BUILTIN profile（只覆盖规范化字段）
            val seedById = builtinSeeds.associateBy { it.id }
            val merged = loaded.map { p ->
                val seed = seedById[p.id]
                if (seed != null && p.source == EngineSource.BUILTIN) {
                    // 模型路径/显示名/capabilities 全部用最新 seed
                    seed
                } else {
                    p
                }
            }
            // 任何 seed 里新增的 id（loaded 缺失）也要补上
            val finalList = merged.ifEmpty { builtinSeeds }
                .let { list ->
                    val haveIds = list.map { it.id }.toSet()
                    val missing = builtinSeeds.filter { it.id !in haveIds }
                    if (missing.isNotEmpty()) list + missing else list
                }
            if (finalList != loaded) {
                // 写回 disk
                profilesFile.parentFile?.mkdirs()
                profilesFile.writeText(
                    json.encodeToString(
                        ListSerializer(EngineProfile.serializer()),
                        finalList
                    )
                )
            }
            _profiles.value = finalList
        }
    }

    /** 新增或更新引擎 */
    suspend fun upsert(profile: EngineProfile) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _profiles.value.toMutableList()
            val idx = list.indexOfFirst { it.id == profile.id }
            if (idx >= 0) list[idx] = profile else list.add(profile)
            persist(list)
        }
    }

    /** 删除用户导入的引擎（内置不可删） */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _profiles.value
            val target = list.firstOrNull { it.id == id } ?: return@withContext false
            if (target.source == EngineSource.BUILTIN) return@withContext false
            val next = list.filterNot { it.id == id }
            persist(next)
            // 删除模型文件：旧 ONNX 布局 models/<id>.onnx + NCNN 布局 models/<id>/ 目录
            File(modelsDir, "$id.onnx").delete()
            File(modelsDir, id).deleteRecursively()
            true
        }
    }

    /** 通过 ID 查找 */
    fun findById(id: String): EngineProfile? = _profiles.value.firstOrNull { it.id == id }

    /** 用户导入时返回模型存放路径 */
    fun modelFileFor(profile: EngineProfile): File? = when (profile.source) {
        EngineSource.BUILTIN -> null // 内置从 assets 取
        EngineSource.USER -> File(modelsDir, "${profile.id}.onnx")
    }

    private fun persist(list: List<EngineProfile>) {
        profilesFile.writeText(
            json.encodeToString(
                ListSerializer(EngineProfile.serializer()),
                list
            )
        )
        _profiles.value = list
    }

    /**
     * 导入用户模型（§20 零配置导入）
     *
     * 1. 复制到内部 modelsDir/<newId> 目录
     * 2. Auto-Probe 推断 baseScale / domain / capabilities
     * 3. 写入 profile.json
     * 4. 返回新 profile
     *
     * 支持格式：
     * - ncnn 模型：.param + 同名 .bin（成对）
     * - 其他（.onnx 等）：抛出异常 —— ONNX 推理后端已移除（统一走 NCNN），
     *   导入 ONNX 会产生无法运行的 profile，必须显式拒绝
     *
     * @param src 待复制的源文件（通常来自 SAF 复制到 cacheDir/imports/）
     * @param binOverride [FIX 2026-08-17] 用户多选时提供的 .bin 文件（可为 null，
     *        此时尝试找 src 同目录同名 .bin）。复制时统一改名为 param 同名，
     *        解决"选了 .bin 提示不支持"的问题
     * @param userDisplayName 用户指定的显示名（默认用文件名）
     */
    suspend fun importOnnx(
        src: File,
        binOverride: File? = null,
        userDisplayName: String? = null
    ): EngineProfile = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!src.name.endsWith(".param", ignoreCase = true)) {
                throw IllegalStateException(
                    "不支持的模型格式（${src.name}）：ONNX 推理已停用，请导入 ncnn 模型（.param 文件，需与同名 .bin 成对选择）"
                )
            }
            // 1. 生成新 ID + 复制 .param 与 .bin 到 models/<id>/ 目录
            val id = "user_${System.currentTimeMillis()}"
            val modelDir2 = File(modelsDir, id).apply { mkdirs() }
            val dst = File(modelDir2, src.name)
            src.copyTo(dst, overwrite = true)
            // bin 来源：优先多选提供的 .bin，否则找 src 同目录同名 .bin
            val srcBin = binOverride?.takeIf { it.exists() && it.name.endsWith(".bin", ignoreCase = true) }
                ?: File(src.parentFile, src.name.replace(".param", ".bin"))
            if (!srcBin.exists()) {
                dst.delete()
                throw IllegalStateException(
                    "缺少模型权重文件：${srcBin.name}（.param 需与同名 .bin 成对选择）"
                )
            }
            // 复制为 param 同名，保证 C++ 端 param→bin 路径推导一致
            srcBin.copyTo(File(modelDir2, dst.name.replace(".param", ".bin")), overwrite = true)

            // 2. Auto-Probe：读文件大小 + 文件名启发式推断
            val sizeBytes = dst.length()
            val probe = AutoProbe.probe(
                file = dst,
                filename = src.name
            )

            // 3. 构造 profile
            val displayName = userDisplayName?.takeIf { it.isNotBlank() }
                ?: src.nameWithoutExtension.take(32)
            // ncnn 模型 modelUri 指向 .param 文件路径（ImageProcessor 据此路由）
            val modelUri = dst.absolutePath
            val profile = EngineProfile(
                id = id,
                displayName = displayName,
                source = EngineSource.USER,
                modelUri = modelUri,
                domain = probe.domain,
                capabilities = EngineCapabilities(
                    baseScale = probe.baseScale,
                    maxInputEdge = probe.maxInputEdge,
                    maxInputPixels = probe.maxInputPixels,
                    inputLayout = "NCHW",
                    inputDtype = "float32",
                    mean = probe.mean,
                    std = probe.std,
                    fixedSize = probe.fixedSize,
                    channels = 3
                ),
                note = "${(sizeBytes / 1024 / 1024)}MB · ${probe.probeNote} · NCNN 模型",
                sha256 = "" // TODO M4: 算 SHA256
            )

            // 4. 持久化
            val list = _profiles.value.toMutableList()
            list.add(profile)
            persist(list)
            profile
        }
    }

    /**
     * 删除导入的引擎（已存在 delete()）
     */
    fun isBuiltin(id: String): Boolean =
        _profiles.value.firstOrNull { it.id == id }?.source == EngineSource.BUILTIN
}