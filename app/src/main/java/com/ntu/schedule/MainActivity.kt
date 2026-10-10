package com.ntu.schedule

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ntu.schedule.core.Appearance
import com.ntu.schedule.core.Course
import com.ntu.schedule.core.DateUtil
import com.ntu.schedule.core.SeasonMode
import com.ntu.schedule.diagnostics.Breadcrumbs
import com.ntu.schedule.ui.AUTHOR_FREE_NOTICE
import com.ntu.schedule.ui.AUTHOR_RIGHTS
import com.ntu.schedule.ui.AppBackground
import com.ntu.schedule.ui.AppViewModel
import com.ntu.schedule.ui.BackgroundSettingsDialog
import com.ntu.schedule.ui.CustomCourseDialog
import com.ntu.schedule.ui.DiagnosticsDialog
import com.ntu.schedule.ui.ImportDialog
import com.ntu.schedule.ui.ImageCropDialog
import com.ntu.schedule.ui.LocalAppearance
import com.ntu.schedule.ui.LoginScreen
import com.ntu.schedule.ui.ReminderSettingsDialog
import com.ntu.schedule.ui.SeasonDialog
import com.ntu.schedule.ui.TodayScreen
import com.ntu.schedule.ui.WeekScreen
import com.ntu.schedule.ui.WidgetHelpDialog
import com.ntu.schedule.ui.panelColor
import com.ntu.schedule.ui.seasonModeLabel
import com.ntu.schedule.ui.theme.NtuScheduleTheme
import com.ntu.schedule.widget.WidgetPinner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题钉死浅色（见 `NtuScheduleTheme`），系统栏图标也必须跟着钉死：
        // 不带参数的 enableEdgeToEdge() 会按**系统**深色模式决定图标明暗 ——
        // 系统一开深色模式，状态栏图标就变成浅色、糊在纯白底上看不见。
        // SystemBarStyle.light 的含义正是「用深色图标」。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            NtuScheduleTheme {
                AppRoot(vm)
            }
        }
    }
}

private enum class Tab(val label: String) {
    Today("今天"),
    Week("周课表"),
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    // 背景铺在最外层：Scaffold 自己的容器色设成透明、顶栏与底栏用半透明的 panelColor，
    // 这样自定义背景能从状态栏一路透到导航栏，而不是只在内容区露出一条。
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    AppBackground(appearance) {
        CompositionLocalProvider(LocalAppearance provides appearance) {
            AppRootBody(vm, appearance)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRootBody(vm: AppViewModel, appearance: Appearance) {
    val context = LocalContext.current
    val schedule by vm.schedule.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val savedStudentId by vm.savedStudentId.collectAsStateWithLifecycle()
    val savedPassword by vm.savedPassword.collectAsStateWithLifecycle()
    val hasCredentials by vm.hasCredentials.collectAsStateWithLifecycle()
    val canRemember by vm.canRememberPassword.collectAsStateWithLifecycle()
    val notificationsEnabled by vm.notificationsEnabled.collectAsStateWithLifecycle()
    val askNotification by vm.askNotificationPermission.collectAsStateWithLifecycle()
    val reminderLead by vm.reminderLead.collectAsStateWithLifecycle()
    val pendingImage by vm.pendingImage.collectAsStateWithLifecycle()
    val seasonMode by vm.seasonMode.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.Today) }
    var showImport by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showBackground by remember { mutableStateOf(false) }
    // 作息档位。用户可以在这里把「按月份自动切」改成固定冬令/夏令，
    // 学校临时调作息时不用等 App 更新。
    var showSeason by remember { mutableStateOf(false) }
    // 自己加一节课：null = 没开；开了就是「编辑哪一门课」（新建时是空的 Course?）
    var showCustom by remember { mutableStateOf(false) }
    var editingCustom by remember { mutableStateOf<Course?>(null) }
    var pendingDeleteCustom by remember { mutableStateOf<Course?>(null) }
    // 提醒自检面板要保持打开：用户点「去设置」跳走、改完返回，面板还在原地自动刷新。
    // 所以用 rememberSaveable —— 跳系统设置期间 Activity 被回收也不会把它弄丢。
    var showReminderInfo by rememberSaveable { mutableStateOf(false) }
    // 小组件面板也要能扛住跳转：用户点「自动添加」后桌面会弹确认框，
    // 期间 Activity 可能被回收，回来时面板得还在。
    var showWidgetHelp by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var importSubmitted by remember { mutableStateOf(false) }

    // 每次回到前台 +1，提醒面板靠它重新检测各项开关
    var resumeTick by remember { mutableIntStateOf(0) }
    // 点过「自动添加小组件」之后 +1，触发下面那段「等一会儿再数一遍」的核对
    var pinTick by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.refreshNotificationState() }

