package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住周次文本解析。
 *
 * 教务接口的 `zcd` 字段写法不统一（实测至少 8 种形态），写错的表现是
 * **课程在一部分周次里凭空消失或多出来**，而用户很难意识到是解析器的锅。
 *
 * 另一条同样重要的约定：**解析不出数字时按「全周」兜底，而不是丢弃这门课**。
 * 宁可多显示，也不让一门课从课表里消失。
 */
class WeekParserTest {

    @Test
    fun `标准区间5到18周`() {
        val r = WeekParser.parse("5-18周")
        assertEquals((5..18).toList(), r.weeks)
        assertEquals(WeekType.ALL, r.weekType)
        assertEquals("", r.note)
    }

    @Test
    fun `波浪号与中文连接符都能当区间符`() {
        assertEquals((1..16).toList(), WeekParser.parse("1~16周").weeks)
        assertEquals((1..16).toList(), WeekParser.parse("1—16周").weeks)
        assertEquals((1..16).toList(), WeekParser.parse("1－16周").weeks)
        assertEquals((1..16).toList(), WeekParser.parse("1至16周").weeks)
    }

    @Test
    fun `带第字与不带周字都能解析`() {
        assertEquals((1..16).toList(), WeekParser.parse("第1-16周").weeks)
        assertEquals((1..16).toList(), WeekParser.parse("1-16").weeks)
        assertEquals((1..16).toList(), WeekParser.parse("第 1 - 16 周").weeks)
    }

    @Test
    fun `离散周次7周15周`() {
        // 真实数据里「形势与政策」就是 zcd="7周,15周"，只有这两周上课
        val r = WeekParser.parse("7周,15周")
        assertEquals(listOf(7, 15), r.weeks)
        assertEquals(WeekType.ALL, r.weekType)
    }

    @Test
    fun `离散列表1到7的奇数周`() {
        assertEquals(listOf(1, 3, 5, 7), WeekParser.parse("1,3,5,7周").weeks)
    }

    @Test
    fun `中文逗号顿号分号都当作分隔符`() {
        assertEquals(listOf(7, 15), WeekParser.parse("7周，15周").weeks)
        assertEquals(listOf(7, 15), WeekParser.parse("7周、15周").weeks)
        assertEquals(listOf(7, 15), WeekParser.parse("7周；15周").weeks)
        assertEquals(listOf(7, 15), WeekParser.parse("7周;15周").weeks)
    }

    @Test
    fun `单周双周的四种写法`() {
        val odd = listOf("1-16周(单)", "1-16单周", "1-16周（单）")
        for (t in odd) {
            val r = WeekParser.parse(t)
            assertEquals("$t 的周次", (1..16).toList(), r.weeks)
            assertEquals("$t 的类型", WeekType.ODD, r.weekType)
        }
        val even = listOf("1-16周(双)", "1-16双周")
        for (t in even) {
            val r = WeekParser.parse(t)
            assertEquals("$t 的周次", (1..16).toList(), r.weeks)
            assertEquals("$t 的类型", WeekType.EVEN, r.weekType)
        }
    }

    @Test
    fun `地点标记符被保留到note而不是丢掉`() {
        // 标记符表示「该周在另一地点上课」，丢了用户会以为整学期都在同一栋楼
        val r = WeekParser.parse("5-18周■")
        assertEquals((5..18).toList(), r.weeks)
        assertEquals("■", r.note)

        assertEquals("◆", WeekParser.parse("7周◆").note)
        assertEquals("▲", WeekParser.parse("9周▲").note)
        assertEquals("★", WeekParser.parse("1-16周★").note)
        // 标记符不能干扰周次解析
        assertEquals(listOf(7), WeekParser.parse("7周◆").weeks)
    }

    @Test
    fun `空文本按全周兜底而不是丢弃课程`() {
        val r = WeekParser.parse("", totalWeeks = 19)
        assertEquals((1..19).toList(), r.weeks)
        assertEquals(WeekType.ALL, r.weekType)

        assertEquals((1..19).toList(), WeekParser.parse(null, 19).weeks)
        assertEquals((1..19).toList(), WeekParser.parse("   ", 19).weeks)
    }

    @Test
    fun `只有周字没有数字也按全周兜底`() {
        assertEquals((1..19).toList(), WeekParser.parse("周", totalWeeks = 19).weeks)
        assertEquals((1..19).toList(), WeekParser.parse("第周", totalWeeks = 19).weeks)
    }

    @Test
    fun `只有标记符没有数字也按全周兜底且保留标记`() {
        val r = WeekParser.parse("■", totalWeeks = 19)
        assertEquals((1..19).toList(), r.weeks)
        assertEquals("■", r.note)
    }

