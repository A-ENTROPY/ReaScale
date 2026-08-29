package io.reascale.app.core.imageio

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * [BATCH-FIX 2026-08-29] 文件夹扫描——绕开系统选择器被杀问题。
 *
 * 背景：第三方文件管理器/SAF 选图产生长时后台窗口 → MIUI/ZUI SmartPower 杀进程。
 * 本方案：app 内直接扫描指定文件夹（MediaStore 查询），全程前台零后台窗口。
 *
 * 权限：READ_MEDIA_IMAGES（Android 13+）/ READ_EXTERNAL_STORAGE（≤12）已在清单。
 * MediaStore 共享媒体 Uri 凭权限即可读，无需持久授权（重启后依然可用）。
 */
object FolderScanner {

    data class ScanResult(val uris: List<Uri>, val scanned: Int, val skipped: Int)

    /**
     * 扫描文件夹内的图片（递归子目录）。
     * @param folderPath 绝对路径，如 /storage/emulated/0/DCIM/Camera；
     *                   也可传相对路径如 DCIM/Camera
     * @param limit 上限（0 = 不限）
     */
    fun scanImages(context: Context, folderPath: String, limit: Int = 0): ScanResult {
        val rel = normalizeToRelative(folderPath)
        val uris = mutableListOf<Uri>()
        var scanned = 0

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = StringBuilder("( ")
        val args = mutableListOf<String>()

        // Android 10+ 有 RELATIVE_PATH（相对 /storage/emulated/0/）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection.append("${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
            args.add("$rel%")
        }
        // DATA 路径匹配（旧系统 / 部分厂商仍可用）
        selection.append(" OR ${MediaStore.Images.Media.DATA} LIKE ? )")
        args.add("%/storage/emulated/0/$rel%" )

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection.toString(),
                args.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext()) {
                    scanned++
                    uris.add(
                        Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol).toString()
                        )
                    )
                    if (limit > 0 && uris.size >= limit) break
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("FolderScanner", "scan failed: $folderPath", t)
        }

        return ScanResult(uris, scanned, 0)
    }

    /** 绝对路径 → MediaStore 相对路径（去掉 /storage/emulated/0/ 等前缀） */
    private fun normalizeToRelative(path: String): String {
        var p = path.trim().trimEnd('/')
        // 移除常见根前缀
        for (prefix in listOf(
            "/storage/emulated/0/", "/storage/emulated/0",
            "/sdcard/", "/sdcard",
            "/storage/self/primary/", "/storage/self/primary"
        )) {
            if (p == prefix.trimEnd('/')) return ""
            if (p.startsWith(prefix)) {
                p = p.removePrefix(prefix)
                break
            }
        }
        return p
    }
}