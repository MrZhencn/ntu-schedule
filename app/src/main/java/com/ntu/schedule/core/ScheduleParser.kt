package com.ntu.schedule.core

/**
 * 把南通大学正方 V9 教务接口的原始响应，解析成 App 内部使用的 [Schedule]。
 *
 * 两份输入（均已在真机账号上实测拿到）：
 * 1. 课表：`POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151`，响应顶层含 `kbList`。
 * 2. 校历：`POST /jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N2154`，响应是数组，
 *    每项 `{ "zs": "1", "rq": "2026-08-31/2026-09-06", ... }`。
 *
 * 设计原则：**任何单个字段解析失败都不能让整次导入失败**。教务接口字段会随学校升级变化，
 * 宁可少显示一条信息，也不要让用户看到「导入失败」。
 */
object ScheduleParser {

    /** 课程名里会出现的周次/安排标记，显示时需要剔除。 */
    private val NAME_NOISE = Regex("[■◆▲★●◇□△☆\\s]+")

    /**
     * @param scheduleJson 课表接口响应原文
     * @param calendarJson 校历接口响应原文，可为空（此时周次日期按第一周推算）
     * @param studentId 学号，仅在接口未返回时兜底
     */
    fun parse(
        scheduleJson: String,
        calendarJson: String? = null,
        studentId: String = "",
        importedAt: Long = System.currentTimeMillis(),
    ): Schedule {
        val root = JsonValue.parse(scheduleJson)
        val meta = readMeta(root)

        val weeks = parseCalendar(calendarJson)
        val totalWeeks = if (weeks.isNotEmpty()) weeks.size else DEFAULT_TOTAL_WEEKS

        val courses = root.collectCourseArrays()
            .mapNotNull { toCourse(it, totalWeeks) }
            .distinct()
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.name }))

        return Schedule(
            academicYear = meta.academicYear,
            termCode = meta.termCode,
            termName = meta.termName,
            studentName = meta.studentName,
            studentId = meta.studentId.ifBlank { studentId },
            major = meta.major,
            className = meta.className,
            totalWeeks = totalWeeks,
            firstMondayIso = weeks.firstOrNull()?.mondayIso ?: "",
            weeks = weeks,
            courses = courses,
            importedAt = importedAt,
        )
    }

    /** 校历接口实测返回 19 周（2026-2027 学年第 1 学期：2026-08-31 起）。 */
    const val DEFAULT_TOTAL_WEEKS = 19

    private data class Meta(
        val academicYear: String = "",
        val termCode: String = "3",
        val termName: String = "",
        val studentName: String = "",
        val studentId: String = "",
        val major: String = "",
        val className: String = "",
    )

    private fun readMeta(root: JsonValue): Meta {
        // 学籍信息实测位于 `xsxx`，字段为全大写；不同版本可能放到别处，故多路兜底。
        val xsxx = root["xsxx"] ?: root.path("data", "xsxx") ?: root.firstOf("xsxx") ?: root
        val academicYear = xsxx.strOf("XNM", "xnm").ifBlank { root.strOf("xnm", "XNM") }
        val termCode = xsxx.strOf("XQM", "xqm").ifBlank { root.strOf("xqm", "XQM") }.ifBlank { "3" }
        // 实测 `XNMC` 只是 `"2026-2027"`，**不含「学年/学期」**，所以还要自己补学期名。
        // 只有当接口给出的是完整表述（含「学年」字样）时才直接采用。
        val rawName = xsxx.strOf("XNMC", "xnmc")
        val built = buildTermName(academicYear, termCode)
        val termName = when {
            rawName.contains("学年") -> rawName
            built.isNotEmpty() -> built
            else -> rawName
        }
        return Meta(
            academicYear = academicYear,
            termCode = termCode,
            termName = termName,
            studentName = xsxx.strOf("XM", "xm"),
            studentId = xsxx.strOf("XH", "XH_ID", "xh"),
            major = xsxx.strOf("ZYMC", "zymc"),
            className = xsxx.strOf("BJMC", "bjmc"),
        )
    }

    /** `2026` + `3` → `2026-2027 学年 第 1 学期`。 */
    fun buildTermName(academicYear: String, termCode: String): String {
        val y = academicYear.toIntOrNull() ?: return ""
        val termCn = when (termCode) {
            "3" -> "第 1 学期"
            "12" -> "第 2 学期"
            "16" -> "第 3 学期"
            else -> "第 $termCode 学期"
        }
        return "$y-${y + 1} 学年 $termCn"
    }

    /**
     * 解析校历。实测每项形如：
     * `{"zs":"1","rq":"2026-08-31/2026-09-06","zcrq":"1(2026-08-31至2026-09-06)","zcrq2":"第1周(...)"}`
     */
    fun parseCalendar(calendarJson: String?): List<CalendarWeek> {
        // 注意 JsonValue.parse 失败时返回的是 JsonValue.Null（而不是 null），所以不能用 ?: 兜底
        val root = JsonValue.parse(calendarJson)
        if (root is JsonValue.Null) return emptyList()
        val items = when (root) {
            is JsonValue.Arr -> root.items
            else -> root.collectCourseArrays().ifEmpty { listOfNotNull(root.path("rows")) }
        }
        if (items.isEmpty()) return emptyList()

        val out = mutableListOf<CalendarWeek>()
        for (item in items) {
            val week = item.intOf("zs", "zsmc", "week", default = 0)
            if (week <= 0) continue
            val monday = extractMonday(item.strOf("rq", "zcrq", "zcrq2"))
            if (monday.isEmpty()) continue
            out += CalendarWeek(week, monday)
        }
        return out.sortedBy { it.week }
    }

    /** 从 `2026-08-31/2026-09-06` 或 `1(2026-08-31至2026-09-06)` 里取出周一日期。 */
    private fun extractMonday(text: String): String {
        if (text.isBlank()) return ""
        val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(text) ?: return ""
        val iso = "%04d-%02d-%02d".format(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
        )
        // 取到的应当是周一；若接口给的是别的日子，归一到该周周一
        return DateUtil.mondayOf(iso) ?: iso
    }

    /** 单条课程记录 → [Course]；无用条目返回 null。 */
    private fun toCourse(node: JsonValue, totalWeeks: Int): Course? {
        val name = cleanName(node.strOf("kcmc", "courseName", "kcbmc", "kcmcView"))
        if (name.isEmpty()) return null

        val day = node.intOf("xqj", "dayOfWeek", "week", "xingqi", default = 0)
        if (day !in 1..7) return null

        val (start, end) = parsePeriods(node)
        if (start <= 0) return null

        val weekText = node.strOf("zcd", "weeks", "zc")
        val parsed = WeekParser.parse(weekText, totalWeeks)

        return Course(
            name = name,
            teacher = node.strOf("xm", "teacher", "jsxm").trim().trim(','),
            room = node.strOf("cdmc", "room", "jsmc").trim(),
            dayOfWeek = day,
            startPeriod = start,
            endPeriod = maxOf(start, end),
            weeks = parsed.weeks,
            weekType = parsed.weekType,
            teachingClass = node.strOf("jxbmc", "jxb"),
            courseCode = node.strOf("kch", "kch_id"),
            campus = node.strOf("xqmc", "campus"),
            weekMarker = parsed.note,
        )
    }

    /** 剔除课名里的 ■◆▲ 与多余空白，同时把全角括号统一，便于同课程归并显示。 */
    fun cleanName(raw: String): String =
        NAME_NOISE.replace(raw, "").trim()

    /**
     * 节次解析，按 `jcs` → `jcor` → `jc` → `periods` → `jcs2` 依次尝试。
     *
     * **必须优先用 `jcs`（起始节次）**：实测同一响应里
     * - `jcs` 是 `"4-5"`，表示「第 4-5 节」——这才是课程真正占用的节次；
     * - `jcor` 多数记录与 `jcs` 相同，但会给出更大的区间（如 `jcs="6-7"` 而 `jcor="2-7"`），
     *   拿它当节次会把课程画到错误的格子里；
     * - `jc` 是 `"6-7节"` 这种带后缀的展示串。
     * 后三者只作兜底，且见过 `"4"`、`"0405"`、`"4,5"` 等写法，故统一用正则取数字。
     */
    private fun parsePeriods(node: JsonValue): Pair<Int, Int> {
        for (key in listOf("jcs", "jcor", "jc", "periods", "jcs2")) {
            val raw = node[key]?.asString?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val nums = Regex("\\d+").findAll(raw).map { it.value.toInt() }.toList()
            if (nums.isEmpty()) continue
            val a = nums[0]
            val b = if (nums.size >= 2) nums[1] else a
            if (a in 1..20) return a to b.coerceAtMost(20)
        }
        // 只有起始节次时按「连上 2 节」估计，避免课程完全消失
        val only = node.intOf("startPeriod", default = 0)
        return if (only in 1..20) only to (only + 1).coerceAtMost(20) else 0 to 0
    }

    /** 把 [Schedule] 转成可持久化的 JSON。字段名与数据类一一对应，便于日后兼容旧缓存。 */
    fun toJson(schedule: Schedule): String {
        val courses = JsonValue.arr(schedule.courses.map { c ->
            JsonValue.obj(
                "name" to JsonValue.of(c.name),
                "teacher" to JsonValue.of(c.teacher),
                "room" to JsonValue.of(c.room),
                "dayOfWeek" to JsonValue.of(c.dayOfWeek),
                "startPeriod" to JsonValue.of(c.startPeriod),
                "endPeriod" to JsonValue.of(c.endPeriod),
                "weeks" to JsonValue.arr(c.weeks.map { JsonValue.of(it) }),
                "weekType" to JsonValue.of(c.weekType.name),
                "teachingClass" to JsonValue.of(c.teachingClass),
                "courseCode" to JsonValue.of(c.courseCode),
                "campus" to JsonValue.of(c.campus),
                "weekMarker" to JsonValue.of(c.weekMarker),
            )
        })
        val weeks = JsonValue.arr(schedule.weeks.map { w ->
            JsonValue.obj(
                "week" to JsonValue.of(w.week),
                "mondayIso" to JsonValue.of(w.mondayIso),
            )
        })
        return JsonValue.obj(
            "version" to JsonValue.of(SCHEMA_VERSION),
            "academicYear" to JsonValue.of(schedule.academicYear),
            "termCode" to JsonValue.of(schedule.termCode),
            "termName" to JsonValue.of(schedule.termName),
            "studentName" to JsonValue.of(schedule.studentName),
            "studentId" to JsonValue.of(schedule.studentId),
            "major" to JsonValue.of(schedule.major),
            "className" to JsonValue.of(schedule.className),
            "totalWeeks" to JsonValue.of(schedule.totalWeeks),
            "firstMondayIso" to JsonValue.of(schedule.firstMondayIso),
            "importedAt" to JsonValue.of(schedule.importedAt),
            "weeks" to weeks,
            "courses" to courses,
        ).toJsonString()
    }

    const val SCHEMA_VERSION = 1

    /** [toJson] 的逆操作。任何异常都返回 null，由调用方决定提示文案。 */
    fun fromJson(text: String?): Schedule? {
        val root = JsonValue.parse(text)
        if (root is JsonValue.Null) return null
        if (root["courses"] == null && root["totalWeeks"] == null) return null

        val courses = root["courses"]?.asArray.orEmpty().mapNotNull { node ->
            val name = node.strOf("name")
            if (name.isEmpty()) return@mapNotNull null
            val day = node.intOf("dayOfWeek", default = 0)
            val start = node.intOf("startPeriod", default = 0)
            if (day !in 1..7 || start <= 0) return@mapNotNull null
            Course(
                name = name,
                teacher = node.strOf("teacher"),
                room = node.strOf("room"),
                dayOfWeek = day,
                startPeriod = start,
                endPeriod = node.intOf("endPeriod", default = start),
                weeks = node["weeks"]?.asArray.orEmpty().mapNotNull { it.asInt },
                weekType = runCatching { WeekType.valueOf(node.strOf("weekType")) }
                    .getOrDefault(WeekType.ALL),
                teachingClass = node.strOf("teachingClass"),
                courseCode = node.strOf("courseCode"),
                campus = node.strOf("campus"),
                weekMarker = node.strOf("weekMarker"),
            )
        }
        val weeks = root["weeks"]?.asArray.orEmpty().mapNotNull { node ->
            val w = node.intOf("week", default = 0)
            val iso = node.strOf("mondayIso")
            if (w <= 0 || iso.isEmpty()) null else CalendarWeek(w, iso)
        }
        return Schedule(
            academicYear = root.strOf("academicYear"),
            termCode = root.strOf("termCode").ifBlank { "3" },
            termName = root.strOf("termName"),
            studentName = root.strOf("studentName"),
            studentId = root.strOf("studentId"),
            major = root.strOf("major"),
            className = root.strOf("className"),
            totalWeeks = root.intOf("totalWeeks", default = DEFAULT_TOTAL_WEEKS),
            firstMondayIso = root.strOf("firstMondayIso"),
            weeks = weeks,
            courses = courses,
            importedAt = root["importedAt"]?.asLong ?: 0L,
        )
    }
}
