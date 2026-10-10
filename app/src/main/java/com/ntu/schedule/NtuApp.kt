package com.ntu.schedule

import android.app.Application
import com.ntu.schedule.core.ClassTimes
import com.ntu.schedule.data.SeasonStore
import com.ntu.schedule.diagnostics.Breadcrumbs
import com.ntu.schedule.diagnostics.CrashHandler

/**
 * 自定义 Application —— 只干两件事：**把崩溃处理器装上**，**把作息档位装上**。
 *
 * 为什么非要有个 Application：崩溃可能发生在任何 Activity 之前。
 * 如果处理器在 MainActivity 里装，那么「打开 App 就闪退」这种最常见的崩溃
 * （读存档时炸、初始化主题时炸）恰好什么都记不下来。
 * `Application.onCreate()` 是进程里能跑到的最早的用户代码，装在这里才没有盲区。
 *
 * 这里刻意**不做任何可能失败的事情**：Application 里抛异常等于整个 App 起不来，
 * 而它唯一的使命是「让崩溃能被记录」，不该自己变成新的崩溃源。
 * 所以读作息档位整段包在 `runCatching` 里 —— 读不到就用默认的「按月份自动切换」，
 * 和以前的行为完全一致。
 */
class NtuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // 必须在**任何**会算上课时间的东西之前装上：上课提醒由 AlarmManager 直接拉起
        // BroadcastReceiver（`notify\ReminderReceiver.kt`），根本不经过 Activity；
        // 桌面小组件同理。装晚了就会出现「课表里是夏令时间、提醒却按冬令响」。
        runCatching { ClassTimes.seasonMode = SeasonStore(this).load() }
        Breadcrumbs.add("app", "Application.onCreate 完成")
    }
}
