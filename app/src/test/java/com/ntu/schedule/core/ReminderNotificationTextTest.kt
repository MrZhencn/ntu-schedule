package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知里那几行字的拼装。
 *
 * 这些字符串原来是直接写在 Android 的通知代码里的，既没人验、也跑不了单元测试；
 * 提到 [ReminderPlanner] 之后就成了纯函数。之所以值得单独钉住，是因为横幅的第二行
 * 会被系统截断成一行多，字写多一个字就看不全 —— 哪些信息放进收起态、哪些放进展开态，是个明确的设计决定。
 */
class ReminderNotificationTextTest {

    @Test
    fun `收起态的正文是课名时间与地点`() {
        assertEquals(
            "《高等数学B（一）》 07:50-09:20 · JX08-109",
            ReminderPlanner.body("高等数学B（一）", "07:50", "09:20", "JX08-109"),
        )
    }

    @Test
    fun `收起态不写教师名`() {
        // 横幅第二行会被截断，教师名放进去只会挤掉地点；想看教师名应该下拉展开。
        val text = ReminderPlanner.body("高等数学B（一）", "07:50", "09:20", "JX08-109")
        assertFalse(text.contains("张明远"))
    }

    @Test
    fun `没有地点时说地点待定而不是留空`() {
        assertEquals(
            "《体育（一）》 11:20-12:00 · 地点待定",
            ReminderPlanner.body("体育（一）", "11:20", "12:00", ""),
        )
    }

    @Test
    fun `拿不到下课时间时只写开始时间`() {
        assertEquals(
            "《形势与政策》 15:20 · JX08-106",
            ReminderPlanner.body("形势与政策", "15:20", "", "JX08-106"),
        )
    }

    @Test
    fun `展开态是三行课名时间地点`() {
        assertEquals(
            "《高等数学B（一）》\n07:50 - 09:20 · 第 4-5 节\nJX08-109 · 张明远",
            ReminderPlanner.bigText("高等数学B（一）", "07:50", "09:20", "4-5", "JX08-109", "张明远"),
        )
    }

    @Test
    fun `展开态恰好三行`() {
        val text = ReminderPlanner.bigText("大学物理", "13:30", "15:00", "6-7", "JX07-105", "张三")
        assertEquals(3, text.lines().size)
    }

    @Test
    fun `展开态缺少节次时不写第几节`() {
        val text = ReminderPlanner.bigText("人工智能通识", "14:00", "15:30", "", "JX08-102", "李四")
        assertEquals("《人工智能通识》\n14:00 - 15:30\nJX08-102 · 李四", text)
        assertFalse(text.contains("第"))
    }

    @Test
    fun `展开态缺少地点与教师时只写地点待定`() {
        val text = ReminderPlanner.bigText("体育（一）", "11:20", "12:00", "5", "", "")
        assertEquals("《体育（一）》\n11:20 - 12:00 · 第 5 节\n地点待定", text)
    }

    @Test
    fun `展开态缺下课时间时不留悬空的连字符`() {
        val text = ReminderPlanner.bigText("形势与政策", "15:20", "", "8-9", "JX08-106", "")
        assertEquals("《形势与政策》\n15:20 · 第 8-9 节\nJX08-106", text)
        assertFalse(text.contains("- "))
        assertFalse(text.contains(" -"))
    }

    @Test
    fun `同一份数据在收起态与展开态里的信息一致`() {
        // 两处各拼各的，很容易改了一个忘了另一个；这里用真实课程做一次交叉核对。
        val course = "基础英语（一）"
        val start = "09:35"
        val end = "11:10"
        val room = "JX08-514（语音室）"
        val body = ReminderPlanner.body(course, start, end, room)
        val big = ReminderPlanner.bigText(course, start, end, "3-4", room, "王五")
        assertTrue(big.startsWith("《$course》"))
        assertTrue(body.contains(start))
        assertTrue(big.contains(start))
        assertTrue(body.endsWith(room))
        assertTrue(big.endsWith("王五"))
        assertTrue(big.contains(room))
    }

    @Test
    fun `标题写清提前量`() {
        assertEquals("还有 1 小时上课", ReminderPlanner.title())
        assertEquals("还有 30 分钟上课", ReminderPlanner.title(30))
        assertEquals(60, ReminderPlanner.DEFAULT_LEAD_MINUTES)
    }
}
