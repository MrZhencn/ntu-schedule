package com.ntu.schedule.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厂商识别。
 *
 * 这个纯函数的唯一职责是决定「去设置」跳到哪个页面，而各家的 ComponentName 完全不同：
 * 认错了厂商，用户点一下只会得到「找不到页面」或者跳到别人的设置页，等于这条引导路径整个失效。
 * 所以每个分支都钉一个真实机型会报出来的 MANUFACTURER / BRAND 组合。
 */
class OemSettingsTest {

    private fun key(manufacturer: String, brand: String) =
        OemSettings.vendorOf(manufacturer, brand).key

    @Test
    fun `荣耀必须排在华为之前`() {
        // 荣耀机器的 Build.MANUFACTURER 是 HONOR，但品牌历史上出现过带 huawei 的写法；
        // 若把 huawei 分支写在前面，荣耀会被误判成华为，跳到不存在的 huawei 页面。
        assertEquals("honor", key("HONOR", "HONOR"))
        assertEquals("honor", key("Huawei", "HONOR"))
        assertEquals("honor", key("HONOR", "hihonor"))
    }

    @Test
    fun `华为`() {
        assertEquals("huawei", key("HUAWEI", "HUAWEI"))
        assertEquals("huawei", key("HUAWEI", "harmony"))
    }

    @Test
    fun `小米红米与POCO都算小米`() {
        assertEquals("xiaomi", key("Xiaomi", "Xiaomi"))
        assertEquals("xiaomi", key("Xiaomi", "Redmi"))
        assertEquals("xiaomi", key("Xiaomi", "POCO"))
    }

    @Test
    fun `OPPO一加与realme共用一套设置页`() {
        assertEquals("oppo", key("OPPO", "OPPO"))
        assertEquals("oppo", key("OnePlus", "OnePlus"))
        assertEquals("oppo", key("realme", "realme"))
    }

    @Test
    fun `vivo与iQOO`() {
        assertEquals("vivo", key("vivo", "vivo"))
        assertEquals("vivo", key("vivo", "iQOO"))
    }

    @Test
    fun `其余有专门设置页的厂商`() {
        assertEquals("meizu", key("Meizu", "meizu"))
        assertEquals("samsung", key("samsung", "samsung"))
        assertEquals("asus", key("asus", "ASUS"))
        assertEquals("nokia", key("HMD Global", "Nokia"))
        // 诺基亚/部分功能机的厂商名是 Evenwell，不是 Nokia。
        assertEquals("nokia", key("Evenwell", "Nokia"))
        assertEquals("letv", key("Letv", "Letv"))
        assertEquals("letv", key("LeEco", "LeEco"))
        assertEquals("smartisan", key("smartisan", "hammer"))
        assertEquals("transsion", key("TECNO", "TECNO"))
        assertEquals("transsion", key("Infinix", "Infinix"))
        assertEquals("sony", key("Sony", "Sony"))
        assertEquals("lge", key("LGE", "LG"))
    }

    @Test
    fun `大小写不影响识别`() {
        assertEquals(key("XIAOMI", "xiaomi"), key("Xiaomi", "Xiaomi"))
        assertEquals(key("oPpO", "OpPo"), key("OPPO", "OPPO"))
    }

    @Test
    fun `认不出来的厂商退到通用而不是误认成某一家`() {
        // 宁可不引导，也不能把用户送到一个不存在的页面上。
        assertEquals("other", key("Google", "Pixel"))
        assertEquals("other", key("", ""))
        assertEquals("other", key("unknown", "unknown"))
    }

    @Test
    fun `每一个厂商都有一个能显示给人看的名字`() {
        val samples = listOf(
            "HONOR" to "HONOR", "HUAWEI" to "HUAWEI", "Xiaomi" to "Redmi",
            "OPPO" to "OPPO", "vivo" to "iQOO", "Meizu" to "meizu",
            "samsung" to "samsung", "asus" to "ASUS", "Nokia" to "Nokia",
            "Letv" to "Letv", "smartisan" to "hammer", "TECNO" to "TECNO",
            "Sony" to "Sony", "LGE" to "LG", "Google" to "Pixel",
        )
        for ((manufacturer, brand) in samples) {
            val vendor = OemSettings.vendorOf(manufacturer, brand)
            assertTrue("$manufacturer/$brand 的 label 为空", vendor.label.isNotBlank())
        }
    }
}
