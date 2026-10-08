package com.ntu.schedule.data

import android.content.Context
import com.ntu.schedule.core.ReminderSettings
import java.io.File

/**
 * 上课提醒设置的持久化（目前只有「提前多久」一项）。
 *
 * 单独一个文件而不是塞进 `appearance.json`：提醒和外观是两件互不相干的事，
 * 混在一起会让「清掉背景」顺手把提醒设置也带走。
 */
class ReminderStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun load(): ReminderSettings = runCatching {
        ReminderSettings.fromJson(if (file.exists()) file.readText(Charsets.UTF_8) else null)
    }.getOrDefault(ReminderSettings.DEFAULT)

    fun save(settings: ReminderSettings) {
        runCatching {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(ReminderSettings.toJson(settings), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(ReminderSettings.toJson(settings), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "reminder.json"
    }
}
