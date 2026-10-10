package com.ntu.schedule.data

import android.content.Context
import com.ntu.schedule.core.JsonValue
import com.ntu.schedule.core.SeasonMode
import java.io.File

/**
 * 「冬令时 / 夏令时」手选档位的持久化。
 *
 * 单独一个文件（`season.json`），不塞进 `schedule.json` 也不塞进外观设置：
 * 这是**作息**设置，和课表数据、界面外观都无关。混进课表文件的话，重新导入时会被
 * 一起冲掉；混进外观设置的话，「恢复默认背景」之类的操作会顺手把它带走 ——
 * 而它一旦被重置回 [SeasonMode.AUTO]，下午的课就会静悄悄地错半小时。
 *
 * 只存一个枚举名，所以读写都用最省事的方式，坏文件一律退回 [SeasonMode.AUTO]。
 */
class SeasonStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** 没设置过、或者文件坏了、或者枚举名不认识（降级安装）→ 都当自动档。 */
    fun load(): SeasonMode {
        val f = file
        if (!f.exists()) return SeasonMode.AUTO
        return runCatching {
            val root = JsonValue.parse(f.readText(Charsets.UTF_8))
            if (root is JsonValue.Null) return SeasonMode.AUTO
            val name = root.strOf(FIELD)
            SeasonMode.values().firstOrNull { it.name == name } ?: SeasonMode.AUTO
        }.getOrDefault(SeasonMode.AUTO)
    }

    fun save(mode: SeasonMode) {
        runCatching {
            val json = "{\"$FIELD\":\"${mode.name}\"}"
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "season.json"
        const val FIELD = "season"
    }
}
