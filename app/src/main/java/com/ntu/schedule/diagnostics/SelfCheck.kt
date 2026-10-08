package com.ntu.schedule.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ntu.schedule.data.ScheduleStore
import com.ntu.schedule.notify.NotificationChannels
import com.ntu.schedule.notify.OemSettings
import com.ntu.schedule.widget.WidgetPinner
import java.io.File

/** 一条自检结果。 */
data class Check(val group: String, val label: String, val value: String, val state: CheckState)

enum class CheckState { OK, WARN, BAD, INFO }

/**
 * 「自动检测程序」。
 *
 * 为什么要有它：这个 App 出问题时，用户能描述的只有「不提醒」「小组件加不上」，
 * 而真正的原因（通知权限被关、电池优化没放行、桌面不支持一键添加、精确闹钟没允许……）
 * 全都在系统设置里，且各家 ROM 的入口还不一样。让用户一条条去翻、再一条条截图回来，
 * 一轮沟通能烧掉半小时。
 *
 * 这里的做法是**把判断做在 App 里，把结果摆成一屏**：能自动查的全部查出来，
 * 查不到的（自启动白名单那种没有接口的）如实写「需要手动确认」，绝不瞎猜成「正常」。
 *
 * [run] 会碰系统 API，没法在 JVM 单测里跑；所以渲染那一步拆成了纯函数 [toText]，
 * 格式本身是可以测的。
 */
object SelfCheck {

    fun run(context: Context): List<Check> {
        val checks = mutableListOf<Check>()
        checks += environment(context)
        checks += data(context)
        checks += widget(context)
        checks += reminders(context)
        checks += crash(context)
        return checks
    }

    // ------------------------------------------------------------------ 运行环境

    private fun environment(context: Context): List<Check> {
        val vendor = runCatching { OemSettings.currentVendor() }
            .getOrDefault(OemSettings.Vendor("other", "Android"))
        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

        return listOf(
            Check("运行环境", "App 版本", "$version（${context.packageName}）", CheckState.INFO),
            Check(
                "运行环境",
                "系统",
                "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
                CheckState.INFO,
            ),
            Check(
                "运行环境",
                "机型",
                "${Build.MANUFACTURER} ${Build.MODEL}（${Build.BRAND}）",
                CheckState.INFO,
            ),
            Check(
                "运行环境",
                "厂商类别",
                "${vendor.label}（识别为 ${vendor.key}）",
                CheckState.INFO,
            ),
            Check(
                "运行环境",
                "CPU 架构",
                Build.SUPPORTED_ABIS?.firstOrNull() ?: "?",
                CheckState.INFO,
            ),
        )
    }

    // ------------------------------------------------------------------ 本地数据

    private fun data(context: Context): List<Check> {
        val store = ScheduleStore(context)
        val schedule = runCatching { store.loadSchedule() }.getOrNull()
        val studentId = runCatching { store.loadStudentId() }.getOrDefault("")

        return listOf(
            Check(
                "本地数据",
                "课表",
                if (schedule == null) {
                    "还没有导入"
                } else {
                    "${schedule.courses.size} 门课 · ${schedule.studentName} · ${schedule.termName}"
                },
                if (schedule == null) CheckState.WARN else CheckState.OK,
            ),
            Check(
                "本地数据",
                "上次用的学号",
                // 只显示尾号：这份自检信息用户是可能要发出去的，学号不该整串出现。
                if (studentId.length >= 4) "已保存（尾号 ${studentId.takeLast(4)}）" else "没有保存",
                CheckState.INFO,
            ),
            Check(
                "本地数据",
                "记住账号密码",
                if (store.hasCredentials()) "已开启（密码由系统 Keystore 加密）" else "未开启",
                CheckState.INFO,
            ),
            Check(
                "本地数据",
                "私有目录可写",
                if (writable(context)) "正常" else "写不进去。课表与报告都保存不了",
                if (writable(context)) CheckState.OK else CheckState.BAD,
            ),
        )
    }

