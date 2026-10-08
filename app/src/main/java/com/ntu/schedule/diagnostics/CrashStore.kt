package com.ntu.schedule.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃报告的落盘位置：`<app files>/crash/crash_<时间>.txt`。
 *
 * 放在私有目录、不用外部存储：**报告里可能还残留一些上下文**，写进 /sdcard 会让别的
 * App 读到；而且 Android 10 之后写外部存储还要额外权限和 MediaStore 那套东西。
 * 私有目录 + FileProvider 授权分享，能分享但不外泄，这才是对的组合。
 */
class CrashStore(private val context: Context) {

    val dir: File get() = File(context.filesDir, DIR_NAME)

    /**
     * 写一份报告。返回写成功的文件；任何一步失败都返回 null。
     *
     * **刻意吞掉所有异常**：这个方法是在「App 已经要死了」的路径上被调用的，
     * 它再抛一次只会把原始崩溃信息盖掉，让报告变成「写报告时又崩了」。
     */
    fun save(text: String, atMillis: Long): File? = runCatching {
        val d = dir
        if (!d.exists() && !d.mkdirs()) return null
        val target = File(d, fileName(atMillis))
        // 先写临时文件再改名：用户很可能在崩溃后立刻长按电源键重启，
        // 半截文件会被下次的列表读到并当成一份「报告」。
        val tmp = File(d, target.name + ".tmp")
        tmp.writeText(text)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        prune()
        target
    }.getOrNull()

    /** 报告列表，**新 → 旧**（按文件修改时间）。 */
    fun list(): List<File> = runCatching {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
        (files ?: emptyArray()).sortedByDescending { it.lastModified() }
    }.getOrDefault(emptyList())

    fun count(): Int = list().size

    fun read(file: File): String? = runCatching { file.readText() }.getOrNull()

    fun newest(): File? = list().firstOrNull()

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    fun deleteAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /** 只留最近 [MAX_FILES] 份。用户可能从来不看，不能无限长下去把手机塞满。 */
    private fun prune() {
        runCatching {
            val list = list()
            if (list.size > MAX_FILES) list.drop(MAX_FILES).forEach { it.delete() }
        }
    }

    private fun fileName(atMillis: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return PREFIX + fmt.format(Date(atMillis)) + SUFFIX
    }

    companion object {
        const val DIR_NAME = "crash"
        const val PREFIX = "crash_"
        const val SUFFIX = ".txt"

        /** 最多留几份。10 份足够覆盖「连着崩了好几次」的情况。 */
        const val MAX_FILES = 10
    }
}
