package com.ntu.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.ntu.schedule.notify.OemSettings

/**
 * 「把小组件放到桌面」这件事，各家 ROM 差别大到不能一句 `requestPinAppWidget` 了事。
 *
 * 背景（这是本文件存在的全部理由）：
 * - `AppWidgetManager.requestPinAppWidget()` 从 API 26 起才有，而且**各家的实现完全不同**：
 *   小米桌面会**不弹确认框直接放上去**；ColorOS、One UI 会弹一个确认框；
 *   华为/荣耀会弹框但桌面放不下时**不会自动开新页**，只提示「当前页面空间不足」；
 *   而 vivo 的 OriginOS 如果不接入它自家的「原子组件」体系，这个方法**调了等于没调**。
 * - 更麻烦的是它「返回 true」只代表「我支持被调用」，**不代表真的加上了**。
 *   唯一可靠的确认方式是调用前后各数一次 `getAppWidgetIds()` 做对比 —— 见 [request] / [takeResult]。
 * - 而且厂商给这套东西起的名字都不一样：小米叫「小部件」，ColorOS 叫「卡片」，
 *   OriginOS 叫「原子组件」，鸿蒙叫「服务卡片 / 万能卡片」，华为老版本叫「窗口小部件」。
 *   用户拿着小米的经验去 OPPO 上找，找不到入口，就会得出「这个 App 只支持小米」的结论。
 *
 * 所以这里的做法是：**能一键就一键，不能一键就把「你这款手机该走哪条路」直接写在脸上**，
 * 并且如实告诉用户「刚才那一下到底成没成」，而不是像以前那样静默 return、什么都不说。
 */
object WidgetPinner {

    /** 请求发出后等这么久再去数一遍。太短会数在桌面还没落盘的时候，误判成失败。 */
    private const val SETTLE_MS = 1500L

    private const val PREFS = "widget_pin"
    private const val KEY_BEFORE = "before_count"
    private const val KEY_AT = "requested_at"

    enum class Outcome { ADDED, NOT_ADDED }

    data class Status(
        /** 当前桌面是否支持 App 内一键添加。 */
        val supported: Boolean,
        /** 桌面上已经放了几块本 App 的小组件。 */
        val installedCount: Int,
        /** 当前桌面的名字，例如「系统桌面」。 */
        val launcherLabel: String,
        val launcherPackage: String,
        /** 识别出来的厂商（复用提醒那套判断，荣耀先于华为）。 */
        val vendorKey: String,
        val vendorLabel: String,
        /** 这款手机上手动添加的步骤。 */
        val manualSteps: List<String>,
    )

    fun status(context: Context): Status {
        val vendor = runCatching { OemSettings.currentVendor() }
            .getOrDefault(OemSettings.Vendor("other", "Android"))
        val launcher = launcherOf(context)
        return Status(
            supported = isPinSupported(context),
            installedCount = installedCount(context),
            launcherLabel = launcher?.first ?: "未知桌面",
            launcherPackage = launcher?.second ?: "",
            vendorKey = vendor.key,
            vendorLabel = vendor.label,
            manualSteps = manualSteps(vendor.key, vendor.label, launcher?.first),
        )
    }

