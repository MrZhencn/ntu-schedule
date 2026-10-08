package com.ntu.schedule.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ntu.schedule.core.DateUtil
import com.ntu.schedule.core.ReminderPlan
import com.ntu.schedule.core.ReminderPlanner
import com.ntu.schedule.core.ReminderSettings
import com.ntu.schedule.core.Schedule
import com.ntu.schedule.data.ReminderStore
import com.ntu.schedule.data.ScheduleStore

/**
 * 给未来若干天的每一节课排一个「上课前 N 分钟」的闹钟（N 由用户在提醒设置里选，默认 1 小时）。
 *
 * 三个刻意的决定：
 *
 * 1. **能用精确闹钟就用，拿不到权限才退回 `setAndAllowWhileIdle`。**
 *    `setExactAndAllowWhileIdle` 在 Android 12+ 需要 `SCHEDULE_EXACT_ALARM`，Android 14 起
 *    对 targetSdk ≥ 33 的应用默认拒绝；`USE_EXACT_ALARM` 虽然自动授予，但只允许闹钟/日历类应用，
 *    会被应用商店卡审核，不适合本 App。所以策略是：**先问 `canScheduleExactAlarms()`，
 *    可以就精确触发，不行就退回非精确** —— 差几分钟远好过为了准点而要求一个拿不到的权限。
 *    （见 [OemSettings.canScheduleExactAlarms] 与提醒自检面板里的放行入口。）
 *
 * 2. **一次只排 7 天，靠一个每日闹钟滚动续排。** 一次把 19 周全排上要上千个闹钟，
 *    而且学期中课表随时可能变（重新导入、调课），排太远等于排了一堆错的。
 *
 * 3. **每次重排前先把整个槽位区间取消掉。** 用固定的 request code 池
 *    （`天序号 × 32 + 当天第几条`），所以「取消全部」是一次确定的循环，
 *    而不是去猜哪些 code 用过 —— 猜漏一个，用户就会收到已经上过的课的提醒。
 */
object ReminderScheduler {

    /** 闹钟附带的数据，供接收器直接发通知，不必再读一次磁盘。 */
    const val EXTRA_DATE = "date"
    const val EXTRA_COURSE = "course"
    const val EXTRA_ROOM = "room"
    const val EXTRA_TEACHER = "teacher"
    const val EXTRA_START = "start"
    const val EXTRA_END = "end"
    const val EXTRA_PERIODS = "periods"
    const val EXTRA_NOTIFY_ID = "notifyId"

    /**
     * 这条闹钟是按「提前多少分钟」排的。
     *
     * 必须随闹钟一起带过去：通知标题「还有 N 分钟上课」是**用户能看到自己设置生效的唯一证据**，
     * 而接收器发通知时再去读一次磁盘既慢又可能读到已经改过的值（闹钟还是按旧提前量排的）。
     */
    const val EXTRA_LEAD = "leadMinutes"

    private const val DAYS = 7
    private const val PER_DAY = 32
    private const val SLOT_COUNT = DAYS * PER_DAY
    private const val DAILY_REQUEST = 0x4E5449 // "NTI"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 课表变化后调用：重排全部提醒，并保证每日续排闹钟在。课表为空时全部取消。 */
    fun refresh(context: Context) {
        val schedule = runCatching { ScheduleStore(context).loadSchedule() }.getOrNull()
        if (schedule == null || schedule.courses.isEmpty()) {
            cancelAll(context)
            return
        }
        reschedule(context, schedule)
        ensureDaily(context)
    }

    /** 只重排具体提醒（不动每日闹钟）。 */
    fun reschedule(context: Context, schedule: Schedule) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        cancelSlots(context)

        // 逐条再问一次权限没有意义，而且用户在排程过程中去设置里改了权限的概率可以忽略。
        val exact = OemSettings.canScheduleExactAlarms(context)

