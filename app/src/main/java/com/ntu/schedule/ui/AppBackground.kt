package com.ntu.schedule.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ntu.schedule.core.Appearance
import com.ntu.schedule.core.BackgroundMode
import java.io.File

/**
 * 当前的外观设置。用 [compositionLocalOf] 而不是层层传参：周课表格子、今日课程卡、
 * 各种占位色都要按「有没有自定义背景」变半透明，一路透传会污染所有中间层的签名。
 */
val LocalAppearance = compositionLocalOf { Appearance.DEFAULT }

/**
 * 全局背景层。放在 `Scaffold` 内容区外面，让背景铺满整屏（含状态栏后面），
 * 上面的页面内容各画各的。
 */
@Composable
fun AppBackground(
    appearance: Appearance,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        BackgroundLayer(appearance, Modifier.fillMaxSize())
        content()
    }
}

@Composable
private fun BackgroundLayer(appearance: Appearance, modifier: Modifier) {
    // 必须是一条真正的 Brush 而不是 null：`background(brush ?: Color.Transparent)` 里
    // 两侧类型不同，Kotlin 会推断成 Any 而找不到重载。
    //
    // 「默认」必须是**不透明**的主题底色（浅色主题下就是纯白）。早先这里画的是全透明，
    // 于是整屏背景实际由 `android:windowBackground` 决定 —— 那是个带蓝灰调的 #F7F8FA，
    // 看起来就不像白底；而且深色主题下窗口底色仍是浅的，文字会糊在浅灰上。
    val surface = MaterialTheme.colorScheme.surface
    val brush: Brush = when (appearance.mode) {
        BackgroundMode.DEFAULT -> Brush.verticalGradient(listOf(surface, surface))

        BackgroundMode.SOLID -> Brush.verticalGradient(
            listOf(Color(appearance.colorStart), Color(appearance.colorStart)),
        )

        BackgroundMode.GRADIENT -> Brush.verticalGradient(
            listOf(Color(appearance.colorStart), Color(appearance.colorEnd)),
        )

        // 图片模式下这一层只负责「图还没解码出来」的瞬间，给它透明、让窗口底色垫着即可。
        BackgroundMode.IMAGE -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    Box(modifier.background(brush)) {
        if (appearance.mode == BackgroundMode.IMAGE) {
            val bitmap = rememberBackgroundBitmap(appearance)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 遮罩：图片背景不可能保证任何位置都衬得出文字，盖一层黑最省事也最稳。
        val dim = appearance.dimFraction()
        if (dim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
    }
}

/**
 * 卡片、面板、空格子的底色。
 *
 * 没有自定义背景时就是主题的 `surface`；设了背景之后改成半透明，否则一块块不透明的
 * 白/灰会把背景切得支离破碎，用户会觉得「背景设了等于没设」。
 */
@Composable
fun panelColor(alpha: Float = 0.9f): Color {
    val base = MaterialTheme.colorScheme.surface
    return if (LocalAppearance.current.isUsable) base.copy(alpha = alpha) else base
}

/**
 * 解码背景图。
 *
 * 刻意不引 Coil/Glide：只有一张图、且是本地文件，一次降采样解码就够了；
 * 为它拉一个图片库会把 APK 撑大几百 KB，还要处理它自己的生命周期。
 *
 * key 用**文件名 + 目标宽度**而不是整个 [Appearance]：拖遮罩滑块时 Appearance 每帧都在变，
 * 按整个对象做 key 会让图片每帧重新解码一遍。
 */
@Composable
private fun rememberBackgroundBitmap(appearance: Appearance): ImageBitmap? {
    val name = appearance.imageName
    if (appearance.mode != BackgroundMode.IMAGE || name.isNullOrBlank()) return null

    val context = LocalContext.current
    val widthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    return remember(name, widthPx) {
        val file = File(context.filesDir, name)
        if (!file.isFile) null else decodeSampled(file, widthPx)?.asImageBitmap()
    }
}

/** 只解到屏幕宽度即可 —— 2K/4K 原图全解出来会吃掉几十 MB。 */
private fun decodeSampled(file: File, reqWidthPx: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= reqWidthPx && sample < 64) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
}
