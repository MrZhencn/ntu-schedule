package com.ntu.schedule.core

/**
 * 统一身份认证（CAS）登录 + 进入教务系统的完整流程。南通大学走的是这条。
 *
 * 端到端链路（2026-10-08 用真实账号实测确认）：
 * ```
 * ① GET  https://authserver.ntu.edu.cn/authserver/login?service=<教务入口>
 *         → 200 登录页，从中取 execution / lt / pwdEncryptSalt
 * ② GET  /authserver/checkNeedCaptcha.htl?username=...   （必须先查，见下）
 * ③ POST /authserver/login?service=<教务入口>            （密码 AES 加密后提交）
 *         → 302 Location: <service>?ticket=ST-xxx
 *         密码错误返回 **HTTP 401**；缺 service 参数返回 **HTTP 500**
 * ④ 跟随 302 到 https://tdjw.ntu.edu.cn/sso/jziotlogin?ticket=...
 *         → 302 → /jwglxt/...  此时才拿到教务会话
 * ```
 *
 * 两条用真实账号换来的硬约束：
 *
 * 1. **不要为了「探测」而多取一次登录页。** Spring CAS 的 `execution` 是会话级递增的
 *    CSRF token：每 GET 一次登录页就 +1（e1s1 → e2s1）。先探测再取表单会让 token 过期，
 *    提交直接 500。所以这里只取一次，且立刻使用同一份响应里的字段。
 * 2. **POST 必须带上 `?service=`。** 页面 `<form action>` 是裸的 `/authserver/login`，
 *    靠页面 JS 在提交前补上；不补则 500（这很容易被误判成「密码错」）。
 *
 * 安全约束：连续 5 次密码错误会锁定账号（页面 `_badCredentialsCount = 5`），因此
 * 遇到验证码、或任何无法确定成功的情况一律**中止并如实报告**，绝不重试密码。
 *
 * 三个地址常量都写死在这个文件里：换学校要改的是它们，而不是调用方。
 */
class CasAuthenticator(private val http: HttpClient) {

    /** 教务系统基址。登录成功后课表接口要打在同一个地址上（会话靠 Cookie 绑定）。 */
    val jwBase: String = JW_BASE

    /** 教务入口（CAS 的 service 参数）。实测经它跳转后才能进课表。 */
    val serviceUrl: String get() = jwBase + SERVICE_PATH

    fun loginUrl(): String = "$AUTH_BASE/authserver/login?service=" + CasFormParser.urlEncode(serviceUrl)

