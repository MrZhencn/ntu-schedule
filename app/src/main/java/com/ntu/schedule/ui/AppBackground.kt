package com.ntu.schedule.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ntu.schedule.core.Appearance
import com.ntu.schedule.core.BackgroundMode
import com.ntu.schedule.core.ImageCrop
import java.io.File
import kotlin.math.roundToInt

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
                BackgroundImage(bitmap, appearance.imageCrop)
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
 * 把图里选中的那一块铺满整块背景。
 *
 * 为什么不用 `Image(contentScale = ContentScale.Crop)`：它只会**居中**裁，用户没法决定
 * 留下照片的哪一半（一张竖构图的合影，居中的结果经常正好把两边的人各切掉一个）。
 * 这里改成自己算源矩形：`visiblePixels` 保证「区域按填满缩放后居中」，
 * 视口比例和区域比例对不上时就多裁一点 —— 宁可多切，绝不拉伸。
 *
 * 视口尺寸就是本节点的尺寸（背景层是 `fillMaxSize`），所以比例直接取 `size.width / size.height`。
 */
@Composable
private fun BackgroundImage(bitmap: ImageBitmap, crop: ImageCrop?) {
    Canvas(Modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val src = ImageCrop.visiblePixels(
            crop = crop,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            viewportAspect = size.width / size.height,
        )
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(src.x, src.y),
            srcSize = IntSize(src.width, src.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
            // 背景图几乎总在缩小，Low 会看出锯齿；Medium 的双线性在这个尺寸下也不贵。
            filterQuality = FilterQuality.Medium,
        )
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
 *
 * 目标宽度还要**除以选中区域的宽度占比**：用户把照片放大到只剩 1/4 宽时，
 * 按整图宽度解码等于只用到四分之一的像素，背景会明显发虚。区域一改就重新解码一次，
 * 而这件事只在「确定裁切」那一下发生。
 */
@Composable
private fun rememberBackgroundBitmap(appearance: Appearance): ImageBitmap? {
    val name = appearance.imageName
    if (appearance.mode != BackgroundMode.IMAGE || name.isNullOrBlank()) return null

    val context = LocalContext.current
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    val cropFraction = appearance.imageCrop?.takeIf { it.isValid }?.width ?: 1f
    val targetWidthPx = (screenWidthPx / cropFraction.coerceIn(MIN_DECODE_FRACTION, 1f)).roundToInt()
        .coerceIn(screenWidthPx, MAX_DECODE_WIDTH_PX)
    return remember(name, targetWidthPx) {
        val file = File(context.filesDir, name)
        if (!file.isFile) null else decodeSampled(file, targetWidthPx)?.asImageBitmap()
    }
}

/** 放大到只剩原图 1/8 宽就不再加码解码了：再细的细节在这个尺寸下也看不出来，只会吃内存。 */
private const val MIN_DECODE_FRACTION = 0.125f

/** 解码宽度上限，防止「1 亿像素全景图 + 极端裁切」把内存顶穿。 */
private const val MAX_DECODE_WIDTH_PX = 4096

/** 只解到屏幕宽度即可 —— 2K/4K 原图全解出来会吃掉几十 MB。 */
internal fun decodeSampled(file: File, reqWidthPx: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= reqWidthPx && sample < 64) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
}
