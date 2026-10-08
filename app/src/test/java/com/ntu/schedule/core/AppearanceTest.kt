package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置的解析与约束。
 *
 * 这一层不碰 `Context`、不碰 `Bitmap`，所以能在纯 JVM 上钉住 —— 背景这种东西一旦解析崩了，
 * 表现是「打开 App 一片黑」，而那时用户已经看不到任何提示了。
 */
class AppearanceTest {

    @Test
    fun `默认是不自定义背景`() {
        val a = Appearance.DEFAULT
        assertEquals(BackgroundMode.DEFAULT, a.mode)
        assertFalse(a.isCustom)
        assertFalse(a.isUsable)
        assertNull(a.imageName)
    }

    @Test
    fun `纯色背景JSON往返`() {
        val a = Appearance(
            mode = BackgroundMode.SOLID,
            colorStart = 0xFF1E6E62.toInt(),
            dimPercent = 12,
        )
        assertEquals(a, Appearance.fromJson(Appearance.toJson(a)))
    }

    @Test
    fun `渐变背景JSON往返`() {
        val a = Appearance(
            mode = BackgroundMode.GRADIENT,
            colorStart = 0xFF0E5C4A.toInt(),
            colorEnd = 0xFF86D3A8.toInt(),
            dimPercent = 0,
        )
        assertEquals(a, Appearance.fromJson(Appearance.toJson(a)))
    }

    @Test
    fun `图片背景JSON往返`() {
        val a = Appearance(
            mode = BackgroundMode.IMAGE,
            dimPercent = 55,
            imageName = "background_1700000000000.jpg",
        )
        assertEquals(a, Appearance.fromJson(Appearance.toJson(a)))
    }

    @Test
    fun `负数的颜色值能原样往返`() {
        // ARGB 里 alpha=FF 的都是负数，写进 JSON 再读回来必须还是同一个数
        val color = 0xFF1F5FA9.toInt()
        assertTrue(color < 0)
        val a = Appearance(mode = BackgroundMode.SOLID, colorStart = color)
        assertEquals(color, Appearance.fromJson(Appearance.toJson(a)).colorStart)
    }

    @Test
    fun `遮罩百分比超出上限会被收敛`() {
        val a = Appearance(dimPercent = 500)
        assertEquals(Appearance.MAX_DIM, Appearance.fromJson(Appearance.toJson(a)).dimPercent)
    }

    @Test
    fun `存档里的遮罩百分比超出上限也会被收敛`() {
        val text = """{"mode":"SOLID","colorStart":-14606423,"colorEnd":-14606423,"dimPercent":9999}"""
        assertEquals(Appearance.MAX_DIM, Appearance.fromJson(text).dimPercent)
    }

    @Test
    fun `遮罩换算成比例`() {
        assertEquals(0f, Appearance(dimPercent = 0).dimFraction(), 0.0001f)
        assertEquals(0.8f, Appearance(dimPercent = 80).dimFraction(), 0.0001f)
        // 越界的一律夹住，绝不让界面拿到 alpha > 1 的 Color
        assertEquals(0.8f, Appearance(dimPercent = 200).dimFraction(), 0.0001f)
        assertEquals(0f, Appearance(dimPercent = -30).dimFraction(), 0.0001f)
    }

    @Test
    fun `存档为空或坏掉时退回默认值`() {
        assertEquals(Appearance.DEFAULT, Appearance.fromJson(null))
        assertEquals(Appearance.DEFAULT, Appearance.fromJson(""))
        assertEquals(Appearance.DEFAULT, Appearance.fromJson("   "))
        assertEquals(Appearance.DEFAULT, Appearance.fromJson("这不是 json"))
        assertEquals(Appearance.DEFAULT, Appearance.fromJson("{"))
        // 顶层是数组而不是对象
        assertEquals(Appearance.DEFAULT, Appearance.fromJson("[1,2,3]"))
        // 空对象：所有字段都取默认
        assertEquals(Appearance.DEFAULT, Appearance.fromJson("{}"))
        assertEquals(Appearance.DEFAULT, Appearance.fromJson("null"))
    }

