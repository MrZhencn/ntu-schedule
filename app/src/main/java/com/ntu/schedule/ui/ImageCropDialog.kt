package com.ntu.schedule.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ntu.schedule.core.ImageCrop
import java.io.File
import kotlin.math.roundToInt

/**
 * 「选择要显示的区域」。
 *
 * 为什么需要它：背景要铺满整屏，而相册里的图几乎不会正好是屏幕的比例，于是必然要裁掉一部分。
 * 之前是**居中裁**——一张竖构图的合影，居中的结果经常正好把两边的人各切掉一个。
 *
 * 交互选了「固定取景框 + 图在下面拖动缩放」而不是「在图上拖一个可调大小的方框」：
 * 前者看到的就是设置后的效果（所见即所得），而且只要一个捏合手势；后者的四个角
 * 在手机上很难点准，也说不清「选出来的方框比例和屏幕不一样时该怎么办」。
 *
 * 取景框的比例**等于屏幕**，所以框里看到的构图就是设成背景之后的构图。
 *
 * @param initialCrop 上次选的区域，进来先摆成那个样子（[ImageCrop.toViewport]）。
 * @param onConfirm 传 null 表示「恢复成默认的居中裁剪」。
 */
@Composable
fun ImageCropDialog(
    imageName: String,
    initialCrop: ImageCrop?,
    onCancel: () -> Unit,
    onConfirm: (ImageCrop?) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenRatio = remember(configuration) {
        val w = configuration.screenWidthDp
        val h = configuration.screenHeightDp
        if (w > 0 && h > 0) w.toFloat() / h else 9f / 19.5f
    }

    val bitmap: ImageBitmap? = remember(imageName) {
        val target = with(density) { configuration.screenWidthDp.dp.roundToPx() }
        val file = File(context.filesDir, imageName)
        if (!file.isFile) null else decodeSampled(file, target)?.asImageBitmap()
    }

    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(ImageCrop.MIN_ZOOM) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var placed by remember(bitmap) { mutableStateOf(false) }

    // 取景框尺寸一拿到就按「上次选的区域」摆好。重新进来时接着上次调，而不是打回居中 ——
    // 每次调整都从头拖一遍，等于在惩罚用户上一次的调整。
    LaunchedEffect(bitmap, frameSize) {
        if (bitmap == null || placed) return@LaunchedEffect
        if (frameSize.width <= 0 || frameSize.height <= 0) return@LaunchedEffect
        val fw = frameSize.width.toFloat()
        val fh = frameSize.height.toFloat()
        val viewport = ImageCrop.toViewport(initialCrop, bitmap.width, bitmap.height, fw, fh)
        val (limitX, limitY) = ImageCrop.panLimit(bitmap.width, bitmap.height, fw, fh, viewport.zoom)
        zoom = viewport.zoom
        offset = Offset(viewport.panX.coerceIn(-limitX, limitX), viewport.panY.coerceIn(-limitY, limitY))
        placed = true
    }

    fun reset() {
        zoom = ImageCrop.MIN_ZOOM
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("选择要显示的区域", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "框里看到的就是设置之后的效果。拖动可以移动，双指捏合或者下面的滑块可以缩放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 取景框按屏幕比例，但不允许高过对话框能放下的高度。
                    val maxFrameHeight = 340.dp
                    val heightIfFullWidth = maxWidth / screenRatio
                    val frameWidth = if (heightIfFullWidth <= maxFrameHeight) maxWidth else maxFrameHeight * screenRatio
                    val frameHeight = if (heightIfFullWidth <= maxFrameHeight) heightIfFullWidth else maxFrameHeight

                    Box(
                        modifier = Modifier
                            .size(frameWidth, frameHeight)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                    ) {
                        if (bitmap == null) {
                            Text(
                                "这张图读不出来",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            CropCanvas(
                                bitmap = bitmap,
                                zoom = zoom,
                                offset = offset,
                                onSize = { frameSize = it },
                                onPan = { pan ->
                                    val (limitX, limitY) = ImageCrop.panLimit(
                                        bitmap.width, bitmap.height,
                                        frameSize.width.toFloat(), frameSize.height.toFloat(),
                                        zoom,
                                    )
                                    offset = Offset(
                                        (offset.x + pan.x).coerceIn(-limitX, limitX),
                                        (offset.y + pan.y).coerceIn(-limitY, limitY),
                                    )
                                },
                                onZoom = { factor ->
                                    val next = (zoom * factor).coerceIn(ImageCrop.MIN_ZOOM, ImageCrop.MAX_ZOOM)
                                    val (limitX, limitY) = ImageCrop.panLimit(
                                        bitmap.width, bitmap.height,
                                        frameSize.width.toFloat(), frameSize.height.toFloat(),
                                        next,
                                    )
                                    zoom = next
                                    offset = Offset(
                                        offset.x.coerceIn(-limitX, limitX),
                                        offset.y.coerceIn(-limitY, limitY),
                                    )
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "缩放",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = zoom,
                        onValueChange = { value ->
                            val next = value.coerceIn(ImageCrop.MIN_ZOOM, ImageCrop.MAX_ZOOM)
                            val (limitX, limitY) = if (bitmap == null) 0f to 0f else ImageCrop.panLimit(
                                bitmap.width, bitmap.height,
                                frameSize.width.toFloat(), frameSize.height.toFloat(),
                                next,
                            )
                            zoom = next
                            offset = Offset(
                                offset.x.coerceIn(-limitX, limitX),
                                offset.y.coerceIn(-limitY, limitY),
                            )
                        },
                        valueRange = ImageCrop.MIN_ZOOM..ImageCrop.MAX_ZOOM,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    TextButton(onClick = { reset() }) { Text("重置") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("取消") }
                    TextButton(
                        onClick = {
                            val image = bitmap
                            if (image == null || frameSize.width <= 0 || frameSize.height <= 0) {
                                onConfirm(null)
                            } else {
                                onConfirm(
                                    ImageCrop.fromViewport(
                                        imageWidth = image.width,
                                        imageHeight = image.height,
                                        frameWidth = frameSize.width.toFloat(),
                                        frameHeight = frameSize.height.toFloat(),
                                        zoom = zoom,
                                        panX = offset.x,
                                        panY = offset.y,
                                    ),
                                )
                            }
                        },
                    ) { Text("确定") }
                }
            }
        }
    }
}

/**
 * 取景框里的那一层：把图按「填满 + 缩放 + 位移」画出来，再叠一个三分线方便找构图。
 *
 * 这里用的是和背景层**同一套** [ImageCrop] 数学（虽然一个是正向摆、一个是反向取），
 * 所以「框里看到的」和「设成背景后的」不会有偏差。
 */
@Composable
private fun CropCanvas(
    bitmap: ImageBitmap,
    zoom: Float,
    offset: Offset,
    onSize: (IntSize) -> Unit,
    onPan: (Offset) -> Unit,
    onZoom: (Float) -> Unit,
) {
    val gridColor = Color.White.copy(alpha = 0.35f)
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged(onSize)
            .then(
                Modifier.pointerInputTransform(
                    key = bitmap,
                    onPan = onPan,
                    onZoom = onZoom,
                ),
            ),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val scale = ImageCrop.coverScale(bitmap.width, bitmap.height, size.width, size.height) * zoom
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val tx = (size.width - drawWidth) / 2f + offset.x
        val ty = (size.height - drawHeight) / 2f + offset.y

        drawImage(
            image = bitmap,
            dstOffset = IntOffset(tx.roundToInt(), ty.roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1)),
        )

        // 三分线：找构图用，不参与任何计算。
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), stroke)
        }
        drawRect(
            color = Color.White.copy(alpha = 0.5f),
            size = size,
            style = Stroke(width = stroke),
        )
    }
}

/** 把「拖动 + 捏合缩放」的识别单独包一层，免得在 [CropCanvas] 的 Canvas 参数里塞一大坨。 */
private fun Modifier.pointerInputTransform(
    key: Any,
    onPan: (Offset) -> Unit,
    onZoom: (Float) -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(key) {
        detectTransformGestures { _, pan, gestureZoom, _ ->
            if (pan != Offset.Zero) onPan(pan)
            if (gestureZoom != 1f) onZoom(gestureZoom)
        }
    },
)
