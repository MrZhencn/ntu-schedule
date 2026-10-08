package com.ntu.schedule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 崩溃报告。
 *
 * 这份文件是用户要**发给别人**的，所以两条底线必须钉死：
 * 1. 该有的信息一个都不能少（否则拿到报告也查不出问题，等于让用户白折腾一趟）；
 * 2. 学号、手机号一个都不能留（用户发的时候不会逐字检查，抹不干净就是我们在泄露）。
 */
class CrashReportTest {

    /** 2026-10-08 21:04:00（北京时间）。 */
    private val crashAt = Instant.parse("2026-10-08T13:04:00Z").toEpochMilli()

    private fun facts(
        throwableText: String = "java.lang.IllegalStateException: boom\n\tat com.ntu.schedule.MainActivity.x(MainActivity.kt:42)",
        crumbs: List<Crumb> = emptyList(),
        secrets: List<String> = emptyList(),
        timeZoneId: String = "Asia/Shanghai",
        timeZoneOffsetMinutes: Int = 480,
        atMillis: Long = crashAt,
    ) = CrashFacts(
        atMillis = atMillis,
        timeZoneId = timeZoneId,
        timeZoneOffsetMinutes = timeZoneOffsetMinutes,
        threadName = "main",
        throwableText = throwableText,
        appVersionName = "1.0",
        appVersionCode = 1,
        packageName = "com.ntu.schedule.debug",
        isDebug = true,
        androidRelease = "14",
        sdkInt = 34,
        manufacturer = "Xiaomi",
        brand = "Redmi",
        model = "2312DRA50C",
        abi = "arm64-v8a",
        locale = "zh-CN",
        uptimeMillis = 3 * 60_000L + 25_000L,
        usedHeapBytes = 32L * 1024 * 1024,
        maxHeapBytes = 256L * 1024 * 1024,
        crumbs = crumbs,
        secrets = secrets,
    )

    @Test
    fun `四个分节一个都不能少`() {
        val report = CrashReport.render(facts())
        assertTrue(report.contains("崩溃现场"))
        assertTrue(report.contains("异常"))
        assertTrue(report.contains("运行环境"))
        assertTrue(report.contains("崩溃前发生了什么"))
        assertTrue(report.contains("报告结束"))
    }

    @Test
    fun `异常原文一字不改地进报告`() {
        // 报告的价值就在这段堆栈上，任何「美化」都会毁掉它。
        val stack = "java.lang.NullPointerException: Attempt to invoke virtual method 'int com.ntu.schedule.core.Course.getStartPeriod()' on a null object reference\n" +
            "\tat com.ntu.schedule.ui.WeekScreenKt.WeekBlock(WeekScreen.kt:88)\n" +
            "\tat androidx.compose.runtime.RecomposeScopeImpl.compose(RecomposeScopeImpl.kt:191)"
        val report = CrashReport.render(facts(throwableText = stack))
        assertTrue(report.contains("Attempt to invoke virtual method"))
        assertTrue(report.contains("WeekScreen.kt:88"))
        assertTrue(report.contains("RecomposeScopeImpl.kt:191"))
    }

    @Test
    fun `报错时的机型系统版本与包名都可查`() {
        val report = CrashReport.render(facts())
        assertTrue(report.contains("com.ntu.schedule.debug"))
        assertTrue(report.contains("Android 14"))
        assertTrue(report.contains("API 34"))
        assertTrue(report.contains("Xiaomi / Redmi / 2312DRA50C"))
        assertTrue(report.contains("arm64-v8a"))
        assertTrue(report.contains("zh-CN"))
        assertTrue(report.contains("1.0 (1) [debug]"))
        assertTrue(report.contains("报告格式    : v1"))
    }

    @Test
    fun `时间按手机所在时区写成人能读的样子`() {
        val report = CrashReport.render(facts())
        assertTrue(report.contains("2026-10-08 21:04:00"))
        assertTrue(report.contains("UTC+08:00"))
        assertTrue(report.contains("Asia/Shanghai"))
    }

    @Test
    fun `同一时刻换个时区显示成当地那一刻`() {
        // 报告是给开发者看的，不能受「手机语言」影响；时区必须如实反映用户当时所在的地方。
        val report = CrashReport.render(
            facts(timeZoneId = "America/New_York", timeZoneOffsetMinutes = -240),
        )
        assertTrue(report.contains("2026-10-08 09:04:00"))
        assertTrue(report.contains("UTC-04:00"))
        assertTrue(report.contains("America/New_York"))
    }

    @Test
    fun `进程存活时长与内存占用写进报告`() {
        val report = CrashReport.render(facts())
        assertTrue(report.contains("3 分 25 秒"))
        assertTrue(report.contains("已用 32.0 MB / 上限 256.0 MB"))
    }