    private fun writable(context: Context): Boolean = runCatching {
        val probe = File(context.filesDir, ".selfcheck")
        probe.writeText("ok")
        val ok = probe.readText() == "ok"
        probe.delete()
        ok
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 桌面小组件

    private fun widget(context: Context): List<Check> {
        val status = runCatching { WidgetPinner.status(context) }
            .getOrNull()
            ?: return listOf(Check("桌面小组件", "检测", "读取失败", CheckState.BAD))

        return listOf(
            Check(
                "桌面小组件",
                "当前桌面",
                status.launcherLabel +
                    if (status.launcherPackage.isNotEmpty()) "（${status.launcherPackage}）" else "",
                CheckState.INFO,
            ),
            Check(
                "桌面小组件",
                "App 内一键添加",
                if (status.supported) {
                    "这台手机支持 —— 点「自动添加到桌面」会直接放上去"
                } else {
                    "这台手机的桌面不支持 App 直接放，只能在桌面手动添加（步骤见「添加桌面小组件」）"
                },
                if (status.supported) CheckState.OK else CheckState.WARN,
            ),
            Check(
                "桌面小组件",
                "桌面上已有",
                if (status.installedCount > 0) "${status.installedCount} 块" else "还没有",
                if (status.installedCount > 0) CheckState.OK else CheckState.INFO,
            ),
            Check(
                "桌面小组件",
                "本机添加路径",
                status.manualSteps.first(),
                CheckState.INFO,
            ),
        )
    }

    // ------------------------------------------------------------------ 上课提醒

    private fun reminders(context: Context): List<Check> {
        val status = runCatching { NotificationChannels.status(context) }.getOrNull()
        val ignoringBattery = runCatching { OemSettings.isIgnoringBatteryOptimizations(context) }
            .getOrDefault(false)
        val exactAlarms = runCatching { OemSettings.canScheduleExactAlarms(context) }
            .getOrDefault(false)
        val vendor = runCatching { OemSettings.currentVendor() }
            .getOrDefault(OemSettings.Vendor("other", "Android"))
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val list = mutableListOf<Check>()
        list += Check(
            "上课提醒",
            "通知权限",
            if (permission) "已允许" else "被关掉了，通知发不出去",
            if (permission) CheckState.OK else CheckState.BAD,
        )
        if (status != null) {
            list += Check(
                "上课提醒",
                "提醒样式（能不能弹横幅）",
                if (!status.channelReady) {
                    "提醒渠道不存在，重新打开 App 会自动重建"
                } else {
                    NotificationChannels.importanceText(status.importance)
                },
                if (status.bannerReady) CheckState.OK else CheckState.WARN,
            )
        }
        list += Check(
            "上课提醒",
            "电池优化",
            if (ignoringBattery) "已放行" else "未放行。省电模式下提醒可能被推迟",
            if (ignoringBattery) CheckState.OK else CheckState.WARN,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Check(
                "上课提醒",
                "精确闹钟",
                if (exactAlarms) "已允许" else "未允许，提醒可能晚几分钟",
                if (exactAlarms) CheckState.OK else CheckState.WARN,
            )
        }
        list += Check(
            "上课提醒",
            "自启动 / 后台运行",
            "系统没有提供检测接口，需要你在「${vendor.label}」的设置里手动确认",
            CheckState.INFO,
        )
        return list
    }

    // ------------------------------------------------------------------ 崩溃报告

    private fun crash(context: Context): List<Check> {
        val store = CrashStore(context)
        val count = store.count()
        return listOf(
            Check(
                "崩溃报告",
                "自动记录",
                if (CrashHandler.isInstalled()) "已启用" else "没有启用（Application 没走到初始化）",
                if (CrashHandler.isInstalled()) CheckState.OK else CheckState.BAD,
            ),
            Check(
                "崩溃报告",
                "已保存的报告",
                if (count > 0) "$count 份（最多保留 ${CrashStore.MAX_FILES} 份）" else "没有，说明还没崩过",
                if (count > 0) CheckState.WARN else CheckState.OK,
            ),
            Check(
                "崩溃报告",
                "覆盖范围",
                "只覆盖 Java/Kotlin 异常；原生崩溃和 ANR 抓不到（进程被系统直接杀掉）",
                CheckState.INFO,
            ),
        )
    }

    // ------------------------------------------------------------------ 渲染

    /** 纯函数：把检测结果排成可复制的文本。单测盯的就是它。 */
    fun toText(checks: List<Check>): String {
        if (checks.isEmpty()) return "（没有检测到任何项目）"
        val sb = StringBuilder(2048)
        sb.append("南通大学课表 · 自检信息\n")
        sb.append("（与崩溃报告一样，已经把学号之类的东西抹掉了，可以直接发出去）\n")
        sb.append("\n")

        var group = ""
        for (c in checks) {
            if (c.group != group) {
                group = c.group
                sb.append("【").append(group).append("】\n")
            }
            sb.append("  ").append(pad(c.label, 24)).append(mark(c.state)).append(c.value).append("\n")
        }
        return sb.toString()
    }

    private fun mark(state: CheckState): String = when (state) {
        CheckState.OK -> ""
        CheckState.WARN -> "△ "
        CheckState.BAD -> "✕ "
        CheckState.INFO -> "· "
    }

    /**
     * 按**显示宽度**补空格，不是按字符数。
     *
     * 中文在等宽环境里占两格、在比例字体里占一个全角。这里用「CJK 及全角标点算 2」估算，
     * 只要同一份文本在任何等宽字体下看着整齐就够了 —— 它是纯文本，用户可能贴在微信里。
     */
    private fun pad(s: String, width: Int): String {
        var w = 0
        for (ch in s) w += if (isWide(ch)) 2 else 1
        if (w >= width) return "$s "
        return s + " ".repeat(width - w)
    }

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in 0x1100..0x115F || // 韩文字母
            code in 0x2E80..0xA4CF || // CJK 部首、假名、汉字
            code in 0xAC00..0xD7A3 || // 韩文音节
            code in 0xF900..0xFAFF || // CJK 兼容汉字
            code in 0xFE30..0xFE6F || // CJK 兼容形式
            code in 0xFF00..0xFF60 || // 全角 ASCII
            code in 0xFFE0..0xFFE6
    }

    /** 分享自检信息时的标题。 */
    fun subject(): String = "南通大学课表自检信息"
}
