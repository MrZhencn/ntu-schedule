package com.ntu.schedule.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.ntu.schedule.core.Appearance
import java.io.File

/**
 * 外观设置的持久化。
 *
 * 图片**复制进 App 私有目录**，而不是记住相册的 `content://` URI：URI 的读取权限
 * 要么是临时的（重启就失效），要么要 `takePersistableUriPermission` 且用户随时能在
 * 相册里把原图删掉 —— 那时背景就变成一片黑。复制一份几十 KB 到几百 KB 的图，
 * 换来的是「设了就一定在」。
 */
class AppearanceStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun load(): Appearance = runCatching {
        Appearance.fromJson(if (file.exists()) file.readText(Charsets.UTF_8) else null)
    }.getOrDefault(Appearance.DEFAULT)

    fun save(appearance: Appearance) {
        runCatching {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(Appearance.toJson(appearance), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(Appearance.toJson(appearance), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    fun imageFile(name: String): File = File(context.filesDir, name)

    /** 背景图是否真的还在磁盘上。文件被清掉时应当退回默认背景，而不是画一片黑。 */
    fun imageExists(name: String?): Boolean =
        !name.isNullOrBlank() && imageFile(name).let { it.isFile && it.length() > 0 }

    /** 背景图是不是一张**能解码**的图。文件在、但内容是 HEIC 或云盘占位时为 false。 */
    fun imageDecodable(name: String?): Boolean {
        if (!imageExists(name)) return false
        val file = imageFile(name ?: return false)
        return runCatching { decodable(file) }.getOrDefault(false)
    }

    /**
     * 把相册选中的图片复制进私有目录。
     *
     * @return 新文件名；失败（选的是云盘占位、文件太大、读取被拒）返回 null。
     */
    fun importImage(uri: Uri, maxBytes: Long = MAX_IMAGE_BYTES): String? = runCatching {
        val name = "background_${System.currentTimeMillis()}.jpg"
        val target = imageFile(name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        // 超大图（比如 50MP 原图）先拒绝，免得每次都 OOM
                        output.flush()
                        target.delete()
                        return@runCatching null
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: return@runCatching null
        // 复制完立刻验一次「这真的是一张能解码的图」。
        //
        // 不验的话，选到 HEIC（部分机型默认拍照格式，API 24-27 的 BitmapFactory 不认）
        // 或云盘占位文件时，字节数看着正常、复制也成功，解码却返回 null —— 背景层那时
        // 什么都不画，整屏只剩窗口底色，用户看到的就是「设了图片却变成一片纯白」。
        if (target.length() <= 0L || !decodable(target)) {
            target.delete()
            null
        } else {
            name
        }
    }.getOrNull()

    /**
     * 只读文件头判断能不能解码，不把像素读进内存。
     *
     * `inJustDecodeBounds` 只填 `outWidth`/`outHeight`，读不了时它们保持 -1。
     */
    private fun decodable(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    fun deleteImage(name: String?) {
        if (name.isNullOrBlank()) return
        runCatching { imageFile(name).delete() }
    }

    fun clear() {
        val current = load()
        deleteImage(current.imageName)
        runCatching { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "appearance.json"

        /** 12 MB。超过这个大小的图必然要重度降采样，不如让用户先裁一下。 */
        const val MAX_IMAGE_BYTES = 12L * 1024 * 1024
    }
}
