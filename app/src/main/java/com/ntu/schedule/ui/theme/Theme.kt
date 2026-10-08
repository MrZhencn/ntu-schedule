package com.ntu.schedule.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 南通大学视觉主色（校徽蓝）。 */
val NtuBlue = Color(0xFF1F5FA9)
val NtuBlueLight = Color(0xFF7FB0E8)
val NtuBlueContainer = Color(0xFFD6E4F5)
val NtuBlueOnContainer = Color(0xFF0A2440)
val NtuAmber = Color(0xFFE08A2B)

private val LightColors = lightColorScheme(
    primary = NtuBlue,
    onPrimary = Color.White,
    primaryContainer = NtuBlueContainer,
    onPrimaryContainer = NtuBlueOnContainer,
    secondary = NtuAmber,
    onSecondary = Color.White,
    // 默认背景就是纯白。`AppBackground` 的「默认」模式直接画这个 surface，
    // 所以改这里等于改全局默认底色。
    background = Color.White,
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF43474E),
)

private val DarkColors = darkColorScheme(
    primary = NtuBlueLight,
    onPrimary = Color(0xFF06213D),
    primaryContainer = Color(0xFF12406F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFF0B36A),
    onSecondary = Color(0xFF3D2A00),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
)

/**
 * 课表 App **固定浅色主题**（永远是白底），不跟随系统的深色模式。
 *
 * 为什么不让它跟随系统：默认背景是主题的 `surface`（浅色下就是纯白 `#FFFFFF`），
 * 而顶部状态栏/底部导航栏的图标颜色是开机时按系统深色模式定的 —— 如果 App 跟随系统变成
 * 深色、图标却仍是深色，就会糊在底色上看不见；反过来也一样。与其去同步这两套开关，
 * 不如把主题钉死（[MainActivity] 里同时用 `SystemBarStyle.light` 钉死系统栏图标）。
 *
 * [darkTheme] 参数与 [DarkColors] 保留着，哪天要做深色模式，改成
 * `darkTheme = isSystemInDarkTheme()` 再把 `SystemBarStyle` 换回 `auto` 即可。
 */
@Composable
fun NtuScheduleTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/**
 * 课程配色盘。索引来自 [com.ntu.schedule.core.Course.colorIndex]（由课程名稳定派生），
 * 所以同一门课在周课表、今日课表、小组件里永远同色。
 *
 * 刻意用中等明度、低饱和的颜色：这些色块上要压深色文字（`onCourseColor`），
 * 太浅则看不清、太深则和深色主题的格子背景融在一起。
 */
private val COURSE_COLORS = listOf(
    Color(0xFFBFD8F5), // 蓝
    Color(0xFFC7E5C9), // 绿
    Color(0xFFF7DCB0), // 橙
    Color(0xFFE8C7E4), // 紫
    Color(0xFFBFE3E0), // 青
    Color(0xFFF5C6C6), // 红
    Color(0xFFD6D3F0), // 靛
    Color(0xFFEAE0B8), // 黄
    Color(0xFFC9E2C1), // 草绿
    Color(0xFFF0CFC0), // 珊瑚
    Color(0xFFC2D9E8), // 灰蓝
    Color(0xFFE3D3C4), // 棕
)

/** 取课程色块颜色；索引越界时回退到第一个，绝不崩。 */
fun courseColor(index: Int): Color = COURSE_COLORS[((index % COURSE_COLORS.size) + COURSE_COLORS.size) % COURSE_COLORS.size]

/** 色块上的文字色。上面那盘颜色明度都够高，深色字才清楚。 */
val OnCourseColor = Color(0xFF16233A)
