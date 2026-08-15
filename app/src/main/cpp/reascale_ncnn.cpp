// ReaScale ncnn 推理实现（2026-08-15 v7 tile 分块版）
//
// 基于 nihui/realcugan-ncnn-vulkan 官方 process_cpu 实现：
// 1. xtiles/ytiles 分块
// 2. 每块 from_pixels_roi 取 ROI（从 Bitmap 像素直接取，带真实 stride）
// 3. copy_make_border(BORDER_REFLECT) 镜像 pad prepadding
// 4. 推理
// 5. to_pixels 带 stride 直接拼接到输出 bitmap
// 6. prepadding 硬拼接（pad 足够大无接缝）
//
// v6 已验证：整图推理 + 手写 BGRA 写回 → 画面完美
// v7 升级：tile 分块支持超大图，其余逻辑与 v6 相同（手写 BGRA 转换已验证正确）

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <cmath>

#include "ncnn/net.h"
#include "ncnn/gpu.h"
#include "ncnn/datareader.h"

static bool g_gpu_inited = false;

struct Session {
    ncnn::Net net;
    int scale = 2;
    int tile_size = 192;
    int prepadding = 18;
    int num_threads = 1;
    // [FIX 2026-08-16] 模型 blob 名（自动探测，兼容任意导入模型）
    std::string in_blob;
    std::string out_blob;
    // [FIX 2026-08-17] 自动探测的模型内部 crop（输入像素；0=未探测到/无 crop）
    // Real-CUGAN 等模型有结构裁剪：输出 = (输入 - crop) * scale
    // 2x=36 / 3x=28 / 4x=38；waifu2x 等无裁剪模型 crop=0
    int probed_crop = 0;
    // 由探测推导的 prepadding（= crop/2，仅探测到 crop 时有效）
    int probed_prepadding = 0;
};

static std::string jstring2str(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string s = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return s;
}

static jlong session_new(JNIEnv* env, jobject thiz, jint gpuid, jint threads, jboolean tta) {
    (void)env; (void)thiz; (void)tta;
    if (gpuid >= 0 && !g_gpu_inited) {
        ncnn::create_gpu_instance();
        g_gpu_inited = true;
    }
    Session* s = new Session();
    // [FIX 2026-08-16] 线程数可调，限制 1-4（OpenMP 多线程在部分设备可能死锁）
    s->num_threads = threads > 0 ? (threads < 5 ? threads : 4) : 1;
    __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn", "session_new gpuid=%d threads=%d", gpuid, s->num_threads);
    return reinterpret_cast<jlong>(s);
}

