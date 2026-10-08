package com.ntu.schedule

import android.app.Application
import com.ntu.schedule.diagnostics.Breadcrumbs
import com.ntu.schedule.diagnostics.CrashHandler

/**
 * 自定义 Application —— 只干一件事：**把崩溃处理器装上**。
 *
 * 为什么非要有个 Application：崩溃可能发生在任何 Activity 之前。
 * 如果处理器在 MainActivity 里装，那么「打开 App 就闪退」这种最常见的崩溃
 * （读存档时炸、初始化主题时炸）恰好什么都记不下来。
 * `Application.onCreate()` 是进程里能跑到的最早的用户代码，装在这里才没有盲区。
 *
 * 这里刻意**不做任何可能失败的事情**：Application 里抛异常等于整个 App 起不来，
 * 而它唯一的使命是「让崩溃能被记录」，不该自己变成新的崩溃源。
 */
class NtuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        Breadcrumbs.add("app", "Application.onCreate 完成")
    }
}
