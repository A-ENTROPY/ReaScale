# ReaScale

Android AI 图像超分辨率应用。NCNN / ONNX Runtime 双引擎，支持用户导入第三方模型；超大图流式输出，突破单张 Bitmap 内存上限。

**私有项目，仅供个人使用。**

## 特性

- **双引擎**
  - **NCNN**（内置 Real-CUGAN 模型，CPU）：2x/3x/4x + GFPGAN 人脸修复，C++ 层 tile 行带并行推理
  - **ONNX Runtime**：支持导入任意 `.onnx` 超分模型，自动探测输入尺寸/倍率/残差语义/归一化域
- **超大图支持**（核心特性）
  - 推理分块（tile）+ 重叠消除接缝
  - 流式编码直写磁盘，输出不驻留整图内存：
    | 格式 | 实现 |
    |---|---|
    | JPEG | 纯 Kotlin baseline 编码器（4:4:4，IJG 质量缩放） |
    | PNG | 纯 Kotlin zlib + 手写 chunk 编码器 |
    | JXL (JPEG XL) | JNI 复用 libjxl（dlopen），chunked frame 官方流式 API |
    | WebP / HEIC / HEIF / AVIF | 无流式入口，自动降级 JPEG |
- **批量队列**：多图排队、前台服务、通知进度、失败重试
- **输出**：系统相册 / SAF 自选目录；质量条全格式映射

## 系统要求

- Android 9.0+（API 26）
- arm64-v8a

## 构建

```bash
# ONNX / Compose 等依赖自动解析
./gradlew :app:assembleDebug

# NCNN 需预编译库（见 app/CMakeLists.txt 头部说明）：
#   解压 ncnn-<version>-android-vulkan 到 <repo 上级>/build_tmp/
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 导入自定义 ONNX 模型

设置 → 引擎管理 → 导入 `.onnx`。引擎会自动探测：

1. **尺寸公式**：双尺寸差分推理得 `scale` 与 `K`（`out = in*scale - K`）
2. **残差 vs 完整图像**：灰阶对照实验判定输出语义
3. **归一化域**：0..1 / 0..255 对照实验
4. **固定输入尺寸模型**：按模型输入尺寸切块 + REPLICATE pad + 输出裁剪

兼容 Real-ESRGAN、Real-CUGAN、animevideo 等常见导出格式。

## 架构

```
app/src/main/java/io/reascale/app/
├── core/
│   ├── engine/        # NcnnEngine / OnnxEngine / UpscaleEngine 接口
│   ├── encode/        # JxlStreamWriter(JNI) + QualityMapper
│   ├── imageio/       # StreamingPngWriter / StreamingJpegWriter / RegionDecoder
│   ├── processing/    # ImageProcessor（三路径决策 + 行带并行 + 流式输出）
│   └── MemoryBudget   # 设备内存预算 → tile 尺寸
├── queue/             # 队列调度（QueueRunner / QueueManager）
├── ui/                # Compose 界面（Home / Queue / Engines / Settings）
└── debug/LogBus       # 环形日志（文件 + logcat 双写）

app/src/main/cpp/
├── reascale_ncnn.cpp      # NCNN JNI：tile 并行 + 语义探测
└── jxl_stream_writer.cpp  # libjxl 流式编码 JNI（dlopen 复用 libjxl.so）
```

## 性能设计

- tile 行带内多线程并行推理（std::thread / 协程），行带间串行保序
- 过订阅防护：外部并行度 × 引擎内部线程数 ≈ CPU 核数
- 流式编码背压：行池按编码进度回收（峰值 = 窗口行数 × 行宽）
- 大输出不落整图：>200MB bitmap 场景全部走流式路径

## 已知限制

- TTA 模式暂不支持 ONNX 引擎
- 超大图 HEIC/HEIF/AVIF 自动降级 JPEG（硬件编码器有分辨率上限）
- ONNX 固定尺寸模型的边缘 tile 使用重叠平均，极高频纹理可能有轻微差异

## 致谢与第三方组件

本项目内置/依赖以下开源组件，感谢原作者：

| 组件 | 用途 | 许可 |
|---|---|---|
| [ncnn](https://github.com/Tencent/ncnn) | 推理框架 | MIT |
| [Real-CUGAN](https://github.com/bilibili/Real-CUGAN) | 内置超分模型（models-se/pro/cunet，NCNN 转换版） | MIT |
| [waifu2x](https://github.com/nagadomi/waifu2x) / [waifu2x-ncnn-vulkan](https://github.com/nihui/waifu2x-ncnn-vulkan) | 内置 upconv_7 系列模型（NCNN 转换版） | MIT |
| [onnxruntime](https://github.com/microsoft/onnxruntime) | ONNX 推理引擎 | MIT |
| [libjxl](https://github.com/libjxl/libjxl)（经 [jxl-coder](https://github.com/awxkee/jxl-coder) 集成） | JPEG XL 编解码 | BSD-3-Clause |
| [avif-coder](https://github.com/awxkee/avif-coder)（libavif + libaom） | AVIF 编码 | 见上游 |
| [Compose / AndroidX](https://developer.android.com/jetpack/androidx) | UI 框架 | Apache-2.0 |

用户导入的第三方 ONNX 模型版权归各自作者所有，使用时请遵循对应模型的许可条款。

## License

本项目代码以 [MIT License](LICENSE) 发布。

内置模型权重分别来自 Real-CUGAN 与 waifu2x（均为 MIT 许可），随应用分发时保留其原始版权声明。
