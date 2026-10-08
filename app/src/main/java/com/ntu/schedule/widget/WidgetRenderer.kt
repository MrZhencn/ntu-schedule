package com.ntu.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ntu.schedule.MainActivity
import com.ntu.schedule.R
import com.ntu.schedule.core.ClassTimes
import com.ntu.schedule.core.Course
import com.ntu.schedule.core.DateUtil
import com.ntu.schedule.core.Schedule
import com.ntu.schedule.data.ScheduleStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面小组件：显示「今天」的课。
 *
 * 刻意用**原生 RemoteViews**，不用 Glance：Glance 依赖本地无缓存、且 RemoteViews 在
 * 各家 ROM（含国产桌面）上兼容性最好，这个 App 的目标用户正是国产 ROM 用户。
 *
 * RemoteViews 不能在运行时拼装布局，所以行数是固定的（[MAX_ROWS] 行，多出的隐藏），
 * 这也是小组件要做成可调整高度的原因 —— 见 `res/xml/widget_today_info.xml`。
 */
object WidgetRenderer {

    /** 布局里预置的行数，改这里要同步改 `widget_today.xml`。 */
    const val MAX_ROWS = 6

    /** 行 id 数组，按顺序对应 layout 里的 row_1..row_6。 */
    private val ROW_IDS = intArrayOf(
        R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
        R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6,
    )

    private val ROW_TIME_IDS = intArrayOf(
        R.id.widget_time_1, R.id.widget_time_2, R.id.widget_time_3,
        R.id.widget_time_4, R.id.widget_time_5, R.id.widget_time_6,
    )

    private val ROW_NAME_IDS = intArrayOf(
        R.id.widget_name_1, R.id.widget_name_2, R.id.widget_name_3,
        R.id.widget_name_4, R.id.widget_name_5, R.id.widget_name_6,
    )

    private val ROW_ROOM_IDS = intArrayOf(
        R.id.widget_room_1, R.id.widget_room_2, R.id.widget_room_3,
        R.id.widget_room_4, R.id.widget_room_5, R.id.widget_room_6,
    )

    /*
     * 日期格式化**不能**用共享的 SimpleDateFormat 实例。
     *
     * SimpleDateFormat 内部持有一个 Calendar，format() 不是线程安全的：
     * 两个线程同时格式化，会互相把对方的 Calendar 改掉，轻则日期串成别的日子，
     * 重则抛 ArrayIndexOutOfBoundsException / NumberFormatException 崩掉进程。
     *
     * 这里恰好是并发高发区：小组件 onUpdate（主线程）与 WidgetRefreshReceiver
     * （零点刷新 / 开机 / 改时间，跑在广播接收器的线程上）都可能同时进来渲染。
     * 每调用一次新建一个的开销可以忽略 —— 一次渲染也就格式化一个日期。
     */
    private fun formatDate(date: Date): String =
        SimpleDateFormat("M月d日", Locale.CHINA).format(date)

