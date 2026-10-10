package com.ntu.schedule.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ntu.schedule.core.Appearance
import com.ntu.schedule.core.BackgroundMode
import com.ntu.schedule.core.ClassTimes
import com.ntu.schedule.core.Course
import com.ntu.schedule.core.DateUtil
import com.ntu.schedule.core.ImageCrop
import com.ntu.schedule.core.ReminderPlanner
import com.ntu.schedule.core.ReminderSettings
import com.ntu.schedule.core.Schedule
import com.ntu.schedule.core.SeasonMode
import com.ntu.schedule.data.AppearanceStore
import com.ntu.schedule.data.ReminderStore
import com.ntu.schedule.data.ScheduleRepository
import com.ntu.schedule.data.SeasonStore
import com.ntu.schedule.diagnostics.Breadcrumbs
import com.ntu.schedule.notify.NotificationChannels
import com.ntu.schedule.notify.ReminderNotification
import com.ntu.schedule.notify.ReminderScheduler
import com.ntu.schedule.widget.WidgetRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 正在等用户「选显示区域」的那张图。
 *
 * @param isNew 这次才复制进私有目录的（取消时要把这个文件删掉，免得留一堆没人用的图）。
 *              false 表示就是当前这张背景图（重新选区域），取消时什么都不用动。
 * @param initialCrop 进去时先摆成什么样。新选的图给 null（居中），重新选区域时给现在这块。
 */
data class PendingImage(
    val name: String,
    val isNew: Boolean,
    val initialCrop: ImageCrop?,
)