// [FIX 2026-08-17] 自动探测模型内部 crop 与正确 prepadding
//
// 旧实现（单尺寸 + round 反推 scale）的 bug：
//   4x Real-CUGAN 输入 128 → 输出 (128-38)*4 = 360，360/128 = 2.81 被 round 成 3，
//   crop 算成 128-360/3 = 8 → prepadding = 4（真实应为 19）
//   → 每 tile 输出比预期少 120px，写回左上角 → 黑条 + 内容错位 + 彩色边缘（用户实测 4x 全黑+彩线）
//   2x/3x 因 round 得 scale 偏小、crop 为负、pad=0 走兜底值（18/14=真实 crop/2）而碰巧正常
//
// 新实现（双尺寸差分，不依赖 round）：
//   模型输出 = (输入 - crop) * scale，crop 与输入尺寸无关（结构裁剪，恒定）
//   o1 = (T1 - crop)*scale，o2 = (T2 - crop)*scale
//   → scale = (o2-o1)/(T2-T1)（精确），crop = T1 - o1/scale
//   探测到 crop>0：prepadding = crop/2，tile 输出精确 = tile*scale，写回左上角
//   探测到 crop=0（waifu2x 等无裁剪模型）：prepadding 用兜底扩边，写回取中间区域
static void session_probe_crop(Session* s) {
    const int T1 = 128;  // 4 的倍数，兼容常见对齐要求
    const int T2 = 192;
    ncnn::Mat in1(T1, T1, 3);
    in1.fill(0.5f);  // 0.5/1 域中性灰
    ncnn::Mat in2(T2, T2, 3);
    in2.fill(0.5f);
    ncnn::Mat out1, out2;
    {
        ncnn::Extractor ex = s->net.create_extractor();
        const char* inb = s->in_blob.empty() ? "in0" : s->in_blob.c_str();
        const char* outb = s->out_blob.empty() ? "out0" : s->out_blob.c_str();
        if (ex.input(inb, in1) != 0) return;
        if (ex.extract(outb, out1) != 0) return;
    }
    {
        ncnn::Extractor ex = s->net.create_extractor();
        const char* inb = s->in_blob.empty() ? "in0" : s->in_blob.c_str();
        const char* outb = s->out_blob.empty() ? "out0" : s->out_blob.c_str();
        if (ex.input(inb, in2) != 0) return;
        if (ex.extract(outb, out2) != 0) return;
    }
    if (out1.empty() || out2.empty() || out1.w <= 0 || out2.w <= 0) return;
    if (out1.h != out1.w || out2.h != out2.w) return;  // 非方形输出，放弃探测

    const double scale_est = (double)(out2.w - out1.w) / (double)(T2 - T1);
    if (scale_est < 1.0 || scale_est > 16.0) {
        __android_log_print(ANDROID_LOG_WARN, "ReaScaleNcnn",
            "探测失败: 差分 scale=%.2f 不合理，放弃自动 prepadding", scale_est);
        return;
    }
    double crop_d = T1 - (double)out1.w / scale_est;
    int crop_i = (int)std::lround(crop_d);
    if (crop_i < 0) crop_i = 0;
    s->probed_crop = crop_i;
    s->probed_prepadding = crop_i / 2;
    __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn",
        "探测: %dx%d→%dx%d, %dx%d→%dx%d, scale≈%.2f, crop≈%dpx, prepadding=%d (当前=%d)",
        T1, T1, out1.w, out1.h, T2, T2, out2.w, out2.h,
        scale_est, crop_i, s->probed_prepadding, s->prepadding);
}

static jboolean session_load_assets(
    JNIEnv* env, jobject thiz, jlong handle,
    jobject asset_mgr, jstring param_path, jbyteArray bin_data
) {
    (void)bin_data;
    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return JNI_FALSE;
    s->net.clear();
    s->net.opt.num_threads = s->num_threads;
    s->net.opt.use_vulkan_compute = false;
    s->net.opt.use_packing_layout = false;
    s->net.opt.use_bf16_storage = false;
    s->net.opt.use_fp16_storage = false;
    s->net.opt.use_int8_storage = false;

    AAssetManager* mgr = AAssetManager_fromJava(env, asset_mgr);
    if (!mgr) return JNI_FALSE;
    std::string ppath = jstring2str(env, param_path);
    if (ppath.empty()) return JNI_FALSE;
    if (s->net.load_param(mgr, ppath.c_str()) != 0) { return JNI_FALSE; }
    std::string bpath = ppath;
    size_t dot = bpath.rfind(".param");
    if (dot != std::string::npos) bpath.replace(dot, 6, ".bin");
    else bpath += ".bin";
    if (s->net.load_model(mgr, bpath.c_str()) != 0) { return JNI_FALSE; }

    // [FIX 2026-08-16] 自动探测输入/输出 blob 名（兼容 waifu2x/Real-ESRGAN 等导入模型）
    {
        const std::vector<const char*>& in_names = s->net.input_names();
        const std::vector<const char*>& out_names = s->net.output_names();
        if (!in_names.empty()) s->in_blob = in_names[0];
        else s->in_blob = "in0";  // 兜底
        if (!out_names.empty()) s->out_blob = out_names[0];
        else s->out_blob = "out0";
        __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn",
            "模型加载成功: %s | in_blob=%s out_blob=%s",
            ppath.c_str(), s->in_blob.c_str(), s->out_blob.c_str());
    }
    session_probe_crop(s);
    return JNI_TRUE;
}

