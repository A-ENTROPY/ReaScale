// jxl_stream_writer.cpp
// 流式 JPEG XL 编码器 JNI 封装（libjxl 0.9.x，dlopen/dlsym 复用应用已加载的 libjxl.so）
//
// 用途：超大图放大时，推理产出的输出行直接流式喂给 libjxl 编码器，
// 内存峰值 = 编码器流水线深度 + 行池中未消费行，不要求整帧驻留内存。
//
// 线程模型：
//   - 生产者线程（Kotlin）：nativeFeedRow 逐行投喂 RGB 数据 → nativeFinishInput
//   - 编码线程（Kotlin 另起）：nativeStart（内部 JxlEncoderAddChunkedFrame，
//     若 libjxl 同步拉取则阻塞等待行就绪）→ nativeDrain 循环取输出字节
//
// ABI 声明取自 libjxl v0.9.2 官方头文件（encode.h / codestream_header.h /
// types.h / parallel_runner.h / memory_manager.h），结构布局严格一致。

#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <mutex>
#include <condition_variable>
#include <vector>
#include <memory>
#include <string>

#include <android/log.h>
#define LOG_TAG "JxlStream"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ============ libjxl 0.9.2 ABI 声明 ============
#define JXL_BOOL int
#define JXL_TRUE 1
#define JXL_FALSE 0

typedef enum {
  JXL_ENC_SUCCESS = 0,
  JXL_ENC_ERROR = 1,
  JXL_ENC_NEED_MORE_OUTPUT = 2,
  JXL_ENC_NOT_SUPPORTED = 3
} JxlEncoderStatus;

typedef enum {
  JXL_TYPE_FLOAT = 0,
  JXL_TYPE_FLOAT16 = 1,
  JXL_TYPE_UINT8 = 2,
  JXL_TYPE_UINT16 = 3,
  JXL_TYPE_UINT32 = 4,
} JxlDataType;

typedef enum {
  JXL_NATIVE_ENDIAN = 0,
  JXL_LITTLE_ENDIAN = 1,
  JXL_BIG_ENDIAN = 2
} JxlEndianness;

typedef struct {
  uint32_t num_channels;
  JxlDataType data_type;
  JxlEndianness endianness;
  size_t align;
} JxlPixelFormat;

typedef enum {
  JXL_ORIENT_IDENTITY = 1,
  JXL_ORIENT_FLIP_HORIZONTAL = 2,
  JXL_ORIENT_ROTATE_180 = 3,
  JXL_ORIENT_FLIP_VERTICAL = 4,
  JXL_ORIENT_TRANSPOSE = 5,
  JXL_ORIENT_ROTATE_90_CW = 6,
  JXL_ORIENT_ANTI_TRANSPOSE = 7,
  JXL_ORIENT_ROTATE_90_CCW = 8
} JxlOrientation;

typedef struct {
  uint32_t xsize;
  uint32_t ysize;
} JxlPreviewHeader;

typedef struct {
  uint32_t tps_numerator;
  uint32_t tps_denominator;
  uint32_t num_loops;
  JXL_BOOL have_timecodes;
} JxlAnimationHeader;

// 布局与 v0.9.2 完全一致
typedef struct {
  JXL_BOOL have_container;
  uint32_t xsize;
  uint32_t ysize;
  uint32_t bits_per_sample;
  uint32_t exponent_bits_per_sample;
  float intensity_target;
  float min_nits;
  JXL_BOOL relative_to_max_display;
  float linear_below;
  JXL_BOOL uses_original_profile;
  JXL_BOOL have_preview;
  JXL_BOOL have_animation;
  JxlOrientation orientation;
  uint32_t num_color_channels;
  uint32_t num_extra_channels;
  uint32_t alpha_bits;
  uint32_t alpha_exponent_bits;
  JXL_BOOL alpha_premultiplied;
  JxlPreviewHeader preview;
  JxlAnimationHeader animation;
  uint32_t intrinsic_xsize;
  uint32_t intrinsic_ysize;
  uint8_t padding[100];
} JxlBasicInfo;

