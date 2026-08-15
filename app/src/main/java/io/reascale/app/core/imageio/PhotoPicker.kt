package io.reascale.app.core.imageio

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * PhotoPicker 封装
 * 对应 §30.7 主页 + §18.1 "选择 1-5000 张"
 *
 * - Android 13+ 使用系统级 PhotoPicker（无 READ_MEDIA_IMAGES 权限要求）
 * - Android 11-12 退回到 PickMultipleVisualMedia（系统降级为 GET_CONTENT 多选）
 * - 输出 SAF Uri 列表，永久读权限由调用方申请（takePersistableUriPermission）
 */
object PhotoPicker {

    /**
     * 在 Activity 上注册 PhotoPicker 多选
     * 返回 launcher，调用 .launch(PickVisualMediaRequest(...)) 即可
     */
    fun registerMultiPicker(
        activity: ComponentActivity,
        onResult: (List<Uri>) -> Unit
    ): androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest> {
        return activity.registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(maxItems = 500)
        ) { uris ->
            // 申请永久读权限，避免后续撤销
            uris.forEach { uri ->
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            onResult(uris)
        }
    }

    /**
     * 在 Activity 上注册 PhotoPicker 单选（用于设置页选输出目录、导入 ONNX 等）
     */
    fun registerSinglePicker(
        activity: ComponentActivity,
        onResult: (Uri?) -> Unit
    ): androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest> {
        return activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            onResult(uri)
        }
    }

    /**
     * SAF 文件夹选择（用于选择输出目录）
     * Android 11+ 用 OpenDocumentTree
     */
    fun registerDirPicker(
        activity: ComponentActivity,
        onResult: (Uri?) -> Unit
    ): androidx.activity.result.ActivityResultLauncher<Uri?> {
        return activity.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
            onResult(uri)
        }
    }

    /**
     * 通用 OpenDocument（用于导入 .onnx 文件，限制为 onnx）
     */
    fun registerOnnxPicker(
        activity: ComponentActivity,
        onResult: (Uri?) -> Unit
    ): androidx.activity.result.ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            onResult(uri)
        }
    }

    /**
     * 复制用户选择的 Uri 到 App 内部存储
     * 用于把用户导入的 .onnx 落地（§20.5）
     */
    fun copyToInternal(context: Context, src: Uri, destName: String): java.io.File {
        val dest = java.io.File(context.filesDir, "imports/${destName}").apply {
            parentFile?.mkdirs()
        }
        context.contentResolver.openInputStream(src)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }
}