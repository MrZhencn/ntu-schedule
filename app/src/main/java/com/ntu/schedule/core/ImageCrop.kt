package com.ntu.schedule.core

/** 一块整数像素矩形。刻意不用 `android.graphics.Rect`，让这一层能在 JVM 单测里跑。 */
data class PixelRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * 裁切界面里那块取景框当前的**视角**：在「填满」基准之上又放大了多少、相对居中挪了多远。
 *
 * 单独抽出来是为了让它可逆：[ImageCrop.fromViewport] 把视角换算成区域，
 * [ImageCrop.toViewport] 再把区域换算回视角 —— 「重新选择区域」时才能接着上次的位置调，
 * 而不是每次都打回居中。
 */
data class CropViewport(
    /** 放大倍数，1 = 刚好填满取景框。 */
    val zoom: Float,
    /** 相对居中的位移，单位是取景框像素。 */
    val panX: Float,
    val panY: Float,
)

/**
 * 背景图里**要显示的那一块**，归一化到 0..1（相对原图宽高）。
 *
 * 为什么需要它：背景层要铺满整屏，而相册里的图几乎不会正好是屏幕的比例，于是必然要裁掉
 * 一部分。默认裁正中间（[FULL] + [visiblePixels] 的居中规则），用户想留哪一块就调这个矩形。
 *
 * 为什么存「原图比例下的矩形」而不是直接裁一张新图：原图只解码一次就能换任意区域，
 * 不重新编码也就没有二次损失；而且这块矩形是纯数据，缩放/旋转屏幕时按新比例重新居中即可，
 * 不会像「裁好了的固定尺寸图」那样在别处被拉伸变形。
 *
 * 不变量（[isValid]）：`0 <= left`、`0 <= top`、`left + width <= 1`、`top + height <= 1`。
 */
