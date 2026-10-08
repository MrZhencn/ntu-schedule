package com.ntu.schedule.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 极简 CookieJar：按 host + path 作用域存取，行为对齐浏览器，够正方/CAS 这套体系用。
 *
 * 刻意不用 `java.net.CookieManager`：它对跨域重定向（authserver → tdjw）的处理
 * 依赖 `CookiePolicy.ACCEPT_ORIGINAL_SERVER`，会把 CASTGC 之外的路径 cookie 丢掉，
 * 而正方正是靠路径 cookie（`/jwglxt`）维持会话。
 */
class CookieJar {

    data class Cookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String = "/",
        val secure: Boolean = false,
    ) {
        val isExpired: Boolean get() = value.isEmpty()
    }

    private val cookies = mutableListOf<Cookie>()

    /** 解析响应 `Set-Cookie` 头并更新存储。 */
    fun store(requestHost: String, setCookieHeaders: List<String>) {
        for (header in setCookieHeaders) {
            val parts = header.split(";")
            val pair = parts.firstOrNull()?.trim().orEmpty()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()

            var domain = requestHost.lowercase()
            var path = "/"
            var secure = false
            for (i in 1 until parts.size) {
                val attr = parts[i].trim()
                val lower = attr.lowercase()
                when {
                    lower.startsWith("domain=") -> {
                        val d = attr.substringAfter('=').trim().trimStart('.')
                        // 只接受与请求 host 匹配的域，防止被伪造的 Set-Cookie 注入别的域
                        if (d.isNotEmpty() && (requestHost.equals(d, true) || requestHost.endsWith(".$d", true))) {
                            domain = d
                        }
                    }
                    lower.startsWith("path=") -> path = attr.substringAfter('=').trim().ifEmpty { "/" }
                    lower.startsWith("secure") -> secure = true
                }
            }

            cookies.removeAll { it.name == name && it.domain == domain && it.path == path }
            // 服务端清除 cookie 时发 value 为空 + Max-Age=0，此时仅删除不再存入
            if (value.isNotEmpty()) {
                cookies += Cookie(name, value, domain, path, secure)
            }
        }
    }

    /** 当前对某 URL 生效的 cookie，按 path 长度降序便于调试。 */
    fun cookiesFor(url: String): List<Cookie> {
        val host = hostOf(url).lowercase()
        val path = pathOf(url)
        val secureScheme = url.startsWith("https://", ignoreCase = true)
        return cookies.filter { c ->
            if (c.isExpired) return@filter false
            if (c.secure && !secureScheme) return@filter false
            val domainOk = host == c.domain || host.endsWith("." + c.domain)
            domainOk && path.startsWith(c.path)
        }.sortedByDescending { it.path.length }
    }

    fun headerFor(url: String): String? {
        val list = cookiesFor(url)
        if (list.isEmpty()) return null
        return list.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** 直接注入已有 cookie（用于「复用会话」场景）。 */
    fun seed(host: String, cookieHeader: String) {
        for (pair in cookieHeader.split(";")) {
            val t = pair.trim()
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val name = t.substring(0, eq).trim()
            val value = t.substring(eq + 1).trim()
            if (value.isEmpty()) continue
            cookies.removeAll { it.name == name && it.domain == host.lowercase() }
            cookies += Cookie(name, value, host.lowercase())
        }
    }

    fun clear() = cookies.clear()

    fun snapshot(): List<Cookie> = cookies.toList()

    companion object {
        fun hostOf(url: String): String =
            Regex("^https?://([^/:]+)").find(url)?.groupValues?.get(1) ?: ""

        fun pathOf(url: String): String {
            val rest = url.replace(Regex("^https?://[^/]+"), "")
            val p = rest.substringBefore('?').substringBefore('#')
            return p.ifEmpty { "/" }
        }
    }
}

/**
 * 不依赖任何第三方库的表单编码。
 * 与原 Node 验证脚本一致：空格编成 `%20`（而非 `+`），正方两端都能正确解析。
 */
object FormEncoder {

    fun encode(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (k, v) -> "${escape(k)}=${escape(v)}" }

    fun encode(map: Map<String, String>): String =
        encode(map.entries.map { it.key to it.value })

    fun escape(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun unescape(s: String): String = URLDecoder.decode(s, "UTF-8")

    /**
     * 提取 HTML 里 `#xnm` / `#xqm` 两个 select 的选项，用于「学年/学期选择」。
     *
     * 实测选项形如 `<option selected value="2026">2026-2027</option>`。**必须保留 selected 标记**：
     * 页面选中的那个才是当前学期，按「第一个选项」或「最后一个选项」猜都会选错学期。
     */
    fun parseSelectOptions(html: String, selectId: String): List<SelectOption> {
        val open = Regex("<select[^>]*\\bid\\s*=\\s*[\"']" + Regex.escape(selectId) + "[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(html) ?: return emptyList()
        val end = html.indexOf("</select>", open.range.last)
        if (end < 0) return emptyList()
        val body = html.substring(open.range.last, end)
        return Regex("<option([^>]*)>(.*?)</option>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(body)
            .map { m ->
                val attrs = m.groupValues[1]
                val value = Regex("\\bvalue\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1).orEmpty()
                val text = CasFormParser.decodeEntities(m.groupValues[2].replace(Regex("<[^>]+>"), "")).trim()
                SelectOption(
                    value = value,
                    label = text,
                    selected = Regex("\\bselected\\b", RegexOption.IGNORE_CASE).containsMatchIn(attrs),
                )
            }
            .filter { it.value.isNotEmpty() }
            .toList()
    }
}

/** `<option>` 的结构化表示。 */
data class SelectOption(val value: String, val label: String, val selected: Boolean)

/** 字节工具：把流读干（部分正方接口不返回 Content-Length）。 */
internal fun ByteArrayOutputStream.readAllFrom(input: java.io.InputStream): ByteArray {
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        write(buf, 0, n)
    }
    return toByteArray()
}
