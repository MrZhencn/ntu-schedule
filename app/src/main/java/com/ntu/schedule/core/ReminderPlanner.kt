package com.ntu.schedule.core

/**
 * 一条「上课前提醒」计划：**纯数据，没有任何 Android 依赖**，所以能在 JVM 单元测试里
 * 把「提前多久、周次单双周、夏令冬令、月底跨月」这些最容易错的地方钉死。
 */
data class ReminderPlan(
    /** 上课那天的日期，ISO `2026-10-08`。 */
    val dateIso: String,
    /** 提醒触发的时刻 = 当天第几分钟（本地时间）。 */
    val atMinuteOfDay: Int,
    val course: Course,
    /** 该节次当天的上课时间（已按月份取夏令/冬令表）。 */
    val startTime: String,
    val endTime: String,
) {
    /** 触发时刻的 `HH:mm`，仅用于排查与测试断言。 */
    val atText: String
        get() = "%02d:%02d".format(atMinuteOfDay / 60, atMinuteOfDay % 60)
}

/**
 * 上课提醒的时刻计算。
 *
 * 为什么不把这件事交给「每天固定一个闹钟」：同一节课在不同月份的**上课时间不一样**
 * （10 月 1 日起第 6 节从 14:00 变成 13:30），所以「提前一小时」的具体时刻必须按
 * **那一天的月份**重新算，不能拿学期初的时间一直用下去。
 *
 * 另外周次也要参与判断：`形势与政策` 只在第 7、15 周上课，第 8 周就不该有提醒。
 */
object ReminderPlanner {

    /** 提前量：上课前 1 小时。 */
    const val LEAD_MINUTES = 60

    /** 一次排程覆盖多少天。到期后由每日闹钟滚动续排（见 ReminderScheduler）。 */
    const val DEFAULT_DAYS_AHEAD = 7

    /**
     * 排出 [fromDateIso] 起 [daysAhead] 天内的全部上课提醒。
     *
     * @param nowMinuteOfDay 传了就把「今天已经过去的时刻」过滤掉，避免刚导入课表就
     *                       补发一串「还有 1 小时上课」的过期通知。
     */
    fun plan(
        schedule: Schedule,
        fromDateIso: String,
        daysAhead: Int = DEFAULT_DAYS_AHEAD,
        nowMinuteOfDay: Int? = null,
    ): List<ReminderPlan> {
        if (schedule.courses.isEmpty()) return emptyList()
        val days = daysAhead.coerceAtLeast(1)
        val out = ArrayList<ReminderPlan>()

        for (i in 0 until days) {
            val dateIso = DateUtil.plusDays(fromDateIso, i) ?: continue
            // 不在学期内的日期（寒暑假、开学前）直接跳过
            val week = schedule.weekOfDate(dateIso) ?: continue
            val dayOfWeek = DateUtil.dayOfWeekOf(dateIso) ?: continue
            val month = DateUtil.monthOf(dateIso)

            val dayCourses = schedule.coursesOfWeek(week).filter { it.dayOfWeek == dayOfWeek }
            for (course in dayCourses) {
                val startSlot = ClassTimes.slotOf(course.startPeriod, month) ?: continue
                val endSlot = ClassTimes.slotOf(course.endPeriod, month) ?: startSlot
                val startMinute = ClassTimes.minuteOf(startSlot.start) ?: continue
                val at = startMinute - LEAD_MINUTES
                if (at < 0) continue
                if (i == 0 && nowMinuteOfDay != null && at <= nowMinuteOfDay) continue
                out += ReminderPlan(dateIso, at, course, startSlot.start, endSlot.end)
            }
        }

        return out.sortedWith(compareBy({ it.dateIso }, { it.atMinuteOfDay }, { it.course.name }))
    }

    /** 通知标题。 */
    fun title(): String = "还有 1 小时上课"

    /**
     * 通知正文（横幅收起时显示的那一行）。
     *
     * **刻意不写教师名**：横幅的第二行本来就会被系统截断，这一行的重心是「什么课、几点、在哪」。
     * 教师名放进展开后的 [bigText]，想看细节时下拉即可。
     */
    fun body(courseName: String, startTime: String, endTime: String, room: String): String {
        val where = room.ifBlank { "地点待定" }
        return buildString {
            append("《").append(courseName).append("》 ").append(startTime)
            if (endTime.isNotBlank()) append('-').append(endTime)
            append(" · ").append(where)
        }
    }

    fun body(plan: ReminderPlan): String =
        body(plan.course.name, plan.startTime, plan.endTime, plan.course.room)

    /**
     * 通知展开后（BigTextStyle）的多行内容 —— 一行一个信息块。
     * 纯函数，已有单元测试；通知里那几行字不再是「拼在 Android 代码里没人验」的字符串。
     */
    fun bigText(
        courseName: String,
        startTime: String,
        endTime: String,
        periods: String,
        room: String,
        teacher: String,
    ): String {
        val time = buildString {
            append(startTime)
            if (endTime.isNotBlank()) append(" - ").append(endTime)
            if (periods.isNotBlank()) append(" · 第 ").append(periods).append(" 节")
        }
        val where = buildString {
            append(room.ifBlank { "地点待定" })
            if (teacher.isNotBlank()) append(" · ").append(teacher)
        }
        return listOf("《$courseName》", time, where).joinToString("\n")
    }
}
