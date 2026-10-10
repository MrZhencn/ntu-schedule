package com.ntu.schedule.core

/**
 * 一条课表记录。
 *
 * 字段命名按「教务接口字段 → 语义」对齐，导入时可一一对应，便于排查。
 * 注意正方把**同一门课的不同周次/地点**拆成多条记录（例如「人工智能通识」在第 7-8 周
 * 在 JX08-102、第 9-16 周在「机房由教师确认」），所以这里不做合并 —— 合并会丢掉地点信息。
 */
data class Course(
    /** 课程名（已剔除 ■◆▲ 等标记）。 */
    val name: String,
    /** 教师，可能多位，用逗号连接。 */
    val teacher: String = "",
    /** 上课地点，如 `JX08-101`；可能为「未排地点」「机房由教师确认」等文字。 */
    val room: String = "",
    /** 星期几，1=周一 … 7=周日。 */
    val dayOfWeek: Int,
    /** 起始节次（1-based），如 `jcs = "4-5"` 的 4。 */
    val startPeriod: Int,
    /** 结束节次（含），如 `jcs = "4-5"` 的 5。 */
    val endPeriod: Int,
    /** 上课周次列表。 */
    val weeks: List<Int> = emptyList(),
    val weekType: WeekType = WeekType.ALL,
    /** 教学班名，用于区分同名课程的不同班。 */
    val teachingClass: String = "",
    /** 课程代码。 */
    val courseCode: String = "",
    /** 校区，如「啬园校区」。 */
    val campus: String = "",
    /** 周次后附带的标记符（■◆▲），表示该段周次另有地点安排。 */
    val weekMarker: String = "",
    /**
     * 只对**用户自己加的课**有值（见 [isCustom]）；教务导入的课永远是空串。
     *
     * 用它而不是「课名 + 地点」来定位一条自定义课，是因为编辑时课名/地点/时间都
     * 可能被改掉 —— 按内容去找就找不到原来那条了，用户会看到「改完变成了两门课」。
     */
    val customId: String = "",
) {
    /** 是不是用户手动加的课（不是从教务导入的）。 */
    val isCustom: Boolean get() = customId.isNotEmpty()

    /** 覆盖的节次区间，用于网格布局。 */
    val periodRange: IntRange get() = startPeriod..endPeriod

    /** 该课在第 week 周是否上课。 */
    fun activeIn(week: Int): Boolean = WeekParser.isActiveIn(weeks, weekType, week)

    /** `4-5` 或单节 `3`。 */
    val periodText: String
        get() = if (startPeriod == endPeriod) "$startPeriod" else "$startPeriod-$endPeriod"

    /** 周次显示文本，如 `5-18周`。 */
    val weeksText: String
        get() = WeekParser.formatWeeks(weeks) + weekMarker

    /**
     * 用于同格子内合并：同名课程若周次连续但地点不同，仍算不同段，
     * 因此按 (课名, 地点, 教师) 判等。
     */
    val mergeKey: Triple<String, String, String> get() = Triple(name, room, teacher)

    /**
     * 稳定的配色索引：同名课程永远同色，跨周次/跨界面一致。
     * 不用 `hashCode()`，因为 Kotlin 的 String.hashCode 虽稳定但负数取模易出错。
     */
    val colorIndex: Int
        get() {
            var h = 7
            for (c in name) h = (h * 31 + c.code) and 0x7FFFFFFF
            return h % 12
        }
}

/** 校历中的一周。 */
data class CalendarWeek(
    /** 第几周，从 1 开始。 */
    val week: Int,
    /** 周一日期，ISO 格式 `2026-08-31`。 */
    val mondayIso: String,
) {
    /** 该周周日的 ISO 日期。 */
    val sundayIso: String
        get() = DateUtil.plusDays(mondayIso, 6) ?: mondayIso
}

/** 一整个学期的课表数据（本地持久化的根对象）。 */
data class Schedule(
    /** 学年，如 `2026`（对应 `xnm`）。 */
    val academicYear: String = "",
    /** 学期代码，`3`=第一学期；对应 `xqm`。 */
    val termCode: String = "3",
    /** 学期名称，如 `2026-2027 第 1 学期`。 */
    val termName: String = "",
    /** 学生姓名。 */
    val studentName: String = "",
    /** 学号。 */
    val studentId: String = "",
    /** 学院/专业，如「纺织工程」。 */
    val major: String = "",
    /** 班级，如「纺262」。 */
    val className: String = "",
    /** 学期总周数。校历接口实测 19 周。 */
    val totalWeeks: Int = 19,
    /** 第一周周一日期（ISO），来自校历接口 `rq` 字段。 */
    val firstMondayIso: String = "",
    /** 校历逐周日期。 */
    val weeks: List<CalendarWeek> = emptyList(),
    val courses: List<Course> = emptyList(),
    /** 导入时间戳（毫秒）。 */
    val importedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = courses.isEmpty()

    /** 指定周次的全部课程，按星期与节次排序。 */
    fun coursesOfWeek(week: Int): List<Course> =
        courses.filter { it.activeIn(week) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.name }))

    /** 指定星期几（1=周一）的课程，按节次排序。 */
    fun coursesOfDay(dayOfWeek: Int): List<Course> =
        courses.filter { it.dayOfWeek == dayOfWeek }
            .sortedWith(compareBy({ it.startPeriod }, { it.name }))

    /** 第 week 周的周一日期。 */
    fun mondayOfWeek(week: Int): String? =
        weeks.firstOrNull { it.week == week }?.mondayIso
            ?: if (firstMondayIso.isEmpty()) null
            else DateUtil.plusDays(firstMondayIso, (week - 1) * 7)

    /** 根据日期推算教学周次；不在学期范围内返回 null。 */
    fun weekOfDate(iso: String): Int? {
        val monday = firstMondayIso.ifEmpty { weeks.firstOrNull()?.mondayIso ?: return null }
        val days = DateUtil.daysBetween(monday, iso) ?: return null
        if (days < 0) return null
        val w = (days / 7 + 1).toInt()
        return if (w in 1..totalWeeks) w else null
    }
}

