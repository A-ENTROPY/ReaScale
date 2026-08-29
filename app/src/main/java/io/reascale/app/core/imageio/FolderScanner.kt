package io.reascale.app.core.imageio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * [BATCH-FIX 2026-08-29] 文件夹扫描——SAF tree 一次性授权 + DocumentFile 遍历。
 *
 * 背景：第三方文件管理器/批量选图产生长时后台窗口 → MIUI/ZUI SmartPower 杀进程。
 * 本方案：系统 DocumentsUI 只弹出一次（几秒的短窗口，且有 KeepAlive 保活），
 * 授权持久化后，扫描/批量处理全程在 app 内进行（零后台窗口）。
 */
object FolderScanner {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "jxl", "bmp", "gif", "tif", "tiff")
    private const val MAX_FILES = 50000

    /**
     * 递归扫描 tree 下的所有图片文件（DocumentFile）
     * @return 图片文件 Uri 列表
     */
    fun scanTree(context: Context, treeUri: Uri): List<Uri> {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<Uri>()
        var count = 0
        collectImages(root, out)
        count = out.size
        android.util.Log.i("FolderScanner", "scanTree: ${out.size} images")
        return out
    }

    private fun collectImages(dir: androidx.documentfile.provider.DocumentFile, out: MutableList<Uri>) {
        if (out.size >= MAX_FILES) return
        for (doc in dir.listFiles()) {
            if (out.size >= MAX_FILES) return
            try {
                if (doc.isDirectory) {
                    collectImages(doc, out)
                } else if (doc.isFile && doc.name?.substringAfterLast('.', "")?.lowercase() in IMAGE_EXT) {
                    out.add(doc.uri)
                }
            } catch (t: Throwable) {
                // 单个文件异常不影响整体
            }
        }
    }

    /** 从持久化授权 Uri 重取 tree（重启后可用） */
    fun fromPersisted(context: Context, treeUri: Uri): List<Uri> =
        runCatching { scanTree(context, treeUri) }.getOrElse { emptyList() }

    /** 提取 tree 显示名（保存用） */
    fun treeDisplayName(context: Context, treeUri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val name = DocumentsContract.getDocumentId(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        )
        name.substringAfterLast(':').ifBlank { null }
    }.getOrNull()
}