package com.ntu.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntu.schedule.core.Appearance
import com.ntu.schedule.core.BackgroundMode
import kotlin.math.roundToInt

/**
 * 自定义课表背景。
 *
 * 只有一行「选类型 + 选颜色 + 拖遮罩」是真正需要用户操作的，其余都是即时生效 ——
 * 对话框是半透明的，用户在拖滑块时能直接看到后面课表在变，不需要额外的预览区。
 */
@Composable
fun BackgroundSettingsDialog(
    appearance: Appearance,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onRecrop: () -> Unit,
    onMode: (BackgroundMode) -> Unit,
    onColor: (Int) -> Unit,
    onGradient: (Int, Int) -> Unit,
    onDim: (Int) -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课表背景") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ModeRow(appearance.mode, onMode)
                Spacer(Modifier.height(14.dp))

                when (appearance.mode) {
                    BackgroundMode.DEFAULT -> Text(
                        "默认就是纯白背景（本 App 固定浅色，不跟随系统深色模式）。" +
                            "选「纯色」「渐变」或「图片」可以换成自己的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    BackgroundMode.SOLID -> {
                        SectionLabel("选一个颜色")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Appearance.PRESET_SOLIDS.take(8).forEach { argb ->
                                Swatch(
                                    selected = argb == appearance.colorStart,
                                    onClick = { onColor(argb) },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(Color(argb)),
                                )
                            }
                        }
                    }

                    BackgroundMode.GRADIENT -> {
                        SectionLabel("选一组渐变")
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Appearance.PRESET_GRADIENTS.chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { (start, end) ->
                                        Swatch(
                                            selected = start == appearance.colorStart &&
                                                end == appearance.colorEnd,
                                            onClick = { onGradient(start, end) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(Color(start), Color(end)),
                                                    ),
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    BackgroundMode.IMAGE -> {
                        SectionLabel("来自相册")
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onPickImage) {
                                Text(
                                    if (appearance.imageName.isNullOrBlank()) "选一张图片"
                                    else "换一张图片",
                                )
                            }
                            // 只有已经有图、且那张图确实铺不满（也就是裁掉过东西）时才给这个入口：
                            // 一张比例刚好合适的图，「选区域」进去也只能原地不动。
                            if (!appearance.imageName.isNullOrBlank()) {
                                TextButton(onClick = onRecrop) { Text("重新选择区域") }
                            }
                        }
                        Text(
                            if (appearance.imageCrop == null) {
                                "图片会被复制一份到 App 自己的目录里（不超过 12MB），" +
                                    "所以之后删掉相册原图也不影响背景。\n" +
                                    "现在按屏幕比例自动居中裁剪，选好之后可以点「重新选择区域」自己挑要留哪一块。"
                            } else {
                                "图片会被复制一份到 App 自己的目录里（不超过 12MB），" +
                                    "所以之后删掉相册原图也不影响背景。\n" +
                                    "当前显示的是你手选的那一块；点「重新选择区域」可以调整，点「恢复默认背景」会一起清掉。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (appearance.isCustom) {
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("背景遮罩  ${appearance.dimPercent}%")
                    Slider(
                        value = appearance.dimPercent.toFloat(),
                        onValueChange = { onDim(it.roundToInt()) },
                        valueRange = 0f..Appearance.MAX_DIM.toFloat(),
                    )
                    Text(
                        "背景压暗一些，课程格子上的字才看得清。默认不压暗，看不清字时再往右拖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onReset) { Text("恢复默认背景") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ModeRow(current: BackgroundMode, onMode: (BackgroundMode) -> Unit) {
    val options = listOf(
        BackgroundMode.DEFAULT to "默认",
        BackgroundMode.SOLID to "纯色",
        BackgroundMode.GRADIENT to "渐变",
        BackgroundMode.IMAGE to "图片",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (mode, label) ->
            val selected = mode == current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onMode(mode) },
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 色块。选中时描一圈主题色边框 —— 只靠颜色本身没法表达「这就是当前选中的那个」。 */
@Composable
private fun Swatch(selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(9.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    )
}
