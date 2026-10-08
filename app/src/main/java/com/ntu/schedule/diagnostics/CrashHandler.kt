package com.ntu.schedule.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.ntu.schedule.data.ScheduleStore
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 崩溃时自动留一份报告。
 *
 * 为什么值得做：这个 App 装在别人的手机上，用户的描述永远是「点了一下就闪退了」。
 * 没有报告，唯一的排查手段是让他装 adb、开 USB 调试、抓 logcat —— 对一个只想看课表的
 * 同学来说，这条路径等于不存在。有了报告，最坏情况也只是一句「菜单 → 自检与诊断 → 导出」。
 *
 * 三个刻意的设计：
 * - **不吞掉崩溃**。写完之后照常交给系统原来的处理器，系统崩溃弹窗、「应用无响应」对话框
 *   一切照旧。偷偷把崩溃吃掉会让 App 停在一个已经错乱的状态里，比闪退更糟。
 * - **自己绝不抛异常**。整段包在 `runCatching` 里。在濒死路径上再崩一次，报告会变成
 *   「记录崩溃时又崩了」，原始信息全丢。
 * - **覆盖范围要说清楚**：这里只管 Java/Kotlin 层的未捕获异常。原生崩溃（NDK/libc 段错误）
 *   和 ANR（主线程卡住，系统主动杀）**不会**走到这里 —— 它们在系统里，进程是被 SIGKILL 掉的。
 *   这一点写在报告结尾，免得用户以为「没报告 = 没崩」。
 */
object CrashHandler {

    private val installed = AtomicBoolean(false)

    /** 在 `Application.onCreate()` 里调用一次。重复调用是安全的。 */
    fun install(app: Application) {
        Breadcrumbs.markStart()
        Breadcrumbs.add("app", "进程启动")

        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Breadcrumbs.add("crash", error.javaClass.simpleName + ": " + (error.message ?: ""))
            runCatching { writeNow(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // 理论上不会走到：Android 一定会装一个默认处理器。保底也按系统的做法收场。
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** 采集现场 + 渲染 + 落盘。返回写出来的文件，失败返回 null。 */
    fun writeNow(context: Context, thread: Thread, error: Throwable): java.io.File? {
        val facts = runCatching { facts(context, thread, error) }.getOrNull() ?: return null
        val text = runCatching { CrashReport.render(facts) }.getOrNull() ?: return null
        return CrashStore(context).save(text, facts.atMillis)
    }

    /**
     * 走**完全相同的采集与写盘路径**造一份报告，但不真的抛异常。
     *
     * 存在的理由：不能让用户「先崩一次试试」。要验证崩溃报告能不能用、
     * 导出的文件长什么样，必须有一个不崩也能跑一遍的入口。
     */
    fun simulate(context: Context): java.io.File? {
        val fake = IllegalStateException(
            "【模拟】这条记录是为验证「崩溃报告」功能生成的，App 并没有真的崩溃。",
        )
        return writeNow(context, Thread.currentThread(), fake)
    }

    // ------------------------------------------------------------------ 采集

    private fun facts(context: Context, thread: Thread, error: Throwable): CrashFacts {
        val pkg = packageInfo(context)
        val rt = Runtime.getRuntime()
        val startedAt = Process.getStartUptimeMillis()
        val uptime = if (startedAt > 0) SystemClock.uptimeMillis() - startedAt else 0L

        return CrashFacts(
            atMillis = System.currentTimeMillis(),
            timeZoneId = TimeZone.getDefault().id,
            timeZoneOffsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000,
            threadName = thread.name,
            throwableText = stackTraceOf(error),
            appVersionName = pkg?.versionName ?: "?",
            appVersionCode = versionCodeOf(pkg),
            packageName = context.packageName,
            isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "?",
            brand = Build.BRAND ?: "?",
            model = Build.MODEL ?: "?",
            abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "?",
            locale = Locale.getDefault().toString(),
            uptimeMillis = uptime,
            usedHeapBytes = rt.totalMemory() - rt.freeMemory(),
            maxHeapBytes = rt.maxMemory(),
            crumbs = Breadcrumbs.snapshot(),
            secrets = secrets(context),
        )
    }

    /**
     * 已知的「能认出是谁」的字面量。目前只有学号 —— 密码从来不落盘，cookie 也不记进面包屑。
     * 崩溃时刻读文件有风险，所以整个包在 runCatching 里，读不到就少一层保险而已
     * （[CrashReport] 里还有「10/11 位连续数字一律抹掉」的兜底）。
     *
     * 取的是**本机出现过的所有学号**（不止当前学校那一个）：报告是用户自己发出去的，
     * 里面混进任何一所学校的学号都不合适。
     */
    private fun secrets(context: Context): List<String> = runCatching {
        ScheduleStore(context).allStudentIds()
    }.getOrDefault(emptyList())

    private fun stackTraceOf(error: Throwable): String {
        val sw = StringWriter(2048)
        PrintWriter(sw).use { error.printStackTrace(it) }
        return sw.toString()
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context): android.content.pm.PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: android.content.pm.PackageInfo?): Int {
        if (info == null) return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }

    /** 当前是否装上了（只用来给自检面板显示）。 */
    fun isInstalled(): Boolean = installed.get()
}
