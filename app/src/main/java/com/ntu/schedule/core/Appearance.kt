package com.ntu.schedule.core

/** 课表背景的四种来源。 */
enum class BackgroundMode {
    /** 跟随主题的纯色背景（默认）。 */
    DEFAULT,

    /** 用户选的一种纯色。 */
    SOLID,

    /** 用户选的渐变色。 */
    GRADIENT,

    /** 用户从相册选的一张图片。 */
    IMAGE,
}

/**
 * 全局外观设置。
 *
 * 刻意做成**纯数据 + 纯函数**（不碰 `Context`、不碰 `Bitmap`），这样 JSON 往返、
 * 取值范围收敛、遮罩换算都能在 JVM 单测里钉住 —— 背景这种东西一旦解析崩了，
 * 表现是「打开 App 全是黑的」，而那时用户已经看不到任何提示了。
 *
 * @param dimPercent 背景上盖一层黑色遮罩的不透明度（0-80）。**默认 0，也就是不压暗** ——
 *   用户挑的颜色/图片就是他想要的亮度，一上来先盖一层黑等于把「选」这件事白做了
 *   还得先去找滑块。图片背景要看不清字时再自己往上拖。
 */
data class Appearance(
    val mode: BackgroundMode = BackgroundMode.DEFAULT,
    val colorStart: Int = PRESET_SOLIDS[0],
    val colorEnd: Int = PRESET_GRADIENTS[0].second,
    val dimPercent: Int = DEFAULT_DIM,
    /** 存在 App 私有目录里的图片文件名；[BackgroundMode.IMAGE] 时才有意义。 */
    val imageName: String? = null,
    /**
     * 图片里显示哪一块（用户在裁切界面手选的）。
     *
     * `null` = 没选过，按屏幕比例**居中裁剪** —— 也就是这个功能之前的行为，
     * 所以老存档读进来一切照旧。见 [ImageCrop.visiblePixels]。
     */
    val imageCrop: ImageCrop? = null,
) {

    /** 有没有自定义背景。没有的话各页面用主题色，有的话格子要变半透明让背景透出来。 */
    val isCustom: Boolean get() = mode != BackgroundMode.DEFAULT

    /** 该背景能不能真的画出来（图片模式下文件名丢了就退回默认，避免黑屏）。 */
    val isUsable: Boolean
        get() = when (mode) {
            BackgroundMode.DEFAULT -> false
            BackgroundMode.SOLID, BackgroundMode.GRADIENT -> true
            BackgroundMode.IMAGE -> !imageName.isNullOrBlank()
        }

    fun withMode(mode: BackgroundMode): Appearance = copy(mode = mode)

    /**
     * 换成刚选好的图片背景。
     *
     * 遮罩**重置成 [DEFAULT_DIM]**，不复用纯色/渐变时调好的值：换图是个新的开始，
     * 上一张图压到 60% 不代表这一张也要压到 60%。
     *
     * @param crop 用户在裁切界面选的区域；不给就是居中裁剪。
     */
    fun withImage(name: String, crop: ImageCrop? = null): Appearance =
        copy(
            mode = BackgroundMode.IMAGE,
            imageName = name,
            imageCrop = crop?.takeIf { it.isValid && !it.isFull },
            dimPercent = DEFAULT_DIM,
        )

    /** 换一张图，但保留当前这张已经调好的显示区域（重新选区域时用）。 */
    fun withCrop(crop: ImageCrop?): Appearance =
        copy(imageCrop = crop?.takeIf { it.isValid && !it.isFull })

    /** 遮罩不透明度，0f..0.8f。 */
    fun dimFraction(): Float = dimPercent.coerceIn(0, MAX_DIM).toFloat() / 100f

    companion object {
        /** 遮罩上限 80%：再高就看不见背景了，等于白设。 */
        const val MAX_DIM = 80

        /** 遮罩默认值 0：挑好的颜色/图先原样显示，要压暗再自己拖。 */
        const val DEFAULT_DIM = 0

        const val SCHEMA_VERSION = 1

        /** 纯色预设。 */
        val PRESET_SOLIDS: List<Int> = listOf(
            0xFF1F5FA9.toInt(), // 通大蓝
            0xFF1E6E62.toInt(), // 竹青
            0xFF6B4E9B.toInt(), // 紫罗兰
            0xFFB4553C.toInt(), // 砖红
            0xFF2B3A55.toInt(), // 夜蓝
            0xFF4A4A4A.toInt(), // 石墨
            0xFF8A6D3B.toInt(), // 秋褐
            0xFF2F5D3A.toInt(), // 松绿
        )

        /** 渐变预设（起点 → 终点）。 */
        val PRESET_GRADIENTS: List<Pair<Int, Int>> = listOf(
            0xFF1F5FA9.toInt() to 0xFF7FB0E8.toInt(), // 通大蓝
            0xFF6B4E9B.toInt() to 0xFFE3A6C8.toInt(), // 暮紫
            0xFF0E5C4A.toInt() to 0xFF86D3A8.toInt(), // 竹青
            0xFFA8452F.toInt() to 0xFFF0C386.toInt(), // 落日
            0xFF232B3A.toInt() to 0xFF61748F.toInt(), // 深空
            0xFF3B3F46.toInt() to 0xFF9AA3AE.toInt(), // 铅灰
        )

        /**
         * 默认外观。
         *
         * **必须写在两个预设之后**：`Appearance` 的构造参数默认值是 `PRESET_SOLIDS[0]` 和
         * `PRESET_GRADIENTS[0].second`，而伴生对象的初始化是从上往下顺序执行的。放在上面时
         * `PRESET_SOLIDS` 还是 null，构造默认值会直接抛 NPE（整个类的初始化就废了）。
         */
        val DEFAULT = Appearance()

        /**
         * 解析存档。**任何异常都退回默认值**，绝不抛出去 —— 存档坏了应该是「背景没了」，
         * 不是「App 打不开」。
         */
        fun fromJson(text: String?): Appearance {
            if (text.isNullOrBlank()) return DEFAULT
            val root = JsonValue.parse(text)
            if (root !is JsonValue.Obj) return DEFAULT

            val mode = when (root.strOf("mode")) {
                "SOLID" -> BackgroundMode.SOLID
                "GRADIENT" -> BackgroundMode.GRADIENT
                "IMAGE" -> BackgroundMode.IMAGE
                else -> BackgroundMode.DEFAULT
            }
            val start = root.intOf("colorStart", default = DEFAULT.colorStart)
            val end = root.intOf("colorEnd", default = DEFAULT.colorEnd)
            val dim = root.intOf("dimPercent", default = DEFAULT.dimPercent)
            val image = root.strOf("imageName").ifBlank { null }

            val parsed = Appearance(
                mode = mode,
                colorStart = start,
                colorEnd = end,
                dimPercent = dim.coerceIn(0, MAX_DIM),
                imageName = image,
                imageCrop = if (mode == BackgroundMode.IMAGE) ImageCrop.fromJsonValue(root["imageCrop"]) else null,
            )
            // 图片模式但文件名没了（用户清了缓存、换了手机），退回默认而不是画一片黑
            return if (parsed.mode == BackgroundMode.IMAGE && parsed.imageName == null) DEFAULT else parsed
        }

        fun toJson(appearance: Appearance): String = JsonValue.obj(
            "v" to JsonValue.of(SCHEMA_VERSION),
            "mode" to JsonValue.of(appearance.mode.name),
            "colorStart" to JsonValue.of(appearance.colorStart),
            "colorEnd" to JsonValue.of(appearance.colorEnd),
            "dimPercent" to JsonValue.of(appearance.dimPercent.coerceIn(0, MAX_DIM)),
            "imageName" to JsonValue.of(appearance.imageName),
            "imageCrop" to (appearance.imageCrop?.takeIf { it.isValid }?.toJsonValue() ?: JsonValue.Null),
        ).toJsonString()
    }
}
