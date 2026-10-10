package com.ntu.schedule.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ntu.schedule.core.ClassTimes
import com.ntu.schedule.core.SeasonMode

/**
 * 档位的中文名。界面、菜单和一次性提示共用这一份 ——
 * 三处各写一遍的话，改文案时总会漏掉一处。
 */
fun seasonModeLabel(mode: SeasonMode): String = when (mode) {
    SeasonMode.AUTO -> "按月份自动切换"
    SeasonMode.WINTER -> "一直用冬令时"
    SeasonMode.SUMMER -> "一直用夏令时"
}

/** 选项第二行的补充说明：直接说清楚「第 6 节会变成几点」，比讲规则好懂。 */
private fun seasonModeHint(mode: SeasonMode): String = when (mode) {
    SeasonMode.AUTO -> "5–9 月用夏令时，10–4 月用冬令时（默认，与学校规定一致）"
    SeasonMode.WINTER -> "第 6–12 节永远按 13:30 那套算，不看月份"
    SeasonMode.SUMMER -> "第 6–12 节永远按 14:00 那套算，不看月份"
}

/**
 * 挑作息档位。
 *
 * 三选一，点一下当场生效（对话框不关）：下面那行「第 6 节是几点」会跟着变，
 * 用户能立刻看出自己选的是不是想要的那套 —— 这正是这个功能存在的意义，
 * 光看「夏令时 / 冬令时」四个字是判断不出来的。
 *
 * @param current 当前档位
 * @param month 拿哪个月份来举例（传当月的月份）
 */
@Composable
fun SeasonDialog(
    current: SeasonMode,
    month: Int,
    onPick: (SeasonMode) -> Unit,
    onDismiss: () -> Unit,
) {
    // 用第 6 节举例：第 1–5 节两套表完全相同，拿第 1 节举例的话切换前后数字一模一样，
    // 会被当成「点了没反应」。第 6 节正是差的这 30 分钟。
    val sixth = ClassTimes.tableFor(month, current).getOrNull(5)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作息时间") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "学校平时按月份自动切换。遇到临时调整（比如通知 10 月继续按夏令时上课），在这里钉住一套。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Column(modifier = Modifier.selectableGroup()) {
                    SeasonMode.values().forEach { mode ->
                        val selected = mode == current
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onPick(mode) },
                                )
                                .padding(vertical = 6.dp),
                        ) {
                            // onClick = null：点击交给外层 selectable，否则无障碍会读两遍
                            RadioButton(selected = selected, onClick = null)
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(
                                    text = seasonModeLabel(mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = seasonModeHint(mode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (sixth != null) {
                        "按这个档位，$month 月的第 6 节是 ${sixth.start}–${sixth.end}"
                    } else {
                        "按这个档位算不出时间（月份不合法）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