// [FIX 2026-08-16] 自动探测模型有效裁剪（crop）与正确 prepadding
// 用 64x64 全 0.5 输入推理，输出尺寸反推 crop：
//   crop = in - out / scale   （in=64, out=模型输出边长）
//   prepadding = crop / 2     （保证 (w+2p-crop)*scale = w*scale）
// 从文件系统加载模型（用户导入的 ncnn 模型：param + bin 在 filesDir）
static jboolean session_load_from_file(
    JNIEnv* env, jobject thiz, jlong handle,
    jstring param_path, jstring bin_path
) {
    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return JNI_FALSE;
    s->net.clear();
    s->net.opt.num_threads = s->num_threads;
    s->net.opt.use_vulkan_compute = false;
    s->net.opt.use_packing_layout = false;
    s->net.opt.use_bf16_storage = false;
    s->net.opt.use_fp16_storage = false;
    s->net.opt.use_int8_storage = false;

    std::string ppath = jstring2str(env, param_path);
    std::string bpath = jstring2str(env, bin_path);
    if (ppath.empty() || bpath.empty()) return JNI_FALSE;

    if (s->net.load_param(ppath.c_str()) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ReaScaleNcnn", "load_param(file:%s) failed", ppath.c_str());
        return JNI_FALSE;
    }
    if (s->net.load_model(bpath.c_str()) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ReaScaleNcnn", "load_model(file:%s) failed", bpath.c_str());
        return JNI_FALSE;
    }

    // 自动探测 blob 名
    {
        const std::vector<const char*>& in_names = s->net.input_names();
        const std::vector<const char*>& out_names = s->net.output_names();
        if (!in_names.empty()) s->in_blob = in_names[0];
        else s->in_blob = "in0";
        if (!out_names.empty()) s->out_blob = out_names[0];
        else s->out_blob = "out0";
        __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn",
            "文件模型加载成功: %s | in_blob=%s out_blob=%s",
            ppath.c_str(), s->in_blob.c_str(), s->out_blob.c_str());
    }
    session_probe_crop(s);
    return JNI_TRUE;
}

