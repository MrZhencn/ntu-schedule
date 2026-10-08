package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实抓取的 CAS 登录页**（`fixtures/cas-login.html`，17125 字节，
 * 取自 `https://authserver.ntu.edu.cn/authserver/login?service=...`）验证表单解析。
 *
 * 这里锁住的是一个真实踩过的坑：`pwdEncryptSalt` 那个 input **没有 name 属性**，
 * 只靠 id 标识；任何「按 name 收集 input」的实现都会漏掉它，于是密码不被加密、
 * 登录必然失败，而失败还会计入 5 次锁定。
 */
class CasFormParserTest {

    private val html = javaClass.classLoader!!
        .getResourceAsStream("fixtures/cas-login.html")!!
        .readBytes().toString(Charsets.UTF_8)

    private val pageUrl =
        "https://authserver.ntu.edu.cn/authserver/login" +
            "?service=https%3A%2F%2Ftdjw.ntu.edu.cn%2Fsso%2Fjziotlogin"

    private val form by lazy { CasFormParser.parse(html, pageUrl) }

    @Test
    fun `能定位到密码登录表单`() {
        assertNotNull(form)
        val segment = CasFormParser.extractForm(html)
        assertNotNull("应能提取到 id=pwdFromId 的 form 片段", segment)
        // 提取的片段必须真的是账号密码表单，而不是同页的 fido/二维码表单
        assertTrue(segment!!.contains("id=\"pwdFromId\""))
        assertTrue(segment.contains("saltPassword"))
        assertFalse("不应把二维码表单的 uuid 混进来", segment.contains("name=\"uuid\""))
    }

    @Test
    fun `提取到无name属性的pwdEncryptSalt`() {
        val f = form!!
        // 实测值就是这一个；若解析器改动导致取不到，这里立刻失败
        assertEquals("GRPrkQnZmgjfVELs", f.salt)
        assertEquals("salt 必须是 16 个 ASCII 字符", 16, f.salt.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `salt不会被写进提交字段`() {
        // pwdEncryptSalt 只是 JS 用来加密的变量，不是表单字段，提交它反而不符合浏览器行为
        assertFalse(form!!.fields.containsKey("pwdEncryptSalt"))
    }

    @Test
    fun `提交字段包含CAS必需项`() {
        val f = form!!.fields
        assertEquals("submit", f["_eventId"])
        assertEquals("userNameLogin", f["cllt"])
        assertEquals("generalLogin", f["dllt"])
        assertEquals("e1s1", f["execution"])
        assertTrue("用户名应预置为空串待填", f.containsKey("username"))
        // 密文承载字段：id=saltPassword name=password
        assertTrue("必须存在 name=password 的密文字段", f.containsKey("password"))
    }

    @Test
    fun `绝不提交明文密码框`() {
        val f = form!!.fields
        assertFalse("passwordText 是明文框，提交会导致服务端收到空密文", f.containsKey("passwordText"))
        assertFalse(f.values.any { it.contains("请输入密码") })
    }

    @Test
    fun `账号密码表单内不含fido或二维码字段`() {
        // 同页还有 loginFromId(fido)、phoneFromId、qrLoginForm，必须取对表单。
        // 二维码表单里的 cllt=qrLogin 绝不能混进来。
        val f = form!!.fields
        assertFalse(f.containsKey("responseJson"))
        assertFalse(f.containsKey("uuid"))
        assertTrue(f["cllt"] != "qrLogin")
    }

    @Test
    fun `action补上了service参数`() {
        val action = form!!.action
        assertTrue("action 应为绝对地址: $action", action.startsWith("https://authserver.ntu.edu.cn/authserver/login"))
        assertEquals(
            "https://tdjw.ntu.edu.cn/sso/jziotlogin",
            CasFormParser.queryParam(action, "service"),
        )
        // 缺 service 会导致服务端 500，所以这里必须校验参数确实存在
        assertTrue(action.contains("service="))
    }

    @Test
    fun `buildAction不会重复追加service`() {
        val already = "https://authserver.ntu.edu.cn/authserver/login?service=AAA"
        assertEquals(already, CasFormParser.buildAction("/authserver/login", already))
    }

    @Test
    fun `buildAction在无service时保持裸地址`() {
        val bare = "https://authserver.ntu.edu.cn/authserver/login"
        assertEquals(bare, CasFormParser.buildAction("/authserver/login", bare))
    }

    @Test
    fun `页面无错误提示`() {
        assertEquals("", form!!.errorMessage)
    }

    @Test
    fun `URL编解码往返一致`() {
        val raw = "https://tdjw.ntu.edu.cn/sso/jziotlogin?x=1&y=中文 空格"
        val enc = CasFormParser.urlEncode(raw)
        assertTrue(enc.startsWith("https%3A%2F%2Ftdjw.ntu.edu.cn%2F"))
        assertEquals(raw, CasFormParser.urlDecode(enc))
        // '+' 在 query 里代表空格
        assertEquals("a b", CasFormParser.urlDecode("a+b"))
    }

    @Test
    fun `queryParam正确取值`() {
        assertEquals("1", CasFormParser.queryParam("http://x/y?a=1&b=2", "a"))
        assertEquals("2", CasFormParser.queryParam("http://x/y?a=1&b=2", "b"))
        assertNull(CasFormParser.queryParam("http://x/y?a=1", "c"))
        assertNull(CasFormParser.queryParam("http://x/y", "c"))
    }

    @Test
    fun `HTML实体被正确解码`() {
        assertEquals("a&b<c>d\"e'f", CasFormParser.decodeEntities("a&amp;b&lt;c&gt;d&quot;e&#39;f"))
    }

    @Test
    fun `结构变化时返回null而不是发出错误请求`() {
        // 5 次错误密码会锁账号，所以宁可解析失败也不能提交残缺表单
        assertNull(CasFormParser.parse("<html><body>没有表单</body></html>", pageUrl))

        val htmlNoPassword = """
            <form id="pwdFromId" method="post" action="/authserver/login">
              <input id="username" name="username" type="text" value="">
              <input id="pwdEncryptSalt" type="hidden" value="AAAAAAAAAAAAAAAA">
            </form>
        """.trimIndent()
        assertNull("没有 name=password 的密文字段时不得提交", CasFormParser.parse(htmlNoPassword, pageUrl))
    }
}