    // 用 GetContent 而不是 PickVisualMedia：系统相册选择器在 API 24-29 上没有，
    // androidx 会退回 ACTION_OPEN_DOCUMENT，行为在各家 ROM 上并不一致；
    // 而 ACTION_GET_CONTENT 从 API 1 就在，且我们**立刻把图复制进私有目录**，
    // 不需要可持久化的读取权限，所以用最朴素的那个反而最稳。
    //
    // 回调里只是「复制 + 挂起待裁」，真正切背景要等用户在裁切框里点确定。
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) vm.beginImagePick(uri) }

    // 从系统设置里开完通知返回时，状态要跟着更新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshNotificationState()
                resumeTick++
                // 有的桌面会弹确认框（用户这时可能已经切走又切回来），有的是静默加上的。
                // 两种都在这儿补一次核对 —— takeResult 核对完就把记录清了，所以只会提醒一次。
                WidgetPinner.takeResult(context)?.let { outcome ->
                    scope.launch { snackbar.showSnackbar(pinMessage(outcome)) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 点完「自动添加到桌面」之后：桌面可能没有任何视觉反馈（小米就是这样，直接静默放上去），
    // 所以自己盯着数 —— 数够了就是加上了，等超时还是没变就是没加上，然后如实告诉用户。
    LaunchedEffect(pinTick) {
        if (pinTick == 0) return@LaunchedEffect
        val outcome = withTimeoutOrNull(PIN_WAIT_MS) {
            var result: WidgetPinner.Outcome? = null
            while (result == null) {
                result = WidgetPinner.takeResult(context)
                if (result == null) delay(400L)
            }
            result
        }
        if (outcome != null) snackbar.showSnackbar(pinMessage(outcome))
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.consumeMessage()
    }

    LaunchedEffect(askNotification) {
        if (!askNotification) return@LaunchedEffect
        vm.consumeNotificationPrompt()
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 导入中不关对话框：等 loading 真的结束再关，否则进度条一闪而过，用户会以为没跑
    LaunchedEffect(loading) {
        if (importSubmitted && !loading) {
            showImport = false
            importSubmitted = false
        }
    }

    Scaffold(
        // 容器色透明：背景由最外层的 AppBackground 画，这里透上去
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (schedule != null) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = panelColor(0.88f)),
                    title = {
                        val sub = listOfNotNull(
                            schedule?.studentName?.takeIf { it.isNotBlank() },
                            schedule?.termName?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        Column {
                            Text("南通大学课表", style = MaterialTheme.typography.titleMedium)
                            if (sub.isNotEmpty()) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.refresh() }, enabled = !loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (schedule == null) "导入课表" else "重新导入") },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                onClick = { menuOpen = false; showImport = true },
                            )
                            DropdownMenuItem(
                                text = { Text("用已保存的登录刷新") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                enabled = !loading,
                                onClick = { menuOpen = false; vm.refresh() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (notificationsEnabled) "上课提醒" else "上课提醒（通知已关闭）") },
                                leadingIcon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                                onClick = { menuOpen = false; showReminderInfo = true },
                            )
                            DropdownMenuItem(
                                text = { Text("添加桌面小组件") },
                                leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                                onClick = { menuOpen = false; showWidgetHelp = true },
                            )
                            DropdownMenuItem(
                                text = { Text("自检与诊断") },
                                leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                                onClick = { menuOpen = false; showDiagnostics = true },
                            )
                            DropdownMenuItem(
                                text = { Text("课表背景") },
                                leadingIcon = { Icon(Icons.Filled.Wallpaper, contentDescription = null) },
                                onClick = { menuOpen = false; showBackground = true },
                            )
                            DropdownMenuItem(
                                // 把当前档位写进标题：不然「自动」和「一直是夏令」在界面上
                                // 长得一模一样，用户点进去才知道自己现在在哪一档。
                                text = { Text("作息时间（${seasonModeLabel(seasonMode)}）") },
                                leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                onClick = { menuOpen = false; showSeason = true },
                            )
                            if (hasCredentials) {
                                DropdownMenuItem(
                                    text = { Text("忘记保存的密码") },
                                    leadingIcon = { Icon(Icons.Filled.LockOpen, contentDescription = null) },
                                    onClick = { menuOpen = false; vm.forgetCredentials() },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("清除本地数据") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; showClearConfirm = true },
                            )
                            DropdownMenuItem(
                                text = { Text("关于") },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = { menuOpen = false; showAbout = true },
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (schedule != null) {
                NavigationBar(containerColor = panelColor(0.88f)) {
                    NavigationBarItem(
                        selected = tab == Tab.Today,
                        onClick = { tab = Tab.Today },
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        label = { Text(Tab.Today.label) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Week,
                        onClick = { tab = Tab.Week },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text(Tab.Week.label) },
                    )
                }
            }
        },
    ) { inner ->
        val current = schedule
        when {
            current == null -> LoginScreen(
                initialStudentId = savedStudentId,
                initialPassword = savedPassword,
                rememberInitial = hasCredentials,
                canRemember = canRemember,
                loading = loading,
                modifier = Modifier.fillMaxSize(),
                onConfirm = { id, pwd, remember -> vm.import(id, pwd, remember) },
            )
            tab == Tab.Today -> TodayScreen(
                schedule = current,
                dateIso = DateUtil.todayIso(),
                nowMinuteOfDay = DateUtil.nowMinuteOfDay(),
                contentPadding = inner,
                onOpenWeek = { tab = Tab.Week },
            )
            else -> WeekScreen(
                schedule = current,
                contentPadding = inner,
                onAddCustom = {
                    editingCustom = null
                    showCustom = true
                },
                onEditCustom = { course ->
                    editingCustom = course
                    showCustom = true
                },
                onDeleteCustom = { course -> pendingDeleteCustom = course },
            )
        }
    }

    if (showImport) {
        ImportDialog(
            initialStudentId = savedStudentId,
            initialPassword = savedPassword,
            rememberInitial = hasCredentials,
            canRemember = canRemember,
            loading = loading,
            onDismiss = { showImport = false },
            onConfirm = { id, pwd, remember ->
                importSubmitted = true
                vm.import(id, pwd, remember)
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除本地数据？") },
            text = {
                Text(
                    "将删除本机保存的课表、登录票据" +
                        (if (hasCredentials) "以及记住的账号密码" else "") +
                        "，并取消已排的上课提醒。学校服务器上的数据不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    vm.clearAll()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showReminderInfo) {
        ReminderSettingsDialog(
            refreshKey = resumeTick,
            leadMinutes = reminderLead,
            onLead = { vm.setReminderLead(it) },
            onDismiss = { showReminderInfo = false },
            onTest = { vm.sendTestReminder() },
        )
    }

    if (showBackground) {
        BackgroundSettingsDialog(
            appearance = appearance,
            onDismiss = { showBackground = false },
            onPickImage = { imageLauncher.launch("image/*") },
            onRecrop = { vm.beginRecrop() },
            onMode = { vm.setBackgroundMode(it) },
            onColor = { vm.setBackgroundColor(it) },
            onGradient = { start, end -> vm.setBackgroundGradient(start, end) },
            onDim = { vm.setBackgroundDim(it) },
            onReset = { vm.clearBackground() },
        )
    }

    if (showSeason) {
        SeasonDialog(
            current = seasonMode,
            month = DateUtil.monthOf(DateUtil.todayIso()),
            onPick = { vm.setSeasonMode(it) },
            onDismiss = { showSeason = false },
        )
    }

    if (showCustom) {
        val editing = editingCustom
        // 时间预览用「这门课第一个上课周所在的月份」：翻到 12 月的周次加课，
        // 却按 9 月的夏令时间给他看，第 6 节会差 30 分钟。新建的课还没周次，就用这个月。
        val customMonth = editing?.weeks?.firstOrNull()
            ?.let { w -> schedule?.mondayOfWeek(w)?.let { DateUtil.monthOf(it) } }
            ?: DateUtil.monthOf(DateUtil.todayIso())
        CustomCourseDialog(
            initial = editing,
            totalWeeks = schedule?.totalWeeks?.coerceAtLeast(1) ?: 1,
            month = customMonth,
            onSave = { course ->
                showCustom = false
                editingCustom = null
                vm.saveCustomCourse(course)
            },
            onDismiss = {
                showCustom = false
                editingCustom = null
            },
        )
    }

    // 删之前问一句：自己加的课重新录一遍要手选星期、节次、周次，误触代价不小。
    pendingDeleteCustom?.let { course ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCustom = null },
            title = { Text("删除「${course.name}」？") },
            text = { Text("这是你自己加的课，删掉之后课表和上课提醒里都不会再出现。教务导入的课不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteCustom = null
                    vm.deleteCustomCourse(course)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCustom = null }) { Text("取消") }
            },
        )
    }

    // 选好图（或点了「重新选择区域」）之后弹这个框，用户拖一拖、缩一缩决定留哪一块。
    // 确认前不动背景，所以取消键点下去视觉上什么都没发生。
    pendingImage?.let { pending ->
        ImageCropDialog(
            imageName = pending.name,
            initialCrop = pending.initialCrop,
            onCancel = { vm.cancelCrop() },
            onConfirm = { vm.confirmCrop(it) },
        )
    }

    if (showWidgetHelp) {
        WidgetHelpDialog(
            refreshKey = resumeTick,
            onDismiss = { showWidgetHelp = false },
            onRequestPin = {
                showWidgetHelp = false
                if (WidgetPinner.request(context)) {
                    pinTick++
                } else {
                    scope.launch {
                        snackbar.showSnackbar(
                            "这台手机的桌面不允许 App 直接放小组件，请照面板里的步骤手动添加",
                        )
                    }
                }
            },
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            refreshKey = resumeTick,
            onDismiss = { showDiagnostics = false },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于") },
            text = { Text(ABOUT_TEXT) },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            },
        )
    }
}

