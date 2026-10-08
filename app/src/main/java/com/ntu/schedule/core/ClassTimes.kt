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
     * 按日历月份选表。
     *
     * @param month 1–12；越界（脏数据）时按冬令时处理，不抛异常
     */
    fun tableForMonth(month: Int): List<Slot> = if (month in SUMMER_MONTHS) SUMMER else WINTER

    /** 取第 period 节（1-based）的时间；越界返回 null 而不是抛异常，避免脏数据导致崩溃。 */
    fun slotOf(period: Int, month: Int): Slot? = tableForMonth(month).getOrNull(period - 1)

    /** 这个月份是不是夏令时（界面据此提示「夏季作息」）。 */
    fun isSummer(month: Int): Boolean = month in SUMMER_MONTHS

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
