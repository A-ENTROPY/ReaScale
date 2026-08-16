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
    // [FIX 2026-08-17 v5] 模型语义探测结果
    // 所有 Real-CUGAN 模型均期望 0..1 域输入（官方 shader 语义）：
    // - 2x/3x（realcugan_postproc.comp）：输出 0..1 域完整图像 → out = bottom*255
    // - 4x（realcugan_4x_postproc.comp）：输出 0..1 域残差 → out = (in/255 + bottom)*255
    // 判定方法（in0.5 灰输入 → 输出均值）：
    //   完整图像模型：0.5 灰输入 → 输出 ≈ 0.5
    //   残差模型：0.5 灰输入 → 残差 ≈ 0（灰输入残差为 0）
    // 注意：不能靠"期望 0..255"判定——残差模型的灰输入残差≈0 会被误判（a16-a18 的教训）
    bool is_residual = false;
    // 自动探测的模型内部 crop（输入像素；0=未探测到/无 crop）
    int probed_crop = 0;
    // 由探测推导的 prepadding（= crop/2，仅探测到 crop 时有效）
    int probed_prepadding = 0;
    // [FIX 2026-08-17 v2] 模型输出尺寸公式：out = in*scale - K（0 表示未探测）
    // up4x: out = 4W-152；up2x: out = 2W-72
    int probed_out_k = 0;
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

