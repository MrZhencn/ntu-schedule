package com.ntu.schedule.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 原作者署名与免费声明。
 *
 * 文案集中在这里，是因为它要出现在两个地方（登录页底部、关于对话框）。分成两份写，
 * 以后改一处忘一处，用户就会看到两套说法。
 */

/** 原作者。 */
const val AUTHOR_NAME = "Mr_Zhen_cn(狐涂)"

/** 版权声明。 */
const val AUTHOR_RIGHTS = "$AUTHOR_NAME 为原作者，保留对本 App 的一切权利"

/** 免费声明。措辞按作者本人的原话，不要「优化」掉那股劲儿。 */
const val AUTHOR_FREE_NOTICE = "该程序免费，如果你是付费得到的，恭喜你被骗了"

/** 关于对话框里用的整段说明。 */
const val AUTHOR_BLOCK = "$AUTHOR_RIGHTS。\n$AUTHOR_FREE_NOTICE。"

/**
 * 登录页底部的署名区。
 *
 * @param onGradient true 时用白字（铺在蓝色渐变上），false 时跟随主题色（放进浅色卡片里）。
 */
@Composable
fun AuthorNotice(
    onGradient: Boolean,
    modifier: Modifier = Modifier,
) {
    val titleColor =
        if (onGradient) Color.White.copy(alpha = 0.95f) else MaterialTheme.colorScheme.onSurface
    val bodyColor =
        if (onGradient) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            AUTHOR_RIGHTS,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = titleColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "$AUTHOR_FREE_NOTICE。",
            style = MaterialTheme.typography.bodySmall,
            color = bodyColor,
            textAlign = TextAlign.Center,
        )
    }
}
