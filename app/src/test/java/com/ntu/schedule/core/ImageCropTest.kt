package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景图「选显示区域」的几何。
 *
 * 这一层全是纯函数，能在 JVM 里跑，所以把三件最容易出错的事钉死：
 * ① 默认的居中裁剪（原来的表现，不能变）；
 * ② 交给 `drawImage` 的像素矩形**永远不越出原图**（越界在真机上是一张花屏或者直接抛异常）；
 * ③ 区域 ⇄ 视角能来回换算（「重新选择区域」要接着上次的位置）。
 */
class ImageCropTest {

    private val phoneAspect = 0.5f      // 竖屏手机，宽:高 ≈ 1:2

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertEquals(what, expected.toDouble(), actual.toDouble(), 1e-3)
    }

    // ---------------------------------------------------------------- 居中裁剪

    @Test
    fun `横图铺到竖屏手机上切掉左右`() {
        // 4000×2000 的横图，屏幕比例 0.5 —— 要填满只能留中间 1000 宽
        val crop = ImageCrop.coverCenter(4000, 2000, phoneAspect)
        assertClose(0.375f, crop.left, "left")
        assertClose(0f, crop.top, "top")
        assertClose(0.25f, crop.width, "width")
        assertClose(1f, crop.height, "height")
    }

    @Test
    fun `竖图铺到竖屏手机上正好铺满不用裁`() {
        // 2000×4000 的比例正好是 0.5，一点都不用切
        val crop = ImageCrop.coverCenter(2000, 4000, phoneAspect)
        assertClose(0f, crop.left, "left")
        assertClose(0f, crop.top, "top")
        assertClose(1f, crop.width, "width")
        assertClose(1f, crop.height, "height")
        assertTrue(crop.isFull)
    }

    @Test
    fun `方图铺到竖屏手机上切掉左右一半`() {
        val crop = ImageCrop.coverCenter(1000, 1000, phoneAspect)
        assertClose(0.25f, crop.left, "left")
        assertClose(0.5f, crop.width, "width")
        assertClose(1f, crop.height, "height")
    }

    @Test
    fun `方图铺到方视口上一点不裁`() {
        assertTrue(ImageCrop.coverCenter(800, 800, 1f).isFull)
    }

    @Test
    fun `尺寸或比例不合法时退回整张图`() {
        assertTrue(ImageCrop.coverCenter(0, 0, phoneAspect).isFull)
        assertTrue(ImageCrop.coverCenter(100, 100, 0f).isFull)
        assertTrue(ImageCrop.coverCenter(100, 100, Float.NaN).isFull)
    }

    @Test
    fun `居中裁剪的结果比例一定等于视口比例`() {
        // 这条是「绝不拉伸」的另一种说法：切出来的那块，宽高比必须和屏幕一样
        val sizes = listOf(4000 to 2000, 2000 to 4000, 1920 to 1080, 1000 to 1000, 3000 to 4000)
        val aspects = listOf(0.4f, 0.5f, 0.5625f, 1f, 1.6f)
        for ((w, h) in sizes) {
            for (aspect in aspects) {
                val crop = ImageCrop.coverCenter(w, h, aspect)
                val actual = (crop.width * w) / (crop.height * h)
                assertClose(aspect, actual, "图 ${w}x$h 在 $aspect 下的比例")
            }
        }
    }

    // ------------------------------------------------------------ 像素矩形

    @Test
    fun `默认区域的像素矩形就是居中那块`() {
        val rect = ImageCrop.visiblePixels(null, 4000, 2000, phoneAspect)
        assertEquals(1500, rect.x)
        assertEquals(0, rect.y)
        assertEquals(1000, rect.width)
        assertEquals(2000, rect.height)
    }

    @Test
    fun `指定区域时只在那块里再居中切一刀`() {
        // 人工选了左上角 0..0.5 的正方形，屏幕是方的 —— 整块都要
        val rect = ImageCrop.visiblePixels(ImageCrop(0f, 0f, 0.5f, 0.5f), 1000, 1000, 1f)
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
        assertEquals(500, rect.width)
        assertEquals(500, rect.height)
    }

    @Test
    fun `选的区域和屏幕比例对不上时多裁一点也不拉伸`() {
        // 选了一整条横带（1000×200），屏幕是方的：只能取中间 200×200
        val rect = ImageCrop.visiblePixels(ImageCrop(0f, 0f, 1f, 0.2f), 1000, 1000, 1f)
        assertEquals(400, rect.x)
        assertEquals(0, rect.y)
        assertEquals(200, rect.width)
        assertEquals(200, rect.height)
    }

    @Test
    fun `像素矩形永远落在原图里且至少有像素`() {
        // 越界的 src 矩形在真机上是一张花屏，严重的直接抛异常 —— 这里穷举一批刁钻尺寸。
        val sizes = listOf(
            4000 to 2000, 2000 to 4000, 1 to 1, 1 to 5000, 5000 to 1,
            3 to 7, 7 to 3, 1920 to 1080, 999 to 1001, 6000 to 6000,
        )
        val aspects = listOf(0.1f, 0.5f, 0.5625f, 1f, 1.7778f, 3f)
        val crops = listOf(
            null,
            ImageCrop.FULL,
            ImageCrop(0f, 0f, 0.5f, 0.5f),
            ImageCrop(0.5f, 0.5f, 0.5f, 0.5f),
            ImageCrop(0.99f, 0.99f, 0.01f, 0.01f),
            ImageCrop(0f, 0f, 1f, 0.002f),          // 极扁的一条
            ImageCrop(0f, 0f, 0.002f, 1f),          // 极窄的一条
        )
        for ((w, h) in sizes) {
            for (aspect in aspects) {
                for (crop in crops) {
                    val r = ImageCrop.visiblePixels(crop, w, h, aspect)
                    val where = "图 ${w}x$h 比例 $aspect 区域 $crop"
                    assertTrue("$where 的 x 越界：$r", r.x >= 0)
                    assertTrue("$where 的 y 越界：$r", r.y >= 0)
                    assertTrue("$where 的宽不够：$r", r.width >= 1)
                    assertTrue("$where 的高不够：$r", r.height >= 1)
                    assertTrue("$where 右边越界：$r", r.x + r.width <= w)
                    assertTrue("$where 下边越界：$r", r.y + r.height <= h)
                }
            }
        }
    }

    // ------------------------------------------------------------ 区域 ⇄ 视角

    @Test
    fun `视角换区域再换回来是原地不动的`() {
        val imageW = 4000
        val imageH = 2000
        val frameW = 500f
        val frameH = 1000f
        for (zoom in listOf(1f, 1.5f, 2f, 3f, 6f)) {
            for ((panX, panY) in listOf(0f to 0f, 300f to -200f, -120f to 80f)) {
                // 位移不能超出这个缩放倍数下的可拖范围，否则会被夹回来，往返自然对不上
                val (limitX, limitY) = ImageCrop.panLimit(imageW, imageH, frameW, frameH, zoom)
                if (kotlin.math.abs(panX) > limitX || kotlin.math.abs(panY) > limitY) continue

                val crop = ImageCrop.fromViewport(imageW, imageH, frameW, frameH, zoom, panX, panY)
                val back = ImageCrop.toViewport(crop, imageW, imageH, frameW, frameH)
                val where = "zoom=$zoom pan=($panX,$panY)"
                assertClose(zoom, back.zoom, "$where 的 zoom")
                assertClose(panX, back.panX, "$where 的 panX")
                assertClose(panY, back.panY, "$where 的 panY")
            }
        }
    }

    @Test
    fun `不拖不缩得到的正是默认的居中区域`() {
        val crop = ImageCrop.fromViewport(4000, 2000, 500f, 1000f, 1f, 0f, 0f)
        val expected = ImageCrop.coverCenter(4000, 2000, phoneAspect)
        assertClose(expected.left, crop.left, "left")
        assertClose(expected.top, crop.top, "top")
        assertClose(expected.width, crop.width, "width")
        assertClose(expected.height, crop.height, "height")
    }

    @Test
    fun `放大之后留下的区域一定更小`() {
        val wide = ImageCrop.fromViewport(4000, 2000, 500f, 1000f, 1f, 0f, 0f)
        val zoomed = ImageCrop.fromViewport(4000, 2000, 500f, 1000f, 2f, 0f, 0f)
        assertTrue("放大后的宽应该更小", zoomed.width < wide.width)
        assertTrue("放大后的高应该更小", zoomed.height < wide.height)
    }

    @Test
    fun `刚好填满时纵向一点都挪不动横向还剩一半`() {
        // zoom=1 时图片的「高」刚好等于取景框，上下没有富余；横向还多出一半可挪
        val (x, y) = ImageCrop.panLimit(4000, 2000, 500f, 1000f, 1f)
        assertClose(750f, x, "横向可拖距离")
        assertClose(0f, y, "纵向可拖距离")
    }

    @Test
    fun `没有区域时视角就是原点`() {
        val v = ImageCrop.toViewport(null, 4000, 2000, 500f, 1000f)
        assertClose(1f, v.zoom, "zoom")
        assertClose(0f, v.panX, "panX")
        assertClose(0f, v.panY, "panY")
    }

    @Test
    fun `尺寸为零时不炸只给一个原点视角`() {
        val v = ImageCrop.toViewport(ImageCrop(0f, 0f, 0.5f, 0.5f), 0, 0, 0f, 0f)
        assertClose(1f, v.zoom, "zoom")
        assertTrue(ImageCrop.fromViewport(0, 0, 0f, 0f, 2f, 0f, 0f).isFull)
    }

    // ---------------------------------------------------------------- 校验

    @Test
    fun `越界与退化的矩形都不合法`() {
        assertTrue(ImageCrop.FULL.isValid)
        assertTrue(ImageCrop(0.25f, 0.25f, 0.5f, 0.5f).isValid)

        assertTrue(!ImageCrop(0.8f, 0f, 0.5f, 1f).isValid)      // right = 1.3
        assertTrue(!ImageCrop(0f, 0.8f, 1f, 0.5f).isValid)      // bottom = 1.3
        assertTrue(!ImageCrop(-0.2f, 0f, 0.5f, 1f).isValid)     // left 是负的
        assertTrue(!ImageCrop(0.5f, 0.5f, 0f, 0.5f).isValid)    // 宽度是 0
        assertTrue(!ImageCrop(0.5f, 0.5f, 0.5f, -0.1f).isValid) // 高度是负的
    }

    @Test
    fun `整张图会被认成没有特意选过`() {
        assertTrue(ImageCrop.FULL.isFull)
        assertTrue(!ImageCrop(0f, 0f, 0.9f, 1f).isFull)
        assertTrue(!ImageCrop(0.1f, 0f, 0.9f, 1f).isFull)
    }

    // ---------------------------------------------------------------- 存档

    @Test
    fun `区域能存进 json 再读回来`() {
        val crop = ImageCrop(0.125f, 0.25f, 0.5f, 0.375f)
        val text = JsonValue.obj("imageCrop" to crop.toJsonValue()).toJsonString()
        val back = ImageCrop.fromJsonValue(JsonValue.parse(text)["imageCrop"])
        assertNotNull(back)
        assertClose(crop.left, back!!.left, "left")
        assertClose(crop.top, back.top, "top")
        assertClose(crop.width, back.width, "width")
        assertClose(crop.height, back.height, "height")
    }

    @Test
    fun `存档里是脏数据就当作没选过`() {
        assertNull(ImageCrop.fromJsonValue(null))
        assertNull(ImageCrop.fromJsonValue(JsonValue.Null))
        assertNull(ImageCrop.fromJsonValue(JsonValue.Str("0.1,0.2")))
        assertNull(ImageCrop.fromJsonValue(JsonValue.Arr(emptyList())))
        // 少字段
        assertNull(ImageCrop.fromJsonValue(JsonValue.obj("left" to JsonValue.Num(0.5))))
        // 类型不对
        assertNull(
            ImageCrop.fromJsonValue(
                JsonValue.obj(
                    "left" to JsonValue.Str("左边"),
                    "top" to JsonValue.Num(0.0),
                    "width" to JsonValue.Num(0.5),
                    "height" to JsonValue.Num(0.5),
                ),
            ),
        )
        // 越界
        assertNull(
            ImageCrop.fromJsonValue(
                JsonValue.obj(
                    "left" to JsonValue.Num(0.8),
                    "top" to JsonValue.Num(0.0),
                    "width" to JsonValue.Num(0.5),
                    "height" to JsonValue.Num(1.0),
                ),
            ),
        )
    }

    // ------------------------------------------------------- 和外观的配合

    @Test
    fun `设置图片时顺手记下区域`() {
        val crop = ImageCrop(0.1f, 0.2f, 0.3f, 0.4f)
        val a = Appearance.DEFAULT.withImage("background_1.jpg", crop)
        assertEquals(BackgroundMode.IMAGE, a.mode)
        assertEquals("background_1.jpg", a.imageName)
        assertEquals(crop, a.imageCrop)
    }

    @Test
    fun `整张图就等于没选区域`() {
        // 免得存档里多出一个「等于全图」的矩形，读的时候还要再判一次
        assertNull(Appearance.DEFAULT.withImage("a.jpg", ImageCrop.FULL).imageCrop)
        assertNull(Appearance.DEFAULT.withImage("a.jpg", null).imageCrop)
        assertNull(Appearance.DEFAULT.withImage("a.jpg").imageCrop)
    }

    @Test
    fun `换个区域不影响别的设置`() {
        val a = Appearance.DEFAULT.withImage("a.jpg", ImageCrop(0.1f, 0.1f, 0.5f, 0.5f))
        val b = a.withCrop(ImageCrop(0.2f, 0.2f, 0.4f, 0.4f))
        assertEquals("a.jpg", b.imageName)
        assertEquals(BackgroundMode.IMAGE, b.mode)
        assertEquals(ImageCrop(0.2f, 0.2f, 0.4f, 0.4f), b.imageCrop)
    }

    @Test
    fun `区域跟着外观一起存进 json 再读回来`() {
        val a = Appearance.DEFAULT.withImage("background_9.jpg", ImageCrop(0.05f, 0.1f, 0.25f, 0.5f))
        val back = Appearance.fromJson(Appearance.toJson(a))
        assertEquals("background_9.jpg", back.imageName)
        val crop = back.imageCrop
        assertNotNull(crop)
        assertClose(0.05f, crop!!.left, "left")
        assertClose(0.5f, crop.height, "height")
    }

    @Test
    fun `不是图片模式时区域一律丢掉`() {
        // 切回纯色之后那个区域就没有意义了，留着只会让存档里多一份过期数据
        val a = Appearance.DEFAULT
            .withImage("background_9.jpg", ImageCrop(0.05f, 0.1f, 0.25f, 0.5f))
            .copy(mode = BackgroundMode.SOLID)
        assertNull(Appearance.fromJson(Appearance.toJson(a)).imageCrop)
    }

    @Test
    fun `旧的存档没有区域字段也能读`() {
        // 升级前存下来的 appearance.json 里没有 imageCrop，必须还能正常打开
        val legacy = """{"v":1,"mode":"IMAGE","colorStart":0,"colorEnd":0,"dimPercent":0,"imageName":"background_1.jpg"}"""
        val back = Appearance.fromJson(legacy)
        assertEquals(BackgroundMode.IMAGE, back.mode)
        assertEquals("background_1.jpg", back.imageName)
        assertNull(back.imageCrop)
    }
}
