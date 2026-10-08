package com.ntu.schedule.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 通知渠道的唯一来源，顺带提供「提醒到底能不能弹出来」的自检。
 *
 * **为什么要换渠道 id（`class_reminder` → `class_reminder_v2`）：**
 * Android 规定，一个渠道一旦被创建，**除了名字和描述，其它一切都不能再改** ——
 * 重要性、声音、震动、锁屏可见性全部被冻结，再调用 `createNotificationChannel` 只会被忽略。
 * 1.x 建的旧渠道没有显式指定声音和锁屏可见性，在部分机型上就是不弹横幅。
 * 因此新版必须用一个新 id 重建，并把旧渠道删掉（否则系统设置里会出现两条同名渠道）。
 *
 * 横幅（heads-up）在原生 Android 上的三个前提，这里逐个落实：
 * 1. 渠道重要性 ≥ HIGH；
 * 2. 通知带声音或有震动（**只满足第 1 条是不够的**，Android 7 上尤其明显）；
 * 3. 锁屏可见性为 PUBLIC（否则锁屏状态下只会静默进通知栏）。
 * 国产 ROM 还会额外要求「悬浮通知 / 横幅通知」开关和「自启动」白名单，
 * 那些只能由用户在系统设置里打开，见 [OemSettings] 与提醒自检面板。
 */
object NotificationChannels {

    /** 当前使用的提醒渠道。 */
    const val REMINDER = "class_reminder_v2"

    /** 1.x 用过的渠道，只用于清理。 */
    const val LEGACY_REMINDER = "class_reminder"

    /*
     * 以下常量与 `android.app.NotificationManager` 的 IMPORTANCE_* 数值一一对应。
     * 这里抄一份是为了让「把重要性翻译成人话」这段逻辑能被纯 JVM 单元测试覆盖 ——
     * `NotificationManager` 在单元测试里是桩实现，读它的字段也拿不到真实行为。
     */
    const val IMPORTANCE_NONE = 0
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_DEFAULT = 3
    const val IMPORTANCE_HIGH = 4
    const val IMPORTANCE_MAX = 5

    private const val REMINDER_NAME = "上课提醒（横幅）"

    /** 短促两下，够引起注意又不至于像来电那样吵人。 */
    private val VIBRATION = longArrayOf(0L, 260L, 180L, 260L)

    /** 建渠道。已存在则原样保留（系统不允许改），并顺手删掉 1.x 的旧渠道。 */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = manager(context) ?: return
        runCatching { manager.deleteNotificationChannel(LEGACY_REMINDER) }
        if (runCatching { manager.getNotificationChannel(REMINDER) }.getOrNull() != null) return

        val audio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val channel = NotificationChannel(
            REMINDER,
            REMINDER_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "上课前 1 小时弹一条横幅（悬浮）提醒，由课表 App 在本地计算并发出"
            enableVibration(true)
            vibrationPattern = VIBRATION
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audio)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    /** 自检快照。 */
    data class Status(
        /** 系统里本应用的通知总开关。 */
        val notificationsEnabled: Boolean,
        /** 提醒渠道是否还在（用户可能把它删了）。 */
        val channelReady: Boolean,
        /** 是否满足「会弹横幅」的条件。 */
        val bannerReady: Boolean,
        /** 渠道当前重要性；渠道不存在或 Android 8 以下时给了 [IMPORTANCE_HIGH]。 */
        val importance: Int,
    )

    fun status(context: Context): Status {
        val enabled = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)
        // Android 8.0 之前没有渠道，横幅只看通知自身的 priority，构造时已给 PRIORITY_HIGH。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Status(enabled, true, enabled, IMPORTANCE_HIGH)
        }
        val channel = runCatching { manager(context)?.getNotificationChannel(REMINDER) }.getOrNull()
        val importance = channel?.importance ?: IMPORTANCE_NONE
        return Status(
            notificationsEnabled = enabled,
            channelReady = channel != null,
            bannerReady = enabled && channel != null && importance >= IMPORTANCE_HIGH,
            importance = importance,
        )
    }

    /** 把渠道重要性翻译成用户看得懂的一句话（纯函数，已有单元测试）。 */
    fun importanceText(importance: Int): String = when {
        importance <= IMPORTANCE_NONE -> "已关闭，不会提醒"
        importance <= IMPORTANCE_LOW -> "静音，只进通知栏，不弹横幅"
        importance == IMPORTANCE_DEFAULT -> "有提示音，但不弹横幅"
        else -> "横幅提醒（推荐）"
    }

    /** 跳到本应用的通知设置页。返回是否成功打开了某个页面。 */
    fun openAppNotificationSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (start(context, direct)) return true
        }
        // ACTION_APP_NOTIFICATION_SETTINGS 要 API 26，minSdk 是 24，24/25 退到应用详情页。
        return start(context, appDetails(context))
    }

    /** 直接跳到提醒渠道的设置页（用户在这里把重要性改成「紧急」才能弹横幅）。 */
    fun openChannelSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, REMINDER)
            if (start(context, intent)) return true
        }
        return openAppNotificationSettings(context)
    }

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
}
