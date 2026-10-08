package com.ntu.schedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ntu.schedule.core.DateUtil

/**
 * 让小组件在「跨天」时自动更新。
 *
 * 为什么不用 `appwidget-provider` 的 `updatePeriodMillis`：系统最小只支持 30 分钟，
 * 而且在国产 ROM 上经常被压制到几小时一次，表现为「小组件显示的昨天的课」。
 * 这里用 AlarmManager 在本地零点后触发一次，收到广播后先校验日期是否真的变了
 * （见 [WidgetRefreshReceiver]），因此**重复触发是安全的**。
 *
 * 用 `setInexactRepeating` 而非 `setExactAndAllowWhileIdle`：后者在 Android 12+
 * 需要 SCHEDULE_EXACT_ALARM 权限，而「零点刷新课表」完全不需要精确到秒 ——
 * 晚几分钟更新没有任何影响。
 */
object WidgetUpdateScheduler {

    private const val REQUEST_CODE = 0x4E5455 // "NTU"

    fun ensureScheduled(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        val triggerAt = nextMidnightMillis()
        runCatching {
            manager.setInexactRepeating(AlarmManager.RTC, triggerAt, AlarmManager.INTERVAL_DAY, pi)
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).setAction(WidgetRefreshReceiver.ACTION_MIDNIGHT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /**
     * 下一个本地零点的毫秒时间戳。
     *
     * 直接复用 [DateUtil.todayStartMillis] —— 日期边界与时区处理只留一份实现，
     * 避免「小组件换天」和「App 里今天」用了两套算法而对不上。
     */
    private fun nextMidnightMillis(): Long =
        DateUtil.todayStartMillis() + DAY_MS

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
