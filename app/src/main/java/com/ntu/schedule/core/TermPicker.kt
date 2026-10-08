package com.ntu.schedule.core

import java.util.Calendar
import java.util.TimeZone

/**
 * 该查哪个学期。
 *
 * 以前是直接拿课表页上 `selected` 的那个学年学期去查。这套默认值**并不可信** ——
 * 实测过另一套正方教务：服务端把上一学年的 `第 2 学期` 渲染成选中状态，而学生的课在
 * 新学年 `第 1 学期`，于是永远查出空课表，界面上只看到一句「该学期没有查询到课程」。
 *
 * 服务端那边也找不到可信的「当前学期」。课表页、登录落地页、课表接口的每一个字段
 * 都翻过了：
 * - 课表页里 `xnm` / `xqm` 两个 select 的 `selected` 是模板里写死的（页面里没有任何
 *   脚本去改它，所以也不是「浏览器会用 JS 纠正」的那种情况）；
 * - 课表接口返回的 `xsxx.XNM` / `xsxx.XQM` 只是**原样回显我们问的那个学期**
 *   （问 2025/12 就返回 2025/12，问 2026/3 就返回 2026/3，连 `KCMS` 课程门数都是
 *   跟着查询变的），拿它当依据等于自问自答；
 * - 登录落地页里 `xnm` / `xqm` / `学年` / `学期` 各 0 次命中。
 *
 * 所以只能自己排几个候选、逐个试到有课为止。排序原则是「用户最想看的那份课表排前面」：
 * 1. 按本机日期推算的当前学期 —— 绝大多数情况下这就是答案；
 * 2. 课表页给的默认值 —— 有些学校这里是对的；
 * 3. 同一学年的另一个学期；
 * 4. 上一学年的第二、第一学期 —— 新学期的课还没排出来时，至少能拿到上学期。
 *
 * 第 4 步是有代价的：如果新学期确实还没排课，用户会看到上学期的课表。所以调用方
 * **必须把最终用的学期名告诉用户**，让人一眼看出手上这份是哪一学期的。
 */
object TermPicker {

    /** 一个学期，对应正方接口的 `xnm`（学年）+ `xqm`（学期码）。 */
    data class Term(val year: String, val term: String) {

        /** 界面上给人看的说法，如 `2026-2027 学年 第 1 学期`。 */
        val label: String
            get() = ScheduleParser.buildTermName(year, term).ifBlank { "$year 学年 xqm=$term" }

        /** 两个字段都得有才敢拿去发请求。 */
        val isUsable: Boolean get() = year.isNotBlank() && term.isNotBlank()
    }

    /** 第一学期。 */
    const val FIRST = "3"

    /** 第二学期。 */
    const val SECOND = "12"

    /** 默认最多试几个。每多一个候选就多一次往返，用户要干等。 */
    const val DEFAULT_LIMIT = 5

    /**
     * 按可能性排好的候选学期。
     *
     * @param pageYear 课表页上 `xnm` 的默认值（读不到就传空串）
     * @param pageTerm 课表页上 `xqm` 的默认值（读不到就传空串）
     * @param nowMillis 用来推算当前学期；测试里固定住，免得用例到了九月就换答案
     * @param timeZoneId 为空则用本机时区
     * @param limit 最多返回几个候选，至少 1 个
     */
    fun candidates(
        pageYear: String,
        pageTerm: String,
        nowMillis: Long,
        timeZoneId: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<Term> {
        val (year, term) = currentTerm(nowMillis, timeZoneId)
        val other = if (term == FIRST) SECOND else FIRST
        return listOf(
            Term(year.toString(), term),
            Term(pageYear, pageTerm),
            Term(year.toString(), other),
            Term((year - 1).toString(), SECOND),
            Term((year - 1).toString(), FIRST),
        ).filter { it.isUsable }.distinct().take(limit.coerceAtLeast(1))
    }

    /**
     * 按日期推算当前学年学期。
     *
     * 学年从 9 月开始，所以 1 月和 2-8 月算的都是**上一个自然年**开的那个学年：
     * - 9-12 月：当年开的学年，第 1 学期；
     * - 1 月：还是那个学年的第 1 学期（期末考试与寒假都在里面）；
     * - 2-8 月：那个学年的第 2 学期（含暑假）。
     *
     * 只做「排第一」用，错了还有后面的候选兜着，所以不需要精确到开学日。
     */
    private fun currentTerm(nowMillis: Long, timeZoneId: String?): Pair<Int, String> {
        // 时区 id 无效时 getTimeZone 会退回 GMT，这里不做额外校验：
        // 反正只是个候选排序，真错了后面还有页面默认值接着试。
        val calendar = if (timeZoneId.isNullOrBlank()) {
            Calendar.getInstance()
        } else {
            Calendar.getInstance(TimeZone.getTimeZone(timeZoneId))
        }
        calendar.timeInMillis = nowMillis
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        return when {
            month >= 9 -> year to FIRST
            month == 1 -> (year - 1) to FIRST
            else -> (year - 1) to SECOND
        }
    }
}
