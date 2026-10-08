package com.ntu.schedule.core

/**
 * 正方教务 V9 课表相关接口。
 *
 * 端点全部在真机账号上实测通过（2026-10-08）：
 * - 课表页：`GET  /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default`
 * - 个人课表：`POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151`
 * - 校历：`POST /jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N2154`
 *
 * 请求体与响应形态均按实测结果固定下来，并在注释里标明「为什么是这个值」，
 * 方便学校升级后对照排查。
 */
class ScheduleApi(
    private val http: HttpClient,
    private val jwBase: String = CasAuthenticator.JW_BASE,
) {

    data class TermOption(val value: String, val label: String)

    /** 学年 + 学期选项，来自课表页的两个 select。 */
    data class Terms(val years: List<TermOption>, val terms: List<TermOption>, val currentYear: String, val currentTerm: String)

    data class FetchResult(
        val schedule: Schedule,
        /** 原始响应，便于排障与「解析器失灵」时的兜底。 */
        val rawScheduleJson: String,
        val rawCalendarJson: String,
        /** 请求轨迹，出错时给用户看「卡在哪一步」。 */
        val trace: List<String>,
    )

    sealed class Result {
        data class Success(val data: FetchResult) : Result()

        /**
         * 试过的学期确实都没有课。
         *
         * **这不是错误**：新学期的课可能还没排出来，换个候选学期接着试就是了。
         * 扫完一整轮候选还是空，才由调用方翻译成给用户看的话。
         */
        data object Empty : Result()

        data class NotLoggedIn(val message: String) : Result()
        data class Failed(val message: String, val status: Int = 0) : Result()
    }

    private fun ajaxHeaders(referer: String) = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer,
        "Origin" to jwBase,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
    )

    private val indexPage: String
        get() = "$jwBase/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"

    /**
     * 会话是否已经失效。
     *
     * 判据就是「被弹回了统一身份认证登录页」：地址里带 `authserver`，页面上带 `pwdEncryptSalt`。
     * 宁可漏判（后面的请求自己会失败并给出更准的错），也不要误判 ——
     * 把「其实登录着」说成「登录已失效」会让用户白白重输一次密码。
     */
    private fun sessionLost(url: String, body: String): Boolean =
        url.contains("authserver") || body.contains("pwdEncryptSalt")

    /** 打开课表页，顺带确认会话仍然有效，并读出可选学年/学期。 */
    fun loadTerms(): Terms? {
        val r = http.follow(indexPage)
        if (r.status != 200) return null
        // 被重定向回登录页 = 会话已失效
        if (sessionLost(r.url, r.body)) return null

        val years = FormEncoder.parseSelectOptions(r.body, "xnm").map { TermOption(it.value, it.label) }
        val terms = FormEncoder.parseSelectOptions(r.body, "xqm").map { TermOption(it.value, it.label) }
        // 页面 selected 的才是当前学期；没有再退回「按显示文本匹配」的策略
        val selectedYear = FormEncoder.parseSelectOptions(r.body, "xnm").firstOrNull { it.selected }?.value
        val selectedTerm = FormEncoder.parseSelectOptions(r.body, "xqm").firstOrNull { it.selected }?.value

        return Terms(
            years = years,
            terms = terms,
            currentYear = selectedYear ?: years.lastOrNull()?.value.orEmpty(),
            currentTerm = selectedTerm ?: terms.lastOrNull()?.value ?: "3",
        )
    }

    /**
     * 按候选顺序逐个学期试，返回第一个真的有课的。
     *
     * 为什么要试多个学期：课表页上的默认学年学期不可信，见 [TermPicker] 的说明。
     * 课表页只打开一次，各候选共用同一次会话上下文。
     *
     * 遇到会话失效或 HTTP 错误就**立刻停**，不接着试下一个学期：那不是「换个学期就好」
     * 的事，继续试只会让用户多干等几个来回，而且每多发一次请求就多一次撞上学校
     * 账号风控的机会。
     *
     * @param candidates 非空；调用方保证第一个是最有希望的
     */
    fun fetchFirstAvailable(candidates: List<TermPicker.Term>): Result {
        val usable = candidates.filter { it.isUsable }
        if (usable.isEmpty()) return Result.Failed("没有可查询的学期，请重新导入")

        val referer = indexPage
        // 打开课表页：既是「会话是否有效」的判据，也让服务端准备好当前学年学期上下文
        val page = http.follow(referer)
        if (page.status != 200 || sessionLost(page.url, page.body)) {
            return Result.NotLoggedIn("登录状态已失效，请重新导入")
        }

        for (term in usable) {
            when (val r = fetchTerm(referer, term)) {
                is Result.Success -> return r
                is Result.NotLoggedIn -> return r
                is Result.Failed -> return r
                is Result.Empty -> Unit // 这个学期没课，接着试下一个候选
            }
        }
        return Result.Empty
    }

    /** 拉一个学期的课表与校历。调用方保证课表页已经打开过（Referer 与上下文都靠它）。 */
    private fun fetchTerm(referer: String, term: TermPicker.Term): Result {
        // 实测有效的最小请求体；xsdm/kclbdm 留空表示「不按学生/课程类别过滤」
        val body = FormEncoder.encode(
            listOf(
                "xnm" to term.year,
                "xqm" to term.term,
                "kzlx" to "ck",
                "xsdm" to "",
                "kclbdm" to "",
            )
        )

        val kb = http.postForm("$jwBase/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151", decodePairs(body), ajaxHeaders(referer))
        if (kb.status == 401 || kb.status == 403 || sessionLost(kb.url, kb.body)) {
            return Result.NotLoggedIn("登录状态已失效，请重新导入")
        }
        if (kb.status != 200) {
            return Result.Failed("获取课表失败（HTTP ${kb.status}）", kb.status)
        }
        if (hasNoCourses(kb.body)) {
            return Result.Empty
        }

        // 校历失败不应导致整次导入失败：没有校历仍能按默认 19 周显示
        val calendar = runCatching {
            http.postForm(
                "$jwBase/jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N2154",
                mapOf("xnm" to term.year, "xqm" to term.term),
                ajaxHeaders(referer),
            )
        }.getOrNull()

        val calendarBody = calendar?.takeIf { it.status == 200 }?.body.orEmpty()
        val schedule = ScheduleParser.parse(kb.body, calendarBody, importedAt = System.currentTimeMillis())

        return Result.Success(
            FetchResult(
                schedule = schedule,
                rawScheduleJson = kb.body,
                rawCalendarJson = calendarBody,
                trace = http.trace.toList(),
            )
        )
    }

    /** 响应里确实没有课程（区分「空课表」与「解析失败」）。 */
    private fun hasNoCourses(body: String): Boolean {
        if (body.isBlank()) return true
        val root = JsonValue.parse(body)
        if (root is JsonValue.Null) return true
        if (root["kbList"] == null && root["rows"] == null && root["datas"] == null) return true
        return root.collectCourseArrays().none { it.strOf("kcmc", "courseName").isNotBlank() }
    }

    /** POST 用已编码的字符串直接发出，这里还原成键值对，避免二次编码。 */
    private fun decodePairs(encoded: String): Map<String, String> =
        encoded.split("&").associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            FormEncoder.unescape(k) to FormEncoder.unescape(v)
        }
}
