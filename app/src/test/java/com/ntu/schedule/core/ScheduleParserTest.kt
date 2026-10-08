package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真机账号实测抓取**的两份接口响应做回归测试。
 *
 * fixtures 来源：`tools/out/课表接口响应.json` 与 `tools/out/校历接口响应.json`，
 * 由 `tools/scrape-schedule.mjs` 在 2026-10-08 对 `tdjw.ntu.edu.cn` 实跑得到。
 * 因此这些断言反映的是学校接口的**真实形态**，而不是我对接口的猜测 ——
 * 一旦解析逻辑被改坏，这里会立刻报错。
 */
class ScheduleParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    private val scheduleJson = fixture("schedule-response.json")
    private val calendarJson = fixture("calendar-response.json")

    // fixture 里的学号与姓名已做脱敏替换（结构与真实响应完全一致，只换掉身份字段）
    private val schedule by lazy { ScheduleParser.parse(scheduleJson, calendarJson, "2025000001") }

    @Test
    fun `课程条数与接口一致`() {
        // 接口 kbList 实测 14 条
        assertEquals(14, schedule.courses.size)
    }

    @Test
    fun `学籍信息解析正确`() {
        assertEquals("测试同学", schedule.studentName)
        assertEquals("2025000001", schedule.studentId)
        assertEquals("纺织工程", schedule.major)
        assertEquals("纺262", schedule.className)
        assertEquals("2026", schedule.academicYear)
        assertEquals("3", schedule.termCode)
        assertEquals("2026-2027 学年 第 1 学期", schedule.termName)
    }

    @Test
    fun `校历周数与开学日期解析正确`() {
        assertEquals(19, schedule.totalWeeks)
        assertEquals("2026-08-31", schedule.firstMondayIso)
        assertEquals(19, schedule.weeks.size)
        assertEquals("2026-08-31", schedule.weeks.first().mondayIso)
        assertEquals("2027-01-04", schedule.weeks.last().mondayIso)
    }

    @Test
    fun `课程名已剔除标记符`() {
        assertTrue(schedule.courses.none { c -> c.name.any { it in "■◆▲★●" } })
        assertTrue(schedule.courses.any { it.name == "高等数学B（一）" })
        assertTrue(schedule.courses.any { it.name == "人工智能通识" })
    }

    @Test
    fun `高等数学周一4到5节5到18周`() {
        val c = schedule.courses.first { it.name == "高等数学B（一）" && it.dayOfWeek == 1 }
        assertEquals(4, c.startPeriod)
        assertEquals(5, c.endPeriod)
        assertEquals((5..18).toList(), c.weeks)
        assertEquals("JX08-101", c.room)
        assertEquals("刘晓惠", c.teacher)
        assertEquals("啬园校区", c.campus)
        assertEquals("5-18周", c.weeksText)
        assertEquals("4-5", c.periodText)
    }

    @Test
    fun `节次取jcs而不是区间更大的jcor`() {
        // 实测 fixture 里基础英语（一）周五有两条相邻记录：
        //   jcs="3-4" / jcor="3-5" / zcd="15周"      —— 只有第 3-4 节
        //   jcs="3-5" / jcor="3-5" / zcd="5-14周"    —— 第 3-5 节
        // 若误用 jcor 当节次，第一条会被撑成 3-5 节，和第二条完全重叠、看起来像排课冲突。
        val eng = schedule.courses.filter { it.name == "基础英语（一）" && it.dayOfWeek == 5 }
        assertEquals(2, eng.size)

        val wide = eng.first { it.startPeriod == 3 && it.endPeriod == 5 }
        assertEquals((5..14).toList(), wide.weeks)

        val narrow = eng.first { it.startPeriod == 3 && it.endPeriod == 4 }
        assertEquals(listOf(15), narrow.weeks)
        assertEquals("3-4", narrow.periodText)
    }

    @Test
    fun `离散周次7周与15周被正确展开`() {
        val c = schedule.courses.first { it.name == "形势与政策" }
        assertEquals(listOf(7, 15), c.weeks)
        assertEquals(4, c.dayOfWeek)
        assertEquals("7,15周", c.weeksText)
    }

    @Test
    fun `同一课程不同地点的分段记录不被合并`() {
        // 人工智能通识：第 7-8 周 JX08-102，第 9-16 周「机房由教师确认」
        val ai = schedule.courses.filter { it.name == "人工智能通识" }
        assertTrue("人工智能通识应有多条分段记录", ai.size >= 3)
        assertTrue(ai.any { it.room == "JX08-102" && it.weeks == (7..8).toList() })
        assertTrue(ai.any { it.room == "机房由教师确认" && it.weeks == (9..16).toList() })
    }

    @Test
    fun `未排地点原样保留而不是丢弃课程`() {
        val pe = schedule.courses.first { it.name == "体育（一）" }
        assertEquals("未排地点", pe.room)
        assertEquals((5..18).toList(), pe.weeks)
    }

    @Test
    fun `2026年10月8日周四当天恰好两门课`() {
        // 这一天是写进 docs\安装与验证.md 的验收锚点，所以在这里钉死，
        // 免得文档里的「应当看到什么」和代码实际行为悄悄跑偏。
        //
        // 周四在真实数据里共有 4 条记录，第 6 周只有其中 2 条命中：
        //   高等数学B（一）  1-2 节  5-18 周  ✓ 第 6 周在内
        //   人工智能通识     4-5 节  7-16 周  ✗ 第 6 周还没开学
        //   工程制图基础     6-7 节  5-16 周  ✓
        //   形势与政策       8-9 节  7,15 周  ✗ 离散周次，第 6 周不在其中
        val date = "2026-10-08"
        assertEquals(6, schedule.weekOfDate(date))
        assertEquals(4, DateUtil.dayOfWeekOf(date))

        val courses = schedule.coursesOfWeek(6).filter { it.dayOfWeek == 4 }
        assertEquals(2, courses.size)
        assertEquals(setOf("高等数学B（一）", "工程制图基础"), courses.map { it.name }.toSet())
        // 两条「不应出现」的课分别代表区间周次与离散周次，出现即说明周次被当成全周兜底了
        assertTrue(courses.none { it.name == "人工智能通识" })
        assertTrue(courses.none { it.name == "形势与政策" })
    }

    @Test
    fun `十月第6到7节按冬令取13点30分`() {
        // 秋季学期会在 10 月 1 日从夏令切到冬令，这是本项目最容易做错的一处。
        val month = DateUtil.monthOf("2026-10-08")
        assertEquals(10, month)
        assertEquals("07:50", ClassTimes.slotOf(1, month)!!.start)
        assertEquals("09:20", ClassTimes.slotOf(2, month)!!.end)
        assertEquals("13:30", ClassTimes.slotOf(6, month)!!.start)
        assertEquals("15:00", ClassTimes.slotOf(7, month)!!.end)
        // 对照：9 月同一节课是夏令时间，晚 30 分钟
        assertEquals("14:00", ClassTimes.slotOf(6, 9)!!.start)
    }

    @Test
    fun `第6周课表与教学周一致`() {
        val week6 = schedule.coursesOfWeek(6)
        assertTrue(week6.isNotEmpty())
        assertTrue(week6.all { 6 in it.weeks })
        // 人工智能通识第 7 周才开始
        assertTrue(week6.none { it.name == "人工智能通识" })
    }

    @Test
    fun `按日期推算教学周`() {
        assertEquals(1, schedule.weekOfDate("2026-08-31"))
        assertEquals(1, schedule.weekOfDate("2026-09-06"))
        assertEquals(2, schedule.weekOfDate("2026-09-07"))
        assertEquals(6, schedule.weekOfDate("2026-10-08"))
        assertEquals(19, schedule.weekOfDate("2027-01-10"))
        assertNull("学期开始前应为 null", schedule.weekOfDate("2026-08-30"))
        assertNull("学期结束后应为 null", schedule.weekOfDate("2027-02-01"))
    }

    @Test
    fun `JSON往返不丢数据`() {
        val json = ScheduleParser.toJson(schedule)
        val back = ScheduleParser.fromJson(json)
        assertNotNull(back)
        back!!
        assertEquals(schedule.courses.size, back.courses.size)
        assertEquals(schedule.studentName, back.studentName)
        assertEquals(schedule.totalWeeks, back.totalWeeks)
        assertEquals(schedule.firstMondayIso, back.firstMondayIso)
        assertEquals(schedule.weeks, back.weeks)
        assertEquals(schedule.courses, back.courses)
        assertEquals(schedule.importedAt, back.importedAt)
    }

    @Test
    fun `空输入不抛异常`() {
        val empty = ScheduleParser.parse("", null)
        assertTrue(empty.isEmpty)
        assertEquals(ScheduleParser.DEFAULT_TOTAL_WEEKS, empty.totalWeeks)
        assertNull(ScheduleParser.fromJson(""))
        assertNull(ScheduleParser.fromJson("null"))
        assertNull(ScheduleParser.fromJson("{}"))
    }

    @Test
    fun `无校历时按默认周数兜底`() {
        val s = ScheduleParser.parse(scheduleJson, null)
        assertEquals(ScheduleParser.DEFAULT_TOTAL_WEEKS, s.totalWeeks)
        assertEquals("", s.firstMondayIso)
        assertEquals(14, s.courses.size)
    }

    @Test
    fun `按星期取课并排序`() {
        val monday = schedule.coursesOfDay(1)
        assertTrue(monday.isNotEmpty())
        // 节次必须单调不减
        assertEquals(monday.map { it.startPeriod }.sorted(), monday.map { it.startPeriod })
        assertTrue(monday.all { it.dayOfWeek == 1 })
    }
}