// 推理单个 tile：从原图取扩边 ROI（真实重叠）→ 边界外才镜像 pad → 推理 → 写回
// [FIX 2026-08-16 接缝根因] nihui 官方做法：
//   - tile ROI = [xi*TILE - p, (xi+1)*TILE + p] 真实相邻像素重叠（非镜像）
//   - 镜像 pad 只在图像最外边界补足
//   - 输出取 out_tile 左上 [0, tile_nopad*scale) 写回 [xi*TILE*scale, ...)
static bool process_tile(
    Session* s,
    const unsigned char* in_pixels,  // BGRA
    int src_w, int src_h, int src_stride,
    int xi, int yi, int tile_nopad,  // tile 索引 + 无 pad 尺寸
    unsigned char* out_pixels,       // BGRA
    int out_stride
) {
    const int p = s->prepadding;
    const int sc = s->scale;

    // tile 无 pad 区域（原图坐标）
    const int nx0 = xi * tile_nopad;
    const int ny0 = yi * tile_nopad;
    const int tile_w = std::min(tile_nopad, src_w - nx0);
    const int tile_h = std::min(tile_nopad, src_h - ny0);
    if (tile_w <= 0 || tile_h <= 0) return true;

    // 扩边 ROI（真实重叠像素）：[nx0-p, nx0+tile_w+p)
    const int rx0 = nx0 - p;
    const int ry0 = ny0 - p;
    const int rw = tile_w + 2 * p;
    const int rh = tile_h + 2 * p;

    // 从原图取扩边 ROI → float32 RGB [0,1]
    // 越界部分填 0（后续用镜像/复制补边界）
    ncnn::Mat tile_f32(rw, rh, 3);
    if (tile_f32.empty()) return false;
    tile_f32.fill(0.f);
    {
        const float inv255 = 1.f / 255.f;
        for (int yy = 0; yy < rh; yy++) {
            const int sy = ry0 + yy;
            if (sy < 0 || sy >= src_h) continue; // 边界外留 0，后面镜像
            const unsigned char* srow = in_pixels + (size_t)sy * src_stride;
            float* rrow = tile_f32.channel(0).row(yy);
            float* grow = tile_f32.channel(1).row(yy);
            float* brow = tile_f32.channel(2).row(yy);
            for (int xx = 0; xx < rw; xx++) {
                const int sx = rx0 + xx;
                if (sx < 0 || sx >= src_w) continue; // 边界外留 0
                rrow[xx] = (float)srow[sx * 4 + 2] * inv255; // R
                grow[xx] = (float)srow[sx * 4 + 1] * inv255; // G
                brow[xx] = (float)srow[sx * 4 + 0] * inv255; // B
            }
        }
    }

    // 边界外镜像 pad（BORDER_REFLECT 填充越界行/列）
    // 注意：ncnn copy_make_border 对内部 tile（无越界）不做任何事（0 pad 因 ROI 已扩边）
    // 这里 tile_f32 已含真实重叠区域，只需对越界边缘做镜像
    // 用 copy_make_border 的 REFLECT 处理：把越界 0 区域替换为镜像
    // 简便：对每个越界行/列手动镜像（REFLECT_101 语义：边缘像素回折）
    {
        // 左/右越界列镜像（x < 0 或 x >= src_w）
        for (int yy = 0; yy < rh; yy++) {
            const int sy = ry0 + yy;
            if (sy < 0 || sy >= src_h) continue;
            for (int xx = 0; xx < rw; xx++) {
                const int sx = rx0 + xx;
                if (sx >= 0 && sx < src_w) continue;
                // 镜像坐标
                int mx = sx;
                if (mx < 0) mx = -mx - 1;          // REFLECT_101: -1 → 0
                if (mx >= src_w) mx = 2 * src_w - mx - 1;
                mx = std::min(std::max(mx, 0), src_w - 1);
                const unsigned char* srow = in_pixels + (size_t)sy * src_stride;
                tile_f32.channel(0).row(yy)[xx] = (float)srow[mx * 4 + 2] * (1.f/255.f);
                tile_f32.channel(1).row(yy)[xx] = (float)srow[mx * 4 + 1] * (1.f/255.f);
                tile_f32.channel(2).row(yy)[xx] = (float)srow[mx * 4 + 0] * (1.f/255.f);
            }
        }
        // 上/下越界行镜像（整行复制）
        for (int yy = 0; yy < rh; yy++) {
            const int sy = ry0 + yy;
            if (sy >= 0 && sy < src_h) continue;
            int my = sy;
            if (my < 0) my = -my - 1;
            if (my >= src_h) my = 2 * src_h - my - 1;
            my = std::min(std::max(my, 0), src_h - 1);
            const unsigned char* srow = in_pixels + (size_t)my * src_stride;
            for (int xx = 0; xx < rw; xx++) {
                const int sx = rx0 + xx;
                const int msx = (sx >= 0 && sx < src_w) ? sx : std::min(std::max((sx < 0 ? -sx - 1 : 2 * src_w - sx - 1), 0), src_w - 1);
                tile_f32.channel(0).row(yy)[xx] = (float)srow[msx * 4 + 2] * (1.f/255.f);
                tile_f32.channel(1).row(yy)[xx] = (float)srow[msx * 4 + 1] * (1.f/255.f);
                tile_f32.channel(2).row(yy)[xx] = (float)srow[msx * 4 + 0] * (1.f/255.f);
            }
        }
    }

    // 推理
    ncnn::Mat tile_out;
    {
        ncnn::Extractor ex = s->net.create_extractor();
        const char* inb = s->in_blob.empty() ? "in0" : s->in_blob.c_str();
        const char* outb = s->out_blob.empty() ? "out0" : s->out_blob.c_str();
        if (ex.input(inb, tile_f32) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "ReaScaleNcnn", "tile(%d,%d) input(%s) failed", xi, yi, inb);
            return false;
        }
        if (ex.extract(outb, tile_out) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "ReaScaleNcnn", "tile(%d,%d) extract(%s) failed", xi, yi, outb);
            return false;
        }
    }
    if (tile_out.empty() || tile_out.c < 3) return false;

    // 调试：打印 tile 输出尺寸
    __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn",
        "tile@(%d,%d) roi=%dx%d pad=%d → out=%dx%dx%d",
        nx0, ny0, rw, rh, p, tile_out.w, tile_out.h, tile_out.c);

    // 输出写回：[FIX 2026-08-17] 按模型 crop 动态取源区域，兼容两类模型
    //
    // 模型输出 = (输入 - crop) * scale，crop 对称分布在两侧（结构裁剪，恒定）
    // 输入 [nx0, nx0+tile_w) 扩边后位于 [p, p+tile_w)
    // → 输出中对应区域 = [(p - crop/2)*scale, (p - crop/2 + tile_w)*scale)
    //
    // 有 crop 模型（Real-CUGAN，探测 pad = crop/2）：src_off = 0 → 取左上角（旧逻辑）
    // 无 crop 模型（waifu2x 等导入模型，探测 crop=0，pad 为兜底扩边）：
    //   src_off = pad*scale → 取中间区域，避免旧逻辑"取左上角"导致的错位
    const int crop_half = s->probed_crop / 2;
    const int src_off_x = (p - crop_half) * sc;
    const int src_off_y = (p - crop_half) * sc;
    const int out_w = tile_w * sc;
    const int out_h = tile_h * sc;
    const int dx0 = nx0 * sc;
    const int dy0 = ny0 * sc;
    const int copy_w = std::min(out_w, std::max(0, tile_out.w - src_off_x));
    const int copy_h = std::min(out_h, std::max(0, tile_out.h - src_off_y));

    for (int yy = 0; yy < copy_h; yy++) {
        const int dy = dy0 + yy;
        const int sy = yy + src_off_y;
        if (sy < 0 || sy >= tile_out.h) continue;
        const float* rrow = tile_out.channel(0).row(sy);
        const float* grow = tile_out.channel(1).row(sy);
        const float* brow = tile_out.channel(2).row(sy);
        unsigned char* drow = out_pixels + (size_t)dy * out_stride;
        for (int xx = 0; xx < copy_w; xx++) {
            const int dx = dx0 + xx;
            const int sx = xx + src_off_x;
            if (sx < 0 || sx >= tile_out.w) continue;
            drow[dx * 4 + 0] = (unsigned char)std::min(std::max((int)std::round(brow[sx] * 255.f), 0), 255);
            drow[dx * 4 + 1] = (unsigned char)std::min(std::max((int)std::round(grow[sx] * 255.f), 0), 255);
            drow[dx * 4 + 2] = (unsigned char)std::min(std::max((int)std::round(rrow[sx] * 255.f), 0), 255);
            drow[dx * 4 + 3] = 255;
        }
    }
    return true;
}

