package com.ntu.schedule.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ntu.schedule.MainActivity
import com.ntu.schedule.R

/**
 * 提醒通知的构造与投递。
 *
 * 目标是「像 QQ / 微信消息那样从屏幕顶部弹出来」，也就是 Android 说的 **heads-up（横幅）通知**。
 * 原生 Android 上横幅要同时满足：渠道重要性 ≥ HIGH、通知带声音或有震动、锁屏可见性 PUBLIC。
 * 这里逐条落实，并把「为什么」写在代码旁边 —— 少任何一条，通知就只会静默躺进通知栏，
 * 用户在锁屏时根本看不到，这正是最容易出、又最难察觉的一类 bug。
 *
 * 上层（[ReminderReceiver]）只负责从闹钟 Intent 里取字段，拼字全部交给
 * [com.ntu.schedule.core.ReminderPlanner] 里的纯函数，那部分有单元测试兜着。
 */
object ReminderNotification {

    /** 「发一条测试横幅」用的通知 id，与真实提醒的槽位 id 不重叠。 */
    const val TEST_NOTIFY_ID = 0x4E544E // "NTN"

    /** Android 8.0 之前没有渠道，只能在通知上直接写死声音与震动节奏。 */
    private val PRE_O_VIBRATION = longArrayOf(0L, 260L, 180L, 260L)

    /**
     * 发出提醒。返回 false 表示系统里通知权限被关掉了，`notify` 会被静默丢弃，
     * 界面据此提示用户去开启（而不是假装提醒已经发出去）。
     */
    fun post(
        context: Context,
        title: String,
        text: String,
        bigText: String,
        notifyId: Int,
    ): Boolean {
        NotificationChannels.ensure(context)
        return runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            manager.notify(notifyId, build(context, title, text, bigText))
            true
        }.getOrDefault(false)
    }

    /** 立刻发一条内容固定的测试横幅，让用户当场验证提醒到底弹不弹得出来。 */
    fun postTest(context: Context): Boolean = post(
        context = context,
        title = "测试提醒：横幅弹出来了吗？",
        text = "看到这条从顶部弹出的横幅，说明提醒能正常显示",
        bigText = "这是一条测试提醒\n\n" +
            "如果它只安静地躺在通知栏里、没有从屏幕顶部弹出来，\n" +
            "请回到「上课提醒」面板，检查通知总开关、渠道是否被改成「静音」，\n" +
            "以及系统里本应用的「悬浮通知 / 横幅通知」开关。",
        notifyId = TEST_NOTIFY_ID,
    )

    private fun build(context: Context, title: String, text: String, bigText: String): Notification {
        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, NotificationChannels.REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF1F5FA9.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            // 下面三行对 Android 8.0 以下是横幅的必要条件（那时还没有渠道，重要性写在通知上）；
            // 8.0+ 由渠道决定，这些设置会被系统忽略，留着不影响。
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(PRE_O_VIBRATION)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // 锁屏上直接显示课名与地点：这是一条自己给自己的提醒，没有隐私可言，
            // 而 PRIVATE 会让锁屏只剩「一条通知」，等于白提醒。
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            // 两小时后自动消失：那时课早开始了，留着只是占通知栏。
            .setTimeoutAfter(2 * 60 * 60 * 1000L)
            .setContentIntent(tap)
            .build()
    }
}