/** 点「自动添加到桌面」之后最多等这么久（毫秒）。桌面弹确认框时人会慢一些，留够时间。 */
private const val PIN_WAIT_MS = 12000L

/** 把核对结果说成人话。以前这里是静默 return，用户点了没反应，就以为「只有小米能用」。 */
private fun pinMessage(outcome: WidgetPinner.Outcome): String = when (outcome) {
    WidgetPinner.Outcome.ADDED ->
        "小组件已放到桌面上了。想挪位置或改大小，长按它拖一下就行。"
    WidgetPinner.Outcome.NOT_ADDED ->
        "没检测到新加的小组件。可能是这台手机的桌面不允许 App 直接放 —— " +
            "打开「添加桌面小组件」看这款手机的手动步骤；也有可能是刚那一页放不下了。"
}

/**
 * 「关于」里的说明。
 *
 * 里面每一句都是**已经被真实行为验证过的**（域名、登录方式、提醒规则、密码保存策略），
 * 改动这个文件时请连带核对，不要把说明写得比实际做到的更好。
 */
private const val ABOUT_TEXT =
    "南通大学课表 v1.0\n\n" +
        AUTHOR_RIGHTS + "。\n" +
        AUTHOR_FREE_NOTICE + "。\n\n" +
        "课表数据来自南通大学教务系统（tdjw.ntu.edu.cn）：用统一身份认证（authserver）登录后读取，" +
        "只保存在这台手机上。\n\n" +
        "• 密码只用于登录。只有勾选「记住账号密码」时才会保存，且由 Android 系统密钥库（Keystore）" +
        "加密，密钥不会离开设备的安全存储；本机若不支持加密，则不会保存，也不会退化成明文。\n" +
        "• 登录一律由你手动触发。教务系统连续 5 次密码错误会锁定账号，所以本 App 从不自动重试密码。\n" +
        "• 上课提醒每节课提前 1 小时，只提前排未来 7 天，打开 App 时自动续排。\n" +
        "• 作息时间默认按日历月份区分：5-9 月为夏季作息，10-4 月为冬季作息。" +
        "学校临时调整时，可以在「作息时间」里固定成冬令或夏令，第 1-5 节两季相同，受影响的只有第 6-12 节。\n" +
        "• 周课表右上角的「+」可以自己加课（临时调课、补课），可以只加某几周，也可以加满整个学期；" +
        "自己加的课在格子上有一道深色描边，点开能改能删，重新导入课表也不会被冲掉。\n" +
        "• 本 App 与南通大学官方无关。\n\n" +
        "作息与课表若有出入，请以教务系统为准。"
