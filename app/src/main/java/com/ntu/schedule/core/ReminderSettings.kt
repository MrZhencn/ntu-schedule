package com.ntu.schedule.core

/**
 * 上课提醒的设置。目前只有一项：**提前多久**。
 *
 * 和 [Appearance] 一样刻意做成**纯数据 + 纯函数**（不碰 `Context`、不碰闹钟），
 * 因为存档解析一旦抛异常，表现是「App 一打开就崩」——那时用户已经看不到任何提示了。
 *
 * 提前量只允许是 [ReminderPlanner.PRESET_LEAD_MINUTES] 里的那五档：这个值直接决定
 * 闹钟数量与「还有 N 分钟上课」那句话，放开成任意数字只会让界面变成输入框，
 * 收益却只有「提前 47 分钟」这种没人要的精度。
 *
 * @param leadMinutes 上课前提前多少分钟提醒。
 */
data class ReminderSettings(
    val leadMinutes: Int = ReminderPlanner.DEFAULT_LEAD_MINUTES,
) {

    /** 界面上用的说法，例如「1 小时」「30 分钟」。 */
    val leadText: String get() = ReminderPlanner.leadText(leadMinutes)

    /** 换成另一档；传了不在预设里的值就退回默认的 1 小时，绝不落一个奇怪的数进存档。 */
    fun withLead(minutes: Int): ReminderSettings =
        copy(leadMinutes = sanitize(minutes))

    companion object {
        const val SCHEMA_VERSION = 1

        val DEFAULT = ReminderSettings()

        /** 只有五档是合法的；其余一律退回默认值。 */
        fun sanitize(minutes: Int): Int =
            if (minutes in ReminderPlanner.PRESET_LEAD_MINUTES) minutes else ReminderPlanner.DEFAULT_LEAD_MINUTES

        fun fromJson(text: String?): ReminderSettings {
            if (text.isNullOrBlank()) return DEFAULT
            val root = JsonValue.parse(text)
            if (root !is JsonValue.Obj) return DEFAULT
            return ReminderSettings(leadMinutes = sanitize(root.intOf("leadMinutes", default = DEFAULT.leadMinutes)))
        }

        fun toJson(settings: ReminderSettings): String = JsonValue.obj(
            "v" to JsonValue.of(SCHEMA_VERSION),
            "leadMinutes" to JsonValue.of(sanitize(settings.leadMinutes)),
        ).toJsonString()
    }
}
