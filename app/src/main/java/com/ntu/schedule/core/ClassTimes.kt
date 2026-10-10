package com.ntu.schedule.core

/**
 * 教学作息时间表（一节课几点到几点）。
 *
 * 界面显示上课时间、详情页显示节次时间、上课提醒算「提前一小时是几点」，全都读这里。
 *
 * ## 按**日历月份**切两套，不是按学期整套切
 *
 * 数据来源：南通大学官网《教学作息时间表》
 * https://www.ntu.edu.cn/2018/0228/c763a35845/page.htm
 *
 * - 5 月–9 月用夏令时（第 6 节 14:00 开始）
 * - 10 月–4 月用冬令时（第 6 节 13:30 开始）
 *
 * 所以秋季学期会**期中切换**（9 月夏令 → 10 月冬令）。任何「按开学月份选一套表」的实现
 * 都会在 10 月之后把第 6–12 节全部算错 30 分钟，因此这里按**具体日期**取表。
 * 上午 1–5 节两套完全相同，无需区分。
 * 手动档见 [SeasonMode]。
 */
object ClassTimes {

    /** 一节课的起止时间，格式 HH:mm。 */
    data class Slot(val start: String, val end: String)

    /** 冬令时：10 月–4 月。 */
    val WINTER = listOf(
        Slot("07:50", "08:30"), // 1
        Slot("08:40", "09:20"), // 2
        Slot("09:35", "10:15"), // 3
        Slot("10:30", "11:10"), // 4
        Slot("11:20", "12:00"), // 5
        Slot("13:30", "14:10"), // 6
        Slot("14:20", "15:00"), // 7
        Slot("15:20", "16:00"), // 8
        Slot("16:10", "16:50"), // 9
        Slot("18:30", "19:10"), // 10
        Slot("19:20", "20:00"), // 11
        Slot("20:10", "20:50"), // 12
    )

    /** 夏令时：5 月–9 月。上午 1–5 节与冬令时完全相同，下午整体推后 30 分钟。 */
    val SUMMER = listOf(
        Slot("07:50", "08:30"), // 1
        Slot("08:40", "09:20"), // 2
        Slot("09:35", "10:15"), // 3
        Slot("10:30", "11:10"), // 4
        Slot("11:20", "12:00"), // 5
        Slot("14:00", "14:40"), // 6
        Slot("14:50", "15:30"), // 7
        Slot("15:50", "16:30"), // 8
        Slot("16:40", "17:20"), // 9
        Slot("19:00", "19:40"), // 10
        Slot("19:50", "20:30"), // 11
        Slot("20:40", "21:20"), // 12
    )

    /** 夏令时覆盖的月份。改这里就够了，下面的取表逻辑都读它。 */
    private val SUMMER_MONTHS = setOf(5, 6, 7, 8, 9)

    /**
     * 当前生效的作息档位。
     *
     * **做成进程级的全局状态，而不是给每个调用点加参数**：作息时间有三个互不相干的
     * 消费者 —— 界面、上课提醒（AlarmManager 触发，可能是被系统单独拉起的进程）、
     * 桌面小组件（BroadcastReceiver）。它们各自从自己的入口进来，逐个传参迟早会漏一处，
     * 而漏掉的那一处**不会报错**，只会安静地按月份自己算 —— 用户看到的就是
     * 「课表里下午的课改了，提醒还是按老时间弹」。
     *
     * 所以统一由 [com.ntu.schedule.NtuApp] 在进程启动时装好，改设置时由
     * `AppViewModel` 立刻更新。默认 [SeasonMode.AUTO]，与学校规定一致。
     *
     * `@Volatile`：读的一方可能在任意线程（闹钟触发、小组件刷新、后台重排）。
     */
    @Volatile
    var seasonMode: SeasonMode = SeasonMode.AUTO

    /** 按指定档位取整张表。 */
    fun tableFor(month: Int, mode: SeasonMode): List<Slot> = when (mode) {
        SeasonMode.WINTER -> WINTER
        SeasonMode.SUMMER -> SUMMER
        // 越界（脏数据）的月份按冬令时处理，不抛异常
        SeasonMode.AUTO -> if (month in SUMMER_MONTHS) SUMMER else WINTER
    }

    /**
     * 按**当前档位**取表。
     *
     * @param month 1–12；[SeasonMode.AUTO] 下越界（脏数据）时按冬令时处理，不抛异常
     */
    fun tableForMonth(month: Int): List<Slot> = tableFor(month, seasonMode)

    /** 取第 period 节（1-based）的时间；越界返回 null 而不是抛异常，避免脏数据导致崩溃。 */
    fun slotOf(period: Int, month: Int): Slot? = tableForMonth(month).getOrNull(period - 1)

    /** 这个月份在当前档位下用的是不是夏令时（界面据此提示「夏季作息」）。 */
    fun isSummer(month: Int): Boolean = tableForMonth(month) === SUMMER

    /** 供界面显示：把 "HH:mm" 与 "HH:mm" 合成 "07:50-08:30"。 */
    fun rangeText(period: Int, month: Int): String =
        slotOf(period, month)?.let { "${it.start}-${it.end}" } ?: ""

    /**
     * `"07:50"` → 470（当天第几分钟）。格式不对返回 null。
     *
     * 只留一份实现：界面判断「正在上课」、提醒排程算「提前一小时是几点」都要用它，
     * 两处各写一遍迟早会不一致。
     */
    fun minuteOf(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}

/**
 * 「用哪一套作息表」。默认 [SeasonMode.AUTO]，即学校规定的按日历月份自动切
 * （5–9 月夏令、10–4 月冬令，见 [ClassTimes]）。
 *
 * 留出手动档，是因为学校会临时调整作息 —— 比如通知「今年 10 月继续按夏令时上课」，
 * 或者某个校区自己把下午的课挪了半小时。这种时候自动切会把第 6–12 节**全部算错
 * 30 分钟**，而且错得看不出来：时间仍然是个合理的时间，只是早了或晚了半小时。
 * 手动钉住之后，界面、上课提醒、桌面小组件会一起改过来，不会各算各的。
 */
enum class SeasonMode {
    /** 按日历月份自动切（默认，与学校规定一致）。 */
    AUTO,

    /** 一直按冬令时。 */
    WINTER,

    /** 一直按夏令时。 */
    SUMMER,
}
