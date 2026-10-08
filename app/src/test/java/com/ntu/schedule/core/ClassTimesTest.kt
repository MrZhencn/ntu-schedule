package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住作息时间表 —— 这是本项目最容易做错、也最难靠肉眼看出来的一处。
 *
 * 学校的规则是「按日历月份切换」：5–9 月夏令、10–4 月冬令。所以同一个秋季学期里，
 * 9 月的第 6 节 14:00 开始，10 月的第 6 节 13:30 开始。任何「按开学月份选一整套表」
 * 的实现都会在 10 月以后把第 6–12 节全部算错 30 分钟，而且错得很隐蔽
 * （时间看起来仍然合理，只是提前了半小时）。
 */
class ClassTimesTest {

    @Test
    fun `两套时间表都是12节`() {
        assertEquals(12, ClassTimes.WINTER.size)
        assertEquals(12, ClassTimes.SUMMER.size)
    }

    @Test
    fun `第1到5节两季完全相同`() {
        // 上午的课不随季节变，所以任何「把上午也切了」的实现都是错的
        for (p in 1..5) {
            assertEquals(
                "第 $p 节在冬令与夏令应一致",
                ClassTimes.WINTER[p - 1],
                ClassTimes.SUMMER[p - 1],
            )
        }
    }

    @Test
    fun `第1节是07点50分`() {
        // 第三方脚本里常写成 08:00，实际学校官网是 07:50
        assertEquals("07:50", ClassTimes.slotOf(1, 10)?.start)
        assertEquals("08:30", ClassTimes.slotOf(1, 10)?.end)
        assertEquals("07:50", ClassTimes.slotOf(1, 5)?.start)
    }

    @Test
    fun `夏令月份5到9月`() {
        for (m in 5..9) {
            assertTrue("$m 月应为夏令", ClassTimes.isSummer(m))
            assertEquals(ClassTimes.SUMMER, ClassTimes.tableForMonth(m))
        }
    }

    @Test
    fun `冬令月份10到4月`() {
        for (m in listOf(10, 11, 12, 1, 2, 3, 4)) {
            assertFalse("$m 月应为冬令", ClassTimes.isSummer(m))
            assertEquals(ClassTimes.WINTER, ClassTimes.tableForMonth(m))
        }
    }

    @Test
    fun `切换发生在9月与10月之间`() {
        // 这两条是本测试的核心：相邻两个月，同一节次差 30 分钟
        assertEquals("14:00", ClassTimes.slotOf(6, 9)?.start)
        assertEquals("14:40", ClassTimes.slotOf(6, 9)?.end)
        assertEquals("13:30", ClassTimes.slotOf(6, 10)?.start)
        assertEquals("14:10", ClassTimes.slotOf(6, 10)?.end)

        assertEquals("20:40", ClassTimes.slotOf(12, 9)?.start)
        assertEquals("21:20", ClassTimes.slotOf(12, 9)?.end)
        assertEquals("20:10", ClassTimes.slotOf(12, 10)?.start)
        assertEquals("20:50", ClassTimes.slotOf(12, 10)?.end)
    }

    @Test
    fun `切换同样发生在4月与5月之间`() {
        assertEquals("13:30", ClassTimes.slotOf(6, 4)?.start)
        assertEquals("14:00", ClassTimes.slotOf(6, 5)?.start)
    }

    @Test
    fun `秋季学期会在期中从夏令切到冬令`() {
        // 用真实校历日期验证「按日期取表」的效果，而不是只比较月份常量。
        // 2026-2027 学年第 1 学期第 1 周周一为 2026-08-31。
        val sept = "2026-09-15"   // 第 3 周，周二，仍在夏令
        val oct = "2026-10-08"    // 第 6 周，周四，已切冬令
        assertEquals(9, DateUtil.monthOf(sept))
        assertEquals(10, DateUtil.monthOf(oct))

        // 同是「第 6 节」，9 月与 10 月的上课时间不同 —— 这正是按学期选表的实现会做错的地方
        assertEquals("14:00", ClassTimes.slotOf(6, DateUtil.monthOf(sept))?.start)
        assertEquals("13:30", ClassTimes.slotOf(6, DateUtil.monthOf(oct))?.start)
    }

    @Test
    fun `节次越界返回null而不是抛异常`() {
        // 教务数据脏的时候不应该让整个界面崩掉
        assertNull(ClassTimes.slotOf(0, 10))
        assertNull(ClassTimes.slotOf(-1, 10))
        assertNull(ClassTimes.slotOf(13, 10))
        assertNull(ClassTimes.slotOf(99, 10))
    }

    @Test
    fun `rangeText拼接起止时间`() {
        assertEquals("10:30-11:10", ClassTimes.rangeText(4, 10))
        assertEquals("14:00-14:40", ClassTimes.rangeText(6, 6))
        assertEquals("13:30-14:10", ClassTimes.rangeText(6, 12))
        assertEquals("", ClassTimes.rangeText(13, 10))
    }

    @Test
    fun `全天节次时间单调递增且不重叠`() {
        for (month in listOf(3, 7)) {
            val table = ClassTimes.tableForMonth(month)
            for (i in 0 until table.size - 1) {
                val cur = minute(table[i].end)
                val next = minute(table[i + 1].start)
                assertTrue(
                    "$month 月第 ${i + 1} 节(${table[i].end}) 与第 ${i + 2} 节(${table[i + 1].start}) 应有间隔",
                    next > cur,
                )
            }
        }
    }

    private fun minute(hhmm: String): Int {
        val (h, m) = hhmm.split(":")
        return h.toInt() * 60 + m.toInt()
    }
}
