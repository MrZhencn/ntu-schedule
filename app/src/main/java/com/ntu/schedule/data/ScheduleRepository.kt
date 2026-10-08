package com.ntu.schedule.data

import android.content.Context
import com.ntu.schedule.core.AuthResult
import com.ntu.schedule.core.CasAuthenticator
import com.ntu.schedule.core.CookieJar
import com.ntu.schedule.core.HttpClient
import com.ntu.schedule.core.Schedule
import com.ntu.schedule.core.ScheduleApi
import com.ntu.schedule.core.TermPicker
import com.ntu.schedule.notify.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入编排：登录 → 拉课表 → 存盘。
 *
 * 三种入口，各自的安全边界都不同，所以分开暴露：
 * - [loginAndImport]：需要密码。**只有用户主动点「导入」时才允许调用** ——
 *   连续 5 次密码错误会锁定账号，绝不能做成后台自动重试。
 * - [refreshWithSession]：用上次保存的会话票据刷新。失败也不代表密码错，无锁定风险。
 * - [loadLocal]：只读本地。
 */
class ScheduleRepository(private val context: Context) {

    private val store = ScheduleStore(context)

    sealed class Outcome {
        data class Success(val schedule: Schedule, val courseCount: Int) : Outcome()
        data class NeedCaptcha(val message: String) : Outcome()
        data class BadCredentials(val message: String) : Outcome()
        data class NotLoggedIn(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun loadLocal(): Schedule? = store.loadSchedule()

    fun savedStudentId(): String = store.loadStudentId()

    // ------------------------------------------------------- 记住的账号密码

    /** 本机能否加密保存密码（少数定制 ROM 关掉了 Keystore）。不能时界面把开关置灰。 */
    fun canRememberPassword(): Boolean = SecretStore.isAvailable()

    fun hasCredentials(): Boolean = store.hasCredentials()

    /**
     * 读取上次记住的账号密码，用于预填登录界面。
     * 解不开（换机、恢复出厂）时返回 null，[ScheduleStore.loadCredentials] 会顺手清掉密文。
     */
    fun savedCredentials(): ScheduleStore.Credentials? = store.loadCredentials()

    /** 保存账号密码。**密码只以 Keystore 密文落盘**；加密不可用返回 false，不做明文降级。 */
    fun rememberCredentials(studentId: String, password: String): Boolean =
        store.saveCredentials(studentId, password)

    fun forgetCredentials() = store.clearCredentials()

    fun clearAll() {
        store.clearAll()
        // 课表没了，提醒也必须跟着消失，否则会弹出「还有 1 小时上课」而 App 里空空如也
        ReminderScheduler.cancelAll(context)
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 用学号 + 密码完整导入。
     *
     * @param year 学年（如 `2026`）。与 [term] 都给了才生效；留空则按候选学期逐个试，
     *             见 [TermPicker] —— 教务系统给的「当前学期」并不可信
     * @param term 学期（如 `3`）
     */
    suspend fun loginAndImport(
        studentId: String,
        password: String,
        year: String = "",
        term: String = "",
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            importOn(studentId, password, year, term)
        } catch (t: Throwable) {
            Outcome.Failed(networkMessage(t))
        }
    }

    /** 用已保存的票据刷新；票据失效就提示重新登录。 */
    suspend fun refreshWithSession(): Outcome = withContext(Dispatchers.IO) {
        val session = store.loadSession() ?: return@withContext Outcome.NotLoggedIn("尚未登录过，请先导入课表")
        val jar = CookieJar()
        jar.seed(CasAuthenticator.JW_BASE, session.cookieHeader)
        val http = HttpClient(jar)
        fetchAndStore(http, CasAuthenticator.JW_BASE, "", "")
    }

    /** 课表页可选的学年/学期。目前界面没有入口调用它，留给将来的「选择学期」。 */
    suspend fun loadTerms(studentId: String, password: String): List<ScheduleApi.TermOption>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val http = HttpClient(CookieJar())
                val auth = CasAuthenticator(http)
                if (auth.login(studentId, password) !is AuthResult.Success) return@runCatching null
                ScheduleApi(http, auth.jwBase).loadTerms()?.terms
            }.getOrNull()
        }

