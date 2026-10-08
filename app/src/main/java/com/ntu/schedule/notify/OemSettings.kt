package com.ntu.schedule.notify

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 国产 ROM 的「额外门槛」统一入口。
 *
 * 原生 Android 上，一条重要性为「紧急」的通知就会弹横幅。国产 ROM 普遍在这个基础上又加了两道闸：
 *
 * 1. **自启动 / 后台运行白名单** —— 不加入的话，App 被清理后收到的闹钟广播会被直接丢掉，
 *    表现为「提醒时有时无」甚至「完全不提醒」；
 * 2. **省电策略** —— 电池优化没放行时，闹钟会被延迟到几十分钟以后。
 *
 * 这两样都只能由用户在系统设置里手动打开，代码无法申请。所以这里能做的是：
 * 按厂商跳到**正确的那个设置页**（各家 ComponentName 完全不同，跳错了用户会一脸茫然），
 * 跳不过去就退回到应用详情页，至少让人有个下手的地方。
 *
 * 设备识别 [vendorOf] 是纯函数，已有单元测试 —— 厂商字符串来自 `Build.MANUFACTURER`/`Build.BRAND`，
 * 写错一个分支就会在对应机型上跳到不存在的页面。
 */
object OemSettings {

    data class Vendor(val key: String, val label: String)

    fun vendorOf(manufacturer: String, brand: String): Vendor {
        val text = "$manufacturer $brand".lowercase()
        return when {
            text.contains("honor") || text.contains("hihonor") -> Vendor("honor", "荣耀")
            text.contains("xiaomi") || text.contains("redmi") || text.contains("poco") ->
                Vendor("xiaomi", "小米 / Redmi")
            text.contains("huawei") || text.contains("harmony") -> Vendor("huawei", "华为")
            text.contains("oppo") || text.contains("realme") || text.contains("oneplus") ->
                Vendor("oppo", "OPPO / 一加 / realme")
            text.contains("vivo") || text.contains("iqoo") -> Vendor("vivo", "vivo / iQOO")
            text.contains("meizu") -> Vendor("meizu", "魅族")
            text.contains("samsung") -> Vendor("samsung", "三星")
            text.contains("asus") -> Vendor("asus", "华硕")
            text.contains("nokia") || text.contains("evenwell") -> Vendor("nokia", "诺基亚")
            text.contains("letv") || text.contains("leeco") -> Vendor("letv", "乐视")
            text.contains("smartisan") || text.contains("hammer") -> Vendor("smartisan", "锤子")
            text.contains("transsion") || text.contains("tecno") ||
                text.contains("infinix") || text.contains("itel") -> Vendor("transsion", "传音")
            text.contains("sony") -> Vendor("sony", "索尼")
            text.contains("lge") -> Vendor("lge", "LG")
            else -> Vendor("other", "通用")
        }
    }

    fun currentVendor(): Vendor = vendorOf(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())

    /**
     * 各厂商「自启动管理」页面的候选 ComponentName。
     *
     * 每家都给了多个候选：同一个品牌在不同系统版本上换过包名和类名，写死一个必然在某代机器上失效。
     * 调用方按顺序试，能打开的第一个就算成功。
     */
    fun autostartCandidates(vendorKey: String): List<ComponentName> = when (vendorKey) {
        "xiaomi" -> listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        "huawei" -> listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )
        "honor" -> listOf(
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        "oppo" -> listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
        )
        "vivo" -> listOf(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"),
        )
        "meizu" -> listOf(
            ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        )
        "samsung" -> listOf(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        )
        "asus" -> listOf(
            ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.autostart.AutoStartActivity",
            ),
            ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.powersaver.PowerSaverSettings",
            ),
        )
        "nokia" -> listOf(
            ComponentName(
                "com.evenwell.powersaving.g3",
                "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
            ),
        )
        "letv" -> listOf(
            ComponentName(
                "com.letv.android.letvsafe",
                "com.letv.android.letvsafe.AutobootManageActivity",
            ),
        )
        "smartisan" -> listOf(
            ComponentName("com.smartisanos.security", "com.smartisanos.security.MainActivity"),
        )
        else -> emptyList()
    }

    /** 跳到本机厂商的自启动管理页；打不开返回 false，调用方应退回到应用详情页。 */
    fun openAutostart(context: Context): Boolean {
        for (component in autostartCandidates(currentVendor().key)) {
            val intent = Intent().setComponent(component)
            if (start(context, intent)) return true
        }
        return false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        power.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * 直接弹系统对话框请求「不优化电池」。需要 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限；
     * 有些 ROM 不给这个对话框，退回到电池优化列表页让用户自己找。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", context.packageName, null))
            if (start(context, direct)) return true
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (start(context, list)) return true
        }
        return openAppDetails(context)
    }

    /**
     * 精确闹钟是否可用。Android 12 起需要 `SCHEDULE_EXACT_ALARM`，
     * 而 Android 14 起对 targetSdk ≥ 33 的应用**默认拒绝**，得用户去设置里放行。
     * 拿不到就退回 `setAndAllowWhileIdle`（Doze 下可能晚几分钟，但绝不会不响）。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            manager.canScheduleExactAlarms()
        }.getOrDefault(false)
    }

    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", context.packageName, null))
            if (start(context, intent)) return true
        }
        return openAppDetails(context)
    }

    fun openAppDetails(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null)),
    )

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