    @Test
    fun `不认识的模式名退回默认背景`() {
        val text = """{"mode":"VIDEO","colorStart":-14606423,"dimPercent":10}"""
        assertEquals(BackgroundMode.DEFAULT, Appearance.fromJson(text).mode)
    }

    @Test
    fun `图片模式但文件名丢了就退回默认而不是画一片黑`() {
        val text = """{"mode":"IMAGE","dimPercent":45,"imageName":""}"""
        assertEquals(Appearance.DEFAULT, Appearance.fromJson(text))

        val missing = """{"mode":"IMAGE","dimPercent":45}"""
        assertEquals(Appearance.DEFAULT, Appearance.fromJson(missing))
    }

    @Test
    fun `图片模式有文件名时是可用的`() {
        val a = Appearance(mode = BackgroundMode.IMAGE, imageName = "background_1.jpg")
        assertTrue(a.isUsable)
        assertTrue(a.isCustom)
    }

    @Test
    fun `纯色与渐变永远可用`() {
        assertTrue(Appearance(mode = BackgroundMode.SOLID).isUsable)
        assertTrue(Appearance(mode = BackgroundMode.GRADIENT).isUsable)
    }

    @Test
    fun `withMode只改模式`() {
        val a = Appearance(colorStart = 0xFF2F5D3A.toInt(), dimPercent = 33)
        val b = a.withMode(BackgroundMode.GRADIENT)
        assertEquals(BackgroundMode.GRADIENT, b.mode)
        assertEquals(a.colorStart, b.colorStart)
        assertEquals(a.dimPercent, b.dimPercent)
    }

    @Test
    fun `预设颜色都是不透明色`() {
        Appearance.PRESET_SOLIDS.forEach { argb ->
            assertEquals(0xFF, (argb ushr 24) and 0xFF)
        }
        Appearance.PRESET_GRADIENTS.forEach { (start, end) ->
            assertEquals(0xFF, (start ushr 24) and 0xFF)
            assertEquals(0xFF, (end ushr 24) and 0xFF)
        }
    }

    @Test
    fun `预设数量稳定`() {
        // 界面按「8 个纯色排一行」「渐变每行 3 组、共 2 行」来排版，改数量要同时改界面
        assertEquals(8, Appearance.PRESET_SOLIDS.size)
        assertEquals(6, Appearance.PRESET_GRADIENTS.size)
    }

    @Test
    fun `默认颜色取自预设第一项`() {
        assertEquals(Appearance.PRESET_SOLIDS[0], Appearance.DEFAULT.colorStart)
        assertEquals(Appearance.PRESET_GRADIENTS[0].second, Appearance.DEFAULT.colorEnd)
    }

    @Test
    fun `默认不压暗背景`() {
        // 用户挑的颜色/图片就是他想要的亮度。默认先盖一层黑，等于把「选」这件事白做了，
        // 还得先找到滑块才能看回原样。
        assertEquals(0, Appearance.DEFAULT_DIM)
        assertEquals(0, Appearance.DEFAULT.dimPercent)
        assertEquals(0f, Appearance.DEFAULT.dimFraction(), 0.0001f)
    }

    @Test
    fun `换成图片时遮罩归零`() {
        // 换图是个新的开始：上一张图压到 60% 不代表这一张也要压到 60%
        val a = Appearance(mode = BackgroundMode.SOLID, dimPercent = 60)
        val b = a.withImage("background_9.jpg")

        assertEquals(BackgroundMode.IMAGE, b.mode)
        assertEquals("background_9.jpg", b.imageName)
        assertEquals(0, b.dimPercent)
        assertTrue(b.isUsable)
    }

    @Test
    fun `换了图片后遮罩仍可再手动调高`() {
        val b = Appearance.DEFAULT.withImage("background_1.jpg").copy(dimPercent = 30)
        assertEquals(30, b.dimPercent)
        assertEquals(b, Appearance.fromJson(Appearance.toJson(b)))
    }

    @Test
    fun `存档里没有遮罩字段时按不压暗处理`() {
        val text = """{"mode":"SOLID","colorStart":-14606423,"colorEnd":-14606423}"""
        assertEquals(0, Appearance.fromJson(text).dimPercent)
    }
}