data class ImageCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
) {

    val right: Float get() = left + width

    val bottom: Float get() = top + height

    /** 越界、反向、退化成一个点或一条线的矩形都算无效，调用方应退回 [FULL]。 */
    val isValid: Boolean
        get() = width > MIN_SIDE && height > MIN_SIDE &&
            left >= -EPS && top >= -EPS &&
            right <= 1f + EPS && bottom <= 1f + EPS

    /** 是不是「整张图」——是的话就不用特意记住它。 */
    val isFull: Boolean
        get() = left <= EPS && top <= EPS && right >= 1f - EPS && bottom >= 1f - EPS

    fun toJsonValue(): JsonValue = JsonValue.obj(
        "left" to JsonValue.Num(left.toDouble()),
        "top" to JsonValue.Num(top.toDouble()),
        "width" to JsonValue.Num(width.toDouble()),
        "height" to JsonValue.Num(height.toDouble()),
    )

    companion object {
        private const val EPS = 1e-3f
        private const val MIN_SIDE = 1e-3f

        /** 整张图。 */
        val FULL = ImageCrop()

        /** 从存档里读；缺字段、类型不对、越界，一律返回 null（调用方当作「居中裁剪」）。 */
        fun fromJsonValue(value: JsonValue?): ImageCrop? {
            val obj = value as? JsonValue.Obj ?: return null
            val left = num(obj, "left") ?: return null
            val top = num(obj, "top") ?: return null
            val width = num(obj, "width") ?: return null
            val height = num(obj, "height") ?: return null
            val crop = ImageCrop(left, top, width, height)
            return if (crop.isValid) crop else null
        }

        private fun num(obj: JsonValue.Obj, key: String): Float? =
            (obj.fields[key] as? JsonValue.Num)?.value?.toFloat()?.takeIf { it.isFinite() }

        /**
         * 「填满」规则下的居中裁剪：图片比视口「宽」就裁掉左右，比视口「窄」就裁掉上下。
         *
         * 这就是**没有手动选区域时的默认表现**，也是把一张 4:3 的照片铺到 9:19.5 的手机上时
         * 唯一不会把人拉变形做法。
         *
         * @param viewportAspect 视口的 宽/高。
         */
        fun coverCenter(imageWidth: Int, imageHeight: Int, viewportAspect: Float): ImageCrop =
            centeredWithin(FULL, imageWidth, imageHeight, viewportAspect)

        /**
         * 在 [region] **内部**再居中切一块比例为 [viewportAspect] 的区域。
         *
         * 两步合成一步用：手选区域本身也有自己的比例，跟屏幕对不上时（转了屏、换了机型、
         * 用户在平板上用同一个存档）就多裁一点，宁可多切也**绝不拉伸**。
         */
        fun centeredWithin(region: ImageCrop, imageWidth: Int, imageHeight: Int, viewportAspect: Float): ImageCrop {
            val safe = if (region.isValid) region else FULL
            if (imageWidth <= 0 || imageHeight <= 0) return safe
            val aspect = viewportAspect.takeIf { it.isFinite() && it > 0f } ?: return safe

            val regionW = safe.width * imageWidth
            val regionH = safe.height * imageHeight
            if (regionW <= 0f || regionH <= 0f) return safe

            var w = regionW
            var h = regionH
            if (regionW / regionH > aspect) {
                w = regionH * aspect      // 区域太宽：左右各切一刀
            } else {
                h = regionW / aspect      // 区域太高：上下各切一刀
            }
            val x = safe.left * imageWidth + (regionW - w) / 2f
            val y = safe.top * imageHeight + (regionH - h) / 2f
            return ImageCrop(x / imageWidth, y / imageHeight, w / imageWidth, h / imageHeight)
        }

        /**
         * 交给 `drawImage(srcOffset, srcSize)` 的那块像素 —— 原图里真正会被看到的部分。
         *
         * [crop] 为 null（或无效）时就是默认的居中裁剪；返回的矩形一定落在原图内且至少有 1 像素，
         * 所以调用方不必再自己兜底。
         */
        fun visiblePixels(
            crop: ImageCrop?,
            imageWidth: Int,
            imageHeight: Int,
            viewportAspect: Float,
        ): PixelRect {
            // 宽高先兜到至少 1 像素：解码失败的图、或者刚建好的 0×0 占位都会走到这里。
            val iw = imageWidth.coerceAtLeast(1)
            val ih = imageHeight.coerceAtLeast(1)
            val region = crop?.takeIf { it.isValid } ?: FULL
            val window = centeredWithin(region, iw, ih, viewportAspect)
            val x = (window.left * iw).toInt().coerceIn(0, iw - 1)
            val y = (window.top * ih).toInt().coerceIn(0, ih - 1)
            // 宽度按「剩余空间」再收一次：`drawImage` 的 src 矩形一旦越出原图就是一张花屏
            // 或者直接抛异常，而 x 与 width 各自取整之后是可能凑出 x + width > iw 的。
            val w = (window.width * iw).toInt().coerceIn(1, iw - x)
            val h = (window.height * ih).toInt().coerceIn(1, ih - y)
            return PixelRect(x = x, y = y, width = w, height = h)
        }

        /** 「填满」基准：把整张图铺满取景框所需的缩放倍数。 */
        fun coverScale(imageWidth: Int, imageHeight: Int, frameWidth: Float, frameHeight: Float): Float {
            if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0f || frameHeight <= 0f) return 1f
            return maxOf(frameWidth / imageWidth, frameHeight / imageHeight)
        }

        /** 放大 [zoom] 倍之后，取景框中心还能相对图片中心挪多远（两个方向各一个值）。 */
        fun panLimit(
            imageWidth: Int,
            imageHeight: Int,
            frameWidth: Float,
            frameHeight: Float,
            zoom: Float,
        ): Pair<Float, Float> {
            if (imageWidth <= 0 || imageHeight <= 0) return 0f to 0f
            val scale = coverScale(imageWidth, imageHeight, frameWidth, frameHeight) * zoom.coerceAtLeast(1f)
            val x = ((imageWidth * scale - frameWidth) / 2f).coerceAtLeast(0f)
            val y = ((imageHeight * scale - frameHeight) / 2f).coerceAtLeast(0f)
            return x to y
        }

        /**
         * 取景框的当前视角 → 归一化区域。**裁切界面的「确定」按的就是这个结果。**
         *
         * @param zoom 在「填满」基准之上的放大倍数，1 = 刚好填满（此时两个方向刚好没有空余）。
         * @param panX/panY 相对居中的位移，单位是取景框像素。
         */
        fun fromViewport(
            imageWidth: Int,
            imageHeight: Int,
            frameWidth: Float,
            frameHeight: Float,
            zoom: Float,
            panX: Float,
            panY: Float,
        ): ImageCrop {
            if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0f || frameHeight <= 0f) return FULL
            val scale = coverScale(imageWidth, imageHeight, frameWidth, frameHeight) * zoom.coerceAtLeast(MIN_ZOOM)
            // 图片左上角在取景框坐标系里的位置（先按居中摆，再叠上位移）。
            val tx = (frameWidth - imageWidth * scale) / 2f + panX
            val ty = (frameHeight - imageHeight * scale) / 2f + panY
            // 取景框反过来投到原图上，就是「看得见的那一块」。
            val crop = ImageCrop(
                left = -tx / scale / imageWidth,
                top = -ty / scale / imageHeight,
                width = frameWidth / scale / imageWidth,
                height = frameHeight / scale / imageHeight,
            )
            return crop.clampToImage()
        }

        /**
         * [fromViewport] 的逆运算：把一个已有的区域换算回「怎么拖出来的」。
         *
         * 「重新选择区域」时用它把界面恢复成上次的样子 —— 用户点进去发现又要从头拖一遍，
         * 等于每次调整都在惩罚他上一次的调整。
         */
        fun toViewport(
            crop: ImageCrop?,
            imageWidth: Int,
            imageHeight: Int,
            frameWidth: Float,
            frameHeight: Float,
        ): CropViewport {
            val region = crop?.takeIf { it.isValid } ?: return CropViewport(1f, 0f, 0f)
            if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0f || frameHeight <= 0f) {
                return CropViewport(1f, 0f, 0f)
            }
            val cover = coverScale(imageWidth, imageHeight, frameWidth, frameHeight)
            val sourceWidth = (region.width * imageWidth).coerceAtLeast(0.5f)
            // 用宽度反推缩放：区域是按取景框的比例切出来的，两个方向的比值本来就该相等。
            val scale = frameWidth / sourceWidth
            val zoom = (scale / cover).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val effective = cover * zoom
            val tx = -region.left * imageWidth * effective
            val ty = -region.top * imageHeight * effective
            return CropViewport(
                zoom = zoom,
                panX = (tx - (frameWidth - imageWidth * effective) / 2f),
                panY = (ty - (frameHeight - imageHeight * effective) / 2f),
            )
        }

        /** 缩放下限 1（再小就露出取景框外了）、上限 6（再大就是马赛克）。 */
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f

        /** 把任意矩形收进 0..1，并保证至少还有一丝面积。存档里读到脏数据时的最后一道防线。 */
        private fun ImageCrop.clampToImage(): ImageCrop {
            val left = this.left.coerceIn(0f, 1f - MIN_SIDE)
            val top = this.top.coerceIn(0f, 1f - MIN_SIDE)
            val width = this.width.coerceIn(MIN_SIDE, 1f - left)
            val height = this.height.coerceIn(MIN_SIDE, 1f - top)
            return ImageCrop(left, top, width, height)
        }
    }
}