    /** 桌面上现在有几块本 App 的小组件。 */
    fun installedCount(context: Context): Int = runCatching {
        val manager = AppWidgetManager.getInstance(context) ?: return 0
        manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java)).size
    }.getOrDefault(0)

    fun isPinSupported(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true
    }.getOrDefault(false)

    /**
     * 请求一键添加。返回**这次请求有没有发出去**，不是「有没有加上」——
     * 真实结果必须靠 [takeResult] 过一会儿再数一次才知道。
     *
     * 先把当前块数记在 SharedPreferences 里，是因为 `requestPinAppWidget` 会弹确认框，
     * 用户点确定之后本 Activity 通常还活着但不一定重新 `onCreate`；把「基线」存在进程外，
     * 无论中间发生什么（旋转、被回收后重建）都能接着核对。
     */
    fun request(context: Context): Boolean {
        val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return false
        if (!manager.isRequestPinAppWidgetSupported) return false
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_BEFORE, installedCount(context))
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        }
        return runCatching {
            manager.requestPinAppWidget(
                ComponentName(context, ScheduleWidgetProvider::class.java),
                null,
                null,
            )
        }.getOrDefault(false)
    }

    /**
     * 回到前台时调一次：如果刚才发过请求、而且已经过了 [SETTLE_MS]，就数一遍做对比。
     *
     * 返回 null 表示「没有待核对的请求」或者「还没到时间」，两种都不该打扰用户。
     * 核对完无论是成是败都清掉记录 —— 同一个请求只提醒一次，不然每次切回前台都弹一遍。
     */
    fun takeResult(context: Context): Outcome? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        if (System.currentTimeMillis() - at < SETTLE_MS) return null

        val before = prefs.getInt(KEY_BEFORE, 0)
        prefs.edit().remove(KEY_AT).remove(KEY_BEFORE).apply()
        return if (installedCount(context) > before) Outcome.ADDED else Outcome.NOT_ADDED
    }

    /** 放弃核对（比如用户又点了一次）。 */
    fun clearPending(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_AT).remove(KEY_BEFORE).apply()
        }
    }

    // -------------------------------------------------------------- 手动添加步骤

    private fun launcherOf(context: Context): Pair<String, String>? = runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }
        val info = resolved?.activityInfo ?: return null
        // 返回 android 的 ResolverActivity 说明「用哪个桌面」还没定，报出来只会让人困惑
        if (info.packageName == "android") return null
        val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
        label to info.packageName
    }.getOrNull()

    /**
     * 这款手机该从哪儿进。
     *
     * 纯函数，直接写单测：这些文案是给**用户**看的，改错了就会把人带进沟里，
     * 不能只靠「上真机点一下」来验证。
     */
    fun manualSteps(vendorKey: String, vendorLabel: String, launcherLabel: String? = null): List<String> {
        val launcher = launcherLabel?.takeIf { it.isNotBlank() } ?: "当前桌面"
        return when (vendorKey) {
            "xiaomi" -> listOf(
                "长按桌面空白处 → 「添加小部件」（也可以双指捏合桌面）",
                "在弹出来的面板里切到「全部」，往下找到「南通大学课表」",
                "长按「今日课表」，拖到桌面上想放的位置",
                "如果列表里根本没有本应用：去「设置 → 应用设置 → 应用管理 → 南通大学课表 → 权限管理」，把「桌面快捷方式」打开，再回来重试",
            )
            "huawei", "honor" -> listOf(
                "长按桌面空白处 → 「窗口小部件」（鸿蒙/新版本叫「服务卡片」或「万能卡片」）",
                "找到「南通大学课表」，选「今日课表」",
                "注意：$vendorLabel 在桌面空间不够时不会自动开新一页，只提示「当前页面空间不足」。先腾出一块 4×3 的空位再放",
            )
            "oppo" -> listOf(
                "长按桌面空白处 → 「卡片」（部分版本写的是「小组件」或「小部件」，ColorOS 把它放在最上面一排）",
                "切到「全部」或「应用」标签，找到「南通大学课表」",
                "长按卡片拖到桌面。ColorOS 一般会先弹一个确认框，点「添加」",
                "如果点了没反应：多半是当前这一页放不下 4×3。滑到空白一点的那一页再试",
            )
            "vivo" -> listOf(
                "长按桌面空白处 → 「原子组件」（老版本 FuntouchOS 叫「桌面挂件」）",
                "在组件面板里切到「安卓组件」或「其他」标签 —— $vendorLabel 把通用的 Android 小组件放在这里，不叫「原子组件」",
                "找到「南通大学课表」，长按拖到桌面",
                "说明：OriginOS 的「原子组件」是 vivo 自家的体系，第三方 App 不接入 vivo 的原子组件 SDK 就无法被 App 主动添加。本应用用的是通用的 Android 小组件，功能一样，只是必须手动放到桌面",
            )
            "meizu" -> listOf(
                "长按桌面空白处 → 「添加工具」→「小组件」",
                "找到「南通大学课表」，拖到桌面",
            )
            "samsung" -> listOf(
                "长按桌面空白处 → 「小组件」",
                "找到「南通大学课表」，拖到桌面",
            )
            else -> listOf(
                "长按桌面空白处，在菜单里找「小组件 / 小部件 / 卡片 / 挂件」—— 各家叫法不同，指的都是同一个东西",
                "找到「南通大学课表」，长按拖到桌面",
                "也可以在「$launcher」的设置里找「桌面布局 / 小组件」相关入口",
            )
        }
    }
}