    /**
     * 渲染一个小组件实例。
     *
     * @param dateIso 要显示的日期，默认今天。写成参数是为了让单元测试能固定日期。
     */
    fun render(context: Context, dateIso: String = DateUtil.todayIso()): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today)
        val store = ScheduleStore(context)
        val schedule = store.loadSchedule()

        wireStaticViews(context, views)

        if (schedule == null) {
            views.setTextViewText(R.id.widget_day, "南通大学课表")
            views.setTextViewText(
                R.id.widget_empty,
                "还没有课表\n点这里打开 App 导入",
            )
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
            hideAllRows(views)
            return views
        }

        val week = schedule.weekOfDate(dateIso)
        val dayOfWeek = DateUtil.dayOfWeekOf(dateIso) ?: 1
        val weekdayCn = DateUtil.weekdayCn(dayOfWeek)
        val month = dateIso.substringAfter('-').substringBefore('-').toIntOrNull() ?: 1

        views.setTextViewText(R.id.widget_day, weekdayCn)
        views.setTextViewText(R.id.widget_date, formatDate(parseDate(dateIso)))
        views.setTextViewText(R.id.widget_week, if (week != null) "第 $week 周" else "假期")

        val courses = if (week == null) {
            emptyList()
        } else {
            schedule.coursesOfWeek(week).filter { it.dayOfWeek == dayOfWeek && it.activeIn(week) }
        }

        if (courses.isEmpty()) {
            views.setTextViewText(
                R.id.widget_empty,
                if (week == null) "今天没有课 🎉" else "今天没有课 🎉",
            )
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
            hideAllRows(views)
            return views
        }

        views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)

        // 课多到一屏放不下时，最后一行让位给「还有 N 门课」，而不是默默丢掉。
        val overflow = courses.size - MAX_ROWS
        val shownCount = if (overflow > 0) maxOf(1, MAX_ROWS - 1) else courses.size

        for (i in 0 until MAX_ROWS) {
            val course = courses.getOrNull(i)
            if (course != null && i < shownCount) {
                views.setViewVisibility(ROW_IDS[i], android.view.View.VISIBLE)
                views.setTextViewText(ROW_TIME_IDS[i], timeText(course, month))
                views.setTextViewText(ROW_NAME_IDS[i], course.name)
                views.setTextViewText(ROW_ROOM_IDS[i], roomText(course))
                continue
            }
            if (overflow > 0 && i == shownCount) {
                views.setViewVisibility(ROW_IDS[i], android.view.View.VISIBLE)
                views.setTextViewText(ROW_TIME_IDS[i], "")
                views.setTextViewText(
                    ROW_NAME_IDS[i],
                    context.getString(R.string.widget_more_courses, overflow),
                )
                views.setTextViewText(ROW_ROOM_IDS[i], context.getString(R.string.widget_more_hint))
                continue
            }
            views.setViewVisibility(ROW_IDS[i], android.view.View.GONE)
        }
        return views
    }

    /** 标题栏与「点开 App」的跳转 —— 每个实例都要单独绑定。 */
    private fun wireStaticViews(context: Context, views: RemoteViews) {
        val intent = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, 0, intent, flags)
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        views.setOnClickPendingIntent(R.id.widget_header, pi)
    }

    private fun hideAllRows(views: RemoteViews) {
        for (id in ROW_IDS) views.setViewVisibility(id, android.view.View.GONE)
    }

    /**
     * 时间显示成**上下两行**：`07:50` 换行 `09:20`。
     *
     * 不用 `07:50-09:20` 一行：完整区间 11 个字符在 11sp 下要 66dp 左右，而时间列只有
     * 54dp（再宽就把课名挤没了），结果会被 ellipsize 成「07:50-0…」—— 这正是用户报的
     * 「课程时间被省略」。拆两行后每行 5 个字符，任何字体缩放都不会被截断。
     */
    private fun timeText(course: Course, month: Int): String {
        val start = ClassTimes.slotOf(course.startPeriod, month)
        val end = ClassTimes.slotOf(course.endPeriod, month)
        if (start == null || end == null) return course.periodText
        return "${start.start}\n${end.end}"
    }

    private fun roomText(course: Course): String {
        val room = course.room.ifBlank { "地点待定" }
        return if (course.teacher.isBlank()) room else "$room · ${course.teacher}"
    }

    private fun parseDate(iso: String): Date =
        runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(iso) }.getOrNull() ?: Date()

    /**
     * 刷新所有小组件实例。放在这里而不是 Provider 里，是为了让 AlarmManager 与
     * 「导入成功后刷新」共用同一段逻辑。
     */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
        if (ids == null || ids.isEmpty()) return
        val views = render(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    /** 数据全清后也要让小组件回到「未导入」状态。 */
    fun refreshAll(context: Context) {
        runCatching { updateAll(context) }
    }
}
