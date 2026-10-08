package com.ntu.schedule.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntu.schedule.core.ReminderPlanner
import com.ntu.schedule.notify.NotificationChannels
import com.ntu.schedule.notify.OemSettings

/**
 * 「上课提醒」自检面板。
 *
 * 为什么需要它：横幅能不能弹出来，取决于好几个**代码申请不到、只能由用户在系统设置里打开**的开关
 * （通知总开关、渠道重要性、悬浮通知、自启动白名单、电池优化）。没有这个面板，用户遇到
 * 「说好的提醒呢」时只能自己去翻系统设置，而且国产 ROM 的入口各家都不一样。
 *
 * 这里的每一行都是「检测 + 一键跳到正确的那个设置页」。跳过去改完返回时，
 * 外层会用新的 [refreshKey] 重建本面板，状态立刻刷新 —— 不用手动关掉再打开。
 *
 * @param refreshKey 每次 App 回到前台都会 +1，用来触发重新检测。
 * @param leadMinutes 当前的提前量（上课前多少分钟）。
 * @param onLead 换一档提前量。
 * @param onTest 发一条测试横幅。
 */
@Composable
fun ReminderSettingsDialog(
    refreshKey: Int,
    leadMinutes: Int,
    onLead: (Int) -> Unit,
    onDismiss: () -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    val status = remember(refreshKey) { NotificationChannels.status(context) }
    val ignoringBattery = remember(refreshKey) { OemSettings.isIgnoringBatteryOptimizations(context) }
    val exactAlarms = remember(refreshKey) { OemSettings.canScheduleExactAlarms(context) }
    val vendor = remember { OemSettings.currentVendor() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上课提醒") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 430.dp),
            ) {
                Text(
                    "每节课开始前 ${ReminderPlanner.leadText(leadMinutes)}，会像 QQ、微信消息那样从屏幕顶部弹出一条横幅。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Text(
                    "提前多久提醒",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                LeadRow(current = leadMinutes, onLead = onLead)
                Spacer(Modifier.height(6.dp))
                Text(
                    "改完会立刻按新的提前量重排所有闹钟。提前 2 小时的话，早上第 1 节" +
                        "（07:50）会在 05:50 提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "下面几项决定了横幅到底能不能弹出来 —— 每一项都可以点右边的按钮去设置，" +
                        "改完回来这里会自动重新检测。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                CheckRow(
                    ok = status.notificationsEnabled,
                    title = "通知权限",
                    detail = if (status.notificationsEnabled) {
                        "已允许"
                    } else {
                        "被关掉了 —— 通知发不出去，也不会有横幅"
                    },
                    actionLabel = "去开启",
                    onAction = { NotificationChannels.openAppNotificationSettings(context) },
                )

                CheckRow(
                    ok = status.bannerReady,
                    title = "提醒样式",
                    detail = if (!status.channelReady) {
                        "提醒渠道不存在了，重新打开 App 会自动重建"
                    } else {
                        NotificationChannels.importanceText(status.importance)
                    },
                    actionLabel = "去设置",
                    onAction = { NotificationChannels.openChannelSettings(context) },
                )

                CheckRow(
                    ok = ignoringBattery,
                    title = "电池优化",
                    detail = if (ignoringBattery) {
                        "已放行，闹钟不会被拖延"
                    } else {
                        "未放行 —— 省电模式下提醒可能被推迟几十分钟"
                    },
                    actionLabel = "去设置",
                    onAction = { OemSettings.requestIgnoreBatteryOptimizations(context) },
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    CheckRow(
                        ok = exactAlarms,
                        title = "精确闹钟",
                        detail = if (exactAlarms) {
                            "已允许，到点准时提醒"
                        } else {
                            "未允许 —— 提醒可能晚几分钟（不影响是否弹横幅）"
                        },
                        actionLabel = "去允许",
                        onAction = { OemSettings.openExactAlarmSettings(context) },
                    )
                }

                CheckRow(
                    ok = null,
                    title = "自启动 / 后台运行",
                    detail = "把本应用加入「${vendor.label}」的自启动与后台运行白名单。" +
                        "不加入的话，清理后台之后到点不会提醒 —— 这一项系统不提供检测接口，只能手动确认。",
                    actionLabel = "去设置",
                    onAction = {
                        if (!OemSettings.openAutostart(context)) OemSettings.openAppDetails(context)
                    },
                )

                HorizontalDivider(Modifier.padding(vertical = 6.dp))

                Text(
                    "改完建议先发一条测试横幅看看效果。如果它只安静地躺在通知栏里、" +
                        "没有从顶部弹出来，通常是渠道被改成了「静音」，或者系统里本应用的" +
                        "「悬浮通知 / 横幅通知」开关没打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTest) { Text("发测试横幅") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 五档提前量。
 *
 * 做成固定几档而不是滑块或输入框：这个值决定「还有 N 分钟上课」那句话，也决定闹钟排在哪，
 * 一档一档地给比让用户填 47 分钟更好理解，也免得填出 0 或负数。
 */
@Composable
private fun LeadRow(current: Int, onLead: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ReminderPlanner.PRESET_LEAD_MINUTES.forEach { minutes ->
            val selected = minutes == current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onLead(minutes) },
            ) {
                Text(
                    ReminderPlanner.leadText(minutes),
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一行检测结果。
 *
 * [ok] 为 `null` 表示「无法检测，需要用户自己确认」—— 用一个中性的圆点而不是打叉，
 * 免得把「检测不到」说成「有问题」。
 */
@Composable
private fun CheckRow(
    ok: Boolean?,
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val mark = when (ok) {
        true -> "✓"
        false -> "✕"
        null -> "•"
    }
    val markColor = when (ok) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(mark, color = markColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
