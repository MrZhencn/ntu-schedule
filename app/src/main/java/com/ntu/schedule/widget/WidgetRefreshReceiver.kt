package com.ntu.schedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ntu.schedule.notify.ReminderScheduler

/**
 * 零点刷新小组件 + 开机 / 更新时间后重新排闹钟。
 *
 * 这里**不需要记录「上次渲染的日期」**：每次收到广播都用 `DateUtil.todayIso()` 重新判定
 * 「今天是哪天」，所以重复触发或延迟触发都只会多画一次，绝不会把日期画错 ——
 * 这点很重要，因为国产 ROM 经常把重复闹钟延迟或压制后补发。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_MIDNIGHT -> {
                WidgetRenderer.updateAll(context)
                // 重排一次：部分 ROM 在清理后台后会丢掉重复闹钟
                WidgetUpdateScheduler.ensureScheduled(context)
                // 顺便把上课提醒的 7 天窗口往前滚一天
                ReminderScheduler.refresh(context)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // 手动改时间或换时区后，已经排好的 RTC 闹钟还停在旧的绝对时刻上，
            // 「提前一小时」就会偏掉一个时差。必须按新的本地时间重排一遍。
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                // 设备重启会清掉所有 AlarmManager 闹钟，这里补回来
                WidgetUpdateScheduler.ensureScheduled(context)
                WidgetRenderer.updateAll(context)
                ReminderScheduler.refresh(context)
            }
        }
    }

    companion object {
        const val ACTION_MIDNIGHT = "com.ntu.schedule.action.MIDNIGHT_REFRESH"
    }
}