    @Test
    fun `超出总周数的部分被裁掉`() {
        // 19 周的学期里出现 1-25 周，应裁到 1-19
        assertEquals((1..19).toList(), WeekParser.parse("1-25周", totalWeeks = 19).weeks)
    }

    @Test
    fun `全部越界时保留原值以免课程凭空消失`() {
        // 20-22 周整体超出 19 周的学期：裁剪后会空，此时退回原值。
        // 这些周次在本学期永远不会 active，课程显示不出来但也不会被误判成「全周」。
        val r = WeekParser.parse("20-22周", totalWeeks = 19)
        assertEquals(listOf(20, 21, 22), r.weeks)
    }

    @Test
    fun `结果升序去重`() {
        assertEquals(listOf(3, 5, 7), WeekParser.parse("7周,3周,5周,3周").weeks)
        assertEquals(listOf(1, 2, 3, 9), WeekParser.parse("1-3周,9周,2周").weeks)
    }

    @Test
    fun `非法输入不会抛异常`() {
        // 脏数据不能把整个导入流程带崩
        for (t in listOf("", "  ", "周", "abc", "1-", "-5", "a-b周", ",,,", "1--2周")) {
            WeekParser.parse(t, totalWeeks = 19)
        }
    }

    @Test
    fun `逆序区间被忽略而不是产生空集或崩溃`() {
        // b >= a 才接受，所以 "18-5周" 里没有合法区间，落到「无数字」兜底
        val r = WeekParser.parse("18-5周", totalWeeks = 19)
        assertTrue("逆序区间不应产生任何合法周次", r.weeks.isNotEmpty())
    }

    @Test
    fun `单双周判定`() {
        val weeks = (1..10).toList()
        assertTrue(WeekParser.isActiveIn(weeks, WeekType.ALL, 4))
        assertTrue(WeekParser.isActiveIn(weeks, WeekType.ODD, 3))
        assertFalse(WeekParser.isActiveIn(weeks, WeekType.ODD, 4))
        assertTrue(WeekParser.isActiveIn(weeks, WeekType.EVEN, 4))
        assertFalse(WeekParser.isActiveIn(weeks, WeekType.EVEN, 3))
        // 不在周次列表里的一律不上课，与单双周无关
        assertFalse(WeekParser.isActiveIn(weeks, WeekType.ALL, 11))
    }

    @Test
    fun `formatWeeks把连续周次压成区间`() {
        assertEquals("1-3,7-8周", WeekParser.formatWeeks(listOf(1, 2, 3, 7, 8)))
        assertEquals("5周", WeekParser.formatWeeks(listOf(5)))
        assertEquals("1-16周", WeekParser.formatWeeks((1..16).toList()))
        assertEquals("", WeekParser.formatWeeks(emptyList()))
        // 乱序输入也要先排序
        assertEquals("1-3周", WeekParser.formatWeeks(listOf(3, 1, 2)))
    }

    @Test
    fun `format拼接多个分段并带上标记`() {
        val one = WeekParser.format(listOf((1..8).toList() to ""))
        assertEquals("1-8周", one)

        val two = WeekParser.format(
            listOf((1..8).toList() to "", (9..16).toList() to "■"),
        )
        assertEquals("1-8周，9-16周■", two)

        assertEquals("", WeekParser.format(emptyList()))
    }

    @Test
    fun `真实数据的周次文本全部可解析`() {
        // 取自 tools/out 里抓到的真实 kbList
        val cases = mapOf(
            "5-18周" to (5..18).toList(),
            "5-12周" to (5..12).toList(),
            "5-16周" to (5..16).toList(),
            "7-8周" to listOf(7, 8),
            "9-16周" to (9..16).toList(),
            "6-13周" to (6..13).toList(),
            "7-16周" to (7..16).toList(),
            "7周,15周" to listOf(7, 15),
            "15周" to listOf(15),
            "5-14周" to (5..14).toList(),
        )
        for ((text, expected) in cases) {
            assertEquals("解析 $text", expected, WeekParser.parse(text, totalWeeks = 19).weeks)
        }
    }

    @Test
    fun `区间加单周混排的周次也能解析`() {
        // 正方教务的周次列有不止一种写法：除了纯区间，还有「区间 + 单周」混排，
        // 中间用英文逗号分隔、末尾带「周」字。南通大学抓到的数据里没有这种混排写法，
        // 所以单独钉一条 —— 解析器本来就能处理，只是从没被测过。
        val cases = mapOf(
            "3-4周,7周,10-18周" to listOf(3, 4, 7) + (10..18).toList(),
            "1-2周,5-6周" to listOf(1, 2, 5, 6),
            "8周" to listOf(8),
            "9-11周,13-15周,17周" to (9..11).toList() + (13..15).toList() + listOf(17),
        )
        for ((text, expected) in cases) {
            assertEquals("解析 $text", expected, WeekParser.parse(text, totalWeeks = 19).weeks)
        }
    }
}
