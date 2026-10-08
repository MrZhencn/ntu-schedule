package com.ntu.schedule.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 上课提醒时刻的回归测试。
 *
 * 这里钉的是三件最容易错的事：
 * 1. 提前一小时是按**那一天**的月份算的 —— 10 月 1 日第 6 节从 14:00 变成 13:30，
 *    9 月 24 日的提醒该在 13:00，10 月 1 日的就该在 12:30；
 * 2. 周次（含单双周）必须参与，形势与政策只在第 7、15 周上课；
 * 3. 导入时不能补发今天已经过去的那节课。
 */
class ReminderPlannerTest {

    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    private val realSchedule by lazy {
        ScheduleParser.parse(
            fixture("schedule-response.json"),
            fixture("calendar-response.json"),
            "2025000001",
        )
    }

    /** 校历第一周周一 = 2026-08-31（真实校历），19 周，与线上一致。 */
    private fun scheduleOf(vararg courses: Course) = Schedule(
        firstMondayIso = "2026-08-31",
        totalWeeks = 19,
        courses = courses.toList(),
    )

    private fun course(
        name: String,
        day: Int,
        start: Int,
        end: Int,
        weeks: List<Int> = (1..19).toList(),
        weekType: WeekType = WeekType.ALL,
        room: String = "JX08-101",
    ) = Course(
        name = name,
        room = room,
        dayOfWeek = day,
        startPeriod = start,
        endPeriod = end,
        weeks = weeks,
        weekType = weekType,
    )

    // ------------------------------------------------------------ 真实数据

    @Test
    fun `2026年10月8日周四只有两条提醒且都按冬令算`() {
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-08", daysAhead = 1)
        assertEquals(listOf("高等数学B（一）", "工程制图基础"), plans.map { it.course.name })
        assertEquals(listOf("06:50", "12:30"), plans.map { it.atText })
        assertEquals(listOf("07:50", "13:30"), plans.map { it.startTime })
        assertEquals(listOf("09:20", "15:00"), plans.map { it.endTime })
    }

    @Test
    fun `第6周不排人工智能通识与形势与政策的提醒`() {
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-08", daysAhead = 1)
        assertTrue(plans.none { it.course.name == "人工智能通识" })
        assertTrue(plans.none { it.course.name == "形势与政策" })
    }

    @Test
    fun `第7周周四才出现形势与政策的提醒`() {
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-15", daysAhead = 1)
        val sz = plans.singleOrNull { it.course.name == "形势与政策" }
        assertTrue("第 7 周应该有形势与政策", sz != null)
        assertEquals("14:20", sz!!.atText)
        assertEquals("15:20", sz.startTime)
        assertEquals("16:50", sz.endTime)
    }

    @Test
    fun `真实课表七天窗口里每条提醒都落在上课前一小时`() {
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-05", daysAhead = 7)
        assertTrue(plans.isNotEmpty())
        for (p in plans) {
            val start = ClassTimes.minuteOf(p.startTime)!!
            assertEquals("${p.dateIso} ${p.course.name} 的提前量不对", start - 60, p.atMinuteOfDay)
        }
    }

    // ------------------------------------------------------- 夏令 / 冬令切换

    @Test
    fun `同一节课九月排在13点十月排在12点30`() {
        val s = scheduleOf(course("工程制图基础", 4, 6, 7))
        val plans = ReminderPlanner.plan(s, "2026-09-24", daysAhead = 15)
        val byDate = plans.associateBy { it.dateIso }

        // 9/24 是周四、第 4 周，还在夏令时（第 6 节 14:00）
        assertEquals("13:00", byDate.getValue("2026-09-24").atText)
        assertEquals("14:00", byDate.getValue("2026-09-24").startTime)

        // 10/1 起换冬令（第 6 节 13:30），同样的课提前一小时就变成 12:30
        assertEquals("12:30", byDate.getValue("2026-10-01").atText)
        assertEquals("13:30", byDate.getValue("2026-10-01").startTime)
        assertEquals("12:30", byDate.getValue("2026-10-08").atText)
    }

