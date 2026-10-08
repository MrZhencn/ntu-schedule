package com.ntu.schedule.core

/**
 * 极简 JSON 解析/生成器 —— 故意不带任何外部依赖，也不使用 `org.json`。
 *
 * 两个理由：
 * 1. `org.json` 在 Android 上是**桩实现**（`android.jar` 里方法体抛异常），
 *    本地 JVM 单元测试跑到它必然失败；而课表解析正是最需要被测试钉住的一环。
 * 2. 课表接口返回的字段是动态的（同一个 `datas` 有时是对象有时是数组），
 *    用强类型库反而要写一堆兜底，不如直接操作通用树。
 *
 * 只实现本项目需要的能力：解析、取值、生成。不追求完整 JSON 规范覆盖
 * （输入来自教务系统与本地缓存，均为合法 UTF-8 JSON）。
 */
sealed class JsonValue {

    object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Num(val value: Double) : JsonValue() {
        /** 教务接口里节次/周次都是整数字符串，取整时避免 `5.0` 这种显示。 */
        fun toIntOrNull(): Int? = if (value.isFinite() && value == Math.floor(value)) value.toInt() else null
    }

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    // --- 便捷取值：全部做成「取值失败返回默认值」，避免脏数据崩掉整个导入 ---

    val asString: String?
        get() = when (this) {
            is Str -> value
            is Num -> if (value == Math.floor(value) && value.isFinite()) value.toLong().toString() else value.toString()
            is Bool -> value.toString()
            else -> null
        }

    val asInt: Int?
        get() = when (this) {
            is Num -> toIntOrNull()
            is Str -> value.trim().toIntOrNull()
            else -> null
        }

    val asLong: Long?
        get() = when (this) {
            is Num -> value.toLong()
            is Str -> value.trim().toLongOrNull()
            else -> null
        }

    val asBool: Boolean?
        get() = when (this) {
            is Bool -> value
            is Str -> when (value.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            is Num -> value != 0.0
            else -> null
        }

    val asArray: List<JsonValue>
        get() = (this as? Arr)?.items ?: emptyList()

    val asObject: Map<String, JsonValue>
        get() = (this as? Obj)?.fields ?: emptyMap()

    operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    /** 多级路径取值，任一环节缺失返回 null。 */
    fun path(vararg keys: String): JsonValue? {
        var cur: JsonValue? = this
        for (k in keys) {
            cur = cur?.get(k) ?: return null
        }
        return cur
    }

    /** 先按对象取值，取不到再按数组当第一个元素取值 —— 兼容 `datas` 的两种形态。 */
    fun firstOf(vararg keys: String): JsonValue? {
        for (k in keys) {
            when (val v = get(k)) {
                null -> continue
                is Arr -> if (v.items.isNotEmpty()) return v.items[0]
                is Obj -> return v
                else -> return v
            }
        }
        return null
    }

    /** 在多个候选字段名中取第一个非空字符串。 */
    fun strOf(vararg keys: String): String {
        for (k in keys) {
            val s = get(k)?.asString
            if (!s.isNullOrBlank()) return s
        }
        return ""
    }

    fun intOf(vararg keys: String, default: Int = 0): Int {
        for (k in keys) {
            val i = get(k)?.asInt
            if (i != null) return i
        }
        return default
    }

    /** 把任意层级的 `kbList` / `rows` / `datas` 全部摊平成课程记录数组。 */
    fun collectCourseArrays(): List<JsonValue> {
        val out = mutableListOf<JsonValue>()
        fun walk(node: JsonValue?) {
            when (node) {
                null -> return
                is Arr -> node.items.forEach { walk(it) }
                is Obj -> {
                    for (key in listOf("kbList", "rows", "datas", "data", "list")) {
                        val v = node.fields[key] ?: continue
                        when (v) {
                            is Arr -> out += v.items
                            is Obj -> out += v
                            else -> Unit
                        }
                    }
                    // 顶层本身就是一条课程记录（含 kcmc）时也算
                    if (node.fields.containsKey("kcmc") || node.fields.containsKey("courseName")) {
                        out += node
                    } else {
                        node.fields.values.forEach { walk(it) }
                    }
                }
                else -> Unit
            }
        }
        walk(this)
        return out
    }

    // --- 生成 ---

    fun toJsonString(): String {
        val sb = StringBuilder()
        write(sb)
        return sb.toString()
    }

    private fun write(sb: StringBuilder) {
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(if (value) "true" else "false")
            is Num -> {
                if (value == Math.floor(value) && value.isFinite() && Math.abs(value) < 1e15) {
                    sb.append(value.toLong())
                } else {
                    sb.append(value)
                }
            }
            is Str -> writeString(sb, value)
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    v.write(sb)
                }
                sb.append(']')
            }
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    v.write(sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    companion object {
        fun of(value: String?): JsonValue = if (value == null) Null else Str(value)
        fun of(value: Int): JsonValue = Num(value.toDouble())
        fun of(value: Long): JsonValue = Num(value.toDouble())
        fun of(value: Boolean): JsonValue = Bool(value)
        fun arr(items: List<JsonValue>): JsonValue = Arr(items)
        fun obj(fields: Map<String, JsonValue>): JsonValue = Obj(fields)
        fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = Obj(linkedMapOf(*pairs))

        fun parse(text: String?): JsonValue {
            if (text.isNullOrBlank()) return Null
            return try {
                Parser(text).parseValue() ?: Null
            } catch (_: Exception) {
                Null
            }
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): JsonValue? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): JsonValue {
            i++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return Obj(map) }
            while (i < s.length) {
                skipWs()
                if (i >= s.length) break
                if (s[i] == '}') { i++; break }
                if (s[i] == ',') { i++; continue }
                if (s[i] != '"') { i++; continue }
                val key = parseString()
                skipWs()
                if (i < s.length && s[i] == ':') i++
                val v = parseValue() ?: Null
                map[key] = v
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
            }
            return Obj(map)
        }

        private fun parseArray(): JsonValue {
            i++ // [
            val list = mutableListOf<JsonValue>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return Arr(list) }
            while (i < s.length) {
                skipWs()
                if (i >= s.length) break
                if (s[i] == ']') { i++; break }
                if (s[i] == ',') { i++; continue }
                list += parseValue() ?: Null
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
            }
            return Arr(list)
        }

        private fun parseString(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 < s.length) {
                                    val hex = s.substring(i + 1, i + 5)
                                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): JsonValue {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val text = s.substring(start, i)
            return Num(text.toDoubleOrNull() ?: 0.0)
        }

        private fun expect(literal: String) {
            if (s.startsWith(literal, i)) i += literal.length
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
