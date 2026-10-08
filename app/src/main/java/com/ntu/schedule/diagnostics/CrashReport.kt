package com.ntu.schedule.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 一条面包屑：崩溃之前某个时刻发生了什么。 */
data class Crumb(val atMillis: Long, val tag: String, val text: String)

/**
 * 崩溃报告的全部素材。
 *
 * 刻意做成**纯数据 + 纯函数渲染**：报告长什么样可以直接写单测，
 * 不需要真机、不需要先崩一次。真正会碰系统 API 的只有 [CrashHandler] 和 [SelfCheck]。
 */
data class CrashFacts(
    val atMillis: Long,
    val timeZoneId: String,
    val timeZoneOffsetMinutes: Int,
    val threadName: String,
    /** 异常类名 + message + 完整堆栈，已由调用方 `printStackTrace` 出来。 */
    val throwableText: String,
    val appVersionName: String,
    val appVersionCode: Int,
    val packageName: String,
    val isDebug: Boolean,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val abi: String,
    val locale: String,
    /** 进程已经活了多久（`SystemClock.uptimeMillis()`）。 */
    val uptimeMillis: Long,
    val usedHeapBytes: Long,
    val maxHeapBytes: Long,
    val crumbs: List<Crumb>,
    /** 额外要抹掉的字面量（学号之类）。 */
    val secrets: List<String>,
)

/**
 * 把 [CrashFacts] 渲染成一份人和机器都能读的纯文本报告。
 *
 * 两条硬要求：
 * 1. **必须脱敏**。用户是要把这份东西发出去的（微信、邮件、贴到 issue），
 *    学号绝不能跟着走。所以 [redact] 是独立可测的一步，而不是散落在拼接里的几行 replace。
 * 2. **必须自解释**。收报告的人大概率不是开发者 —— 报告开头就写清楚这是什么、
 *    该看哪几段，省掉一轮「你发我个 txt 干嘛」。
 */
object CrashReport {

    /** 报告格式版本。以后加字段就 +1，方便对照。 */
    const val FORMAT_VERSION = 1

    private val REDACTED = "******"

    /** 报告里的时间统一按这个格式，跟手机语言的显示习惯无关，方便按时间排序。 */
    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

    fun render(facts: CrashFacts): String {
        val sb = StringBuilder(4096)
        val time = formatTime(facts.atMillis, facts.timeZoneId)

        sb.append("南通大学课表 · 崩溃报告\n")
        sb.append("（这份文件由 App 自动生成，已抹掉学号等个人信息。发给开发者时请连同下面全部内容一起发。）\n")
        sb.append("\n")

        sb.append("================ 崩溃现场 ================\n")
        sb.append("时间        : ").append(time)
            .append(" （").append(zoneText(facts.timeZoneOffsetMinutes)).append(" ").append(facts.timeZoneId).append("）\n")
        sb.append("线程        : ").append(facts.threadName).append("\n")
        sb.append("进程活了    : ").append(durationText(facts.uptimeMillis)).append("\n")
        sb.append("内存        : 已用 ").append(mb(facts.usedHeapBytes))
            .append(" / 上限 ").append(mb(facts.maxHeapBytes)).append("\n")
        sb.append("\n")

        sb.append("================ 异常 ================\n")
        sb.append(facts.throwableText.trimEnd()).append("\n")
        sb.append("\n")

        sb.append("================ 运行环境 ================\n")
        sb.append("App 版本    : ").append(facts.appVersionName)
            .append(" (").append(facts.appVersionCode).append(")")
            .append(if (facts.isDebug) " [debug]" else "").append("\n")
        sb.append("包名        : ").append(facts.packageName).append("\n")
        sb.append("系统        : Android ").append(facts.androidRelease)
            .append(" (API ").append(facts.sdkInt).append(")\n")
        sb.append("机型        : ").append(facts.manufacturer)
            .append(" / ").append(facts.brand)
            .append(" / ").append(facts.model).append("\n")
        sb.append("CPU 架构    : ").append(facts.abi).append("\n")
        sb.append("语言        : ").append(facts.locale).append("\n")
        sb.append("报告格式    : v").append(FORMAT_VERSION).append("\n")
        sb.append("\n")

        sb.append("================ 崩溃前发生了什么（新 → 旧）================\n")
        if (facts.crumbs.isEmpty()) {
            sb.append("（没有记录到任何操作痕迹，说明崩溃发生得很早）\n")
        } else {
            // 报告里按「新 → 旧」排：读的人最想知道的是「崩之前最后一下点了什么」。
            for (c in facts.crumbs.asReversed()) {
                sb.append(formatTime(c.atMillis, facts.timeZoneId).substring(11))
                    .append("  ").append(pad(c.tag, 6)).append("  ").append(c.text).append("\n")
            }
        }
        sb.append("\n")
        sb.append("================ 报告结束 ================\n")
        sb.append("说明：这份报告只覆盖 Java/Kotlin 层的未捕获异常。\n")
        sb.append("原生崩溃（NDK、底层库）和「应用无响应（ANR）」不会产生报告 —— 那两种情况下\n")
        sb.append("进程是被系统直接杀掉的，任何代码都来不及跑。那种问题只能靠 adb logcat 或\n")
        sb.append("系统「开发者选项 → 错误报告」来抓。\n")

        return redact(sb.toString(), facts.secrets)
    }

