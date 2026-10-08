package com.ntu.schedule.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * 钉住日期工具。
 *
 * 这里把时区**显式固定成 Asia/Shanghai**，因为本类要防的正是「拿 UTC 当本地用」的 bug。
 * 如果跟随机器默认时区，这个 bug 在 UTC 环境下会表现正常，测试就成了摆设。
 * （`java.time` 只出现在测试里 —— 主源码不能用，它需要 API 26 而本项目 minSdk 24。）
 */
class DateUtilTest {

    private lateinit var savedZone: TimeZone

    @Before
    fun fixTimeZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(savedZone)
    }

    /** 北京时间的某个瞬间，转成毫秒时间戳。 */
    private fun beijing(utcIso: String): Long = Instant.parse(utcIso).toEpochMilli()

    @Test
    fun `1970年1月1日的日序号是0`() {
        assertEquals(0L, DateUtil.toEpochDay("1970-01-01"))
        assertEquals(1L, DateUtil.toEpochDay("1970-01-02"))
        assertEquals(-1L, DateUtil.toEpochDay("1969-12-31"))
        assertEquals("1970-01-01", DateUtil.fromEpochDay(0))
    }

    @Test
    fun `解析非法日期返回null`() {
        for (s in listOf("", "abc", "2026", "2026-13-01", "2026-00-10", "2026-10-32", "2026/10/08")) {
            assertNull("应拒绝「$s」", DateUtil.toEpochDay(s))
        }
    }

    @Test
    fun `宽松接受不补零的月日`() {
        assertEquals(DateUtil.toEpochDay("2026-10-08"), DateUtil.toEpochDay("2026-10-8"))
    }

    @Test
    fun `真实校历第1周是8月31日到9月6日`() {
        // 校历接口实测：第 1 周 rq = "2026-08-31/2026-09-06"
        assertEquals(1, DateUtil.dayOfWeekOf("2026-08-31"))
        assertEquals("2026-09-06", DateUtil.plusDays("2026-08-31", 6))
        assertEquals(6L, DateUtil.daysBetween("2026-08-31", "2026-09-06"))
        // 2026-10-08 属于第 6 周，其周一应为 2026-10-05，与第 1 周相差 35 天
        assertEquals(35L, DateUtil.daysBetween("2026-08-31", "2026-10-05"))
    }

    @Test
    fun `加减天数跨月与跨年`() {
        assertEquals("2026-08-30", DateUtil.plusDays("2026-08-31", -1))
        assertEquals("2026-09-01", DateUtil.plusDays("2026-08-31", 1))
        assertEquals("2027-01-01", DateUtil.plusDays("2026-12-31", 1))
        assertEquals("2026-12-31", DateUtil.plusDays("2027-01-01", -1))
        // 2028 是闰年，2 月 29 日必须存在
        assertEquals("2028-02-29", DateUtil.plusDays("2028-02-28", 1))
        assertEquals("2028-03-01", DateUtil.plusDays("2028-02-29", 1))
        assertNull(DateUtil.plusDays("bad", 1))
    }

    @Test
    fun `星期几`() {
        // 真实校历：第 1 周周一为 2026-08-31，故 2026-10-08 是周四
        assertEquals(1, DateUtil.dayOfWeekOf("2026-08-31"))
        assertEquals(7, DateUtil.dayOfWeekOf("2026-09-06"))
        assertEquals(4, DateUtil.dayOfWeekOf("2026-10-08"))
        assertEquals("周四", DateUtil.weekdayCn(4))
        assertEquals("周一", DateUtil.weekdayCn(1))
        assertEquals("周日", DateUtil.weekdayCn(7))
        assertNull(DateUtil.dayOfWeekOf("bad"))
    }

    @Test
    fun `所在周的周一`() {
        assertEquals("2026-10-05", DateUtil.mondayOf("2026-10-08"))
        assertEquals("2026-10-05", DateUtil.mondayOf("2026-10-05"))
        // 周日属于同一周的周一 + 6 天，不能跳到下一周
        assertEquals("2026-10-05", DateUtil.mondayOf("2026-10-11"))
        assertEquals("2026-08-31", DateUtil.mondayOf("2026-09-06"))
    }

    @Test
    fun `月与月日文本`() {
        assertEquals(10, DateUtil.monthOf("2026-10-08"))
        assertEquals(9, DateUtil.monthOf("2026-09-06"))
        assertEquals("10月8日", DateUtil.monthDayText("2026-10-08"))
        assertEquals("9月6日", DateUtil.monthDayText("2026-09-06"))
        // 脏输入不应崩，退回默认值
        assertEquals(1, DateUtil.monthOf("bad"))
    }

    // --- 下面几条是本类存在的理由：本地时间 ≠ UTC 时间 ---

    @Test
    fun `本地凌晨算作当天而不是前一天`() {
        // 北京时间 2026-10-08 00:01 = UTC 2026-10-07 16:01。
        // 旧实现直接用 millis / 86_400_000（UTC 日序号），这里会算成 10-07，
        // 表现就是「每天早上 8 点前小组件显示昨天的课」。
        assertEquals("2026-10-08", DateUtil.todayIso(beijing("2026-10-07T16:01:00Z")))
    }

    @Test
    fun `本地午夜前一刻算作前一天`() {
        assertEquals("2026-10-07", DateUtil.todayIso(beijing("2026-10-07T15:59:59Z")))
    }

    @Test
    fun `本地跨天的分界点正是当地零点`() {
        val justBefore = beijing("2026-10-07T15:59:59.999Z")
        val justAfter = beijing("2026-10-07T16:00:00Z")
        assertEquals("2026-10-07", DateUtil.todayIso(justBefore))
        assertEquals("2026-10-08", DateUtil.todayIso(justAfter))
    }

    @Test
    fun `今天零点的时间戳`() {
        // 北京时间 2026-10-08 10:00 的「今天零点」= 北京时间 2026-10-08 00:00 = UTC 2026-10-07 16:00
        val now = beijing("2026-10-08T02:00:00Z")
        assertEquals(beijing("2026-10-07T16:00:00Z"), DateUtil.todayStartMillis(now))
        // 零点之后一毫秒仍属于同一天，零点之前一毫秒属于前一天
        assertEquals("2026-10-08", DateUtil.todayIso(DateUtil.todayStartMillis(now)))
        assertEquals("2026-10-07", DateUtil.todayIso(DateUtil.todayStartMillis(now) - 1))
    }

    @Test
    fun `本地当天第几分钟`() {
        // 北京时间 00:00
        assertEquals(0, DateUtil.nowMinuteOfDay(beijing("2026-10-07T16:00:00Z")))
        // 北京时间 00:01
        assertEquals(1, DateUtil.nowMinuteOfDay(beijing("2026-10-07T16:01:00Z")))
        // 北京时间 08:00 —— 第 1 节 07:50 已开始
        assertEquals(8 * 60, DateUtil.nowMinuteOfDay(beijing("2026-10-08T00:00:00Z")))
        // 北京时间 13:35 —— 冬令第 6 节(13:30-14:10) 正在进行
        assertEquals(13 * 60 + 35, DateUtil.nowMinuteOfDay(beijing("2026-10-08T05:35:00Z")))
        // 北京时间 23:59，仍在 0..1439 内
        assertEquals(23 * 60 + 59, DateUtil.nowMinuteOfDay(beijing("2026-10-08T15:59:00Z")))
    }

    @Test
    fun `分钟数始终落在一天之内`() {
        // 覆盖一整天，防止取模写错导致负值或溢出
        val dayStart = beijing("2026-10-07T16:00:00Z")
        for (h in 0 until 24) {
            val t = dayStart + h * 3_600_000L
            val m = DateUtil.nowMinuteOfDay(t)
            assertEquals(h * 60, m)
        }
    }

    // --- startOfDayMillis：上课提醒的闹钟时刻就是它加上分钟数算出来的 ---

    @Test
    fun `任意日期的本地零点时间戳`() {
        // 北京时间 2026-10-08 00:00 = UTC 2026-10-07 16:00
        assertEquals(beijing("2026-10-07T16:00:00Z"), DateUtil.startOfDayMillis("2026-10-08"))
        assertEquals(beijing("2026-10-04T16:00:00Z"), DateUtil.startOfDayMillis("2026-10-05"))
        assertEquals(beijing("2026-09-05T16:00:00Z"), DateUtil.startOfDayMillis("2026-09-06"))
    }

    @Test
    fun `startOfDayMillis与todayStartMillis给出同一个时刻`() {
        val now = beijing("2026-10-08T02:00:00Z")
        assertEquals(DateUtil.todayStartMillis(now), DateUtil.startOfDayMillis("2026-10-08"))
        // 对任意瞬间，「今天的零点」必须等于「拿今天日期算出的零点」
        assertEquals(DateUtil.startOfDayMillis(DateUtil.todayIso(now)), DateUtil.todayStartMillis(now))
    }

    @Test
    fun `startOfDayMillis算出的正是当天零点`() {
        val start = DateUtil.startOfDayMillis("2026-10-08")!!
        assertEquals("2026-10-08", DateUtil.todayIso(start))
        assertEquals("2026-10-07", DateUtil.todayIso(start - 1))
        assertEquals("2026-10-08", DateUtil.todayIso(start + 86_399_999L))
    }

    @Test
    fun `startOfDayMillis遇到非法日期返回null`() {
        assertNull(DateUtil.startOfDayMillis("bad"))
        assertNull(DateUtil.startOfDayMillis(""))
        assertNull(DateUtil.startOfDayMillis("2026-13-01"))
    }

    @Test
    fun `提醒触发时刻落在目标日期的那一分钟`() {
        // ReminderScheduler 就是用 startOfDayMillis(dateIso) + atMinuteOfDay * 60_000 排闹钟的。
        // 这里把「提前一小时」的真实锚点跑一遍：10 月 8 日冬令第 1 节 07:50 -> 06:50。
        val dateIso = "2026-10-08"
        val atMinute = 6 * 60 + 50
        val trigger = DateUtil.startOfDayMillis(dateIso)!! + atMinute * 60_000L
        assertEquals(dateIso, DateUtil.todayIso(trigger))
        assertEquals(atMinute, DateUtil.nowMinuteOfDay(trigger))
        assertEquals(beijing("2026-10-07T22:50:00Z"), trigger)
    }
}
