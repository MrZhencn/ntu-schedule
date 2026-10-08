package com.ntu.schedule.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ntu.schedule.core.ReminderPlanner

/**
 * 收到「上课提醒」闹钟 → 弹一条横幅；收到每日续排闹钟 → 把提醒窗口往前挪一天。
 *
 * 通知内容全部来自闹钟 Intent 的 extras，接收器**不再读一次本地课表**：
 * 触发这一刻读盘既慢又没必要，而且课表若在排程后被清空，闹钟早已被一并取消
 * （见 [ReminderScheduler.refresh]），不会出现「提醒一门已经不存在的课」。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_REMIND -> postReminder(context, intent)
            ACTION_REARM -> ReminderScheduler.refresh(context)
        }
    }

    private fun postReminder(context: Context, intent: Intent) {
        val course = intent.getStringExtra(ReminderScheduler.EXTRA_COURSE) ?: return
        val start = intent.getStringExtra(ReminderScheduler.EXTRA_START).orEmpty()
        val end = intent.getStringExtra(ReminderScheduler.EXTRA_END).orEmpty()
        val room = intent.getStringExtra(ReminderScheduler.EXTRA_ROOM).orEmpty()
        val teacher = intent.getStringExtra(ReminderScheduler.EXTRA_TEACHER).orEmpty()
        val periods = intent.getStringExtra(ReminderScheduler.EXTRA_PERIODS).orEmpty()
        val notifyId = intent.getIntExtra(ReminderScheduler.EXTRA_NOTIFY_ID, DEFAULT_NOTIFY_ID)

        ReminderNotification.post(
            context = context,
            title = ReminderPlanner.title(),
            text = ReminderPlanner.body(course, start, end, room),
            bigText = ReminderPlanner.bigText(course, start, end, periods, room, teacher),
            notifyId = notifyId,
        )
    }

    companion object {
        const val ACTION_REMIND = "com.ntu.schedule.action.CLASS_REMIND"
        const val ACTION_REARM = "com.ntu.schedule.action.REMINDER_REARM"

        private const val DEFAULT_NOTIFY_ID = 0x4E5450
    }
}
