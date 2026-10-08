package com.ntu.schedule.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ntu.schedule.widget.WidgetPinner

/**
 * 「桌面小组件」面板 —— 这个面板本身就是对「为什么这个 App 只能加小米的小组件」的回答。
 *
 * 以前这里只是一句 `requestPinAppWidget()`，桌面不支持时静默 return，用户点了没反应、
 * 也没有任何解释，于是合理的结论就是「它只支持小米」。
 * 现在把三件事直接摆在用户面前：**这台手机的桌面支不支持**、**已经放上去几块**、
 * **如果不支持，你这款 ROM 该从哪个入口手动加**。
 *
 * @param refreshKey 每次回到前台 +1。用户去桌面添加完再回来，数字要跟着变。
 * @param onRequestPin 点「自动添加到桌面」时回调，由外层决定关不关面板。
 */
@Composable
fun WidgetHelpDialog(
    refreshKey: Int,
    onDismiss: () -> Unit,
    onRequestPin: () -> Unit,
) {
    val context = LocalContext.current
    val status = remember(refreshKey) { WidgetPinner.status(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("桌面小组件") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 430.dp),
            ) {
                Text(
                    "小组件是通用的 Android 桌面组件，不限机型。区别只在怎么放上去：" +
                        "有些桌面允许 App 直接放，有些不允许，只能你自己在桌面手动添加 —— " +
                        "下面就是你这台手机的情况。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                StatusRow(
                    ok = null,
                    title = "当前桌面",
                    detail = status.launcherLabel +
                        if (status.launcherPackage.isNotEmpty()) "（${status.launcherPackage}）" else "",
                )

                StatusRow(
                    ok = status.supported,
                    title = "App 内一键添加",
                    detail = if (status.supported) {
                        "这台手机的桌面支持，点下面的「自动添加到桌面」会直接放上去"
                    } else {
                        "这台手机的桌面不支持被 App 直接添加，只能在桌面手动加（步骤见下）"
                    },
                )

                StatusRow(
                    ok = status.installedCount > 0,
                    title = "桌面上已有",
                    detail = if (status.installedCount > 0) {
                        "${status.installedCount} 块。同一块想加第二个可以重复添加"
                    } else {
                        "还没有。桌面上一块都没有时，小组件不会更新，也不会占用任何资源"
                    },
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text(
                    "在「${status.vendorLabel}」上手动添加的步骤",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                status.manualSteps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (status.supported) {
                TextButton(onClick = onRequestPin) { Text("自动添加到桌面") }
            } else {
                // 不支持时不给一个按了没用的按钮 —— 那正是以前让人以为「只支持小米」的原因。
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            if (status.supported) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/**
 * 一行状态。[ok] 为 `null` 表示「中性信息，没有好坏之分」，用圆点而不是打叉 ——
 * 把「不用检测」说成「有问题」会平白吓人一跳。
 */
@Composable
private fun StatusRow(ok: Boolean?, title: String, detail: String) {
    val mark = when (ok) {
        true -> "✓"
        false -> "✕"
        null -> "•"
    }
    val color = when (ok) {
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
        Text(mark, color = color, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
