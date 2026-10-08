package com.ntu.schedule.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntu.schedule.core.ClassTimes
import com.ntu.schedule.core.Course
import com.ntu.schedule.core.CourseBlock
import com.ntu.schedule.core.CourseBlocks
import com.ntu.schedule.core.DateUtil
import com.ntu.schedule.core.Schedule
import com.ntu.schedule.ui.theme.OnCourseColor
import com.ntu.schedule.ui.theme.courseColor
import kotlinx.coroutines.launch

private val TIME_COL_WIDTH = 40.dp
private val UNIT_HEIGHT = 54.dp
private val DAY_HEADER_HEIGHT = 40.dp
private val GRID_STROKE = 0.5.dp

/**
 * 课程格子的圆角与四周留白。
 *
 * 留白不是为了好看，是**必须**的：圆角只有离开网格线才看得出来。贴着格子边缘画圆角，
 * 圆的四角会正好压在网格线上 —— 竖线被色块吃掉半条（0.5dp 的线只剩 0.25dp），
 * 横线也一样，整张网格会看起来粗细不匀。
 *
 * 内缩之后，缝里露出来的是页面底色和整页统一画的 0.5dp 网格线，
 * 视觉上就变成「浮在网格上的一叠圆角卡片」。
 *
 * 取值是按列宽定的：一天大约 46dp 宽，左右各让 1.5dp 还剩 43dp，
 * 8dp 圆角在这张卡片上已经很清楚，又不会圆到像药丸。
 */
private val COURSE_CORNER = 8.dp
private val COURSE_INSET_H = 1.5.dp
private val COURSE_INSET_V = 1.dp

/**
 * 周课表：**左右翻页**，一页一周，从第 1 周划到第 19 周；每周内部纵向滚动。
 *
 * 为什么改成翻页（原来是一个纵向 `LazyColumn`、每周一项）：
 * 1. 纵向列表里「下周」要滑很久，「第 3 周」和「第 12 周」之间没有语义边界；
 *    翻页有明确的「一周一屏」，配上面的周次跳转正好。
 * 2. 流畅度：`LazyColumn` 一次要量 19 项；`HorizontalPager` 只组合当前页和相邻页
 *    （`beyondViewportPageCount = 1`），划动时没有整列表的重新布局。
 * 3. 纵向滚动位置由**所有页共享**一个 [ScrollState]：从第 6 周划到第 7 周时，
 *    视线停在第 6-7 节那一带，第 7 周也停在那里，不会跳回最上面。
 *
 * 格子用 [CourseBlocks] 划分：连着的课（4-5 节）是**一个**格子，不是两个带缝的小格。
 * 点格子弹出 [CourseDetailDialog]。
 */