typedef void* (*jpegxl_alloc_func)(void* opaque, size_t size);
typedef void (*jpegxl_free_func)(void* opaque, void* address);

typedef struct JxlMemoryManagerStruct {
  void* opaque;
  jpegxl_alloc_func alloc;
  jpegxl_free_func free;
} JxlMemoryManager;

typedef struct JxlEncoderStruct JxlEncoder;
typedef struct JxlEncoderFrameSettingsStruct JxlEncoderFrameSettings;

typedef int JxlParallelRetCode;
typedef int (*JxlParallelRunInit)(void* jpegxl_opaque, size_t num_threads);
typedef void (*JxlParallelRunFunction)(void* jpegxl_opaque, uint32_t value,
                                       size_t num_threads);
typedef int (*JxlParallelRunner)(void* runner_opaque, void* jpegxl_opaque,
                                 JxlParallelRunInit init,
                                 JxlParallelRunFunction func,
                                 size_t start_range, size_t end_range);

// 0.9.2：C 风格 struct（按值传参）
typedef struct JxlChunkedFrameInputSource {
  void* opaque;
  void (*get_color_channels_pixel_format)(void* opaque,
                                          JxlPixelFormat* pixel_format);
  const void* (*get_color_channel_data_at)(void* opaque, size_t xpos,
                                           size_t ypos, size_t xsize,
                                           size_t ysize, size_t* row_offset);
  void (*get_extra_channel_pixel_format)(void* opaque, size_t ec_index,
                                         JxlPixelFormat* pixel_format);
  const void* (*get_extra_channel_data_at)(void* opaque, size_t ec_index,
                                           size_t xpos, size_t ypos,
                                           size_t xsize, size_t ysize,
                                           size_t* row_offset);
  void (*release_buffer)(void* opaque, const void* buf);
} JxlChunkedFrameInputSource;

typedef enum {
  JXL_ENC_FRAME_SETTING_EFFORT = 0,
  JXL_ENC_FRAME_SETTING_DECODING_SPEED = 1
} JxlEncoderFrameSettingId;

// 0.10.2：输出处理器（push 模型，与 ProcessOutput 互斥）
typedef struct JxlEncoderOutputProcessor {
  void* opaque;
  void* (*get_buffer)(void* opaque, size_t* size);
  void (*release_buffer)(void* opaque, size_t written_bytes);
  void (*seek)(void* opaque, uint64_t position);
  void (*set_finalized_position)(void* opaque, uint64_t finalized_position);
} JxlEncoderOutputProcessor;

// ============ 动态链接函数指针 ============
struct JxlApi {
  void* lib = nullptr;
  void* threadsLib = nullptr;

  uint32_t (*version)() = nullptr;
  JxlEncoder* (*create)(const JxlMemoryManager*) = nullptr;
  void (*destroy)(JxlEncoder*) = nullptr;
  void (*initBasicInfo)(JxlBasicInfo*) = nullptr;
  JxlEncoderStatus (*setBasicInfo)(JxlEncoder*, const JxlBasicInfo*) = nullptr;
  JxlEncoderStatus (*setParallelRunner)(JxlEncoder*, JxlParallelRunner,
                                        void*) = nullptr;
  JxlEncoderFrameSettings* (*frameSettingsCreate)(JxlEncoder*,
                                                  const JxlEncoderFrameSettings*) = nullptr;
  JxlEncoderStatus (*setOption)(JxlEncoderFrameSettings*,
                                JxlEncoderFrameSettingId, int64_t) = nullptr;
  JxlEncoderStatus (*setFrameLossless)(JxlEncoderFrameSettings*, JXL_BOOL) = nullptr;
  JxlEncoderStatus (*setFrameDistance)(JxlEncoderFrameSettings*, float) = nullptr;
  float (*distanceFromQuality)(float) = nullptr;
  JxlEncoderStatus (*addChunkedFrame)(const JxlEncoderFrameSettings*, JXL_BOOL,
                                      struct JxlChunkedFrameInputSource) = nullptr;
  void (*closeInput)(JxlEncoder*) = nullptr;
  JxlEncoderStatus (*processOutput)(JxlEncoder*, uint8_t**, size_t*) = nullptr;
  JxlEncoderStatus (*setOutputProcessor)(JxlEncoder*,
                                         struct JxlEncoderOutputProcessor) = nullptr;
  JxlEncoderStatus (*flushInput)(JxlEncoder*) = nullptr;
  int (*getError)(JxlEncoder*) = nullptr;

