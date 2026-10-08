package com.ntu.schedule.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 一个够用的 HTTP 客户端：手动跟随重定向 + 自管 Cookie。
 *
 * 为什么不用 OkHttp 自带的 `followRedirects(true)`：
 * CAS 登录是一条 **跨域重定向链**（`tdjw.ntu.edu.cn` → `authserver.ntu.edu.cn`
 * → 带 ticket 回 `tdjw.ntu.edu.cn/sso/jziotlogin` → 再跳 `/jwglxt`）。
 * 手动接管才能：① 记录每一步用于排障；② 确保 CASTGC 与 JSESSIONID 被正确落到各自域；
 * ③ 在需要时对某个 POST 关闭跟随（例如想自己检查 302 的 Location 是否带 ticket）。
 */
class HttpClient(
    private val jar: CookieJar = CookieJar(),
    timeoutSeconds: Long = 25,
) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    data class Response(
        val url: String,
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

        val isRedirect: Boolean get() = status in 300..399
        val location: String? get() = header("Location")
    }

    /** 请求轨迹，便于把「导入失败」定位到具体一步。 */
    val trace = mutableListOf<String>()

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response = send("GET", url, null, headers)

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): Response =
        send("POST", url, FormEncoder.encode(form), headers)

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Response =
        send("POST", url, json, headers + ("Content-Type" to "application/json;charset=UTF-8"))

    /**
     * 发送请求（不跟随重定向），并把响应的 Set-Cookie 落到 jar 里。
     */
    fun send(method: String, url: String, body: String?, headers: Map<String, String>): Response {
        val builder = Request.Builder().url(url)

        for ((k, v) in headers) {
            runCatching { builder.header(k, v) }
        }
        jar.headerFor(url)?.let { runCatching { builder.header("Cookie", it) } }

        if (method == "POST") {
            val contentType = headers.entries
                .firstOrNull { it.key.equals("Content-Type", true) }
                ?.value ?: "application/x-www-form-urlencoded;charset=UTF-8"
            val payload = (body ?: "").toByteArray(Charsets.UTF_8)
            builder.post(payload.toRequestBody(contentType.toMediaType()))
        }

        val response = client.newCall(builder.build()).execute()
        response.use { res ->
            val text = res.body?.string().orEmpty()
            // 注意：OkHttp 4 的 Response.headerValues 返回 List<String>
            jar.store(CookieJar.hostOf(url), res.headers.values("Set-Cookie"))
            trace += "$method ${CookieJar.pathOf(url)} -> ${res.code}" +
                (res.header("Location")?.let { "  => $it" } ?: "")
            return Response(
                url = res.request.url.toString(),
                status = res.code,
                headers = res.headers.toMultimap(),
                body = text,
            )
        }
    }

    /**
     * 跟随重定向直到非 3xx。
     *
     * @param maxHops 最大跳数，防止服务端配置错误导致死循环
     * @return 最终响应；若超出跳数则返回最后一次重定向响应
     */
    fun follow(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        maxHops: Int = 12,
    ): Response {
        var currentUrl = url
        var currentMethod = method
        var currentBody = body
        var currentHeaders = headers
        var last: Response

        repeat(maxHops) {
            last = send(currentMethod, currentUrl, currentBody, currentHeaders)
            val loc = last.location
            if (!last.isRedirect || loc.isNullOrBlank()) return last

            currentUrl = absolute(loc, currentUrl)
            // 302/303 一律降级为 GET，且丢掉 POST 的 body 与 Content-Type —— 与浏览器一致。
            // 少了这一步，带 ticket 回跳时会把登录表单再 POST 一次，服务端报错。
            if (last.status == 301 || last.status == 302 || last.status == 303) {
                currentMethod = "GET"
                currentBody = null
                currentHeaders = currentHeaders - "Content-Type" - "Origin"
            }
        }
        return send(currentMethod, currentUrl, currentBody, currentHeaders)
    }

    fun clearCookies() = jar.clear()

    /**
     * 取出该地址会带上的 Cookie 头，用于把会话持久化下来（下次「刷新」不必再登录）。
     * 只存这一串，不存账号密码。
     */
    fun cookieHeaderFor(url: String): String = jar.headerFor(url).orEmpty()

    companion object {
        /** 把 Location 相对地址补成绝对地址。 */
        fun absolute(location: String, baseUrl: String): String {
            if (location.startsWith("http://") || location.startsWith("https://")) return location
            val origin = Regex("^(https?://[^/]+)").find(baseUrl)?.groupValues?.get(1) ?: return location
            return if (location.startsWith("/")) origin + location else origin + "/" + location
        }
    }
}