// [FIX 2026-08-17 v2] 自动探测：输出尺寸公式 + 输入域对照实验
//
// 1) 尺寸公式（双尺寸差分，不依赖 round）：
//    模型输出 = (输入 - crop) * scale 或 = 输入*scale - K（左对齐，见 up4x param 实测）
//    o1 = f(T1), o2 = f(T2) → scale = (o2-o1)/(T2-T1) 精确；K = T1*scale - o1
//    up4x-conservative 实测: 128→500, 192→756 → scale=4, K=12 → out = 4W-12
// 2) 输入域对照实验（全黑根因排查）：
//    0..1 域灰 0.5 与 0..255 域灰 127.5 分别推理，统计输出均值：
//    - 模型期望 0..1  → 0.5 输入输出≈0.5，127.5 输入输出≈大数
//    - 模型期望 0..255 → 0.5 输入输出≈0.002（我们现在的输入 → 全黑！），127.5 输出≈0.5
static void session_probe_crop(Session* s) {
    const int T1 = 128;  // 4 的倍数，兼容常见对齐要求
    const int T2 = 192;
    ncnn::Mat in1(T1, T1, 3);
    in1.fill(0.5f);  // 0..1 域中性灰
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

    // === 模型语义探测（残差 vs 完整图像）===
    // [FIX v5] 用 in0.5 灰输入的输出均值判定：
    //   完整图像模型（2x/3x）：0.5 灰 → 输出 ≈ 0.5
    //   残差模型（4x）：0.5 灰 → 残差 ≈ 0（灰输入残差为 0）
    // 旧 v2 逻辑（in127.5 输出 vs in0.5 输出）对残差模型误判为"期望 0..255 输入" → 输入域错误 → 条纹噪点
    {
        ncnn::Mat in255(T1, T1, 3);
        in255.fill(127.5f);  // 0..255 域中性灰（对照）
        ncnn::Mat out255;
        ncnn::Extractor ex = s->net.create_extractor();
        const char* inb = s->in_blob.empty() ? "in0" : s->in_blob.c_str();
        const char* outb = s->out_blob.empty() ? "out0" : s->out_blob.c_str();
        if (ex.input(inb, in255) == 0 && ex.extract(outb, out255) == 0 && !out255.empty()) {
            auto stat = [](const ncnn::Mat& m, float& avg, float& maxv) {
                double sum = 0; float mx = 0; long long cnt = 0;
                const int chans = std::min(m.c, 3);
                for (int c = 0; c < chans; c++) {
                    const float* p = m.channel(c);
                    const long long n = (long long)m.w * m.h;
                    for (long long i = 0; i < n; i++) {
                        float v = p[i] < 0 ? -p[i] : p[i];
                        sum += v; if (v > mx) mx = v;
                    }
                    cnt += n;
                }
                avg = cnt ? (float)(sum / cnt) : 0.f;
                maxv = mx;
            };
            float a1 = 0, m1 = 0, a255 = 0, m255 = 0;
            stat(out1, a1, m1);
            stat(out255, a255, m255);
            // 残差判定：0.5 灰输入的输出均值 ≈ 0（残差）vs ≈ 0.5（完整图像）
            const bool residual = (a1 < 0.05f);
            s->is_residual = residual;
            __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn",
                "probe-semantic: in0.5→avg|v|=%.5f max=%.5f | in127.5→avg|v|=%.4f max=%.4f | 模型=%s",
                a1, m1, a255, m255, residual ? "残差(输出=输入+残差)" : "完整图像(输出=模型输出)");
        }
    }

    // === 尺寸公式探测 ===
    const double scale_est = (double)(out2.w - out1.w) / (double)(T2 - T1);
    if (scale_est < 1.0 || scale_est > 16.0) {
        __android_log_print(ANDROID_LOG_WARN, "ReaScaleNcnn",
            "探测失败: 差分 scale=%.2f 不合理，放弃自动 prepadding", scale_est);
        return;
    }
    // K = T1*scale - o1（模型输出 = 输入*scale - K，左对齐）
    double k_d = T1 * scale_est - out1.w;
    int k_i = (int)std::lround(k_d);
    if (k_i < 0) k_i = 0;
    s->probed_out_k = k_i;
    // crop 语义（对称裁剪模型）：crop = K/scale
    double crop_d = k_d / scale_est;
    int crop_i = (int)std::lround(crop_d);
    if (crop_i < 0) crop_i = 0;
    s->probed_crop = crop_i;
    s->probed_prepadding = crop_i / 2;
    __android_log_print(ANDROID_LOG_INFO, "ReaScaleNcnn",
        "probe-size: %dx%d→%dx%d, %dx%d→%dx%d, scale≈%.2f, out=in*scale-%d, crop≈%dpx, prepadding=%d",
        T1, T1, out1.w, out1.h, T2, T2, out2.w, out2.h,
        scale_est, k_i, crop_i, s->probed_prepadding);
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
    // [FIX v6] 3x 模型对齐修复：scale==1/3 时输入必须对齐 4 的倍数，scale==2/4 对齐 2
    // 依据官方 realcugan.cpp：prepadding_bottom += (tile+3)/4*4 - tile（scale 1/3）
    // 3x 模型内部含 stride-2 层，非 4 倍数输入（如 tile 90 → ROI 118）输出尺寸偏差，
    // 导致右/下边缘模糊（"没放大就拼合"）。2x/4x 仅需对齐 2，通常天然满足。
    const int rx0 = nx0 - p;
    const int ry0 = ny0 - p;
    const int rw_raw = tile_w + 2 * p;
    const int rh_raw = tile_h + 2 * p;
    const int align = (sc == 1 || sc == 3) ? 4 : 2;
    const int rw = ((rw_raw + align - 1) / align) * align;
    const int rh = ((rh_raw + align - 1) / align) * align;

    // 从原图取扩边 ROI → float32 RGB [0,1]
    // [FIX v5] 所有 Real-CUGAN 模型期望 0..1 输入（官方 shader 语义确认）
    // [FIX v6] REPLICATE 填充（官方 preproc shader 用 clamp）：越界行/列取最近有效像素；
    //   对齐扩展区（末尾多出的 <align 行/列）复制最后一行/列
    ncnn::Mat tile_f32(rw, rh, 3);
    if (tile_f32.empty()) return false;
    tile_f32.fill(0.f);
    const float in_scale = 1.f / 255.f;
    {
        for (int yy = 0; yy < rh; yy++) {
            int sy = ry0 + yy;
            if (yy >= rh_raw) sy = ry0 + rh_raw - 1;  // 对齐扩展行：复制最后有效行
            sy = std::min(std::max(sy, 0), src_h - 1); // REPLICATE
            const unsigned char* srow = in_pixels + (size_t)sy * src_stride;
            float* rrow = tile_f32.channel(0).row(yy);
            float* grow = tile_f32.channel(1).row(yy);
            float* brow = tile_f32.channel(2).row(yy);
            for (int xx = 0; xx < rw; xx++) {
                int sx = rx0 + xx;
                if (xx >= rw_raw) sx = rx0 + rw_raw - 1;  // 对齐扩展列：复制最后有效列
                sx = std::min(std::max(sx, 0), src_w - 1); // REPLICATE
                rrow[xx] = (float)srow[sx * 4 + 2] * in_scale; // R
                grow[xx] = (float)srow[sx * 4 + 1] * in_scale; // G
                brow[xx] = (float)srow[sx * 4 + 0] * in_scale; // B
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

    // 输出写回：[FIX 2026-08-17 v5] 左对齐取左上角 + 残差模型加回输入
    //
    // 官方 realcugan-ncnn-vulkan postproc shader 语义（GitHub 源码确认）：
    // - 2x/3x（realcugan_postproc.comp）：模型输出为 0..1 域完整图像
    //     out = clamp(bottom * 255)
    // - 4x（realcugan_4x_postproc.comp）：模型输出为 0..1 域残差
    //     imx = gx/scale + crop_x（输出 gx 对应原图 imx，左对齐）
    //     out = clamp((in[imx]/255 + bottom[gx]) * 255)
    // - 写回：模型输出 [0, tile*scale) 直接对应 tile 无 pad 区域（取左上角），
    //   pad 必须 = crop/2（探测保证）：tile 输出 = (T+2p-crop)*scale = T*scale 精确
    // 历史：v1 src_off=0 但探测错(crop=3)；v2 src_off=p*scale 错位；
    //       v3 改回 src_off=0 ✓；v4 输入域判定错误(0..255)+残差加回 → 条纹噪点；
    //       v5 统一 0..1 输入 + 残差按 probe-semantic 判定
    const int crop_half = s->probed_crop / 2;
    const int src_off_x = (p - crop_half) * sc;
    const int src_off_y = (p - crop_half) * sc;
    const int out_w = tile_w * sc;
    const int out_h = tile_h * sc;
    const int dx0 = nx0 * sc;
    const int dy0 = ny0 * sc;
    const int copy_w = std::min(out_w, std::max(0, tile_out.w - src_off_x));
    const int copy_h = std::min(out_h, std::max(0, tile_out.h - src_off_y));
    // 残差模型（4x）：输出 = (输入 + 残差) * 255；完整图像模型（2x/3x）：输出 = 模型输出 * 255
    const bool is_residual = s->is_residual;

    for (int yy = 0; yy < copy_h; yy++) {
        const int dy = dy0 + yy;
        const int sy = yy + src_off_y;
        if (sy < 0 || sy >= tile_out.h) continue;
        // 输出行 gy → 输入行 gy/scale + p（shader 整数除法语义）
        const int in_ty = yy / sc + p;
        if (in_ty < 0 || in_ty >= rh) continue;
        const float* rrow = tile_out.channel(0).row(sy);
        const float* grow = tile_out.channel(1).row(sy);
        const float* brow = tile_out.channel(2).row(sy);
        unsigned char* drow = out_pixels + (size_t)dy * out_stride;
        const float* in_r = is_residual ? tile_f32.channel(0).row(in_ty) : nullptr;
        const float* in_g = is_residual ? tile_f32.channel(1).row(in_ty) : nullptr;
        const float* in_b = is_residual ? tile_f32.channel(2).row(in_ty) : nullptr;
        for (int xx = 0; xx < copy_w; xx++) {
            const int dx = dx0 + xx;
            const int sx = xx + src_off_x;
            if (sx < 0 || sx >= tile_out.w) continue;
            // 输出列 gx → 输入列 gx/scale + p
            const int in_tx = xx / sc + p;
            if (in_tx < 0 || in_tx >= rw) continue;
            float r, g, b;
            if (is_residual) {
                // 官方 shader：(in/255 + bottom) * 255，in 为 0..1 域 tile_f32
                r = (in_r[in_tx] + rrow[sx]) * 255.f;
                g = (in_g[in_tx] + grow[sx]) * 255.f;
                b = (in_b[in_tx] + brow[sx]) * 255.f;
            } else {
                r = rrow[sx] * 255.f;
                g = grow[sx] * 255.f;
                b = brow[sx] * 255.f;
            }
            drow[dx * 4 + 0] = (unsigned char)std::min(std::max((int)std::round(b), 0), 255);
            drow[dx * 4 + 1] = (unsigned char)std::min(std::max((int)std::round(g), 0), 255);
            drow[dx * 4 + 2] = (unsigned char)std::min(std::max((int)std::round(r), 0), 255);
            drow[dx * 4 + 3] = 255;
        }
    }
    return true;
}

// 推理：input Bitmap → output Bitmap（tile 分块）
// [FIX 2026-08-17 v7] 增加进度回调：每完成一个 tile 回调 listener.onProgress(done/total)
// 让 Kotlin 侧可以实时平滑显示进度（不再一段段跳）
static jboolean session_process(
    JNIEnv* env, jobject thiz, jlong handle,
    jobject input_bmp, jobject output_bmp,
    jint scale, jint noise, jint tile_size, jint prepadding,
    jobject progress_listener
) {
    (void)noise;
    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return JNI_FALSE;
    if (scale > 0) s->scale = scale;
    if (tile_size >= 32) s->tile_size = tile_size;
    // [FIX 2026-08-17 v2] prepadding 选择（写回统一取中间区域，见 process_tile）：
    //   p = max(官方经验值, 探测值, Kotlin 传入值, 3)
    // - 官方经验值：Real-CUGAN 2x=18/3x=14/4x=19（nihui realcugan-ncnn-vulkan）
    // - 探测值：对导入模型推导（4W-12 类模型探测出 pad=1，被下限 3 与官方值抬升）
    // - 下限 3：写回覆盖需要 p*scale ≥ K（K=12 → p≥3），且 pad 大只增加重叠不破坏正确性
    {
        int default_pad = (s->scale == 2) ? 18 : (s->scale == 3) ? 14 : (s->scale == 4) ? 19 : 18;
        int p = default_pad;
        if (s->probed_prepadding > 0) p = std::max(p, s->probed_prepadding);
        if (prepadding > 0) p = std::max(p, prepadding);
        p = std::max(p, 3);
        s->prepadding = p;
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
        "process: in=%dx%d scale=%d tile=%d pad=%d tiles=%dx%d residual=%d probed_crop=%d",
        w, h, sc, tile_nopad, p, xtiles, ytiles, s->is_residual ? 1 : 0, s->probed_crop);

    // 进度回调（listener.onProgress(float)），每 tile 一次
    jmethodID on_progress = nullptr;
    jclass listener_cls = nullptr;
    if (progress_listener != nullptr) {
        listener_cls = env->GetObjectClass(progress_listener);
        on_progress = env->GetMethodID(listener_cls, "onProgress", "(F)V");
    }
    const int total_tiles = xtiles * ytiles;
    int done_tiles = 0;

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
            done_tiles++;
            if (on_progress != nullptr) {
                env->CallVoidMethod(progress_listener, on_progress,
                    (jfloat)done_tiles / (jfloat)total_tiles);
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
    {"nativeProcess",      "(JLandroid/graphics/Bitmap;Landroid/graphics/Bitmap;IIIILio/reascale/app/core/engine/ReascaleNcnn$NcnnProgressListener;)Z", (void*)&session_process},
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