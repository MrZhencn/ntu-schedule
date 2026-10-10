package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自己加的课要存得下、读得回，还要和教务导入的课分得开。
 *
 * 这一组盯的是三件容易做错、而且错了不容易发现的事：
 *
 * 1. **写出去再读回来，字段一个都不能少。** 少一个就是「改完变样」——
 *    用户明明只改了地点，周次却丢了。
 * 2. **`customCoursesFromJson` 只认带 `customId` 的条目。** 自己加的课存的是
 *    **整份课表格式的课程数组**，如果不按 `customId` 过滤，任何一份教务课表
 *    都能被当成「自定义课」读进来 —— 结果是重新导入之后每门课都变成两条。
 * 3. **`weeks` 不能留空表示「全部」。** [WeekParser.isActiveIn] 对空列表直接返回
 *    false，所以「整个学期」必须老老实实写成具体的 `1..N`，否则这门课一辈子不出现，
 *    而界面上看起来一切正常。
 */
class CustomCourseTest {

    private fun course(
        name: String = "临时调课",
        weeks: List<Int> = (1..19).toList(),
        customId: String = "c-1",
    ) = Course(
        name = name,
        teacher = "王老师",
        room = "JX03-201",
        dayOfWeek = 3,
        startPeriod = 6,
        endPeriod = 7,
        weeks = weeks,
        customId = customId,
    )

    @Test
    fun `自己加的课能原样存回来`() {
        val one = course()
        val encoded = ScheduleParser.customCoursesToJson(listOf(one))
        val back = ScheduleParser.customCoursesFromJson(encoded)
        assertEquals(1, back.size)
        assertEquals(one, back[0])
    }

    @Test
    fun `读回来的课仍然算自己加的`() {
        val back = ScheduleParser.customCoursesFromJson(
            ScheduleParser.customCoursesToJson(listOf(course())),
        )
        assertTrue(back[0].isCustom)
    }

    @Test
    fun `存档里的键名是固定的`() {
        // 磁盘格式不是内部实现细节：用户升级 App 之后旧文件还得读得回来，
        // 所以键名一旦定下就要在这里钉住。
        val encoded = ScheduleParser.customCoursesToJson(listOf(course()))
        for (key in listOf(
            "name", "teacher", "room", "dayOfWeek", "startPeriod", "endPeriod",
            "weeks", "weekType", "customId",
        )) {
            assertTrue("自定义课存档里应该有 $key 这个键：$encoded", encoded.contains("\"$key\""))
        }
    }

    @Test
    fun `没带customId的课不算自己加的`() {
        // 导入的课 customId 是空串。这里不过滤的话，重新导入会把整份教务课表
        // 当成自定义课再叠加一次 —— 课表里每门课都变成两条。
        val imported = course(name = "高等数学", customId = "")
        val encoded = ScheduleParser.customCoursesToJson(listOf(imported))
        assertTrue(ScheduleParser.customCoursesFromJson(encoded).isEmpty())
    }

    @Test
    fun `空的和不存在的存档都返回空列表`() {
        assertTrue(ScheduleParser.customCoursesFromJson(null).isEmpty())
        assertTrue(ScheduleParser.customCoursesFromJson("").isEmpty())
        assertTrue(ScheduleParser.customCoursesFromJson("{}").isEmpty())
        assertTrue(ScheduleParser.customCoursesFromJson("这不是 JSON").isEmpty())
    }

    @Test
    fun `脏数据只丢那一条不废掉整个文件`() {
        // 直接按磁盘格式写（不经过编码器）：这样连键名一起钉住了，
        // 以后谁改了 toJson 的键名，这里会先炸。
        val text = """
            {"version":1,"courses":[
              {"name":"好的","dayOfWeek":3,"startPeriod":6,"endPeriod":7,"weeks":[1,5],"customId":"c-1"},
              {"name":"坏的","dayOfWeek":99,"startPeriod":6,"endPeriod":7,"weeks":[1],"customId":"c-2"},
              {"name":"","dayOfWeek":1,"startPeriod":1,"endPeriod":1,"weeks":[1],"customId":"c-3"},
              {"name":"没 id","dayOfWeek":2,"startPeriod":1,"endPeriod":2,"weeks":[1]}
            ]}
        """.trimIndent()
        val back = ScheduleParser.customCoursesFromJson(text)
        assertEquals(1, back.size)
        assertEquals("好的", back[0].name)
        assertEquals(listOf(1, 5), back[0].weeks)
    }

    @Test
    fun `整份课表存档能带上自己加的课`() {
        val stored = Schedule(
            courses = listOf(course(name = "高等数学", customId = ""), course()),
        )
        val back = ScheduleParser.fromJson(ScheduleParser.toJson(stored))
        assertEquals(2, back?.courses?.size)
        assertEquals(1, back?.courses?.count { it.isCustom })
        // 导入的那门课读回来必须还是「不是自己加的」，否则下次保存会被当成自定义课剥掉
        assertEquals("高等数学", back?.courses?.first { !it.isCustom }?.name)
    }

    @Test
    fun `空weeks的课查不到任何一周`() {
        // 这就是 CustomCourseDialog 里「整个学期」必须写成 1..N 而不是留空的原因
        val noWeeks = course(weeks = emptyList())
        assertFalse(noWeeks.activeIn(1))
        assertFalse(noWeeks.activeIn(10))
        assertTrue(course().activeIn(1))
        assertTrue(course().activeIn(19))
        assertFalse(course().activeIn(20))
    }

    @Test
    fun `只加几周的课只在选中的周出现`() {
        val odd = course(weeks = listOf(3, 5, 7))
        assertFalse(odd.activeIn(4))
        assertTrue(odd.activeIn(5))
        assertFalse(odd.activeIn(8))
    }

    @Test
    fun `同名课的颜色是稳定的且自己加的课也参与`() {
        // 自己加的课如果拿不到颜色，格子上会是一块没有底色的文字
        val a = course(name = "临时调课")
        val b = course(name = "临时调课", customId = "c-9")
        assertEquals(a.colorIndex, b.colorIndex)
        assertTrue(a.colorIndex in 0..11)
    }
}