// 推理：input Bitmap → output Bitmap（tile 分块）
static jboolean session_process(
    JNIEnv* env, jobject thiz, jlong handle,
    jobject input_bmp, jobject output_bmp,
    jint scale, jint noise, jint tile_size, jint prepadding
) {
    (void)noise;
    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return JNI_FALSE;
    if (scale > 0) s->scale = scale;
    if (tile_size >= 32) s->tile_size = tile_size;
    // [FIX 2026-08-17] prepadding 选择：
    // 1. 探测到模型 crop > 0 → prepadding = crop/2（tile 输出精确 = tile*scale，写回左上角）
    // 2. 否则（无 crop 模型或探测失败）→ 用 Kotlin 传入值或经验兜底扩边，
    //    写回时按 (pad - crop/2)*scale 偏移取中间区域（见 process_tile）
    if (s->probed_prepadding > 0) {
        s->prepadding = s->probed_prepadding;
    } else if (prepadding > 0) {
        s->prepadding = prepadding;
    }
    // prepadding 兜底：2x=18, 3x=14, 4x=19（Real-CUGAN 官方值，对无 crop 模型仅作扩边）
    if (s->prepadding <= 0) {
        s->prepadding = (s->scale == 2) ? 18 : (s->scale == 3) ? 14 : (s->scale == 4) ? 19 : 18;
    }
    const int sc = s->scale;
    const int p = s->prepadding;

    AndroidBitmapInfo in_info, out_info;
    if (AndroidBitmap_getInfo(env, input_bmp, &in_info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, output_bmp, &out_info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;

    const int w = in_info.width;
    const int h = in_info.height;
    const int in_stride = in_info.stride;
    const int out_stride = out_info.stride;

    // 输出 bitmap 尺寸必须 = w*sc x h*sc
    if (out_info.width != w * sc || out_info.height != h * sc) {
        __android_log_print(ANDROID_LOG_ERROR, "ReaScaleNcnn",
            "输出尺寸不匹配: 期望 %dx%d 实得 %dx%d", w * sc, h * sc, out_info.width, out_info.height);
        return JNI_FALSE;
    }

    void* in_pixels = nullptr;
    void* out_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, input_bmp, &in_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, output_bmp, &out_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, input_bmp);
        return JNI_FALSE;
    }

    const int tile_nopad = s->tile_size;
    const int xtiles = (w + tile_nopad - 1) / tile_nopad;
    const int ytiles = (h + tile_nopad - 1) / tile_nopad;

    __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn",
        "process: in=%dx%d scale=%d tile=%d pad=%d tiles=%dx%d",
        w, h, sc, tile_nopad, p, xtiles, ytiles);

    bool ok = true;
    for (int yi = 0; yi < ytiles && ok; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            if (!process_tile(s, (const unsigned char*)in_pixels, w, h, in_stride,
                    xi, yi, tile_nopad, (unsigned char*)out_pixels, out_stride)) {
                __android_log_print(ANDROID_LOG_ERROR, "ReaScaleNcnn",
                    "tile(%d/%d,%d/%d) 失败", xi, xtiles, yi, ytiles);
                ok = false;
                break;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, input_bmp);
    AndroidBitmap_unlockPixels(env, output_bmp);

    if (!ok) return JNI_FALSE;
    __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn", "process OK: %dx%d → %dx%d (%d tiles)", w, h, w * sc, h * sc, xtiles * ytiles);
    return JNI_TRUE;
}