@Composable
fun WeekScreen(
    schedule: Schedule,
    contentPadding: PaddingValues,
) {
    val totalWeeks = schedule.totalWeeks.coerceAtLeast(1)
    val todayIso = DateUtil.todayIso()
    val currentWeek = schedule.weekOfDate(todayIso)
    val range = remember(schedule) { CourseBlocks.periodsInUse(schedule) }
    val firstPeriod = range.first
    val lastPeriod = range.last

    val initialPage = ((currentWeek ?: 1) - 1).coerceIn(0, totalWeeks - 1)
    val pagerState = rememberPagerState(initialPage = initialPage) { totalWeeks }
    // 所有页共用一份纵向滚动位置，翻周时不会跳回顶部。
    val verticalScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var showJump by remember { mutableStateOf(false) }
    var jumpFrom by remember { mutableIntStateOf(1) }
    var detail by remember { mutableStateOf<Course?>(null) }
    var detailWeek by remember { mutableIntStateOf(1) }

    // 刻意**不**在这个作用域里读 `pagerState.currentPage`：一读，翻页时整个 WeekScreen
    // （含上百个格子）就会跟着重组，这是左右划动卡顿的主因。谁需要页号谁自己读 ——
    // 工具栏在自己的组合里读（只重组工具栏），点按钮时读（不在组合里，不订阅）。
    //
    // 回调也必须 `remember` 成稳定实例：否则每次重组都产生新 lambda，
    // 会把无效重组沿 WeekPage → BlockCell → CourseCellContent 一路传到每一个格子。
    val onCourseClick: (Course, Int) -> Unit = remember {
        { course, week ->
            detail = course
            detailWeek = week
        }
    }

    fun goToWeek(target: Int) {
        val page = (target - 1).coerceIn(0, totalWeeks - 1)
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        WeekToolbar(
            pagerState = pagerState,
            schedule = schedule,
            totalWeeks = totalWeeks,
            currentWeek = currentWeek,
            onJump = {
                jumpFrom = (pagerState.currentPage + 1).coerceIn(1, totalWeeks)
                showJump = true
            },
            onBackToCurrent = { currentWeek?.let { goToWeek(it) } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // 不设 beyondViewportPageCount：默认只组合「看得见的页 + 正在拖进来的那一页」。
            // 之前设成 1，三页同时存在，每页上百个节点，划动时白白多量一页。
            key = { it },
        ) { index ->
            WeekPage(
                schedule = schedule,
                week = index + 1,
                firstPeriod = firstPeriod,
                lastPeriod = lastPeriod,
                todayIso = todayIso,
                verticalScroll = verticalScroll,
                onCourseClick = onCourseClick,
            )
        }
    }

    if (showJump) {
        WeekJumpDialog(
            totalWeeks = totalWeeks,
            currentWeek = currentWeek,
            visibleWeek = jumpFrom,
            schedule = schedule,
            onDismiss = { showJump = false },
            onPick = { w ->
                showJump = false
                goToWeek(w)
            },
        )
    }

    // 月份取「被点的那一格所属那一周」的月份，不能用今天：翻到 10 月之后的周次时，
    // 第 6-12 节是冬令时间，用今天的月份会差 30 分钟。
    detail?.let { course ->
        val month = schedule.mondayOfWeek(detailWeek)?.let { DateUtil.monthOf(it) }
            ?: DateUtil.monthOf(todayIso)
        CourseDetailDialog(course = course, month = month, onDismiss = { detail = null })
    }
}

@Composable
private fun WeekToolbar(
    pagerState: PagerState,
    schedule: Schedule,
    totalWeeks: Int,
    currentWeek: Int?,
    onJump: () -> Unit,
    onBackToCurrent: () -> Unit,
) {
    // 页号只在这里读：翻页时重组范围被限制在工具栏，pager 的页面内容不动。
    val visibleWeek = (pagerState.currentPage + 1).coerceIn(1, totalWeeks)
    val visibleMonday = remember(schedule, visibleWeek) { schedule.mondayOfWeek(visibleWeek) }
    val isCurrent = currentWeek != null && visibleWeek == currentWeek
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        TextButton(onClick = onJump, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("第 $visibleWeek 周", fontWeight = FontWeight.Medium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择周次")
        }
        Text(
            weekRangeText(visibleMonday),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    "本周",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "共 $totalWeeks 周",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (currentWeek != null && !isCurrent) {
            TextButton(onClick = onBackToCurrent, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Filled.Today, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("本周")
            }
        }
    }
}

@Composable
private fun WeekJumpDialog(
    totalWeeks: Int,
    currentWeek: Int?,
    visibleWeek: Int,
    schedule: Schedule,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳到第几周") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.height(300.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(totalWeeks) { index ->
                    val w = index + 1
                    val selected = w == visibleWeek
                    val isCurrent = w == currentWeek
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            selected -> MaterialTheme.colorScheme.primary
                            isCurrent -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .clickable { onPick(w) },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(
                                "第 $w 周",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            val monday = schedule.mondayOfWeek(w)
                            if (monday != null) {
                                Text(
                                    DateUtil.monthDayText(monday),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 一周 = 一页。
 *
 * 左右划动要跟手，所以这一页刻意压低了「节点数」和「绘制节点数」：
 * 1. **整个网格只用一条 `drawBehind` 画线**。原来是每个格子自己画顶边和左边
 *    （一页 104 个 `drawBehind` + 96 个 `background`），拖动时每帧要重跑两百个绘制节点。
 *    网格是规则的，横线就在 `表头高 + n × 单位高` 处，竖线按 7 等分算就行。
 *    这些线画在父节点上，**在课程色块下面**；色块四周留了白（见 [COURSE_CORNER]），
 *    线就从缝里透出来 —— 圆角卡片浮在网格上，靠的正是这个层次关系。
 * 2. **连续的空节次合并成一段**（见 [mergeEmptyRuns]）：7 天里通常只有两三段有课，
 *    一页的格子节点从 84 个降到 20 多个。
 * 3. 底色只铺一次（整页一个），不再每个格子叠一层。
 * 4. [CourseBlocks.ofDay] 的结果按 `(本周课程, 首节, 末节)` 缓存，划回来不重算。
 */
@Composable
private fun WeekPage(
    schedule: Schedule,
    week: Int,
    firstPeriod: Int,
    lastPeriod: Int,
    todayIso: String,
    verticalScroll: ScrollState,
    onCourseClick: (Course, Int) -> Unit,
) {
    val mondayIso = remember(schedule, week) { schedule.mondayOfWeek(week) }
    val month = mondayIso?.let { DateUtil.monthOf(it) } ?: DateUtil.monthOf(todayIso)
    val weekCourses = remember(schedule, week) { schedule.coursesOfWeek(week) }

    val daySlots = remember(weekCourses, firstPeriod, lastPeriod) {
        (1..7).map { day ->
            mergeEmptyRuns(
                CourseBlocks.ofDay(
                    weekCourses.filter { it.dayOfWeek == day },
                    firstPeriod,
                    lastPeriod,
                ),
            )
        }
    }
    val hasAnyCourse = weekCourses.isNotEmpty()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val cellBackground = panelColor()
    val rowCount = lastPeriod - firstPeriod + 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cellBackground)
            .verticalScroll(verticalScroll),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // DrawScope 本身就是 Density，DP 常量在这里可以直接换成像素。
                    val stroke = GRID_STROKE.toPx()
                    val headerPx = DAY_HEADER_HEIGHT.toPx()
                    val unitPx = UNIT_HEIGHT.toPx()
                    val timeColPx = TIME_COL_WIDTH.toPx()
                    val dayWidth = (size.width - timeColPx) / 7f
                    val gridBottom = headerPx + rowCount * unitPx

                    // 横线：表头顶边 + 每一节的顶边（最下面一条由下面的 Spacer 收口）
                    for (i in 0..rowCount) {
                        val y = headerPx + i * unitPx
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), stroke)
                    }
                    // 竖线：时间轴左沿从表头下面起（表头左侧原本就没有线），7 天的分界从顶起
                    drawLine(gridColor, Offset(0f, headerPx), Offset(0f, gridBottom), stroke)
                    for (i in 0..6) {
                        val x = timeColPx + i * dayWidth
                        drawLine(gridColor, Offset(x, 0f), Offset(x, gridBottom), stroke)
                    }
                },
        ) {
            DayHeaderRow(mondayIso)

            Row(modifier = Modifier.fillMaxWidth()) {
                TimeColumn(firstPeriod, lastPeriod, month)
                for (day in 1..7) {
                    DayColumn(
                        slots = daySlots[day - 1],
                        month = month,
                        week = week,
                        onCourseClick = onCourseClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 网格收口：横线只画每行的顶边，最后一行没有下线，这里补一条，
            // 高度与颜色严格和网格线一致，避免看起来「少了一边」。
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(GRID_STROKE)
                    .background(gridColor),
            )

            if (!hasAnyCourse) {
                Text(
                    "这一周没有课",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TIME_COL_WIDTH + 12.dp, top = 12.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 一页里某一天的纵向排布：[Empty] 是一段没课的节次（可能连着好几节），
 * [Filled] 是一个（可能跨多节的）课程格子。
 *
 * 为什么要合并空格子：一页 7 天 × 最多 12 节 = 84 个格子，但其中绝大多数是空的。
 * 合成「段」之后，一页的节点数从 84 降到 20 多。
 */
private sealed class DaySlot(val span: Int) {
    class Empty(span: Int) : DaySlot(span)
    class Filled(val block: CourseBlock) : DaySlot(block.span)
}

private fun mergeEmptyRuns(blocks: List<CourseBlock>): List<DaySlot> {
    val slots = ArrayList<DaySlot>(4)
    var emptyRun = 0
    for (block in blocks) {
        if (block.isEmpty) {
            emptyRun += block.span
        } else {
            if (emptyRun > 0) {
                slots += DaySlot.Empty(emptyRun)
                emptyRun = 0
            }
            slots += DaySlot.Filled(block)
        }
    }
    if (emptyRun > 0) slots += DaySlot.Empty(emptyRun)
    return slots
}

@Composable
private fun DayHeaderRow(mondayIso: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DAY_HEADER_HEIGHT),
    ) {
        // 时间轴上方是留白：网格线由整个网格统一画，这里不再自己画边。
        Spacer(Modifier.width(TIME_COL_WIDTH).height(DAY_HEADER_HEIGHT))
        for (day in 1..7) {
            val dateIso = mondayIso?.let { DateUtil.plusDays(it, day - 1) }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(DAY_HEADER_HEIGHT),
            ) {
                Text(
                    DateUtil.weekdayCn(day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (dateIso != null) {
                    Text(
                        // 列宽只有 ~46dp，用「10/8」而不是「10月8日」，否则会被挤到换行
                        "${DateUtil.monthOf(dateIso)}/${dateIso.substringAfterLast('-')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 左侧时间轴：**按节次逐行**，每行只占一个单位高度，因此和右侧合并格子的边界天然对齐。 */
@Composable
private fun TimeColumn(
    firstPeriod: Int,
    lastPeriod: Int,
    month: Int,
) {
    Column(modifier = Modifier.width(TIME_COL_WIDTH)) {
        for (p in firstPeriod..lastPeriod) {
            val start = ClassTimes.slotOf(p, month)?.start.orEmpty()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UNIT_HEIGHT),
            ) {
                Text(
                    "第$p",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (start.isNotEmpty()) {
                    Text(
                        start,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 一天。**布局上格子仍然紧密堆叠、不设间距**，网格线由整页统一画（见 [WeekPage]）——
 * 连堂课是一整个格子，中间不会有横线。
 *
 * 相邻格子之间那条 0.5dp 的线是**画在底下的**，课程色块盖在上面；色块自己向内缩了
 * [COURSE_INSET_V]（见 [CourseCellContent]），所以线会从缝里完整露出来。
 * 空格子是完全透明的 [Spacer]，不产生任何绘制节点。
 */
@Composable
private fun DayColumn(
    slots: List<DaySlot>,
    month: Int,
    week: Int,
    onCourseClick: (Course, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        for (slot in slots) {
            when (slot) {
                // 一段空节次就是一个透明的占位，不产生任何绘制节点。
                is DaySlot.Empty -> Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(UNIT_HEIGHT * slot.span),
                )

                is DaySlot.Filled -> BlockCell(
                    block = slot.block,
                    month = month,
                    week = week,
                    onCourseClick = onCourseClick,
                    height = UNIT_HEIGHT * slot.span,
                )
            }
        }
    }
}

@Composable
private fun BlockCell(
    block: CourseBlock,
    month: Int,
    week: Int,
    onCourseClick: (Course, Int) -> Unit,
    height: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        block.courses.forEach { course ->
            CourseCellContent(
                course = course,
                span = block.span,
                month = month,
                onClick = { onCourseClick(course, week) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun CourseCellContent(
    course: Course,
    span: Int,
    month: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startText = ClassTimes.slotOf(course.startPeriod, month)?.start.orEmpty()
    val endText = ClassTimes.slotOf(course.endPeriod, month)?.end.orEmpty()
    Column(
        modifier = modifier
            // 先内缩，再画圆角色块（见 COURSE_CORNER 的说明）。
            .padding(horizontal = COURSE_INSET_H, vertical = COURSE_INSET_V)
            // 顺序要紧：`clip` 必须在 `background` 和 `clickable` **之前**。
            // 色块本身用 RoundedCornerShape 也能画出圆角，但那样只圆了背景，
            // 点下去的水波纹仍是方形的 —— 会从四个角溢出色块外面。
            .clip(RoundedCornerShape(COURSE_CORNER))
            // 周课表里唯一保留的「突出」：每门课按课程名稳定分配一种底色，
            // 同一门课每周、每次打开都是同一个颜色，方便一眼扫到。
            .background(courseColor(course.colorIndex))
            // 点格子看详情。`clickable` 放在 background 之后，水波纹才会画在色块上面
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        Text(
            course.name,
            fontSize = if (span >= 3) 11.sp else 10.sp,
            lineHeight = if (span >= 3) 13.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            color = OnCourseColor,
            maxLines = when {
                span >= 4 -> 4
                span == 3 -> 3
                span == 2 -> 3
                else -> 2
            },
            overflow = TextOverflow.Ellipsis,
        )
        if (span >= 2 && startText.isNotEmpty() && endText.isNotEmpty()) {
            Text(
                "$startText-$endText",
                fontSize = 8.sp,
                color = OnCourseColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (course.room.isNotBlank()) {
            Text(
                course.room,
                fontSize = 9.sp,
                color = OnCourseColor.copy(alpha = 0.86f),
                maxLines = if (span >= 3) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (span >= 3 && course.teacher.isNotBlank()) {
            Text(
                course.teacher,
                fontSize = 8.sp,
                color = OnCourseColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun weekRangeText(mondayIso: String?): String {
    if (mondayIso == null) return ""
    val sunday = DateUtil.plusDays(mondayIso, 6) ?: mondayIso
    val start = DateUtil.monthDayText(mondayIso)
    val end = DateUtil.monthDayText(sunday)
    return if (start == end) start else "$start - $end"
}
