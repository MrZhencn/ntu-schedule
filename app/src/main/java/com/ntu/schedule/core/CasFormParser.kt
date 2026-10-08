package com.ntu.schedule.core

/**
 * 从统一身份认证（CAS）登录页 HTML 里抽取提交表单所需的一切。
 *
 * 为什么必须解析而不是硬编码：`pwdEncryptSalt`、`execution` 每次会话都不同，
 * `lt` 可能为空也可能有值。任何硬编码都会在下次登录时失效。
 *
 * 实测页面结构（`authserver.ntu.edu.cn/authserver/login?service=...`，17083 字节）：
 * - 账号密码表单的 id 是 **`pwdFromId`**（**不是** `casLoginForm`，那个名字是旧版/文档误传）。
 *   同页还有 `loginFromId`（fido 扫码）、`phoneFromId`（动态码）、`qrLoginForm`（二维码），
 *   所以必须精确定位，取错表单会导致提交字段不对。
 * - 隐藏字段：`lt`、`execution`、`_eventId`、`cllt`、`dllt`、`pwdEncryptSalt` 等。
 * - 可见输入框是 `#username` 与 `#password`（密文实际提交到隐藏的 `#password`，
 *   页面 JS 会把明文框的值加密后写入）。
 * - 表单 `action` 是**裸的** `/authserver/login`，缺 `?service=`；页面 login.js 用
 *   `utils.setUrlParam("pwdFromId","?service",...)` 在提交前补上。我们必须等价地补，
 *   否则服务端直接 500（不是 401 —— 401 是密码错，500 是请求缺 service）。
 */
object CasFormParser {

    data class LoginForm(
        /** 表单内所有 input 的 name→value，已剔除密码明文框。 */
        val fields: MutableMap<String, String>,
        /** `pwdEncryptSalt`，密码 AES 的密钥。 */
        val salt: String,
        /** 提交地址（已补上 service 参数）。 */
        val action: String,
        /** 是否要求验证码。 */
        val needCaptcha: Boolean,
        /** 错误提示（若页面直接返回了错误）。 */
        val errorMessage: String,
    )

    private val FORM_ID = "pwdFromId"

    /** 提取指定 id 的 `<form>...</form>` 片段。 */
    fun extractForm(html: String, formId: String = FORM_ID): String? {
        val openTag = Regex(
            "<form[^>]*\\bid\\s*=\\s*[\"']" + Regex.escape(formId) + "[\"'][^>]*>",
            RegexOption.IGNORE_CASE,
        )
        val m = openTag.find(html) ?: return null
        val end = html.indexOf("</form>", m.range.last)
        return if (end < 0) html.substring(m.range.first) else html.substring(m.range.first, end)
    }

    /**
     * @param html 登录页全文
     * @param pageUrl 登录页最终 URL（含 `?service=`），用于解析相对 action
     */
    fun parse(html: String, pageUrl: String): LoginForm? {
        val form = extractForm(html) ?: return null

        val inputs = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(form)
        val fields = LinkedHashMap<String, String>()
        var needCaptcha = false
        var saltFromForm: String? = null

        for (tag in inputs) {
            val raw = tag.value
            val id = attr(raw, "id") ?: ""
            val type = (attr(raw, "type") ?: "text").lowercase()
            val value = decodeEntities(attr(raw, "value") ?: "")

            // 实测：`<input type="hidden" id="pwdEncryptSalt" value="GRPrkQnZmgjfVELs" />`
            // 这个 input **没有 name 属性**，只靠 id 标识！早期实现按 name 过滤会把它整体跳过，
            // 导致 salt 为空、密码不加密，登录必然失败。必须在这里单独取。
            if (id == "pwdEncryptSalt") {
                saltFromForm = value
                continue
            }

            val name = attr(raw, "name") ?: continue

            if (type == "checkbox" || type == "radio") {
                if (raw.contains("checked", ignoreCase = true) && value.isNotEmpty()) fields[name] = value
                continue
            }
            if (type == "submit" || type == "button" || type == "reset") continue
            if (type == "captcha") needCaptcha = true
            // 明文密码框：`<input id="password" name="passwordText" type="password">`。
            // 绝不能提交它 —— 服务端只认被 JS 写入密文的 `<input id="saltPassword" name="password">`。
            if (id == "passwordText" || name == "passwordText") continue
            fields[name] = value
        }

        // 必须存在承载密文的 password 字段，否则说明页面结构变了，宁可报错也不要发出错误请求
        // （密码错会计入 5 次锁定，不能拿用户账号试错）。
        if (!fields.containsKey("password")) return null

        val actionRaw = Regex("<form[^>]*\\baction\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(form)?.groupValues?.get(1)
            ?: "/authserver/login"

        return LoginForm(
            fields = fields,
            salt = saltFromForm ?: extractSalt(html).orEmpty(),
            action = buildAction(decodeEntities(actionRaw), pageUrl),
            needCaptcha = needCaptcha,
            errorMessage = extractError(html),
        )
    }

    /**
     * 把裸 action 补成带 service 的绝对地址。
     * 这是踩过的真实故障：少了 service 参数服务端返回 500。
     */
    fun buildAction(action: String, pageUrl: String): String {
        val base = baseOf(pageUrl)
        val service = queryParam(pageUrl, "service")
        val absolute = if (action.startsWith("http://") || action.startsWith("https://")) {
            action
        } else {
            base.trimEnd('/') + "/" + action.trimStart('/')
        }
        if (service.isNullOrEmpty()) return absolute
        if (queryParam(absolute, "service") != null) return absolute
        val sep = if (absolute.contains('?')) "&" else "?"
        return absolute + sep + "service=" + urlEncode(service)
    }

    /** 兜底：整页搜 `pwdEncryptSalt`（某些模板会把它放在 form 之外）。 */
    fun extractSalt(html: String): String? {
        val m = Regex(
            "id\\s*=\\s*[\"']pwdEncryptSalt[\"'][^>]*value\\s*=\\s*[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE,
        ).find(html)
        if (m != null) return m.groupValues[1]
        return Regex("pwdEncryptSalt[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
    }

    /** 页面顶部常见错误提示容器。 */
    fun extractError(html: String): String {
        for (pattern in listOf(
            "<span[^>]*id\\s*=\\s*[\"']msg[\"'][^>]*>(.*?)</span>",
            "<div[^>]*id\\s*=\\s*[\"']showErrorTip[\"'][^>]*>(.*?)</div>",
            "<p[^>]*class\\s*=\\s*[\"'][^\"']*error[^\"']*[\"'][^>]*>(.*?)</p>",
        )) {
            val m = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            val text = m?.groupValues?.getOrNull(1)?.let { stripTags(it) }?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun stripTags(s: String): String =
        decodeEntities(s.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b" + Regex.escape(name) + "\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag) ?: return null
        return m.groupValues[1]
    }

    fun decodeEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")

    private fun baseOf(url: String): String {
        val m = Regex("^(https?://[^/]+)").find(url) ?: return url
        return m.groupValues[1]
    }

    fun queryParam(url: String, key: String): String? {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return null
        val raw = q.substringBefore('#')
        for (pair in raw.split('&')) {
            val k = pair.substringBefore('=', "")
            if (k == key) return urlDecode(pair.substringAfter('=', ""))
        }
        return null
    }

    fun urlEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") sb.append(c)
            else sb.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun urlDecode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) { out.write(hex); i += 3 } else { out.write(c.code); i++ }
                }
                c == '+' -> { out.write(' '.code); i++ }
                else -> { out.write(c.code); i++ }
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
