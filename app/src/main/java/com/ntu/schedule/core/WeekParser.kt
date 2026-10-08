package com.ntu.schedule.core

/**
 * 正方教务「周次」文本解析器。
 *
 * 教务接口对 `zcd` 字段的写法并不统一，实测与整理到的全部形态：
 * - `5-18周`、`1~16周`、`第1-16周`、`1-16`（无「周」字）
 * - `7周,15周`、`1,3,5,7周`
 * - `1-16周(单)`、`1-16周(双)`、`1-16单周`、`1-16双周`
 * - `3,5,7周` 这类离散列表
 * - 带地点标记符：`5-18周■`、`7周◆`、`9周▲` —— 符号表示「该周在另一地点上课」，
 *   对应正方课表里同名课程的分段记录。**必须保留**，否则用户会以为整学期都在同一栋楼。
 * - 空串或 `周` 之类无信息值 → 视为全周（1..totalWeeks），而不是丢弃该课。
 */
object WeekParser {

    /** ■◆▲★● 等标记符，在正方课表中表示该段周次的特殊地点安排。 */
    private const val MARKERS = "■◆▲★●◇□△☆"

    data class Result(
        /** 升序、去重后的周次列表。 */
        val weeks: List<Int>,
        val weekType: WeekType,
        /** 去掉周次与标记符后的剩余说明文本（通常是地点标记含义），可能为空。 */
        val note: String,
    )

    /**
     * @param text 原始周次文本，如 `5-18周`
     * @param totalWeeks 学期总周数，用于处理「全周」与越界裁剪
     */
    fun parse(text: String?, totalWeeks: Int = 20): Result {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return Result((1..totalWeeks).toList(), WeekType.ALL, "")

        // 标记符单独留在 note 里，其余字符进入周次解析
        val markers = raw.filter { it in MARKERS }
        val body = raw.filterNot { it in MARKERS }

        val weekType = when {
            body.contains("单") -> WeekType.ODD
            body.contains("双") -> WeekType.EVEN
            else -> WeekType.ALL
        }

        // 只保留数字、范围分隔符与逗号，其余字符一律剔除 —— 而不是逐个 replace 掉
        // 「第」「周」「(单)」。replace 链的问题是漏一个就退化成「全周」：
        // `1-16周(单)` 里括号留着会让 `"16(单)".toIntOrNull()` 返回 null，
        // 整条周次解析不出任何数字，于是课程被保守地当成每周都上（实测踩过）。
        // 单双周此时已记入 weekType，标记符已记入 markers，都不需要留在数字串里。
        // 空白也在这里一并丢掉：Kotlin 的 toIntOrNull 不接受 "1 " 这种带空格的串，
        // 而教务数据里确实出现过 `第 1 - 16 周` 这类写法。
        val normalized = body
            .map { c ->
                when {
                    c in '0'..'9' -> c
                    c in '０'..'９' -> '0' + (c - '０') // 全角数字
                    c == ',' || c == '，' || c == '、' || c == '；' || c == ';' -> ','
                    c in RANGE_CHARS -> '-'
                    else -> ' '
                }
            }
            .joinToString("")
            .replace(Regex("\\s"), "")

        val weeks = LinkedHashSet<Int>()
        for (token in normalized.split(",")) {
            val t = token.trim()
            if (t.isEmpty()) continue
            val range = RANGE.split(t)
            if (range.size >= 2) {
                val a = range[0].toIntOrNull()
                val b = range[1].toIntOrNull()
                if (a != null && b != null && a > 0 && b >= a) {
                    for (w in a..b) if (w <= totalWeeks + 4) weeks.add(w)
                }
            } else {
                t.toIntOrNull()?.let { if (it > 0) weeks.add(it) }
            }
        }

        if (weeks.isEmpty()) {
            // 文本没有任何可用数字（例如只有标记符）：保守地当作全周，避免课程凭空消失
            return Result((1..totalWeeks).toList(), weekType, markers)
        }

        val filtered = weeks.filter { it <= totalWeeks }.ifEmpty { weeks.toList() }
        return Result(filtered.sorted(), weekType, markers)
    }

    /** 正方用过的全部范围分隔符写法：`1-16`、`1~16`、`1—16`、`1－16`、`1至16`。 */
    private const val RANGE_CHARS = "-~—－至"

    private val RANGE = Regex("[-~—－至]")

    /**
     * 把一批「周次分段」压成人类可读文本。
     * 例：`[1-8 正常, 9-16 ■]` → `1-8周, 9-16周■`
     */
    fun format(segments: List<Pair<List<Int>, String>>): String {
        if (segments.isEmpty()) return ""
        if (segments.size == 1) return formatWeeks(segments[0].first) + segments[0].second
        return segments.joinToString("，") { formatWeeks(it.first) + it.second }
    }

    /** `[1,2,3,7,8]` → `1-3,7-8周`。 */
    fun formatWeeks(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.distinct().sorted()
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1..sorted.size) {
            val cur = sorted.getOrNull(i)
            if (cur != null && cur == prev + 1) {
                prev = cur
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            if (cur != null) {
                start = cur
                prev = cur
            }
        }
        return parts.joinToString(",") + "周"
    }

    /** 该课程在第 week 周是否上课（同时考虑单双周）。 */
    fun isActiveIn(weeks: List<Int>, weekType: WeekType, week: Int): Boolean {
        if (week !in weeks) return false
        return when (weekType) {
            WeekType.ALL -> true
            WeekType.ODD -> week % 2 == 1
            WeekType.EVEN -> week % 2 == 0
        }
    }
}

enum class WeekType { ALL, ODD, EVEN }
