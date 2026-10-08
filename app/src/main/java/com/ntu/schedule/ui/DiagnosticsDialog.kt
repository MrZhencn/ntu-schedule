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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ntu.schedule.diagnostics.Check
import com.ntu.schedule.diagnostics.CheckState
import com.ntu.schedule.diagnostics.CrashHandler
import com.ntu.schedule.diagnostics.CrashStore
import com.ntu.schedule.diagnostics.DiagnosticsSharing
import com.ntu.schedule.diagnostics.SelfCheck
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「自检与诊断」面板 —— 用户 m02792 要的「自动检测程序」的门面。
 *
 * 自检分五组（跑在本机、不联网、不上传）：
 * 运行环境 / 本地数据 / 桌面小组件 / 上课提醒 / 崩溃报告。
 *
 * 拿到结果之后必须能**送出去**，否则自检等于白跑 —— 所以底下两排按钮分别是
 * 「复制」（粘进微信对话框）和「导出最新的一份」（当附件发）。
 *
 * @param refreshKey 每次回到前台 +1，用来重跑自检、重数崩溃报告。
 */
@Composable
fun DiagnosticsDialog(
    refreshKey: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(refreshKey) { CrashStore(context) }
    val checks = remember(refreshKey) { SelfCheck.run(context) }
    var crashCount by remember(refreshKey) { mutableIntStateOf(store.count()) }
    var newest by remember(refreshKey) { mutableStateOf(store.newest()) }
    var note by remember(refreshKey) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自检与诊断") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 430.dp),
            ) {
                Text(
                    "下面这些全部在本机检查，不联网、不上传。要发给别人时用底下的「复制」或「导出」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                checks.groupBy { it.group }.forEach { (group, items) ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        group,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    items.forEach { CheckRow(it) }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Text(
                    "崩溃报告",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (crashCount <= 0) {
                    CheckRow(
                        Check(
                            group = "崩溃报告",
                            label = "已保存",
                            value = "0 份。App 没崩过，或者崩溃发生在报告装好之前",
                            state = CheckState.INFO,
                        ),
                    )
                    Text(
                        "可以先点下面的「模拟一次」，看这份报告长什么样、能不能发出去。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CheckRow(
                        Check(
                            group = "崩溃报告",
                            label = "已保存",
                            value = "$crashCount 份，最多保留 ${CrashStore.MAX_FILES} 份，更旧的自动删",
                            state = CheckState.OK,
                        ),
                    )
                    newest?.let { file ->
                        CheckRow(
                            Check(
                                group = "崩溃报告",
                                label = "最新一份",
                                value = "${file.name}（${formatWhen(file)}）",
                                state = CheckState.INFO,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row {
                    if (crashCount > 0) {
                        val file = newest
                        TextButton(
                            onClick = {
                                if (file != null) {
                                    val ok = DiagnosticsSharing.shareFile(context, file, "南通大学课表 崩溃报告")
                                    note = if (ok) {
                                        "已把 ${file.name} 交给分享面板。选微信、QQ、邮件都行。"
                                    } else {
                                        "这台机器上没有能接收 txt 的 App，改用「复制」再粘出去试试。"
                                    }
                                }
                            },
                        ) { Text("导出最新的") }

                        TextButton(
                            onClick = {
                                note = if (file != null && DiagnosticsSharing.copyToClipboard(context, "崩溃报告", store.read(file) ?: "")) {
                                    "报告全文已复制到剪贴板，直接粘贴就行。"
                                } else {
                                    "复制失败。"
                                }
                            },
                        ) { Text("复制") }

                        TextButton(
                            onClick = {
                                store.deleteAll()
                                crashCount = 0
                                newest = null
                                note = "已清空本机保存的崩溃报告。"
                            },
                        ) { Text("清空") }
                    }

                    TextButton(
                        onClick = {
                            val file = CrashHandler.simulate(context)
                            crashCount = store.count()
                            newest = store.newest()
                            note = if (file != null) {
                                "已生成 ${file.name}。App 并没有真的崩溃 —— 这条是走同一条写盘路径造出来的，格式与真实崩溃完全一致。"
                            } else {
                                "写盘失败。可能是私有目录满了或者被系统限制。"
                            }
                        },
                    ) { Text("模拟一次") }
                }

                note?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "抓不到的情况：C/C++ 层崩溃（系统写在 /data/tombstones）和「应用无响应」（ANR）" +
                        "不经过这条路径，它们不是 Java 异常。那两类要在「开发者选项 → 错误报告」里取，或者用 adb。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ok = DiagnosticsSharing.copyToClipboard(context, "自检信息", SelfCheck.toText(checks))
                    note = if (ok) "自检信息已整段复制到剪贴板。" else "复制失败。"
                },
            ) { Text("复制自检信息") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun CheckRow(check: Check) {
    val mark = when (check.state) {
        CheckState.OK -> "✓"
        CheckState.WARN -> "△"
        CheckState.BAD -> "✕"
        CheckState.INFO -> "·"
    }
    val color = when (check.state) {
        CheckState.OK -> MaterialTheme.colorScheme.primary
        CheckState.WARN -> MaterialTheme.colorScheme.tertiary
        CheckState.BAD -> MaterialTheme.colorScheme.error
        CheckState.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(mark, color = color, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(
                check.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatWhen(file: File): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(file.lastModified()))