    /**
     * 完整登录。
     *
     * @param username 学号
     * @param password 明文密码，仅在本方法内存在，绝不落盘/打日志
     */
    fun login(username: String, password: String): AuthResult {
        // ① 取登录页 —— 只取这一次
        val page = http.follow(loginUrl(), headers = mapOf("Accept" to "text/html,application/xhtml+xml"))
        if (page.status != 200) {
            return AuthResult.Failed("无法打开统一身份认证登录页（HTTP ${page.status}）", page.status)
        }
        val form = CasFormParser.parse(page.body, page.url)
            ?: return AuthResult.Failed("登录页结构已变化，未能解析出账号密码表单")
        if (form.errorMessage.isNotEmpty()) {
            return AuthResult.Failed("认证服务返回：${form.errorMessage}")
        }

        // ② 先问服务端本账号是否需要验证码。
        //    必须先查：图形验证码无法自动识别，一旦带着空验证码提交就会计入错误次数（共 5 次）。
        if (!captchaNotRequired(username, page.url, form.needCaptcha)) {
            return AuthResult.NeedCaptcha(
                "学校要求输入图形验证码，App 无法自动识别。请在浏览器登录一次教务系统，" +
                    "或稍后重试（连续输错 5 次会锁定账号，请不要在 App 里反复尝试）。"
            )
        }

        // ③ 构造提交体。字段全部来自页面，不硬编码 —— 学校改模板后仍能用。
        val fields = LinkedHashMap(form.fields)
        fields["username"] = username
        if (form.salt.isEmpty()) {
            // 没有 salt 说明页面结构变了。宁可失败也不要提交明文/未加密密码。
            return AuthResult.Failed("登录页缺少 pwdEncryptSalt，无法安全地加密密码")
        }
        fields["password"] = AesPassword.encryptPassword(password, form.salt)
        // 明文框不提交（parser 已剔除），这里再兜一层保险
        fields.remove("passwordText")

        val resp = http.send(
            "POST",
            form.action,
            FormEncoder.encode(fields),
            mapOf(
                "Referer" to page.url,
                "Origin" to AUTH_BASE,
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                "Accept" to "text/html,application/xhtml+xml",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )

        when (resp.status) {
            401 -> return AuthResult.BadCredentials("学号或密码不正确（学校返回 401）。请核对后重试，不要连续尝试。")
            500 -> return AuthResult.Failed("认证服务返回 500：通常是提交缺少 service 参数或会话已过期。")
            in 200..299 -> {
                val err = CasFormParser.extractError(resp.body)
                if (err.isNotEmpty()) return AuthResult.BadCredentials(err)
                // 200 却停在登录页 = 没通过（例如要求验证码但页面没显式标注）
                if (resp.body.contains("pwdEncryptSalt")) {
                    return AuthResult.BadCredentials(
                        CasFormParser.extractError(resp.body).ifEmpty { "登录未通过，请核对账号密码" }
                    )
                }
            }
        }

        // ④ 跟随携带 ticket 的重定向回教务系统
        val landing = if (resp.isRedirect && !resp.location.isNullOrBlank()) {
            http.follow(HttpClient.absolute(resp.location!!, resp.url))
        } else {
            resp
        }

        val landedOnLoginAgain = landing.body.contains("pwdEncryptSalt") || landing.url.contains("authserver")
        if (landedOnLoginAgain) {
            return AuthResult.Failed("登录后未能进入教务系统（被重定向回认证页），请稍后重试")
        }
        if (landing.status >= 400) {
            return AuthResult.Failed("进入教务系统失败（HTTP ${landing.status}）", landing.status)
        }
        return AuthResult.Success(landing.url)
    }

    /**
     * 判断「本次登录不需要验证码」。
     *
     * 三重依据，任一环节不确定都返回 false（保守拒绝登录 —— 冒锁定账号的风险去试密码不值得）：
     * 1. 登录页里存在验证码输入框 → 需要；
     * 2. `checkNeedCaptcha.htl` 明确回答 `isNeed:false` → 不需要；
     * 3. 接口不可用或响应无法识别 → 视为需要。
     */
    private fun captchaNotRequired(username: String, referer: String, pageShowsCaptcha: Boolean): Boolean {
        if (pageShowsCaptcha) return false
        if (username.isBlank()) return false

        val url = "$AUTH_BASE/authserver/checkNeedCaptcha.htl?username=" +
            CasFormParser.urlEncode(username) + "&_=" + System.currentTimeMillis()
        return try {
            val r = http.get(
                url,
                mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to referer,
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
            )
            if (r.status != 200) return false
            val body = r.body.trim()
            if (body.isEmpty()) return false
            val root = JsonValue.parse(body)
            // 实测该接口返回形如 {"isNeed":false}；不同版本可能用 needCaptcha
            val need = root["isNeed"]?.asBool ?: root["needCaptcha"]?.asBool
            need?.let { !it } ?: false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** 统一身份认证（CAS）服务器。 */
        const val AUTH_BASE = "https://authserver.ntu.edu.cn"

        /** 教务系统。课表接口全部打在它下面。 */
        const val JW_BASE = "https://tdjw.ntu.edu.cn"

        /**
         * CAS 登录成功后回跳的教务入口路径。
         *
         * 不能省成「直接跳教务首页」：只有经它换到正方自己的会话 Cookie，
         * 后面的 `/jwglxt/...` 才认得我们。
         */
        const val SERVICE_PATH = "/sso/jziotlogin"
    }
}
