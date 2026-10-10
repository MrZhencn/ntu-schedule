package com.ntu.schedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ntu.schedule.core.ClassTimes
import com.ntu.schedule.core.Course

/**
 * 点课程格子后弹出的详情。
 *
 * @param month 用哪个月份的作息表算上课时间 —— 由「这一周属于几月」决定，
 *   而不是「今天几月」，否则翻到 10 月的周次会用夏令时间（差 30 分钟）。
 * @param onEdit 非 null 时显示「修改」按钮。只有自己加的课（[Course.isCustom]）才给 ——
 *   教务导入的课改了也没用，下次刷新就被覆盖回去，不如不给这个按钮。
 * @param onDelete 同上，显示「删除」按钮。
 */
@Composable
fun CourseDetailDialog(
    course: Course,
    month: Int,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val start = ClassTimes.slotOf(course.startPeriod, month)
    val end = ClassTimes.slotOf(course.endPeriod, month)
    val timeText = when {
        start == null || end == null -> "第 ${course.periodText} 节"
        course.startPeriod == course.endPeriod -> "${start.start} - ${start.end}（第 ${course.startPeriod} 节）"
        else -> "${start.start} - ${end.end}（第 ${course.startPeriod}-${course.endPeriod} 节）"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(course.name, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                DetailRow("时间", timeText)
                DetailRow("星期", weekText(course.dayOfWeek))
                DetailRow("地点", course.room.ifBlank { "未排地点" })
                if (course.teacher.isNotBlank()) DetailRow("教师", course.teacher)
                DetailRow("周次", course.weeksText)
                if (course.isCustom) {
                    // 说明白它的来历：不然用户会奇怪「这门课教务系统里根本没有，哪来的」
                    DetailRow("来源", "自己加的（重新导入课表不会丢）")
                }
                if (course.weekMarker.isNotBlank()) {
                    DetailRow("备注", "带「${course.weekMarker}」的周次在上课地点上有所不同")
                }
                if (course.teachingClass.isNotBlank()) DetailRow("教学班", course.teachingClass)
                if (course.campus.isNotBlank()) DetailRow("校区", course.campus)
                if (course.courseCode.isNotBlank()) DetailRow("课程代码", course.courseCode)
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = when {
            onEdit == null && onDelete == null -> null
            else -> {
                {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (onEdit != null) {
                        TextButton(onClick = onEdit) { Text("修改") }
                    }
                }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 与 [com.ntu.schedule.core.DateUtil.weekdayCn] 一致，但只接受 1..7。 */
private fun weekText(dayOfWeek: Int): String = when (dayOfWeek) {
    1 -> "星期一"
    2 -> "星期二"
    3 -> "星期三"
    4 -> "星期四"
    5 -> "星期五"
    6 -> "星期六"
    7 -> "星期日"
    else -> "第 $dayOfWeek 天"
}
