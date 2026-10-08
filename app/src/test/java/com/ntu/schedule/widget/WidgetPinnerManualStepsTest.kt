package com.ntu.schedule.widget

import com.ntu.schedule.notify.OemSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件的「手动添加」说明文案。
 *
 * 这几段话就是用户点开「添加桌面小组件」之后看到的全部内容。它必须同时做对三件事：
 *   1. 用**这台手机自己的叫法**指路 —— 小米叫「小部件」、ColorOS 叫「卡片」、
 *      OriginOS 叫「原子组件」，说错了用户翻遍菜单也找不到；
 *   2. 说清「点了没反应」时该怎么办 —— 这正是「只有小米能加小组件」这个印象的来源；
 *   3. 每一句都要出现应用名，否则用户不知道该在列表里找谁。
 *
 * 纯函数，所以直接单测 —— 靠上真机点一遍来验证这几百个字是不现实的。
 */
class WidgetPinnerManualStepsTest {

    /**
     * 可能传进 manualSteps 的各种 key：OemSettings 会产出的全部厂商 key，
     * 外加几个别名和一个不存在的 —— 别名必须安全退到通用说明，不能崩、也不能给出误导性的步骤。
     */
    private val allVendorKeys = listOf(
        "xiaomi", "redmi", "poco",
        "huawei", "honor",
        "oppo", "realme", "oneplus",
        "vivo", "iqoo",
        "meizu", "samsung", "asus", "nokia", "letv", "smartisan", "transsion", "sony", "lge",
        "other", "不认识的厂商",
    )

    @Test
    fun `每个厂商都能拿到能照着做的步骤`() {
        for (key in allVendorKeys) {
            val steps = WidgetPinner.manualSteps(key, "某厂商")
            assertTrue("$key 没有给步骤", steps.size >= 2)
            assertTrue("$key 有空白步骤", steps.none { it.isBlank() })
        }
    }

    @Test
    fun `每一步都点名要在列表里找哪个应用`() {
        for (key in allVendorKeys) {
            val steps = WidgetPinner.manualSteps(key, "某厂商")
            assertTrue("$key 的步骤里没提应用名", steps.any { it.contains("南通大学课表") })
        }
    }

    @Test
    fun `OPPO 要写卡片 - ColorOS 里不叫小组件`() {
        val steps = WidgetPinner.manualSteps("oppo", "OPPO")
        assertTrue(steps.any { it.contains("卡片") })
        // ColorOS 会弹确认框，不写清楚用户会以为卡住了
        assertTrue(steps.any { it.contains("确认") })
        // 「点了没反应」多半是当前页放不下 —— 这一条正是用户最需要的那句
        assertTrue(steps.any { it.contains("放不下") })
    }

    @Test
    fun `一加走的是 OPPO 这一套`() {
        // OemSettings 把一加 / realme 归到 oppo 这个 key，文案必须跟着一起走，
        // 否则一加用户拿到的会是「在菜单里找小组件」这种等于没说的通用说明。
        val vendor = OemSettings.vendorOf("OnePlus", "OnePlus")
        assertEquals("oppo", vendor.key)
        assertTrue(vendor.label.contains("一加"))
        val steps = WidgetPinner.manualSteps(vendor.key, vendor.label)
        assertTrue("一加用户也要被告知菜单叫「卡片」", steps.any { it.contains("卡片") })
    }

    @Test
    fun `vivo 要指明通用的小组件藏在安卓组件标签里`() {
        val steps = WidgetPinner.manualSteps("vivo", "vivo")
        assertTrue("vivo 面板里通用组件不在「原子组件」这个标签下", steps.any { it.contains("安卓组件") })
        assertTrue("不解释清楚用户会以为本应用不支持 vivo", steps.any { it.contains("SDK") })
    }

    @Test
    fun `华为与荣耀要提醒空间不够不会自动开新一页`() {
        for (key in listOf("huawei", "honor")) {
            val steps = WidgetPinner.manualSteps(key, "华为")
            assertTrue("$key 没提醒空间不足的后果", steps.any { it.contains("不会自动开新一页") })
        }
    }

    @Test
    fun `小米要给出桌面快捷方式这条后路`() {
        // 小米桌面把「能不能放小组件」挂在「桌面快捷方式」权限上，没开就整个列表都找不到
        val steps = WidgetPinner.manualSteps("xiaomi", "小米")
        assertTrue(steps.any { it.contains("桌面快捷方式") })
        assertTrue(steps.any { it.contains("小部件") })
    }

    @Test
    fun `不认识的厂商退到通用说明并且带上当前桌面的名字`() {
        val steps = WidgetPinner.manualSteps("other", "Android", "小米桌面")
        assertTrue(steps.any { it.contains("小米桌面") })
        // 各家叫法一次性给全，用户至少能在菜单里认出其中一个
        assertTrue(steps.any { it.contains("卡片") && it.contains("挂件") })
    }

    @Test
    fun `拿不到桌面名字时用当前桌面兜底而不是留空`() {
        val steps = WidgetPinner.manualSteps("other", "Android", "   ")
        assertTrue(steps.any { it.contains("当前桌面") })
        assertFalse(steps.any { it.contains("  ") }) // 不能拼出两个连续空格
    }

    @Test
    fun `文案里不能出现 Markdown 星号`() {
        // Compose 的 Text 不会把 **粗体** 渲染成粗体，只会原样显示星号 —— 之前真的发生过
        for (key in allVendorKeys) {
            for (step in WidgetPinner.manualSteps(key, "某厂商", "某桌面")) {
                assertFalse("$key 的文案里有星号：$step", step.contains("**"))
            }
        }
    }

    @Test
    fun `文案里不能留没替换掉的占位符`() {
        for (key in allVendorKeys) {
            for (step in WidgetPinner.manualSteps(key, "某厂商", "某桌面")) {
                assertFalse("$key 的文案里有裸露的美元号：$step", step.contains("$"))
                assertFalse("$key 的文案里有花括号：$step", step.contains("{") || step.contains("}"))
            }
        }
    }
}