/**
 * 纯 Kotlin 的日期工具，避免依赖 `java.time`（Android 上需 API 26，本项目 minSdk 24）
 * 或 `java.util.Calendar` 的时区陷阱。全部按「本地日期字符串」处理。
 */
object DateUtil {

    private val WEEKDAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayCn(dayOfWeek: Int): String = WEEKDAY_CN.getOrElse(dayOfWeek - 1) { "" }

    /** 解析 `yyyy-MM-dd`，返回自 1970-01-01 起的天数；失败返回 null。 */
    fun toEpochDay(iso: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(iso.trim()) ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val d = m.groupValues[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        return civilToEpochDay(y, mo, d)
    }

    fun fromEpochDay(epochDay: Long): String {
        val (y, m, d) = epochDayToCivil(epochDay)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    /** `iso` 加减天数；解析失败返回 null。 */
    fun plusDays(iso: String, days: Int): String? =
        toEpochDay(iso)?.let { fromEpochDay(it + days) }

    /** 两个日期相差天数（to - from）。 */
    fun daysBetween(fromIso: String, toIso: String): Long? {
        val a = toEpochDay(fromIso) ?: return null
        val b = toEpochDay(toIso) ?: return null
        return b - a
    }

    /** 1=周一 … 7=周日。 */
    fun dayOfWeekOf(iso: String): Int? {
        val e = toEpochDay(iso) ?: return null
        // 1970-01-01 是星期四，故 +3 后对 7 取模得到 0=周一
        val idx = ((e + 3) % 7 + 7) % 7
        return idx.toInt() + 1
    }

    /** 该日期所在周的周一。 */
    fun mondayOf(iso: String): String? {
        val dow = dayOfWeekOf(iso) ?: return null
        return plusDays(iso, -(dow - 1))
    }

    /**
     * 今天的**本地**日期（`yyyy-MM-dd`）。
     *
     * 注意不能直接写 `System.currentTimeMillis() / 86_400_000` —— 那是 UTC 的日序号，
     * 在东八区会导致**每天早上 8 点前算成前一天**（UTC 00:00 = 北京 08:00）。
     * 所以先加上本地时区偏移，再取整日。
     */
    fun todayIso(nowMillis: Long = System.currentTimeMillis()): String =
        fromEpochDay(localEpochDay(nowMillis))

    /** 本地时区下的「今天零点」对应的毫秒时间戳，供 `AlarmManager` 等使用。 */
    fun todayStartMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val offset = java.util.TimeZone.getDefault().getOffset(nowMillis)
        return localEpochDay(nowMillis) * 86_400_000L - offset
    }

    /**
     * 指定日期的本地零点毫秒时间戳（如 `2026-10-08` → 当天 00:00 的 millis）。
     * 解析失败返回 null。提醒排程靠它把「当天第几分钟」换算成绝对时刻。
     */
    fun startOfDayMillis(iso: String): Long? {
        val epochDay = toEpochDay(iso) ?: return null
        val guess = epochDay * 86_400_000L
        // 中国没有夏令时，偏移量恒定；这里仍按该时刻取一次偏移，避免在别的时区上错一小时
        return guess - java.util.TimeZone.getDefault().getOffset(guess)
    }

    /** 本地当天的第几分钟（0-1439），用于判断「正在上课」。 */
    fun nowMinuteOfDay(nowMillis: Long = System.currentTimeMillis()): Int {
        val offset = java.util.TimeZone.getDefault().getOffset(nowMillis)
        val localMillis = nowMillis + offset
        return ((localMillis % 86_400_000L) / 60_000L).toInt()
    }

    /** `2026-10-08` → 10 */
    fun monthOf(iso: String): Int = iso.substringAfter('-', "").substringBefore('-', "").toIntOrNull() ?: 1

    /** `2026-10-08` → `10月8日` */
    fun monthDayText(iso: String): String {
        val mo = monthOf(iso)
        val d = iso.substringAfterLast('-', "").toIntOrNull() ?: return iso
        return "$mo" + "月" + "$d" + "日"
    }

    /** 本地时区的日序号（自 1970-01-01 起的本地天数）。 */
    private fun localEpochDay(nowMillis: Long): Long {
        val offset = java.util.TimeZone.getDefault().getOffset(nowMillis)
        return Math.floorDiv(nowMillis + offset, 86_400_000L)
    }

    // --- Howard Hinnant 的 civil_from_days / days_from_civil，纯整数运算，无时区依赖 ---

    private fun civilToEpochDay(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097L + doe.toLong() - 719468L
    }

    private fun epochDayToCivil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468L
        val era = (if (z >= 0) z else z - 146096L) / 146097L
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = mp + (if (mp < 10L) 3L else -9L)
        return Triple((y + if (m <= 2L) 1L else 0L).toInt(), m.toInt(), d.toInt())
    }
}