  // libjxl_threads
  void* (*resizableRunnerCreate)(const JxlMemoryManager*) = nullptr;
  void (*resizableRunnerSetThreads)(void* runner, size_t num_threads) = nullptr;
  JxlParallelRetCode (*resizableRunner)(void* runner_opaque, void* jpegxl_opaque,
                                        JxlParallelRunInit init,
                                        JxlParallelRunFunction func,
                                        size_t start_range, size_t end_range) = nullptr;
  void (*resizableRunnerDestroy)(void* runner) = nullptr;
  size_t (*resizableRunnerSuggestThreads)(uint64_t xsize, uint64_t ysize) = nullptr;

  bool resolve(void* libIn, void* threadsLibIn) {
    lib = libIn;
    threadsLib = threadsLibIn;
#define LOAD(sym, ptr)                                                     \
  do {                                                                     \
    ptr = reinterpret_cast<decltype(ptr)>(dlsym(lib, #sym));               \
    if (!ptr) return false;                                                \
  } while (0)
    LOAD(JxlEncoderVersion, version);
    LOAD(JxlEncoderCreate, create);
    LOAD(JxlEncoderDestroy, destroy);
    LOAD(JxlEncoderInitBasicInfo, initBasicInfo);
    LOAD(JxlEncoderSetBasicInfo, setBasicInfo);
    LOAD(JxlEncoderSetParallelRunner, setParallelRunner);
    LOAD(JxlEncoderFrameSettingsCreate, frameSettingsCreate);
    LOAD(JxlEncoderFrameSettingsSetOption, setOption);
    LOAD(JxlEncoderSetFrameLossless, setFrameLossless);
    LOAD(JxlEncoderSetFrameDistance, setFrameDistance);
    LOAD(JxlEncoderDistanceFromQuality, distanceFromQuality);
    LOAD(JxlEncoderAddChunkedFrame, addChunkedFrame);
    LOAD(JxlEncoderCloseInput, closeInput);
    LOAD(JxlEncoderProcessOutput, processOutput);
    LOAD(JxlEncoderSetOutputProcessor, setOutputProcessor);
    LOAD(JxlEncoderFlushInput, flushInput);
    LOAD(JxlEncoderGetError, getError);
#undef LOAD
    if (threadsLib) {
      resizableRunnerCreate = reinterpret_cast<decltype(resizableRunnerCreate)>(
          dlsym(threadsLib, "JxlResizableParallelRunnerCreate"));
      resizableRunnerSetThreads = reinterpret_cast<decltype(resizableRunnerSetThreads)>(
          dlsym(threadsLib, "JxlResizableParallelRunnerSetThreads"));
      resizableRunner = reinterpret_cast<decltype(resizableRunner)>(
          dlsym(threadsLib, "JxlResizableParallelRunner"));
      resizableRunnerDestroy = reinterpret_cast<decltype(resizableRunnerDestroy)>(
          dlsym(threadsLib, "JxlResizableParallelRunnerDestroy"));
      resizableRunnerSuggestThreads =
          reinterpret_cast<decltype(resizableRunnerSuggestThreads)>(
              dlsym(threadsLib, "JxlResizableParallelRunnerSuggestThreads"));
    }
    return true;
  }
};

static JxlApi g_api;

// ============ 编码器上下文 ============
struct StreamEncoder {
  JxlEncoder* enc = nullptr;
  JxlEncoderFrameSettings* fs = nullptr;
  void* runner = nullptr;
  uint32_t width = 0;
  uint32_t height = 0;
  size_t rowBytes = 0;  // width * 3

  std::mutex mu;
  std::condition_variable cv;
  // 行池：unique_ptr 便于已消费行及时释放内存（流式关键）
  std::vector<std::unique_ptr<std::vector<uint8_t>>> rows;
  // 回收水位：getColorDataAt 观察到 ypos 前移后，释放落后窗口的行
  size_t clearUpToY = 0;
  bool inputFinished = false;
  bool started = false;
  bool failed = false;
  std::string error;

  // 输出队列（release_buffer 时追加；nativeDrain 拉取）
  std::vector<uint8_t> outQueue;
  size_t outQueuePos = 0;
  uint8_t* activeBuf = nullptr;

  // 回收：释放 y < [ypos - WINDOW] 的行（WINDOW 覆盖 group 高 + 并行安全余量）
  void recycleUpTo(size_t ypos) {
    const size_t kWindow = 256;
    if (ypos <= kWindow || ypos - kWindow <= clearUpToY) return;
    size_t target = ypos - kWindow;
    for (size_t y = clearUpToY; y < target && y < rows.size(); ++y) {
      rows[y].reset();  // 释放行数据内存
    }
    clearUpToY = target;
  }
};

// ============ 输出处理器回调 ============
static void* outGetBuffer(void* opaque, size_t* size) {
  auto* se = static_cast<StreamEncoder*>(opaque);
  size_t sz = (*size < 4096) ? 4096 : *size;
  auto* buf = new uint8_t[sz];
  se->activeBuf = buf;
  *size = sz;
  return buf;
}

static void outReleaseBuffer(void* opaque, size_t written_bytes) {
  auto* se = static_cast<StreamEncoder*>(opaque);
  if (se->activeBuf) {
    if (written_bytes > 0) {
      std::lock_guard<std::mutex> lk(se->mu);
      se->outQueue.insert(se->outQueue.end(), se->activeBuf,
                          se->activeBuf + written_bytes);
    }
    delete[] se->activeBuf;
    se->activeBuf = nullptr;
  }
}

static void outSeek(void* opaque, uint64_t position) {
  (void)opaque; (void)position;
}

static void outSetFinalizedPos(void* opaque, uint64_t pos) {
  (void)opaque; (void)pos;
}

// ============ chunked 输入回调 ============
static void getColorPixelFormat(void* opaque, JxlPixelFormat* pf) {
  pf->num_channels = 3;
  pf->data_type = JXL_TYPE_UINT8;
  pf->endianness = JXL_NATIVE_ENDIAN;
  pf->align = 0;
}

static const void* getColorDataAt(void* opaque, size_t xpos, size_t ypos,
                                  size_t xsize, size_t ysize,
                                  size_t* row_offset) {
  auto* se = static_cast<StreamEncoder*>(opaque);
  {
    std::unique_lock<std::mutex> lk(se->mu);
    // 精确等待所需各行全部就绪（支持乱序喂行）
    auto rowsReady = [&] {
      for (size_t y = ypos; y < ypos + ysize; ++y) {
        if (y >= se->rows.size() || !se->rows[y]) return false;
      }
      return true;
    };
    se->cv.wait(lk, [&] { return se->failed || rowsReady(); });
    if (se->failed) return nullptr;
  }
  // 组装连续矩形（行距 = xsize*3）
  auto* buf = new uint8_t[xsize * ysize * 3];
  {
    std::lock_guard<std::mutex> lk(se->mu);
    for (size_t yy = 0; yy < ysize; ++yy) {
      const auto& row = *se->rows[ypos + yy];
      const uint8_t* src = row.data() + xpos * 3;
      memcpy(buf + yy * xsize * 3, src, xsize * 3);
    }
    // 编码进度前移 → 回收已消费的行（内存峰值 = 窗口行数）
    se->recycleUpTo(ypos + ysize);
  }
  *row_offset = xsize * 3;
  return buf;
}

static void getExtraPixelFormat(void* opaque, size_t ec_index,
                                JxlPixelFormat* pf) {
  (void)opaque; (void)ec_index; (void)pf;
}

static const void* getExtraDataAt(void* opaque, size_t ec_index, size_t xpos,
                                  size_t ypos, size_t xsize, size_t ysize,
                                  size_t* row_offset) {
  (void)opaque; (void)ec_index; (void)xpos; (void)ypos; (void)xsize;
  (void)ysize; (void)row_offset;
  return nullptr;
}

static void releaseBuffer(void* opaque, const void* buf) {
  (void)opaque;
  delete[] static_cast<const uint8_t*>(buf);
}

static JxlChunkedFrameInputSource makeInputSource(StreamEncoder* se) {
  JxlChunkedFrameInputSource src{};
  src.opaque = se;
  src.get_color_channels_pixel_format = getColorPixelFormat;
  src.get_color_channel_data_at = getColorDataAt;
  src.get_extra_channel_pixel_format = getExtraPixelFormat;
  src.get_extra_channel_data_at = getExtraDataAt;
  src.release_buffer = releaseBuffer;
  return src;
}

// ============ JNI ============
static JavaVM* g_vm = nullptr;

static StreamEncoder* getCtx(JNIEnv* env, jlong handle) {
  if (!handle) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                  "jxl encoder handle is null");
    return nullptr;
  }
  return reinterpret_cast<StreamEncoder*>(static_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeVersion(JNIEnv*, jclass) {
  if (!g_api.lib) {
    g_api.lib = dlopen("libjxl.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_api.lib) {
      LOGE("dlopen libjxl.so failed: %s", dlerror());
      return -1;
    }
    g_api.threadsLib = dlopen("libjxl_threads.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_api.resolve(g_api.lib, g_api.threadsLib)) {
      LOGE("resolve jxl symbols failed: %s", dlerror());
      return -2;
    }
    LOGI("libjxl loaded, version=%u", g_api.version());
  }
  return static_cast<jint>(g_api.version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeCreate(
    JNIEnv* env, jclass, jint width, jint height, jint quality,
    jboolean lossless) {
  if (!g_api.lib) {
    if (Java_io_reascale_app_core_encode_JxlStreamWriter_nativeVersion(
            env, nullptr) < 0) {
      env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                    "libjxl.so 加载失败");
      return 0;
    }
  }
  auto* se = new StreamEncoder();
  se->width = static_cast<uint32_t>(width);
  se->height = static_cast<uint32_t>(height);
  se->rowBytes = static_cast<size_t>(width) * 3;
  se->rows.resize(static_cast<size_t>(height));

  se->enc = g_api.create(nullptr);
  if (!se->enc) { se->failed = true; se->error = "JxlEncoderCreate failed"; return 0; }

  JxlBasicInfo info;
  g_api.initBasicInfo(&info);
  info.xsize = se->width;
  info.ysize = se->height;
  info.bits_per_sample = 8;
  info.exponent_bits_per_sample = 0;
  info.num_color_channels = 3;
  info.num_extra_channels = 0;
  info.orientation = JXL_ORIENT_IDENTITY;
  // 原色域（sRGB）直接编码：避免 XYB 色彩空间转换，
  // 保证 lossless 精确无损、lossy 颜色与 jxl-coder 行为一致
  info.uses_original_profile = JXL_TRUE;
  if (g_api.setBasicInfo(se->enc, &info) != JXL_ENC_SUCCESS) {
    se->failed = true; se->error = "JxlEncoderSetBasicInfo failed";
    g_api.destroy(se->enc); se->enc = nullptr; return 0;
  }

  // 并行 runner（libjxl_threads.so）
  if (g_api.resizableRunnerCreate && g_api.resizableRunner &&
      g_api.resizableRunnerSetThreads) {
    se->runner = g_api.resizableRunnerCreate(nullptr);
    if (se->runner) {
      size_t threads = g_api.resizableRunnerSuggestThreads(
          static_cast<uint64_t>(width), static_cast<uint64_t>(height));
      if (threads < 1) threads = 1;
      if (threads > 4) threads = 4;
      g_api.resizableRunnerSetThreads(se->runner, threads);
      g_api.setParallelRunner(se->enc, g_api.resizableRunner, se->runner);
    }
  }

  se->fs = g_api.frameSettingsCreate(se->enc, nullptr);
  if (!se->fs) { se->failed = true; se->error = "frameSettingsCreate failed"; return 0; }

  int effort = 7;  // squirrel，与 jxl-coder 相近画质档
  g_api.setOption(se->fs, JXL_ENC_FRAME_SETTING_EFFORT, effort);
  if (lossless == JXL_TRUE) {
    g_api.setFrameLossless(se->fs, JXL_TRUE);
  } else {
    float q = static_cast<float>(quality);
    float d = g_api.distanceFromQuality(q);
    g_api.setFrameDistance(se->fs, d);
  }

  // 输出处理器（streaming 输出；与 ProcessOutput 互斥）
  JxlEncoderOutputProcessor outProc{};
  outProc.opaque = se;
  outProc.get_buffer = outGetBuffer;
  outProc.release_buffer = outReleaseBuffer;
  outProc.seek = outSeek;
  outProc.set_finalized_position = outSetFinalizedPos;
  if (g_api.setOutputProcessor(se->enc, outProc) != JXL_ENC_SUCCESS) {
    se->failed = true; se->error = "setOutputProcessor failed";
    return 0;
  }
  return static_cast<jlong>(reinterpret_cast<intptr_t>(se));
}

extern "C" JNIEXPORT void JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeFeedRow(
    JNIEnv* env, jclass, jlong handle, jint y, jbyteArray row) {
  auto* se = getCtx(env, handle);
  if (!se) return;
  jsize len = env->GetArrayLength(row);
  if (static_cast<size_t>(len) != se->rowBytes) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                  "row length mismatch");
    return;
  }
  jbyte* raw = env->GetByteArrayElements(row, nullptr);
  if (!raw) return;
  {
    std::lock_guard<std::mutex> lk(se->mu);
    if (y < 0 || static_cast<size_t>(y) >= se->rows.size()) {
      env->ReleaseByteArrayElements(row, raw, JNI_ABORT);
      env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                    "row index out of range");
      return;
    }
    auto& slot = se->rows[static_cast<size_t>(y)];
    if (!slot) slot = std::make_unique<std::vector<uint8_t>>(se->rowBytes);
    memcpy(slot->data(), raw, se->rowBytes);
  }
  env->ReleaseByteArrayElements(row, raw, JNI_ABORT);
  se->cv.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeFeedRowAt(
    JNIEnv* env, jclass, jlong handle, jint y, jint xOffset, jbyteArray row) {
  auto* se = getCtx(env, handle);
  if (!se) return;
  jsize len = env->GetArrayLength(row);
  size_t off = static_cast<size_t>(xOffset) * 3;
  if (off + static_cast<size_t>(len) > se->rowBytes) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                  "row exceeds width");
    return;
  }
  jbyte* raw = env->GetByteArrayElements(row, nullptr);
  if (!raw) return;
  {
    std::lock_guard<std::mutex> lk(se->mu);
    if (y < 0 || static_cast<size_t>(y) >= se->rows.size()) {
      env->ReleaseByteArrayElements(row, raw, JNI_ABORT);
      env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                    "row index out of range");
      return;
    }
    auto& slot = se->rows[static_cast<size_t>(y)];
    if (!slot) slot = std::make_unique<std::vector<uint8_t>>(se->rowBytes);
    memcpy(slot->data() + off, raw, static_cast<size_t>(len));
  }
  env->ReleaseByteArrayElements(row, raw, JNI_ABORT);
  se->cv.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeFinishInput(
    JNIEnv* env, jclass, jlong handle) {
  auto* se = getCtx(env, handle);
  if (!se) return;
  {
    std::lock_guard<std::mutex> lk(se->mu);
    se->inputFinished = true;
  }
  se->cv.notify_all();
}