    @Test
    fun `九月十五日按夏令算`() {
        val s = scheduleOf(course("晚间课", 2, 10, 10))
        val plans = ReminderPlanner.plan(s, "2026-09-15", daysAhead = 1)
        assertEquals(1, plans.size)
        assertEquals("19:00", plans.single().startTime)
        assertEquals("18:00", plans.single().atText)
    }

    @Test
    fun `十月十五日同一节按冬令算`() {
        val s = scheduleOf(course("晚间课", 4, 10, 10))
        val plans = ReminderPlanner.plan(s, "2026-10-15", daysAhead = 1)
        assertEquals("18:30", plans.single().startTime)
        assertEquals("17:30", plans.single().atText)
    }

    // ---------------------------------------------------------------- 周次

    @Test
    fun `只在第7与第15周上课的课程其他周不排提醒`() {
        val s = scheduleOf(course("形势与政策", 4, 8, 9, weeks = listOf(7, 15)))
        assertTrue(ReminderPlanner.plan(s, "2026-10-08", daysAhead = 1).isEmpty())
        assertEquals(1, ReminderPlanner.plan(s, "2026-10-15", daysAhead = 1).size)
        assertTrue(ReminderPlanner.plan(s, "2026-10-22", daysAhead = 1).isEmpty())
    }

    @Test
    fun `单周课在双周不排提醒`() {
        val s = scheduleOf(course("单周课", 4, 1, 2, weekType = WeekType.ODD))
        // 9/17 是第 3 周（单），9/24 是第 4 周（双）
        assertEquals(1, ReminderPlanner.plan(s, "2026-09-17", daysAhead = 1).size)
        assertTrue(ReminderPlanner.plan(s, "2026-09-24", daysAhead = 1).isEmpty())
    }

    @Test
    fun `双周课在单周不排提醒`() {
        val s = scheduleOf(course("双周课", 4, 1, 2, weekType = WeekType.EVEN))
        assertTrue(ReminderPlanner.plan(s, "2026-09-17", daysAhead = 1).isEmpty())
        assertEquals(1, ReminderPlanner.plan(s, "2026-09-24", daysAhead = 1).size)
    }

    @Test
    fun `学期之外的日期一条都不排`() {
        val s = scheduleOf(course("课", 4, 1, 2))
        // 2027-02-04 早已超出 19 周
        assertTrue(ReminderPlanner.plan(s, "2027-02-04", daysAhead = 7).isEmpty())
        // 开学前一周（校历第一周之前的日期）
        assertTrue(ReminderPlanner.plan(s, "2026-08-20", daysAhead = 7).isEmpty())
    }

    // ------------------------------------------------------------ 过滤与边界

    @Test
    fun `导入时过滤掉今天已经过去的时刻`() {
        // 10/8 当天 11:40：06:50 那条已经过去，只剩 12:30 那条
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-08", daysAhead = 1, nowMinuteOfDay = 700)
        assertEquals(listOf("12:30"), plans.map { it.atText })
    }

