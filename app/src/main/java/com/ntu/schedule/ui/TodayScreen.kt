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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ntu.schedule.core.ClassTimes
import com.ntu.schedule.core.Course
import com.ntu.schedule.core.DateUtil
import com.ntu.schedule.core.Schedule

/**
 * 「今天」：按上课先后列出当天的课。
 *
 * 时间按**具体日期所在月份**的作息表算（5-9 月夏令 / 10-4 月冬令），
 * 而不是按学期选一套 —— 见 [ClassTimes] 的说明。
 *
 * 卡片样式刻意朴素：**不做配色、不做「正在上」高亮**，所有卡片长得一样。
 */
@Composable
fun TodayScreen(
    schedule: Schedule,
    dateIso: String,
    nowMinuteOfDay: Int,
    contentPadding: PaddingValues,
    onOpenWeek: (Int) -> Unit,
) {
    val week = schedule.weekOfDate(dateIso)
    val dayOfWeek = DateUtil.dayOfWeekOf(dateIso) ?: 1
    val month = DateUtil.monthOf(dateIso)

    val courses = remember(schedule, dateIso) {
        if (week == null) emptyList()
        else schedule.coursesOfWeek(week).filter { it.dayOfWeek == dayOfWeek }
    }

    var detail by remember { mutableStateOf<Course?>(null) }

    if (week == null) {
        EmptyHint(
            title = "现在是假期",
            hint = "当前日期不在 ${schedule.termName.ifBlank { "本学期" }} 的教学周内。",
            padding = contentPadding,
        )
        return
    }

    if (courses.isEmpty()) {
        EmptyHint(
            title = "今天没有课 🎉",
            hint = "第 $week 周 · ${DateUtil.weekdayCn(dayOfWeek)} · ${DateUtil.monthDayText(dateIso)}",
            padding = contentPadding,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenWeek(week) }
                    .padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    text = "第 $week 周 · ${DateUtil.weekdayCn(dayOfWeek)} · 共 ${courses.size} 节安排",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "看本周 ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(courses, key = { "${it.name}|${it.room}|${it.periodText}|${it.weeksText}" }) { course ->
            TodayCourseCard(
                course = course,
                month = month,
                isPast = course.hasEnded(nowMinuteOfDay, month),
                onClick = { detail = course },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    detail?.let { course ->
        CourseDetailDialog(course = course, month = month, onDismiss = { detail = null })
    }
}

@Composable
private fun TodayCourseCard(
    course: Course,
    month: Int,
    isPast: Boolean,
    onClick: () -> Unit,
) {
    val start = ClassTimes.slotOf(course.startPeriod, month)
    val end = ClassTimes.slotOf(course.endPeriod, month)
    val timeText = if (start != null && end != null) "${start.start} - ${end.end}" else course.periodText

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        // 设了自定义背景时卡片要半透明，否则一块块不透明的白会把背景切碎
        color = panelColor(),
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$timeText · 第 ${course.periodText} 节",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                val detail = buildString {
                    append(course.room.ifBlank { "地点待定" })
                    if (course.teacher.isNotBlank()) append(" · ").append(course.teacher)
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = course.weeksText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isPast) {
                Text(
                    text = "已下课",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
fun EmptyHint(title: String, hint: String, padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 供 [TodayScreen] 用的极小工具，避免为一行字符串引入依赖。 */
private fun Course.hasEnded(minuteOfDay: Int, month: Int): Boolean {
    val e = ClassTimes.slotOf(endPeriod, month) ?: return false
    val to = ClassTimes.minuteOf(e.end) ?: return false
    return minuteOfDay > to
}
