package com.ntu.schedule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自检信息的文本渲染。
 *
 * 「自检」面板本身要跑真机，但**排版**是纯函数 —— 而排版恰好是最容易出错、又最容易被忽略的部分：
 * 一个分组标题印了三遍、或者每个状态都长一样，用户看到的就是一坨没有重点的文本，
 * 复制出去发给别人也没人愿意读。所以这里逐条钉住。
 */
class SelfCheckTextTest {

    @Test
    fun `没有任何项目时说一句人话而不是空白`() {
        assertEquals("（没有检测到任何项目）", SelfCheck.toText(emptyList()))
    }

    @Test
    fun `开头交代这份东西是什么`() {
        val text = SelfCheck.toText(listOf(Check("本地数据", "课表", "5 门课", CheckState.OK)))
        assertTrue(text.startsWith("南通大学课表 · 自检信息"))
        assertTrue(text.contains("已经把学号之类的东西抹掉了"))
    }

    @Test
    fun `同一个分组只印一次标题`() {
        val text = SelfCheck.toText(
            listOf(
                Check("本地数据", "课表", "5 门课", CheckState.OK),
                Check("本地数据", "学号", "尾号 0060", CheckState.INFO),
                Check("桌面小组件", "桌面", "系统桌面", CheckState.INFO),
            ),
        )
        assertEquals(1, text.split("【本地数据】").size - 1)
        assertEquals(1, text.split("【桌面小组件】").size - 1)
    }

    @Test
    fun `四种状态各自的记号不一样`() {
        // 记号就是这一屏的全部信息量：全用同一个符号，用户得逐行读完才知道哪条有问题。
        val text = SelfCheck.toText(
            listOf(
                Check("提醒", "通知权限", "被关掉了，通知发不出去", CheckState.BAD),
                Check("提醒", "电池优化", "未放行。省电模式下提醒可能被推迟", CheckState.WARN),
                Check("提醒", "渠道", "已建好", CheckState.OK),
                Check("提醒", "自启动", "各家叫法不同，没查到就自己看一眼", CheckState.INFO),
            ),
        )
        assertTrue(text.contains("✕ 被关掉了，通知发不出去"))
        assertTrue(text.contains("△ 未放行。省电模式下提醒可能被推迟"))
        assertTrue(text.contains("· 各家叫法不同，没查到就自己看一眼"))
        // 「一切正常」不加记号 —— 满屏符号反而看不出重点
        assertFalse(text.contains("✓"))
        assertTrue(text.contains("已建好"))
    }

    @Test
    fun `按显示宽度补齐而不是按字符数`() {
        // 只按字符数补，「课表」这种两个字的值就会被后面的内容顶歪，
        // 粘到微信里（等宽字体）整段就参差不齐了。
        val text = SelfCheck.toText(
            listOf(
                Check("g", "课表", "x", CheckState.OK),
                Check("g", "ab", "y", CheckState.OK),
            ),
        )
        assertTrue("两个汉字应算 4 格，补 20 个空格", text.contains("课表" + " ".repeat(20) + "x"))
        assertTrue("两个半角字符算 2 格，补 22 个空格", text.contains("ab" + " ".repeat(22) + "y"))
    }

    @Test
    fun `过长的标签后面至少留一个空格`() {
        val long = "一".repeat(20) // 显示宽度 40，已经超过 24
        val text = SelfCheck.toText(listOf(Check("g", long, "值", CheckState.OK)))
        assertTrue(text.contains(long + " 值"))
    }

    @Test
    fun `每一行都以两个空格缩进`() {
        val text = SelfCheck.toText(listOf(Check("本地数据", "课表", "5 门课", CheckState.OK)))
        // 标题行「南通大学课表 · 自检信息」里也有「课表」两个字，所以按值来挑行
        val body = text.lines().filter { it.contains("5 门课") }
        assertTrue(body.isNotEmpty())
        assertTrue("结果行应缩进在分组标题之下", body.all { it.startsWith("  ") })
    }

    @Test
    fun `分享标题固定`() {
        assertEquals("南通大学课表自检信息", SelfCheck.subject())
    }
}
