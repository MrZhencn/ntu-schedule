package com.ntu.schedule.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.ntu.schedule.core.ClassTimes
import com.ntu.schedule.core.Course
import com.ntu.schedule.core.WeekType

/** 星期一…星期日，只取一个字，7 个并排也放得下。 */
private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 自己加一节课 / 改一节自己加的课。
 *
 * 用途是**临时调课、临时加课** —— 教务系统里还没改、或者干脆不会改，但人确实要去上。
 * 所以这里完全不碰教务数据，只往本地加一条；重新导入课表也不会把它冲掉
 * （见 [com.ntu.schedule.data.ScheduleStore]）。
 *
 * 时间用「选节次」而不是「选几点几分」：作息表本身就有两套（见 [ClassTimes]），
 * 让用户手填钟点等于让他自己承担「现在到底按哪套算」的判断，填错了还看不出来。
 * 选节次则由 App 按当前档位换算，顺便在下面把换算结果写出来给他核对。
 *
 * @param initial 要改的那门课；新建时传 null
 * @param totalWeeks 整个学期多少周，「整个学期」选项就是 1..totalWeeks
 * @param month 用来算时间预览的月份（一般传当月）
 */
@Composable
fun CustomCourseDialog(
    initial: Course?,
    totalWeeks: Int,
    month: Int,
    onSave: (Course) -> Unit,
    onDismiss: () -> Unit,
) {
    val total = totalWeeks.coerceAtLeast(1)
    val editing = initial != null

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var room by remember { mutableStateOf(initial?.room.orEmpty()) }
    var teacher by remember { mutableStateOf(initial?.teacher.orEmpty()) }
    var day by remember { mutableIntStateOf(initial?.dayOfWeek ?: 1) }
    var fromPeriod by remember { mutableIntStateOf(initial?.startPeriod ?: 1) }
    var toPeriod by remember { mutableIntStateOf(initial?.endPeriod ?: initial?.startPeriod ?: 1) }
    // 新建时默认「整个学期」：临时加课大多是整学期都上，只有加几周才需要展开去点。
    // 改一门已有课时，按它现在的周次是不是覆盖满整个学期来判断。
    var wholeTerm by remember { mutableStateOf(initial == null || initial.weeks.size >= total) }
    var picked by remember {
        mutableStateOf(initial?.weeks?.toSet().orEmpty().ifEmpty { setOf(1) })
    }

    val first = minOf(fromPeriod, toPeriod)
    val last = maxOf(fromPeriod, toPeriod)
    // isActiveIn 对空周次表返回 false，所以「整个学期」必须写成具体的 1..total，
    // 不能留空表示「全部」—— 那样这门课一辈子都不会出现。
    val weeks: List<Int> = if (wholeTerm) {
        (1..total).toList()
    } else {
        picked.filter { it in 1..total }.sorted()
    }
    val canSave = name.isNotBlank() && weeks.isNotEmpty()

    val startText = ClassTimes.slotOf(first, month)?.start.orEmpty()
    val endText = ClassTimes.slotOf(last, month)?.end.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "修改这节课" else "自己加一节课") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                FieldLabel("星期")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    DAY_LABELS.forEachIndexed { index, label ->
                        val value = index + 1
                        Chip(
                            label = label,
                            selected = value == day,
                            onClick = { day = value },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                FieldLabel("节次")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PeriodPicker(value = fromPeriod, maxPeriod = 12, onPick = { fromPeriod = it })
                    Text("到", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 6.dp))
                    PeriodPicker(value = toPeriod, maxPeriod = 12, onPick = { toPeriod = it })
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (startText.isNotEmpty() && endText.isNotEmpty()) {
                        "$startText – $endText"
                    } else {
                        "算不出时间"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                FieldLabel("周次")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(
                        label = "整个学期（1-$total 周）",
                        selected = wholeTerm,
                        onClick = { wholeTerm = true },
                        modifier = Modifier.weight(1f),
                    )
                    Chip(
                        label = "只加几周",
                        selected = !wholeTerm,
                        onClick = { wholeTerm = false },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (wholeTerm) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "第 1–$total 周都上",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        modifier = Modifier.height(156.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(total) { index ->
                            val w = index + 1
                            Chip(
                                label = "$w",
                                selected = w in picked,
                                onClick = {
                                    picked = if (w in picked) picked - w else picked + w
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { picked = (1..total).toSet() }) { Text("全选") }
                        TextButton(onClick = { picked = emptySet() }) { Text("清空") }
                        Text(
                            text = if (weeks.isEmpty()) "一周都没选" else "共 ${weeks.size} 周",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("地点（可不填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师（可不填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "自己加的课存在本机，重新导入课表、或者换一门课导入，都不会把它冲掉。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        Course(
                            name = name.trim(),
                            teacher = teacher.trim(),
                            room = room.trim(),
                            dayOfWeek = day,
                            startPeriod = first,
                            endPeriod = last,
                            weeks = weeks,
                            weekType = WeekType.ALL,
                            // 新建时留空，由 ViewModel 分配 id；改的时候必须带上原来那个
                            customId = initial?.customId.orEmpty(),
                        )
                    )
                },
            ) {
                Text(if (editing) "保存" else "加进课表")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 5.dp),
    )
}

/**
 * 一个可选中的方块。
 *
 * 星期和「第几周」都用它：`Surface` + `clickable` 而不是 `FilterChip`，
 * 是因为周次最多 19 个要挤成 7 列，`FilterChip` 自带的内边距会把「10」这样的
 * 两位数撑出格子。
 */
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier.height(34.dp).clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

/** 节次下拉。12 个选项竖着排得下，`DropdownMenu` 自己会滚。 */
@Composable
private fun PeriodPicker(
    value: Int,
    maxPeriod: Int,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("第 $value 节", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (1..maxPeriod).forEach { p ->
                DropdownMenuItem(
                    text = { Text("第 $p 节") },
                    onClick = {
                        onPick(p)
                        open = false
                    },
                )
            }
        }
    }
}
