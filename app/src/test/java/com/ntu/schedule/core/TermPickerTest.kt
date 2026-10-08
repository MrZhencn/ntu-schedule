package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [TermPicker] 的唯一职责是**排序**：把「用户最可能想看的那一学期」排到候选列表最前面，
 * 并保证候选不重复、不带空学期、不超过上限。
 *
 * 它不是过滤器 —— 真正有没有课只有服务器知道，所以这里只测顺序与边界，
 * 尤其在「课表页默认学期滞后一整年」那个真实事故上钉一颗钉子：页面写死默认
 * `2025-2026 第 2 学期`，但学生的课在 `2026-2027 第 1 学期`，候选必须把后者排第一。
 */
class TermPickerTest {

    /** 构造某个时区里某个时刻的毫秒数。时区写死，免得用例跟着跑测机器的时区变。 */
    private fun millisAt(timeZoneId: String, year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(timeZoneId))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, 0, 0)
        return calendar.timeInMillis
    }

    private fun shanghai(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        millisAt("Asia/Shanghai", year, month, day, hour)

    // ------------------------------------------------------------ 真实事故

    @Test
    fun `页面默认是上一学年时，真正的当前学期排第一`() {
        val list = TermPicker.candidates("2025", "12", shanghai(2026, 10, 8), "Asia/Shanghai")
        assertEquals(
            listOf(
                TermPicker.Term("2026", "3"),
                TermPicker.Term("2025", "12"),
                TermPicker.Term("2026", "12"),
                TermPicker.Term("2025", "3"),
            ),
            list,
        )
    }

    @Test
    fun `页面默认本来就对时，排第一的还是当前学期`() {
        val list = TermPicker.candidates("2026", "3", shanghai(2026, 10, 8), "Asia/Shanghai")
        assertEquals(
            listOf(
                TermPicker.Term("2026", "3"),
                TermPicker.Term("2026", "12"),
                TermPicker.Term("2025", "12"),
                TermPicker.Term("2025", "3"),
            ),
            list,
        )
    }

    @Test
    fun `页面默认值排在按日期算出来的当前学期后面`() {
        // 页面说 2023，日期说是 2026：页面那个仍然在候选里（可能是短学期之类的特例），
        // 但不能挡住当前学期
        val list = TermPicker.candidates("2023", "16", shanghai(2026, 10, 8), "Asia/Shanghai")
        assertEquals(TermPicker.Term("2026", "3"), list.first())
        assertTrue(list.contains(TermPicker.Term("2023", "16")))
    }

    // ------------------------------------------------------------ 日期推算

    @Test
    fun `学年从九月开始算`() {
        fun firstAt(month: Int) =
            TermPicker.candidates("", "", shanghai(2026, month, 15), "Asia/Shanghai").first()

        assertEquals(TermPicker.Term("2026", "3"), firstAt(9))
        assertEquals(TermPicker.Term("2026", "3"), firstAt(12))
        // 1 月还是上个自然年开的那个学年的第一学期（期末与寒假都在里面）
        assertEquals(TermPicker.Term("2025", "3"), firstAt(1))
        // 2 月到 8 月算的是那个学年的第二学期（含暑假）
        assertEquals(TermPicker.Term("2025", "12"), firstAt(2))
        assertEquals(TermPicker.Term("2025", "12"), firstAt(6))
        assertEquals(TermPicker.Term("2025", "12"), firstAt(8))
    }

    @Test
    fun `指定时区后按那个时区判断月份`() {
        // 上海 9 月 1 日 0 点，在 UTC 还是 8 月 31 日 —— 学年因此差了一整年
        val instant = shanghai(2026, 9, 1, hour = 0)
        assertEquals(
            TermPicker.Term("2026", "3"),
            TermPicker.candidates("", "", instant, "Asia/Shanghai").first(),
        )
        assertEquals(
            TermPicker.Term("2025", "12"),
            TermPicker.candidates("", "", instant, "UTC").first(),
        )
    }

    @Test
    fun `不传时区也能算出候选`() {
        val list = TermPicker.candidates("2025", "12", shanghai(2026, 10, 8))
        assertEquals(4, list.size)
        assertTrue(list.all { it.isUsable })
    }

    // ------------------------------------------------------------ 列表本身

    @Test
    fun `页面没有默认值时不产生空学期`() {
        val list = TermPicker.candidates("", "", shanghai(2026, 10, 8), "Asia/Shanghai")
        assertEquals(
            listOf(
                TermPicker.Term("2026", "3"),
                TermPicker.Term("2026", "12"),
                TermPicker.Term("2025", "12"),
                TermPicker.Term("2025", "3"),
            ),
            list,
        )
        assertTrue(list.none { it.year.isBlank() || it.term.isBlank() })
    }

    @Test
    fun `只填了一半的页面默认值也不进来`() {
        // 只有学年没有学期，拿去请求等于问一个不存在的学期
        assertTrue(TermPicker.candidates("2025", "", shanghai(2026, 10, 8), "Asia/Shanghai")
            .none { it.term.isBlank() })
        assertTrue(TermPicker.candidates("", "12", shanghai(2026, 10, 8), "Asia/Shanghai")
            .none { it.year.isBlank() })
    }

    @Test
    fun `候选不重复`() {
        // 这种排布下，「上一学年第二学期」与页面默认值本来就是同一个学期
        val list = TermPicker.candidates("2025", "12", shanghai(2026, 10, 8), "Asia/Shanghai")
        assertEquals(list.size, list.distinct().size)
        assertEquals(1, list.count { it == TermPicker.Term("2025", "12") })
    }

    @Test
    fun `候选数量有上限`() {
        val at = shanghai(2026, 10, 8)
        assertEquals(2, TermPicker.candidates("2025", "12", at, "Asia/Shanghai", limit = 2).size)
        // 上限再小也得给一个，否则调用方会拿着空列表去请求
        assertEquals(1, TermPicker.candidates("2025", "12", at, "Asia/Shanghai", limit = 0).size)
        assertEquals(1, TermPicker.candidates("2025", "12", at, "Asia/Shanghai", limit = -3).size)
    }

    @Test
    fun `默认上限够放下上一学年的两个学期`() {
        // 少了的话「新学期还没排课，退回上学期」这条兜底就断了
        val list = TermPicker.candidates("2025", "12", shanghai(2026, 10, 8), "Asia/Shanghai")
        assertTrue(list.size <= TermPicker.DEFAULT_LIMIT)
        assertTrue(list.contains(TermPicker.Term("2025", "12")))
        assertTrue(list.contains(TermPicker.Term("2025", "3")))
    }

    // ------------------------------------------------------------ 给人看的说法

    @Test
    fun `学期名是人能看懂的说法`() {
        assertEquals("2026-2027 学年 第 1 学期", TermPicker.Term("2026", "3").label)
        assertEquals("2026-2027 学年 第 2 学期", TermPicker.Term("2026", "12").label)
        assertEquals("2026-2027 学年 第 3 学期", TermPicker.Term("2026", "16").label)
    }

    @Test
    fun `学年读不出来时也要回退出一个能认的说法`() {
        // 提示里绝不能出现空白 —— 用户会以为是 App 坏了
        val label = TermPicker.Term("", "3").label
        assertTrue(label.isNotBlank())
        assertTrue(label.contains("3"))
        assertTrue(TermPicker.Term("2026", "7").label.contains("7"))
    }

    @Test
    fun `学期码常量与正方一致`() {
        assertEquals("3", TermPicker.FIRST)
        assertEquals("12", TermPicker.SECOND)
    }
}