        val today = DateUtil.todayIso()
        // 提前量是用户可调的，读一次就用在整批排程上；改设置后必须再调一次 refresh，
        // 否则已经排出去的闹钟还按旧提前量响（见 AppViewModel.setReminderLead）。
        val lead = runCatching { ReminderStore(context).load().leadMinutes }
            .getOrDefault(ReminderSettings.DEFAULT.leadMinutes)
        val plans = ReminderPlanner.plan(
            schedule = schedule,
            fromDateIso = today,
            daysAhead = DAYS,
            nowMinuteOfDay = DateUtil.nowMinuteOfDay(),
            leadMinutes = lead,
        )

        for ((dateIso, dayPlans) in plans.groupBy { it.dateIso }) {
            val dayIndex = (DateUtil.daysBetween(today, dateIso) ?: continue).toInt()
            if (dayIndex !in 0 until DAYS) continue

            dayPlans.forEachIndexed { slot, plan ->
                if (slot !in 0 until PER_DAY) return@forEachIndexed
                val requestCode = dayIndex * PER_DAY + slot
                val triggerAt = (DateUtil.startOfDayMillis(dateIso) ?: return@forEachIndexed) +
                    plan.atMinuteOfDay * 60_000L
                if (triggerAt <= System.currentTimeMillis()) return@forEachIndexed

                val pi = alarmIntent(context, plan, requestCode, lead)
                setAlarm(manager, triggerAt, pi, exact)
            }
        }
    }

    /**
     * 排一个闹钟。拿不到精确闹钟权限、或者权限在检查之后被撤销（会抛 `SecurityException`）时，
     * **必须退回到非精确闹钟而不是放弃** —— 放弃就等于这节课不会响。
     */
    private fun setAlarm(manager: AlarmManager, triggerAt: Long, pi: PendingIntent, exact: Boolean) {
        if (exact) {
            val ok = runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }.isSuccess
            if (ok) return
        }
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
    }

    /** 每日零点后滚动续排：把窗口往前挪一天，否则 7 天之后提醒就断了。 */
    fun ensureDaily(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = rearmIntent(context) ?: return
        runCatching {
            manager.setInexactRepeating(
                AlarmManager.RTC,
                DateUtil.todayStartMillis() + DAY_MS,
                AlarmManager.INTERVAL_DAY,
                pi,
            )
        }
    }

    fun cancelAll(context: Context) {
        cancelSlots(context)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(rearmIntent(context, create = false) ?: return) }
    }

    private fun cancelSlots(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (code in 0 until SLOT_COUNT) {
            val pi = slotIntent(context, code, create = false) ?: continue
            runCatching { manager.cancel(pi) }
            pi.cancel()
        }
    }

    private fun alarmIntent(context: Context, plan: ReminderPlan, requestCode: Int, leadMinutes: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMIND)
            .putExtra(EXTRA_DATE, plan.dateIso)
            .putExtra(EXTRA_COURSE, plan.course.name)
            .putExtra(EXTRA_ROOM, plan.course.room)
            .putExtra(EXTRA_TEACHER, plan.course.teacher)
            .putExtra(EXTRA_START, plan.startTime)
            .putExtra(EXTRA_END, plan.endTime)
            .putExtra(EXTRA_PERIODS, plan.course.periodText)
            .putExtra(EXTRA_NOTIFY_ID, requestCode)
            .putExtra(EXTRA_LEAD, leadMinutes)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** `Intent.filterEquals` 不看 extras，所以这里能用「同 component + 同 action」找回已有的 PendingIntent。 */
    private fun slotIntent(context: Context, requestCode: Int, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMIND)
        var flags = PendingIntent.FLAG_IMMUTABLE
        if (create) flags = flags or PendingIntent.FLAG_UPDATE_CURRENT
        else flags = flags or PendingIntent.FLAG_NO_CREATE
        return runCatching { PendingIntent.getBroadcast(context, requestCode, intent, flags) }.getOrNull()
    }

    private fun rearmIntent(context: Context, create: Boolean = true): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REARM)
        val flags = if (create) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        return runCatching { PendingIntent.getBroadcast(context, DAILY_REQUEST, intent, flags) }.getOrNull()
    }
}