    // ------------------------------------------------------------------ 内部

    private suspend fun importOn(
        studentId: String,
        password: String,
        year: String,
        term: String,
    ): Outcome {
        val http = HttpClient(CookieJar())
        val auth = CasAuthenticator(http)

        when (val r = auth.login(studentId, password)) {
            is AuthResult.BadCredentials -> return Outcome.BadCredentials(r.message)
            is AuthResult.NeedCaptcha -> return Outcome.NeedCaptcha(r.message)
            is AuthResult.Failed -> return Outcome.Failed(r.message)
            is AuthResult.Success -> Unit
        }

        // 登录成功才保存会话：失败的票据没有任何价值
        runCatching {
            store.saveSession(studentId, http.cookieHeaderFor(auth.jwBase), System.currentTimeMillis())
        }
        store.saveStudentId(studentId)

        return fetchAndStore(http, auth.jwBase, year, term)
    }

    private suspend fun fetchAndStore(
        http: HttpClient,
        base: String,
        year: String,
        term: String,
    ): Outcome {
        val api = ScheduleApi(http, base)
        val terms = runCatching { api.loadTerms() }.getOrNull()
            ?: return Outcome.NotLoggedIn("登录状态已失效，请重新导入")

        // 学期不是拿页面默认值直接用的，而是排几个候选逐个试 ——
        // 课表页上的「当前学期」并不可信（详见 TermPicker）。
        val candidates = if (year.isNotBlank() && term.isNotBlank()) {
            // 调用方明确指定了学期。目前没有入口会这么调，留给将来的「选择学期」。
            listOf(TermPicker.Term(year, term))
        } else {
            TermPicker.candidates(terms.currentYear, terms.currentTerm, System.currentTimeMillis())
        }

        return when (val r = api.fetchFirstAvailable(candidates)) {
            is ScheduleApi.Result.NotLoggedIn -> Outcome.NotLoggedIn(r.message)
            is ScheduleApi.Result.Failed -> Outcome.Failed(r.message)
            is ScheduleApi.Result.Empty -> Outcome.Failed(noCoursesMessage(candidates))
            is ScheduleApi.Result.Success -> {
                val s = r.data.schedule
                if (s.courses.isEmpty()) {
                    // 接口说有课、解析出来是空的 —— 这是解析器失灵，不是「没有课」
                    Outcome.Failed("课表没能解析出来（${s.termName.ifBlank { candidates.first().label }}），请把这条信息反馈一下")
                } else {
                    store.saveSchedule(s)
                    // 课表变了，上课提醒必须按新数据重排（星期、节次、周次都可能变）
                    ReminderScheduler.refresh(context)
                    Outcome.Success(s, s.courses.size)
                }
            }
        }
    }

    /**
     * 一整轮候选都没查到时说的话。
     *
     * 把试过的学期列出来是**故意的**：这句提示以前只有一句「该学期没有查询到课程」，
     * 用户看到时完全不知道 App 查的是哪个学期、为什么查那个 —— 排查全靠猜。
     * 列出候选，下次再有类似问题一眼就能看出差在哪一学期上。
     */
    private fun noCoursesMessage(candidates: List<TermPicker.Term>): String {
        val shown = candidates.take(3).joinToString("、") { it.label }
        val more = if (candidates.size > 3) "等 ${candidates.size} 个学期" else ""
        return "没有查询到课程。已经试过 $shown$more。可能是学校还没排课，或者账号下确实没有课表。"
    }

    private fun networkMessage(t: Throwable): String {
        val detail = t.message?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        return "网络请求失败$detail"
    }
}
