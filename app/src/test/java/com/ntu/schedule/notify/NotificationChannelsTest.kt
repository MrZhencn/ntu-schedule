package com.ntu.schedule.notify

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 渠道重要性 → 人话 的映射。
 *
 * 这段逻辑看着简单，但它决定了自检面板会不会把「静音、根本不弹横幅」说成「一切正常」——
 * 那是最坏的一种错：用户按面板的绿灯放心了，第二天上课却没有提醒。
 */
class NotificationChannelsTest {

    @Test
    fun `常量与系统Importance数值一致`() {
        // NotificationManager 的 IMPORTANCE_* 是编译期常量，比较在编译时就定下来了，
        // 这里再断言一次是为了防止有人手误改成本地数值后无人发现。
        assertEquals(NotificationManager.IMPORTANCE_NONE, NotificationChannels.IMPORTANCE_NONE)
        assertEquals(NotificationManager.IMPORTANCE_MIN, NotificationChannels.IMPORTANCE_MIN)
        assertEquals(NotificationManager.IMPORTANCE_LOW, NotificationChannels.IMPORTANCE_LOW)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, NotificationChannels.IMPORTANCE_DEFAULT)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, NotificationChannels.IMPORTANCE_HIGH)
        assertEquals(NotificationManager.IMPORTANCE_MAX, NotificationChannels.IMPORTANCE_MAX)
    }

    @Test
    fun `关闭的重要性说成不会提醒`() {
        assertEquals("已关闭，不会提醒", NotificationChannels.importanceText(NotificationChannels.IMPORTANCE_NONE))
    }

    @Test
    fun `静音的重要性明确说不弹横幅`() {
        // MIN 与 LOW 在系统里都算「静音」，对横幅而言都是弹不出来。
        assertEquals(
            "静音，只进通知栏，不弹横幅",
            NotificationChannels.importanceText(NotificationChannels.IMPORTANCE_MIN),
        )
        assertEquals(
            "静音，只进通知栏，不弹横幅",
            NotificationChannels.importanceText(NotificationChannels.IMPORTANCE_LOW),
        )
    }

    @Test
    fun `默认的重要性有声音但不弹横幅`() {
        assertEquals(
            "有提示音，但不弹横幅",
            NotificationChannels.importanceText(NotificationChannels.IMPORTANCE_DEFAULT),
        )
    }

    @Test
    fun `高与最高的重要性才算横幅`() {
        assertEquals("横幅提醒（推荐）", NotificationChannels.importanceText(NotificationChannels.IMPORTANCE_HIGH))
        assertEquals("横幅提醒（推荐）", NotificationChannels.importanceText(NotificationChannels.IMPORTANCE_MAX))
    }

    @Test
    fun `只有达到HIGH才算能弹横幅`() {
        // 逐档扫一遍：分界线必须正好落在 HIGH 上，早一档或晚一档都是 bug。
        val bannerReady = (0..NotificationChannels.IMPORTANCE_MAX)
            .filter { NotificationChannels.importanceText(it) == "横幅提醒（推荐）" }
        assertEquals(
            listOf(NotificationChannels.IMPORTANCE_HIGH, NotificationChannels.IMPORTANCE_MAX),
            bannerReady,
        )
    }

    @Test
    fun `异常数值不会崩也不会被说成能弹横幅`() {
        assertEquals("已关闭，不会提醒", NotificationChannels.importanceText(-1))
        // 系统以后若加了新的更高档位，应当继续算作「能弹横幅」，而不是掉进 else 之外的坑里。
        assertEquals("横幅提醒（推荐）", NotificationChannels.importanceText(99))
    }

    @Test
    fun `每一档的文案都各不相同`() {
        val texts = listOf(
            NotificationChannels.IMPORTANCE_NONE,
            NotificationChannels.IMPORTANCE_LOW,
            NotificationChannels.IMPORTANCE_DEFAULT,
            NotificationChannels.IMPORTANCE_HIGH,
        ).map { NotificationChannels.importanceText(it) }
        assertEquals(texts.size, texts.toSet().size)
        assertNotEquals(texts[0], texts[3])
    }

    @Test
    fun `渠道id与旧渠道不同`() {
        // 旧渠道的重要性被系统冻结、改不了，所以必须换新 id 重建。
        assertNotEquals(NotificationChannels.LEGACY_REMINDER, NotificationChannels.REMINDER)
    }

    @Test
    fun `测试通知的id不与真实提醒的槽位冲突`() {
        // 真实提醒用 0x4E5450 + 序号（见 ReminderReceiver），测试横幅固定用 0x4E544E。
        val reminderIds = (0 until 32).map { 0x4E5450 + it }
        assertEquals(false, ReminderNotification.TEST_NOTIFY_ID in reminderIds)
    }
}
