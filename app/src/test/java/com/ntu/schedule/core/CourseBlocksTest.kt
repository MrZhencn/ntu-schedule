package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周课表格子划分的回归测试。
 *
 * 这一层最容易静默出错的地方是「列高不一致」：只要某一天的格子高度之和比别的天少一节，
 * 右侧网格就会从此错位，而且看起来只是「有点歪」，很难追。所以除功能断言外，
 * 每一条都补上「格子高度之和 == 节次总数」。
 */
class CourseBlocksTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    private val schedule by lazy {
        ScheduleParser.parse(
            fixture("schedule-response.json"),
            fixture("calendar-response.json"),
            "2025000001",
        )
    }

    private fun course(
        name: String,
        day: Int,
        start: Int,
        end: Int,
        weeks: List<Int> = (1..19).toList(),
    ) = Course(
        name = name,
        dayOfWeek = day,
        startPeriod = start,
        endPeriod = end,
        weeks = weeks,
    )

    // ------------------------------------------------------------ 真实数据

    @Test
    fun `真实课表的节次范围是1到9`() {
        assertEquals(1..9, CourseBlocks.periodsInUse(schedule))
    }

    @Test
    fun `空课表的节次范围兜底为1到1`() {
        assertEquals(1..1, CourseBlocks.periodsInUse(Schedule()))
    }

    @Test
    fun `高等数学周一4到5节合并成一个span为2的格子`() {
        val blocks = CourseBlocks.ofDay(
            schedule.coursesOfWeek(6).filter { it.dayOfWeek == 1 },
            1,
            9,
        )
        val math = blocks.single { it.primary?.name == "高等数学B（一）" }
        assertEquals(4, math.startPeriod)
        assertEquals(5, math.endPeriod)
        assertEquals(2, math.span)
        // 关键：第 5 节不再单独出格子 —— 这正是「连堂课被切成两个小格」的复现点
        assertTrue(
            "第 5 节不应作为任何格子的起点",
            blocks.none { it.startPeriod == 5 },
        )
    }

    @Test
    fun `思想道德与法治周三3到5节合并成一个span为3的格子`() {
        val blocks = CourseBlocks.ofDay(
            schedule.coursesOfWeek(6).filter { it.dayOfWeek == 3 },
            1,
            9,
        )
        val c = blocks.single { it.primary?.name == "思想道德与法治" }
        assertEquals(3, c.startPeriod)
        assertEquals(5, c.endPeriod)
        assertEquals(3, c.span)
    }

    @Test
    fun `每一天的格子高度之和都等于节次总数`() {
        // 4-5 两份地点不同的「人工智能通识」与「基础英语（一）」分段也要参与
        for (week in 5..16) {
            for (day in 1..7) {
                val blocks = CourseBlocks.ofDay(
                    schedule.coursesOfWeek(week).filter { it.dayOfWeek == day },
                    1,
                    9,
                )
                assertEquals(
                    "第 $week 周 周$day 的格子高度之和不对（列会错位）",
                    9,
                    blocks.sumOf { it.span },
                )
            }
        }
    }

    @Test
    fun `每一天的格子连续覆盖1到9且无重叠`() {
        for (week in 5..16) {
            for (day in 1..7) {
                val blocks = CourseBlocks.ofDay(
                    schedule.coursesOfWeek(week).filter { it.dayOfWeek == day },
                    1,
                    9,
                )
                var expected = 1
                for (b in blocks) {
                    assertEquals("第 $week 周 周$day 有空洞或重叠", expected, b.startPeriod)
                    expected = b.endPeriod + 1
                }
                assertEquals("第 $week 周 周$day 没有覆盖到第 9 节", 10, expected)
            }
        }
    }

    @Test
    fun `分段的同一门课各自成格子不合并`() {
        // 人工智能通识：周二 6-7 节在 7-8 周（JX08-102）与 9-16 周（机房由教师确认）
        // 第 8 周那天只应出现前者，第 9 周只应出现后者，两段的格子各自成立
        val week8 = CourseBlocks.ofDay(
            schedule.coursesOfWeek(8).filter { it.dayOfWeek == 2 },
            1,
            9,
        )
        val ai8 = week8.filter { it.primary?.name == "人工智能通识" }
        assertEquals(1, ai8.size)
        assertEquals("JX08-102", ai8.single().primary?.room)

        val week9 = CourseBlocks.ofDay(
            schedule.coursesOfWeek(9).filter { it.dayOfWeek == 2 },
            1,
            9,
        )
        val ai9 = week9.filter { it.primary?.name == "人工智能通识" }
        assertEquals(1, ai9.size)
        assertEquals("机房由教师确认", ai9.single().primary?.room)
    }

    @Test
    fun `形势与政策只在第7周出现第8周不出格子`() {
        val week7 = CourseBlocks.ofDay(
            schedule.coursesOfWeek(7).filter { it.dayOfWeek == 4 },
            1,
            9,
        )
        assertNotNull(week7.firstOrNull { it.primary?.name == "形势与政策" })

        val week8 = CourseBlocks.ofDay(
            schedule.coursesOfWeek(8).filter { it.dayOfWeek == 4 },
            1,
            9,
        )
        assertTrue(week8.none { it.primary?.name == "形势与政策" })
    }

    // ------------------------------------------------------------ 构造数据

    @Test
    fun `没有课的节次出空格子把网格填满`() {
        val blocks = CourseBlocks.ofDay(
            listOf(course("A", 1, 1, 2), course("B", 1, 5, 6)),
            1,
            8,
        )
        assertEquals(listOf(2, 1, 1, 2, 1, 1), blocks.map { it.span })
        assertTrue(blocks[1].isEmpty)
        assertTrue(blocks[2].isEmpty)
        assertTrue(blocks[4].isEmpty)
        assertTrue(blocks[5].isEmpty)
        assertTrue(!blocks[0].isEmpty && !blocks[3].isEmpty)
        assertEquals(8, blocks.sumOf { it.span })
    }

    @Test
    fun `完全没课时每个节次都是一个空格子`() {
        val blocks = CourseBlocks.ofDay(emptyList(), 3, 6)
        assertEquals(4, blocks.size)
        assertTrue(blocks.all { it.isEmpty })
        assertEquals(listOf(3, 4, 5, 6), blocks.map { it.startPeriod })
    }

    @Test
    fun `首节有课也不影响前面的节次`() {
        val blocks = CourseBlocks.ofDay(listOf(course("A", 1, 1, 4)), 1, 6)
        assertEquals(listOf(4, 1, 1), blocks.map { it.span })
        assertEquals(6, blocks.sumOf { it.span })
    }

    @Test
    fun `同一天节次真重叠时并进同一个格子而不是丢失`() {
        val a = course("A", 1, 3, 5)
        val b = course("B", 1, 4, 4)
        val blocks = CourseBlocks.ofDay(listOf(a, b), 1, 6)
        val merged = blocks.single { it.startPeriod == 3 }
        assertEquals(5, merged.endPeriod)
        assertEquals(setOf("A", "B"), merged.courses.map { it.name }.toSet())
        // 一门都不能丢
        assertEquals(2, blocks.flatMap { it.courses }.size)
        assertEquals(6, blocks.sumOf { it.span })
    }

    @Test
    fun `起点超出范围的课被忽略而不是撑破网格`() {
        val blocks = CourseBlocks.ofDay(
            listOf(course("A", 1, 1, 2), course("越界", 1, 15, 16)),
            1,
            4,
        )
        assertEquals(4, blocks.sumOf { it.span })
        assertTrue(blocks.none { it.primary?.name == "越界" })
    }

    @Test
    fun `跨到范围外的课被裁到边界内`() {
        // 起始节次在范围内、结束节次超出（数据脏），不能画出第 5 节以外
        val blocks = CourseBlocks.ofDay(listOf(course("A", 1, 3, 9)), 1, 4)
        val b = blocks.single { it.startPeriod == 3 }
        assertEquals(4, b.endPeriod)
        assertEquals(4, blocks.sumOf { it.span })
    }

    @Test
    fun `每一门课在格子序列里恰好出现一次`() {
        for (week in 5..16) {
            val courses = schedule.coursesOfWeek(week)
            val placed = ArrayList<Course>()
            for (day in 1..7) {
                val dayCourses = courses.filter { it.dayOfWeek == day }
                placed += CourseBlocks.ofDay(dayCourses, 1, 9).flatMap { it.courses }
            }
            assertEquals("第 $week 周有课被漏掉或重复", courses.size, placed.size)
            assertEquals(courses.toSet(), placed.toSet())
        }
    }
}