    @Test
    fun `都已经过去时一条也不排`() {
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-08", daysAhead = 1, nowMinuteOfDay = 23 * 60)
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `不传当前时刻时不做过滤`() {
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-08", daysAhead = 1, nowMinuteOfDay = null)
        assertEquals(2, plans.size)
    }

    @Test
    fun `最早的课也能排到当天的06点50`() {
        // 第 1 节 07:50 对应提醒 06:50，是当天最早的，不会出现负数
        val s = scheduleOf(course("早课", 4, 1, 1))
        assertEquals("06:50", ReminderPlanner.plan(s, "2026-10-08", daysAhead = 1).single().atText)
    }

    @Test
    fun `空课表不排提醒`() {
        assertTrue(ReminderPlanner.plan(Schedule(), "2026-10-08", daysAhead = 7).isEmpty())
    }

    @Test
    fun `daysAhead 至少有一天`() {
        val s = scheduleOf(course("早课", 4, 1, 1))
        assertEquals(1, ReminderPlanner.plan(s, "2026-10-08", daysAhead = 0).size)
    }

    @Test
    fun `结果按日期与时刻排序`() {
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-05", daysAhead = 7)
        val sorted = plans.sortedWith(compareBy({ it.dateIso }, { it.atMinuteOfDay }))
        assertEquals(sorted.map { it.dateIso + it.atText }, plans.map { it.dateIso + it.atText })
    }

    // ------------------------------------------------------------ 通知文案

    @Test
    fun `提醒标题默认是还有一小时上课`() {
        assertEquals("还有 1 小时上课", ReminderPlanner.title())
        assertEquals(60, ReminderPlanner.DEFAULT_LEAD_MINUTES)
    }

    // ------------------------------------------------------------ 提前量可配

    @Test
    fun `提前量文案把整小时写成小时其余写分钟`() {
        assertEquals("10 分钟", ReminderPlanner.leadText(10))
        assertEquals("20 分钟", ReminderPlanner.leadText(20))
        assertEquals("30 分钟", ReminderPlanner.leadText(30))
        assertEquals("1 小时", ReminderPlanner.leadText(60))
        assertEquals("2 小时", ReminderPlanner.leadText(120))
        // 90 既不是整小时也不是预设档，但函数本身要给出合理说法（不能崩、不能写出「1.5 小时」）
        assertEquals("90 分钟", ReminderPlanner.leadText(90))
    }

    @Test
    fun `五档预设就是需求里说的那五个`() {
        assertEquals(listOf(10, 20, 30, 60, 120), ReminderPlanner.PRESET_LEAD_MINUTES)
    }

    @Test
    fun `提醒标题跟着提前量走`() {
        assertEquals("还有 10 分钟上课", ReminderPlanner.title(10))
        assertEquals("还有 30 分钟上课", ReminderPlanner.title(30))
        assertEquals("还有 1 小时上课", ReminderPlanner.title(60))
        assertEquals("还有 2 小时上课", ReminderPlanner.title(120))
    }

    @Test
    fun `提前十分钟时早课排在七点四十`() {
        val s = scheduleOf(course("早课", 4, 1, 1))
        val plan = ReminderPlanner.plan(s, "2026-10-08", daysAhead = 1, leadMinutes = 10).single()
        // 提前量只挪「几点响」，上课时间本身还是 07:50
        assertEquals("07:50", plan.startTime)
        assertEquals("07:40", plan.atText)
        assertEquals("2026-10-08", plan.dateIso)
    }

    @Test
    fun `提前两小时时早课排在五点五十`() {
        // 冬令第 1 节 07:50 往前两小时是 05:50，**不会跨到前一天** ——
        // 这是「2 小时档」不需要处理日期回退的原因，钉住它。
        val s = scheduleOf(course("早课", 4, 1, 1))
        val plan = ReminderPlanner.plan(s, "2026-10-08", daysAhead = 1, leadMinutes = 120).single()
        assertEquals("05:50", plan.atText)
        assertEquals("2026-10-08", plan.dateIso)
    }

    @Test
    fun `提前量传负数当零处理也就是正点提醒`() {
        val s = scheduleOf(course("早课", 4, 1, 1))
        val plan = ReminderPlanner.plan(s, "2026-10-08", daysAhead = 1, leadMinutes = -30).single()
        assertEquals("07:50", plan.atText)
    }

    @Test
    fun `同一天不同提前量排出的条数一样多`() {
        // 提前量只改「几点响」，不该把任何一节课挤出窗口
        val base = ReminderPlanner.plan(realSchedule, "2026-10-08", daysAhead = 1)
        for (lead in ReminderPlanner.PRESET_LEAD_MINUTES) {
            val plans = ReminderPlanner.plan(realSchedule, "2026-10-08", daysAhead = 1, leadMinutes = lead)
            assertEquals("提前 $lead 分钟时条数变了", base.size, plans.size)
        }
    }

    @Test
    fun `通知正文含课名时间与地点`() {
        val plans = ReminderPlanner.plan(realSchedule, "2026-10-08", daysAhead = 1)
        val math = plans.first { it.course.name == "高等数学B（一）" }
        assertEquals("《高等数学B（一）》 07:50-09:20 · JX08-109", ReminderPlanner.body(math))
    }

    @Test
    fun `没有地点时正文写地点待定`() {
        val s = scheduleOf(course("体育（一）", 4, 1, 2, room = ""))
        val plan = ReminderPlanner.plan(s, "2026-10-08", daysAhead = 1).single()
        assertTrue(ReminderPlanner.body(plan).endsWith("· 地点待定"))
    }
}
