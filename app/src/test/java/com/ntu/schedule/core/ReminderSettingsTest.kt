package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReminderSettings] 的合法档位与存档往返。
 *
 * 这里最要紧的一条是「**不认识的档位一律退回默认**」：存档是用户手机上的一个普通 json，
 * 崩了的话 App 一打开就起不来，那时用户看不到任何提示，只能卸载重装。
 */
class ReminderSettingsTest {

    @Test
    fun `默认就是一小时`() {
        assertEquals(60, ReminderSettings.DEFAULT.leadMinutes)
        assertEquals(ReminderPlanner.DEFAULT_LEAD_MINUTES, ReminderSettings.DEFAULT.leadMinutes)
    }

    @Test
    fun `需求里的五档原样保留`() {
        assertEquals(listOf(10, 20, 30, 60, 120), ReminderPlanner.PRESET_LEAD_MINUTES)
        for (minutes in ReminderPlanner.PRESET_LEAD_MINUTES) {
            assertEquals("第 $minutes 分钟这一档被改掉了", minutes, ReminderSettings.sanitize(minutes))
        }
    }

    @Test
    fun `不在预设里的值一律退回一小时`() {
        assertEquals(60, ReminderSettings.sanitize(0))
        assertEquals(60, ReminderSettings.sanitize(15))
        assertEquals(60, ReminderSettings.sanitize(45))
        assertEquals(60, ReminderSettings.sanitize(90))
        assertEquals(60, ReminderSettings.sanitize(-30))
        assertEquals(60, ReminderSettings.sanitize(1440))
    }

    @Test
    fun `withLead 也会过一遍校验`() {
        assertEquals(20, ReminderSettings.DEFAULT.withLead(20).leadMinutes)
        assertEquals(120, ReminderSettings.DEFAULT.withLead(120).leadMinutes)
        // 界面上点不到的档位，从别处传进来也不能落进存档
        assertEquals(60, ReminderSettings.DEFAULT.withLead(7).leadMinutes)
    }

    @Test
    fun `提前量文案跟着走`() {
        assertEquals("1 小时", ReminderSettings.DEFAULT.leadText)
        assertEquals("10 分钟", ReminderSettings(10).leadText)
        assertEquals("30 分钟", ReminderSettings(30).leadText)
        assertEquals("2 小时", ReminderSettings(120).leadText)
    }

    @Test
    fun `五档存了再读都还是原来那一档`() {
        for (minutes in ReminderPlanner.PRESET_LEAD_MINUTES) {
            val text = ReminderSettings.toJson(ReminderSettings(minutes))
            assertEquals("第 $minutes 分钟这一档没能往返", minutes, ReminderSettings.fromJson(text).leadMinutes)
        }
    }

    @Test
    fun `存档里是脏数据就退回一小时`() {
        assertEquals(60, ReminderSettings.fromJson(null).leadMinutes)
        assertEquals(60, ReminderSettings.fromJson("").leadMinutes)
        assertEquals(60, ReminderSettings.fromJson("   ").leadMinutes)
        assertEquals(60, ReminderSettings.fromJson("不是 json").leadMinutes)
        assertEquals(60, ReminderSettings.fromJson("{}").leadMinutes)
        assertEquals(60, ReminderSettings.fromJson("[1,2,3]").leadMinutes)
        assertEquals(60, ReminderSettings.fromJson("""{"v":1,"leadMinutes":"半小时"}""").leadMinutes)
        // 类型对、甚至数值也「像个提前量」，但不认识的档位不能原样信
        assertEquals(60, ReminderSettings.fromJson("""{"v":1,"leadMinutes":45}""").leadMinutes)
    }

    @Test
    fun `存档里认得出的档位要读出来`() {
        assertEquals(10, ReminderSettings.fromJson("""{"v":1,"leadMinutes":10}""").leadMinutes)
        assertEquals(120, ReminderSettings.fromJson("""{"v":1,"leadMinutes":120}""").leadMinutes)
        // 教务那套接口习惯把数字写成字符串，这里也照收
        assertEquals(30, ReminderSettings.fromJson("""{"leadMinutes":"30"}""").leadMinutes)
    }

    @Test
    fun `存档带版本号与字段名`() {
        val text = ReminderSettings.toJson(ReminderSettings.DEFAULT)
        assertTrue(text, text.contains("\"v\""))
        assertTrue(text, text.contains("\"leadMinutes\""))
        assertTrue(text, text.contains("60"))
    }

    @Test
    fun `写存档时也过一遍校验`() {
        // 直接构造一个越界的对象（绕过 withLead）也不能把它写进存档
        val text = ReminderSettings.toJson(ReminderSettings(leadMinutes = 999))
        assertEquals(60, ReminderSettings.fromJson(text).leadMinutes)
    }

    @Test
    fun `读出来的设置能直接喂给排程`() {
        // 这条是接口契约：ReminderScheduler 就是 load().leadMinutes 直接传给 plan()
        val plans = ReminderPlanner.plan(
            Schedule(),
            "2026-10-08",
            daysAhead = 1,
            leadMinutes = ReminderSettings.DEFAULT.leadMinutes,
        )
        assertTrue(plans.isEmpty())
    }
}