static void session_set_scale(JNIEnv* env, jobject thiz, jlong handle, jint scale) {
    (void)env; (void)thiz;
    Session* s = reinterpret_cast<Session*>(handle);
    if (s && scale > 0) s->scale = scale;
}
static void session_set_tile_size(JNIEnv* env, jobject thiz, jlong handle, jint tile) {
    (void)env; (void)thiz;
    Session* s = reinterpret_cast<Session*>(handle);
    if (s && tile >= 32) s->tile_size = tile;
}
static jint session_get_tile_size(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    Session* s = reinterpret_cast<Session*>(handle);
    return s ? s->tile_size : 0;
}
static void session_set_prepadding(JNIEnv* env, jobject thiz, jlong handle, jint pad) {
    (void)env; (void)thiz;
    Session* s = reinterpret_cast<Session*>(handle);
    if (s && pad >= 0) s->prepadding = pad;
}
static void session_set_num_threads(JNIEnv* env, jobject thiz, jlong handle, jint threads) {
    (void)env; (void)thiz;
    Session* s = reinterpret_cast<Session*>(handle);
    // [FIX 2026-08-16] 同时更新 net.opt，让推理实际使用该线程数
    if (s && threads > 0) {
        s->num_threads = threads < 5 ? threads : 4;
        s->net.opt.num_threads = s->num_threads;
    }
}
static void session_destroy(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    Session* s = reinterpret_cast<Session*>(handle);
    if (s) { s->net.clear(); delete s; }
}

static JNINativeMethod gMethods[] = {
    {"nativeCreate",       "(IIZ)J",                              (void*)&session_new},
    {"nativeLoadFromAssets","(JLandroid/content/res/AssetManager;Ljava/lang/String;[B)Z", (void*)&session_load_assets},
    {"nativeLoadFromFile", "(JLjava/lang/String;Ljava/lang/String;)Z",          (void*)&session_load_from_file},
    {"nativeProcess",      "(JLandroid/graphics/Bitmap;Landroid/graphics/Bitmap;IIII)Z", (void*)&session_process},
    {"nativeSetScale",     "(JI)V",                               (void*)&session_set_scale},
    {"nativeSetTileSize",  "(JI)V",                               (void*)&session_set_tile_size},
    {"nativeGetTileSize",  "(J)I",                                (void*)&session_get_tile_size},
    {"nativeSetPrepadding","(JI)V",                               (void*)&session_set_prepadding},
    {"nativeSetNumThreads","(JI)V",                               (void*)&session_set_num_threads},
    {"nativeDestroy",      "(J)V",                                (void*)&session_destroy},
};

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = env->FindClass("io/reascale/app/core/engine/ReascaleNcnn");
    if (!clazz) { return JNI_ERR; }
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) { return JNI_ERR; }
    __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn", "JNI_OnLoad OK");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    if (g_gpu_inited) { ncnn::destroy_gpu_instance(); g_gpu_inited = false; }
}