extern "C" JNIEXPORT jint JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeStart(
    JNIEnv* env, jclass, jlong handle) {
  auto* se = getCtx(env, handle);
  if (!se) return -1;
  {
    std::lock_guard<std::mutex> lk(se->mu);
    if (se->started) return 0;
    se->started = true;
  }
  auto src = makeInputSource(se);
  LOGI("AddChunkedFrame start w=%u h=%u (streaming via SetOutputProcessor)", se->width, se->height);
  JxlEncoderStatus s = g_api.addChunkedFrame(se->fs, JXL_TRUE, src);
  LOGI("AddChunkedFrame done status=%d outQueue=%zu", (int)s, se->outQueue.size());
  if (s != JXL_ENC_SUCCESS) {
    se->failed = true;
    se->error = "AddChunkedFrame failed err=" + std::to_string(g_api.getError(se->enc));
    LOGE("AddChunkedFrame failed err=%d", g_api.getError(se->enc));
    return -1;
  }
  // 保险：AddChunkedFrame 内部已 FlushInput；大帧可能需多轮 flush 才产出全部输出
  for (int i = 0; i < 8; i++) {
    JxlEncoderStatus fs2 = g_api.flushInput(se->enc);
    LOGI("extra FlushInput #%d status=%d outQueue=%zu", i, (int)fs2, se->outQueue.size());
    if (fs2 != JXL_ENC_SUCCESS) break;
  }
  return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeDrain(
    JNIEnv* env, jclass, jlong handle, jbyteArray out, jint cap) {
  auto* se = getCtx(env, handle);
  if (!se) return -1;
  if (se->failed) return -2;
  jsize bufLen = env->GetArrayLength(out);
  if (bufLen < cap) cap = bufLen;
  if (cap <= 0) return 0;

  jbyte* raw = env->GetByteArrayElements(out, nullptr);
  if (!raw) return -1;

  int copied = 0;
  {
    std::lock_guard<std::mutex> lk(se->mu);
    size_t avail = se->outQueue.size() - se->outQueuePos;
    size_t n = (avail < static_cast<size_t>(cap)) ? avail : static_cast<size_t>(cap);
    if (n > 0) {
      memcpy(raw, se->outQueue.data() + se->outQueuePos, n);
      se->outQueuePos += n;
      copied = static_cast<int>(n);
    }
  }
  env->ReleaseByteArrayElements(out, raw, copied > 0 ? 0 : JNI_ABORT);
  LOGI("drain copied=%d queuedLeft=%zu", copied,
       se->outQueue.size() > se->outQueuePos ? se->outQueue.size() - se->outQueuePos : 0);
  return copied;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeFlushExtra(
    JNIEnv* env, jclass, jlong handle) {
  auto* se = getCtx(env, handle);
  if (!se) return -1;
  if (se->failed) return -2;
  int last = 0;
  for (int i = 0; i < 8; i++) {
    JxlEncoderStatus s = g_api.flushInput(se->enc);
    if (s == JXL_ENC_ERROR) { se->failed = true; return -2; }
    last = (int)s;
  }
  return last;
}

extern "C" JNIEXPORT void JNICALL
Java_io_reascale_app_core_encode_JxlStreamWriter_nativeDestroy(
    JNIEnv* env, jclass, jlong handle) {
  auto* se = reinterpret_cast<StreamEncoder*>(static_cast<intptr_t>(handle));
  if (!se) return;
  if (se->enc) g_api.destroy(se->enc);
  if (se->runner && g_api.resizableRunnerDestroy) {
    g_api.resizableRunnerDestroy(se->runner);
  }
  delete se;
}