    /** 分享时的标题，例如 `南通大学课表崩溃报告 2026-10-08 21:04`。 */
    fun subject(facts: CrashFacts): String =
        "南通大学课表崩溃报告 " + formatTime(facts.atMillis, facts.timeZoneId).substring(0, 16)

    /**
     * 抹掉能认出「这是谁」的东西。
     *
     * 两条规则：
     * - [secrets] 里逐个字面替换（学号是已知的，最可靠）；
     * - **连续的 10 位或 11 位数字**整体替换 —— 学号 10 位、手机号 11 位。
     *   刻意不匹配更长的数字串：`System.currentTimeMillis()` 是 13 位、
     *   `20261008` 这种日期是 8 位，它们出现在堆栈里很正常，抹掉反而让报告没法读。
     *   用前后不接数字的断言保证「整段」匹配，而不是长数字串里的一截。
     */
    fun redact(text: String, secrets: List<String>): String {
        var out = text
        for (s in secrets) {
            if (s.length >= 4) out = out.replace(s, REDACTED)
        }
        out = ELEVEN_DIGITS.replace(out, REDACTED)
        out = TEN_DIGITS.replace(out, REDACTED)
        return out
    }

    private val ELEVEN_DIGITS = Regex("(?<!\\d)\\d{11}(?!\\d)")
    private val TEN_DIGITS = Regex("(?<!\\d)\\d{10}(?!\\d)")

    // ------------------------------------------------------------------ 小工具

    private fun formatTime(millis: Long, timeZoneId: String): String {
        val fmt = SimpleDateFormat(TIME_PATTERN, Locale.US)
        // 每调用一次新建一个 SimpleDateFormat：共享实例在多线程下会读到别人的 Calendar，
        // 输出乱序时间甚至抛异常。报告本身就是要用来查崩溃的，不能自己再添一个坑。
        fmt.timeZone = TimeZone.getTimeZone(timeZoneId)
        return fmt.format(Date(millis))
    }

    private fun zoneText(offsetMinutes: Int): String {
        val sign = if (offsetMinutes < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetMinutes)
        return "UTC%s%02d:%02d".format(Locale.US, sign, abs / 60, abs % 60)
    }

    private fun mb(bytes: Long): String = "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)

    private fun durationText(millis: Long): String {
        val total = millis / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "$h 小时 $m 分 $s 秒"
            m > 0 -> "$m 分 $s 秒"
            else -> "$s 秒"
        }
    }

    private fun pad(s: String, width: Int): String =
        if (s.length >= width) s else s + " ".repeat(width - s.length)
}