    @Test
    fun `面包屑按新到旧排 - 最后一下点了什么排在最前`() {
        val report = CrashReport.render(
            facts(
                crumbs = listOf(
                    Crumb(crashAt - 9_000L, "app", "进程启动"),
                    Crumb(crashAt - 5_000L, "import", "开始登录并导入课表"),
                    Crumb(crashAt - 1_000L, "ui", "切到周课表"),
                ),
            ),
        )
        val newest = report.indexOf("切到周课表")
        val middle = report.indexOf("开始登录并导入课表")
        val oldest = report.indexOf("进程启动")
        assertTrue("三条面包屑应该都在报告里", newest >= 0 && middle >= 0 && oldest >= 0)
        assertTrue("最新的那条应该排在最前", newest < middle && middle < oldest)
    }

    @Test
    fun `面包屑带时间与标签`() {
        val report = CrashReport.render(
            facts(crumbs = listOf(Crumb(crashAt - 60_000L, "ui", "打开菜单"))),
        )
        // 时间只保留 HH:mm:ss（日期在「崩溃现场」里已经写过一次）
        assertTrue(report.contains("21:03:00"))
        assertFalse(report.contains("2026-10-08 21:03:00"))
        assertTrue(report.contains("ui"))
        assertTrue(report.contains("打开菜单"))
    }

    @Test
    fun `没有任何面包屑时说明崩得很早`() {
        val report = CrashReport.render(facts(crumbs = emptyList()))
        assertTrue(report.contains("没有记录到任何操作痕迹"))
    }

    @Test
    fun `报告开头就写清楚这是什么 - 收报告的人可能不是开发者`() {
        val report = CrashReport.render(facts())
        assertTrue(report.startsWith("南通大学课表 · 崩溃报告"))
        assertTrue(report.contains("已抹掉学号等个人信息"))
    }

    @Test
    fun `报告末尾主动交代抓不到哪些崩溃`() {
        // 不写清楚的话，用户会以为「没有报告 = 没崩过」，反而更难排查。
        val report = CrashReport.render(facts())
        assertTrue(report.contains("Java/Kotlin 层的未捕获异常"))
        assertTrue(report.contains("ANR"))
        assertTrue(report.contains("adb logcat"))
    }

    @Test
    fun `分享标题带上崩溃时刻`() {
        assertEquals("南通大学课表崩溃报告 2026-10-08 21:04", CrashReport.subject(facts()))
    }

    // ------------------------------------------------------------------ 脱敏

    @Test
    fun `十位学号被抹掉`() {
        assertEquals("学号 ****** 登录成功", CrashReport.redact("学号 2025000001 登录成功", emptyList()))
    }

    @Test
    fun `十一位手机号被抹掉`() {
        assertEquals("tel ****** ok", CrashReport.redact("tel 13800138000 ok", emptyList()))
    }

    @Test
    fun `十三位时间戳与八位日期不动`() {
        // 这两个在堆栈与文件名里到处都是，抹掉报告就没法读了。
        assertEquals(
            "at=1760000000000 date=20261008 id=******",
            CrashReport.redact("at=1760000000000 date=20261008 id=1234567890", emptyList()),
        )
    }

    @Test
    fun `只替换整段数字而不是长数字串里的一截`() {
        assertEquals("******", CrashReport.redact("99999999999", emptyList()))
        // 15 位：从任何一位开始取 11 位，前后都会碰上数字，所以整段保留
        assertEquals("123456789012345", CrashReport.redact("123456789012345", emptyList()))
    }

    @Test
    fun `记在 secrets 里的学号按字面抹掉`() {
        // 学号也可能不是 10 位（比如留级的旧学号），这时靠位数猜不出来，只能靠已知值。
        assertEquals("user ****** logged in", CrashReport.redact("user 20231234 logged in", listOf("20231234")))
    }

    @Test
    fun `太短的 secret 不参与替换 - 免得把正常文字打成星号`() {
        assertEquals("abc123 正常显示", CrashReport.redact("abc123 正常显示", listOf("abc")))
    }

    @Test
    fun `报告里的学号不会跟着发出去`() {
        val studentId = "2025000001"
        val report = CrashReport.render(
            facts(
                throwableText = "java.lang.IllegalStateException: 读取 $studentId 的课表失败\n" +
                    "\tat com.ntu.schedule.data.ScheduleStore.read(ScheduleStore.kt:120)",
                crumbs = listOf(Crumb(crashAt - 2_000L, "import", "开始登录并导入课表 studentId=$studentId")),
                secrets = listOf(studentId),
            ),
        )
        assertFalse("学号绝不能出现在要发出去的文本里", report.contains(studentId))
        assertTrue(report.contains("******"))
        // 抹掉之后剩下的部分还得能读，否则脱敏就成了毁报告
        assertTrue(report.contains("ScheduleStore.kt:120"))
        assertTrue(report.contains("的课表失败"))
    }
}
