package com.ntu.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * 「今日课表」小组件入口。
 *
 * 系统只会在「添加到桌面」「尺寸变化」「updatePeriodMillis 到点」这几种时机回调这里。
 * 我们的刷新时机（每天零点换天、导入成功后）由 [WidgetUpdateScheduler] 与
 * [WidgetRenderer.updateAll] 负责，不依赖系统回调。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = WidgetRenderer.render(context)
        for (id in appWidgetIds) manager.updateAppWidget(id, views)
        // 每次都重新排一次零点刷新：用户删了又加、加了又删，闹钟不会丢
        WidgetUpdateScheduler.ensureScheduled(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // 尺寸变化时重画，让新增的行有机会显示出来
        manager.updateAppWidget(appWidgetId, WidgetRenderer.render(context))
    }

    override fun onEnabled(context: Context) {
        // 第一个实例被添加
        WidgetUpdateScheduler.ensureScheduled(context)
    }

    override fun onDisabled(context: Context) {
        // 最后一个实例被移除：留着闹钟只会在后台白干活
        WidgetUpdateScheduler.cancel(context)
    }
}
