package com.ntu.schedule.core

/**
 * 周课表里「一个格子」的划分结果。
 *
 * [startPeriod]..[endPeriod] 是这条格子占用的节次区间。**连着的课必须落在同一个格子里**
 * （例如周一 4-5 节的高等数学就是一条 span=2 的格子，而不是上下两个各自带边框的小格），
 * 这样一眼就能看出「这是一节课上两节」，而且格子之间不会出现割裂的横线。
 */
data class CourseBlock(
    val startPeriod: Int,
    val endPeriod: Int,
    /** 占用这个格子的课程。正常情况恰好 1 门；同一格多门时并排显示（见 [CellContent]）。 */
    val courses: List<Course>,
) {
    /** 跨几个节次，用于算格子高度。 */
    val span: Int get() = (endPeriod - startPeriod + 1).coerceAtLeast(1)

    val isEmpty: Boolean get() = courses.isEmpty()

    val primary: Course? get() = courses.firstOrNull()
}

/**
 * 把一天的课程排成互不重叠、覆盖满 `firstPeriod..lastPeriod` 的格子序列。
 *
 * 为什么需要它：教务返回的是「一条记录 = 一段连续节次」，而课表要画成网格。
 * 如果直接按「每个节次一行」渲染，4-5 节的一门课会变成上下两个带边框的小格子，
 * 看起来像两门不同的课，中间还多出一条缝 —— 这正是用户反馈要修的问题。
 *
 * 算法（对真实数据是精确的，对重叠数据是尽力而为的）：
 * 1. 按 `startPeriod` 分组，从 `firstPeriod` 扫到 `lastPeriod`；
 * 2. 某个节次有课从这里开始 → 格子向右（向下）延伸到这组课里最大的 `endPeriod`，
 *    中间被覆盖的节次标记为「已占用」，不再单独出格子；
 * 3. 某个节次没有任何课从这里开始 → 出一个空格子（保证网格完整、列高一致）；
 * 4. 收尾：若有课因为起始节次被上面的格子占用而没排进去（同一天节次区间真重叠，
 *    教务数据里不该出现），把它塞进与之重叠的那个格子，并排显示而不是丢弃。
 *
 * 所有列的格子高度之和恒等于 `(lastPeriod - firstPeriod + 1) * 单位高度`，
 * 因此不同天的行天然对齐 —— 这正是「合并格子」能成立的前提。
 */
object CourseBlocks {

    /** 一学期里实际用到的节次范围。跨周保持稳定，避免每周网格行数跳来跳去。 */
    fun periodsInUse(schedule: Schedule): IntRange {
        val courses = schedule.courses
        if (courses.isEmpty()) return 1..1
        val first = courses.minOf { it.startPeriod }.coerceIn(1, MAX_PERIOD)
        val last = courses.maxOf { it.endPeriod }.coerceIn(first, MAX_PERIOD)
        return first..last
    }

    /**
     * 划出某一天的格子序列，结果连续覆盖 `firstPeriod..lastPeriod`，无空洞、无重叠。
     *
     * @param courses 已确认「这一周这一天要上」的课；调用方负责先按周次/星期过滤。
     */
    fun ofDay(courses: List<Course>, firstPeriod: Int, lastPeriod: Int): List<CourseBlock> {
        val from = firstPeriod.coerceAtLeast(1)
        val to = lastPeriod.coerceAtLeast(from)
        if (courses.isEmpty()) return (from..to).map { CourseBlock(it, it, emptyList()) }

        val byStart = courses.filter { it.startPeriod in from..to }
            .groupBy { it.startPeriod }
        val placed = HashSet<Int>()
        val emitted = HashSet<Course>()
        val out = ArrayList<CourseBlock>(to - from + 1)

        var p = from
        while (p <= to) {
            if (placed.contains(p)) {
                p++
                continue
            }
            val group = byStart[p]
            if (group.isNullOrEmpty()) {
                out += CourseBlock(p, p, emptyList())
                placed += p
                p++
            } else {
                val end = group.maxOf { it.endPeriod }.coerceIn(p, to)
                for (k in p..end) placed += k
                emitted += group
                out += CourseBlock(p, end, group)
                p = end + 1
            }
        }

        // 收尾：真出现同一天节次重叠时，别把课弄丢了
        val leftovers = courses.filter { !emitted.contains(it) && it.startPeriod in from..to }
        if (leftovers.isEmpty()) return out
        return out.map { block ->
            val extra = leftovers.filter { it.startPeriod in block.startPeriod..block.endPeriod }
            if (extra.isEmpty()) block else block.copy(courses = block.courses + extra)
        }
    }

    const val MAX_PERIOD = 12
}