/**
 * 界面状态。
 *
 * 特意做成 [AndroidViewModel]：导入是「登录 + 两个接口」的长任务（实测 10-30 秒），
 * 用户很可能在这期间转屏或切后台，状态放在 ViewModel 里才不会被打断。
 * 只持有 application context，不会泄漏 Activity。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    /** 只持 application context，绝不持 Activity。 */
    private val appContext: Application = app

    private val repo = ScheduleRepository(app)

    private val _schedule = MutableStateFlow<Schedule?>(null)
    val schedule: StateFlow<Schedule?> = _schedule.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 一次性提示（导入失败原因等），显示后由界面调 [consumeMessage]。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _savedStudentId = MutableStateFlow("")
    val savedStudentId: StateFlow<String> = _savedStudentId.asStateFlow()

    /** 上次记住的密码（已解密），只用于预填登录框；没记住时是空串。 */
    private val _savedPassword = MutableStateFlow("")
    val savedPassword: StateFlow<String> = _savedPassword.asStateFlow()

    private val _hasCredentials = MutableStateFlow(false)
    val hasCredentials: StateFlow<Boolean> = _hasCredentials.asStateFlow()

    /** 本机能否用 Keystore 加密保存密码；不能时登录界面把「记住密码」置灰。 */
    private val _canRememberPassword = MutableStateFlow(false)
    val canRememberPassword: StateFlow<Boolean> = _canRememberPassword.asStateFlow()

    /** 导入成功后若通知权限还没给，置 true 让界面申请一次。 */
    private val _askNotificationPermission = MutableStateFlow(false)
    val askNotificationPermission: StateFlow<Boolean> = _askNotificationPermission.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val appearanceStore = AppearanceStore(app)

    /** 全局外观（背景）。默认是主题纯色，用户可以换成纯色 / 渐变 / 相册图片。 */
    private val _appearance = MutableStateFlow(Appearance.DEFAULT)
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val reminderStore = ReminderStore(app)

    /** 上课提醒的提前量（分钟）。默认 1 小时，可选 10/20/30/60/120。 */
    private val _reminderLead = MutableStateFlow(ReminderSettings.DEFAULT.leadMinutes)
    val reminderLead: StateFlow<Int> = _reminderLead.asStateFlow()

    private val seasonStore = SeasonStore(app)

    /**
     * 当前作息档位（冬令时 / 夏令时 / 按月份自动切）。
     *
     * 权威副本在 [ClassTimes.seasonMode] —— 这个只是给界面看的镜像。两处都留着，是因为
     * 桌面小组件和闹钟触发时不会创建 ViewModel，它们只能读 [ClassTimes]；
     * 而 Compose 要重组又必须有个 StateFlow。
     */
    private val _seasonMode = MutableStateFlow(SeasonMode.AUTO)
    val seasonMode: StateFlow<SeasonMode> = _seasonMode.asStateFlow()

    /**
     * 选好图片之后、定下显示区域之前的那张图。非 null 时界面弹裁切框。
     *
     * 刻意**先不动 [_appearance]**：用户在裁切框里点取消时，背景必须还是原来那张
     * （甚至原来那个区域），不能中途闪一下新图。
     */
    private val _pendingImage = MutableStateFlow<PendingImage?>(null)
    val pendingImage: StateFlow<PendingImage?> = _pendingImage.asStateFlow()

    init {
        // 作息档位必须**最先**装好：下面 ReminderScheduler.refresh 会按它算提醒时间，
        // 装晚了这一轮就按自动档排出去，第 6–12 节会差 30 分钟。
        applySeasonMode(repo.loadSeasonMode())
        _schedule.value = repo.loadLocal()
        _canRememberPassword.value = repo.canRememberPassword()
        prefillLogin()
        _notificationsEnabled.value = notificationsOn()
        NotificationChannels.ensure(appContext)
        // 每次冷启动都重排一次提醒：既是把 7 天窗口往前滚，也顺手修掉被 ROM 清掉的闹钟
        ReminderScheduler.refresh(appContext)
        loadAppearance()
        _reminderLead.value = reminderStore.load().leadMinutes
    }

    /**
     * 把登录框要预填的账号密码读出来。
     */
    private fun prefillLogin() {
        val creds = if (_canRememberPassword.value) repo.savedCredentials() else null
        if (creds != null) {
            _savedStudentId.value = creds.studentId
            _savedPassword.value = creds.password
            _hasCredentials.value = true
        } else {
            _savedStudentId.value = repo.savedStudentId()
            _savedPassword.value = ""
            // 拿不到密钥库时那份密文根本解不开，就当作没保存过 ——
            // 否则界面会显示一个开着却按不动的「记住密码」开关。
            _hasCredentials.value = _canRememberPassword.value && repo.hasCredentials()
        }
    }

    /**
     * 读外观设置。
     *
     * 存的图片可能已经不在了（用户清了应用缓存、或换机恢复只带回了 json），也可能还在、
     * 却已经解不出像素（下了一半、格式变了）。两种都要退回默认背景 —— 否则背景层什么都
     * 画不出来，整屏只剩窗口底色，看起来就是「设了图片反而变成一片纯白」。
     */
    private fun loadAppearance() {
        val saved = appearanceStore.load()
        val usable = saved.mode != BackgroundMode.IMAGE ||
            appearanceStore.imageDecodable(saved.imageName)
        _appearance.value = if (usable) saved else Appearance.DEFAULT
        if (!usable) appearanceStore.save(Appearance.DEFAULT)
    }

    /**
     * 发一条测试横幅。
     *
     * 「提醒到底会不会弹出来」是用户最没把握、也最难自查的一件事（涉及通知总开关、渠道重要性、
     * 悬浮通知、自启动白名单好几道闸），所以给一个当场就能看到结果的按钮，
     * 比让人等到明天早上第一节课更靠谱。
     */
    fun sendTestReminder() {
        val posted = ReminderNotification.postTest(appContext)
        _message.value = if (posted) {
            "已发出，看看屏幕顶部有没有弹出横幅"
        } else {
            "通知被系统关掉了，请先在「通知权限」里允许本应用发送通知"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeNotificationPrompt() {
        _askNotificationPermission.value = false
    }

    fun refreshNotificationState() {
        _notificationsEnabled.value = notificationsOn()
    }

    /**
     * 用学号 + 密码导入。**只能由用户明确点击触发。**
     *
     * @param remember 勾了「记住密码」才把密码加密存下来；取消勾选会顺手删掉旧密文，
     *                 否则用户以为忘了、实际还在，下次开机又被填回来。
     */
    fun import(studentId: String, password: String, remember: Boolean) {
        if (_loading.value) return
        if (studentId.isBlank() || password.isBlank()) {
            _message.value = "请填写学号与密码"
            return
        }
        _loading.value = true
        Breadcrumbs.add("import", "开始登录并导入课表")
        viewModelScope.launch {
            try {
                when (val outcome = repo.loginAndImport(studentId.trim(), password)) {
                    is ScheduleRepository.Outcome.Success -> {
                        _schedule.value = outcome.schedule
                        _savedStudentId.value = studentId.trim()
                        WidgetRenderer.updateAll(appContext)
                        Breadcrumbs.add("import", "导入成功，共 ${outcome.courseCount} 条课程安排（${outcome.schedule.termName}）")
                        // 先刷新通知状态再拼提示，否则提示里用的是上一次的旧值
                        maybeAskForNotifications()
                        val rememberWarning = applyRememberChoice(studentId.trim(), password, remember)
                        _message.value = buildString {
                            append("导入成功，共 ").append(outcome.courseCount).append(" 条课程安排")
                            // 学年学期一定要写出来：导入时是逐学期试出来的，
                            // 万一试中的不是用户想要的那一学期，这句话就是唯一的线索
                            outcome.schedule.termName.takeIf { it.isNotBlank() }
                                ?.let { append("（").append(it).append("）") }
                            if (rememberWarning != null) append("；").append(rememberWarning)
                            if (!_notificationsEnabled.value) append("；开启通知后才能收到上课提醒")
                        }
                    }
                    is ScheduleRepository.Outcome.BadCredentials -> {
                        Breadcrumbs.add("import", "学号或密码不对")
                        _message.value = outcome.message
                    }
                    is ScheduleRepository.Outcome.NeedCaptcha -> {
                        Breadcrumbs.add("import", "教务系统要求验证码")
                        _message.value = outcome.message
                    }
                    is ScheduleRepository.Outcome.NotLoggedIn -> {
                        Breadcrumbs.add("import", "没能登录")
                        _message.value = outcome.message
                    }
                    is ScheduleRepository.Outcome.Failed -> {
                        Breadcrumbs.add("import", "导入失败：${outcome.message}")
                        _message.value = outcome.message
                    }
                }
            } catch (t: Throwable) {
                Breadcrumbs.add("import", "导入抛异常：${t.javaClass.name}")
                _message.value = "导入失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    /** 用已保存的票据刷新；失败也不会影响已导入的数据。 */
    fun refresh() {
        if (_loading.value) return
        if (_schedule.value == null) {
            _message.value = "还没有课表，请先导入"
            return
        }
        _loading.value = true
        Breadcrumbs.add("refresh", "用已保存的登录刷新")
        viewModelScope.launch {
            try {
                when (val outcome = repo.refreshWithSession()) {
                    is ScheduleRepository.Outcome.Success -> {
                        _schedule.value = outcome.schedule
                        WidgetRenderer.updateAll(appContext)
                        val termName = outcome.schedule.termName
                        Breadcrumbs.add("refresh", "已更新（$termName）")
                        // 同样带上学期：刷新也是逐学期试的，得让人看得出刷到哪一学期去了
                        _message.value = if (termName.isBlank()) "已更新" else "已更新（$termName）"
                    }
                    is ScheduleRepository.Outcome.NotLoggedIn -> _message.value = "登录已过期，请重新导入"
                    is ScheduleRepository.Outcome.BadCredentials -> _message.value = outcome.message
                    is ScheduleRepository.Outcome.NeedCaptcha -> _message.value = outcome.message
                    is ScheduleRepository.Outcome.Failed -> _message.value = outcome.message
                }
            } catch (t: Throwable) {
                Breadcrumbs.add("refresh", "刷新抛异常：${t.javaClass.name}")
                _message.value = "刷新失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    /** 只删掉记住的密码，课表与会话票据都保留。 */
    fun forgetCredentials() {
        repo.forgetCredentials()
        _savedPassword.value = ""
        _hasCredentials.value = false
        _message.value = "已忘记保存的密码"
    }

    fun clearAll() {
        Breadcrumbs.add("data", "清除本地数据")
        repo.clearAll()
        _schedule.value = null
        _savedStudentId.value = ""
        _savedPassword.value = ""
        _hasCredentials.value = false
        WidgetRenderer.updateAll(appContext)
        _message.value = "已清除本地数据"
    }

    /** 今天对应的教学周次（不在学期内返回 null）。供界面与小组件共用。 */
    fun currentWeek(): Int? = _schedule.value?.weekOfDate(DateUtil.todayIso())

    // -------------------------------------------------------- 用户自己加的课

    /**
     * 新增或修改一门自己加的课（临时调课、临时加课）。
     *
     * 新建时 [Course.customId] 是空的，id 在这里分配 —— 界面只管填内容，不用操心
     * 「怎么给一条课算个唯一标识」。已有 id 的按 **id** 替换，不按内容匹配：
     * 用户很可能正是在改课名或者时间，按内容去找就找不到原来那条了，
     * 结果是「改完变成两门课」。
     */
    fun saveCustomCourse(course: Course) {
        val current = repo.customCourses()
        val existing = course.customId.isNotEmpty() && current.any { it.customId == course.customId }
        val next = if (existing) {
            current.map { if (it.customId == course.customId) course else it }
        } else {
            current + course.copy(customId = UUID.randomUUID().toString())
        }
        repo.saveCustomCourses(next)
        afterCustomChange(if (existing) "已改好" else "已加进课表")
    }

    /** 删掉一门自己加的课。传进来的只要 [Course.customId] 对得上就够。 */
    fun deleteCustomCourse(course: Course) {
        val current = repo.customCourses()
        val next = current.filterNot { it.customId == course.customId }
        if (next.size == current.size) return
        repo.saveCustomCourses(next)
        afterCustomChange("已删除这门课")
    }

    /**
     * 自定义课改动之后的统一收尾。三件事**一件都不能少**：
     * 1. 重读课表 —— [com.ntu.schedule.data.ScheduleStore.loadSchedule] 会把自定义课并进来，
     *    只有重读界面才会变；
     * 2. 刷桌面小组件；
     * 3. 重排上课提醒。
     *
     * 少做哪一件，症状都是「课表里改了，但提醒/小组件还是老样子」——
     * 而且不会报错，只会安静地不对。
     */
    private fun afterCustomChange(message: String) {
        _schedule.value = repo.loadLocal()
        WidgetRenderer.updateAll(appContext)
        ReminderScheduler.refresh(appContext)
        _message.value = message
    }

    // -------------------------------------------------------------- 作息档位

    /**
     * 手动指定冬令时 / 夏令时。
     *
     * [SeasonMode.AUTO] 是学校规定的按月份自动切；两个手动档用来对付**临时调整**
     * （比如学校通知 10 月继续按夏令时上课）。
     *
     * 改完必须重排提醒：第 6–12 节两套表差 30 分钟，已经排出去的闹钟全是按旧表算的，
     * 不重排就会在错误的时间响 —— 而用户刚为此改过设置，看到只会更困惑。
     */
    fun setSeasonMode(mode: SeasonMode) {
        if (mode == _seasonMode.value) return
        repo.saveSeasonMode(mode)
        applySeasonMode(mode)
        Breadcrumbs.add("season", "作息档位改成 ${mode.name}")
        ReminderScheduler.refresh(appContext)
        WidgetRenderer.updateAll(appContext)
        _message.value = seasonModeLabel(mode)
    }

    /**
     * 把档位装进 [ClassTimes]（界面 / 提醒 / 小组件都读它），并同步给界面。
     *
     * 装的是**全局**状态：桌面小组件和闹钟触发时根本不会创建 ViewModel，
     * 它们只能直接从 [ClassTimes] 读。
     */
    private fun applySeasonMode(mode: SeasonMode) {
        ClassTimes.seasonMode = mode
        _seasonMode.value = mode
    }

    // ------------------------------------------------------------------ 外观

    fun setBackgroundMode(mode: BackgroundMode) = updateAppearance { it.copy(mode = mode) }

    fun setBackgroundColor(color: Int) = updateAppearance {
        it.copy(mode = BackgroundMode.SOLID, colorStart = color)
    }

    fun setBackgroundGradient(start: Int, end: Int) = updateAppearance {
        it.copy(mode = BackgroundMode.GRADIENT, colorStart = start, colorEnd = end)
    }

    fun setBackgroundDim(percent: Int) = updateAppearance {
        it.copy(dimPercent = percent.coerceIn(0, Appearance.MAX_DIM))
    }

    fun clearBackground() {
        val old = _appearance.value.imageName
        appearanceStore.save(Appearance.DEFAULT)
        _appearance.value = Appearance.DEFAULT
        // 图删在状态切换之后：先切状态再删文件的话，删的瞬间界面还可能引用着这张图
        if (old != null) appearanceStore.deleteImage(old)
    }

    /**
     * 相册里选中了一张图：先**复制**进 App 私有目录，再交给用户选显示区域。
     *
     * 为什么先复制再裁：直接记 `content://` 的话权限是临时的，或者用户随手把原图删了，
     * 背景就会变成一片黑；而且裁切界面要反复解码这张图，从私有目录读文件比每次过一遍
     * `contentResolver` 稳得多。
     *
     * 这一步**不动当前背景**，等 [confirmCrop] 才生效。
     */
    fun beginImagePick(uri: Uri) {
        val name = appearanceStore.importImage(uri)
        if (name == null) {
            _message.value = "这张图读不出来，换一张试试（可能是 HEIC 格式，或文件太大）"
            return
        }
        _pendingImage.value = PendingImage(name = name, isNew = true, initialCrop = null)
    }

    /** 对现在这张背景图重新选区域。 */
    fun beginRecrop() {
        val current = _appearance.value
        if (current.mode != BackgroundMode.IMAGE) return
        val name = current.imageName ?: return
        if (!appearanceStore.imageExists(name)) {
            _message.value = "背景图不见了，请重新选一张"
            return
        }
        _pendingImage.value = PendingImage(name = name, isNew = false, initialCrop = current.imageCrop)
    }

    /**
     * 定下显示区域。
     *
     * @param crop null 表示「恢复成默认的居中裁剪」。
     */
    fun confirmCrop(crop: ImageCrop?) {
        val pending = _pendingImage.value ?: return
        _pendingImage.value = null

        val old = _appearance.value.imageName
        updateAppearance { it.withImage(pending.name, crop) }
        // 图删在状态切换之后：先切状态再删文件的话，删的瞬间界面还可能引用着这张图
        if (old != null && old != pending.name) appearanceStore.deleteImage(old)
        _message.value = if (crop == null) "背景已设置（居中裁剪）" else "背景已设置"
    }

    /** 放弃这次选区域。新导入的那张图要删掉，否则私有目录里会越攒越多没人用的图。 */
    fun cancelCrop() {
        val pending = _pendingImage.value ?: return
        _pendingImage.value = null
        if (pending.isNew) appearanceStore.deleteImage(pending.name)
    }

    /**
     * 换一档提前量。
     *
     * 存完必须**立刻重排闹钟**：已经排出去的每一条都是按旧提前量算的时刻，
     * 而通知标题里的「还有 N 分钟上课」也是随闹钟带过去的。不重排的话，用户改完设置
     * 会看到未来几天仍然按老时间响、标题还写着老时长 —— 看起来就像设置没生效。
     */
    fun setReminderLead(minutes: Int) {
        val next = ReminderSettings.sanitize(minutes)
        if (next == _reminderLead.value) return
        reminderStore.save(ReminderSettings(leadMinutes = next))
        _reminderLead.value = next
        Breadcrumbs.add("reminder", "提前量改成 ${ReminderPlanner.leadText(next)}")
        ReminderScheduler.refresh(appContext)
    }

    private fun updateAppearance(change: (Appearance) -> Appearance) {
        val next = change(_appearance.value)
        if (next == _appearance.value) return
        _appearance.value = next
        appearanceStore.save(next)
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 按勾选状态保存或清除密码。
     *
     * 取消勾选时会顺手删掉旧密文，否则用户以为忘了、实际还在，下次开机又被填回来。
     *
     * @return 需要提示给用户的警告（本机无法加密保存），没有则 null。
     *         刻意返回给调用方而不是自己写 [_message]，免得把「导入成功」这条覆盖掉。
     */
    private fun applyRememberChoice(studentId: String, password: String, remember: Boolean): String? {
        if (!remember) {
            if (_hasCredentials.value) repo.forgetCredentials()
            _savedPassword.value = ""
            _hasCredentials.value = false
            return null
        }
        val ok = repo.rememberCredentials(studentId, password)
        _hasCredentials.value = ok
        _savedPassword.value = if (ok) password else ""
        return if (ok) null else "本机无法加密保存密码，本次没有记住"
    }

    private fun notificationsOn(): Boolean =
        runCatching { NotificationManagerCompat.from(appContext).areNotificationsEnabled() }
            .getOrDefault(false)

    private fun maybeAskForNotifications() {
        _notificationsEnabled.value = notificationsOn()
        if (_notificationsEnabled.value) return
        // Android 13 以下没有运行时通知权限，只能提示用户去系统设置里开
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            _askNotificationPermission.value = true
        }
    }
}
