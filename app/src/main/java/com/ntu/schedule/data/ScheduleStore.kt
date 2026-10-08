package com.ntu.schedule.data

import android.content.Context
import com.ntu.schedule.core.Schedule
import com.ntu.schedule.core.ScheduleParser
import java.io.File

/**
 * 课表与登录信息的本地存储。
 *
 * 设计取舍：**直接写文件，不用 Room / DataStore**。这份数据只有一份、结构简单、
 * 全部读写都在主进程，引入注解处理器（KSP）只会给构建增加风险。
 *
 * 目录：`<app files>/` 下
 * - `schedule.json`  课表本体（schema 由 [ScheduleParser.SCHEMA_VERSION] 管）
 * - `session.json`   教务会话 cookie + 学号，用于「刷新」而不必重新输密码
 * - `account.json`   上次使用的学号，用来预填登录框
 * - `credentials.json` 用户勾选「记住密码」时才存在；密码字段是 [SecretStore] 用
 *   Keystore 密钥加密后的密文，**明文密码任何时候都不落盘**
 */
class ScheduleStore(private val context: Context) {

    private val dir: File get() = context.filesDir

    private val scheduleFile: File get() = File(dir, "schedule.json")
    private val sessionFile: File get() = File(dir, "session.json")
    private val accountFile: File get() = File(dir, "account.json")
    private val credentialsFile: File get() = File(dir, "credentials.json")

    // ---------------------------------------------------------------- 课表

    fun loadSchedule(): Schedule? {
        val f = scheduleFile
        if (!f.exists()) return null
        return runCatching { ScheduleParser.fromJson(f.readText()) }.getOrNull()
    }

    fun saveSchedule(schedule: Schedule) {
        writeAtomically(scheduleFile, ScheduleParser.toJson(schedule))
    }

    fun hasSchedule(): Boolean = scheduleFile.exists()

    // -------------------------------------------------------------- 会话票据

    /** 记住教务会话，使「刷新」不必再登录（登录有 5 次错误锁定的风险，能少一次就少一次）。 */
    fun saveSession(studentId: String, cookieHeader: String, savedAt: Long) {
        val json = "{\"studentId\":${quote(studentId)},\"cookie\":${quote(cookieHeader)},\"savedAt\":$savedAt}"
        writeAtomically(sessionFile, json)
    }

    fun loadSession(): Session? {
        val f = sessionFile
        if (!f.exists()) return null
        return runCatching {
            val text = f.readText()
            val id = field(text, "studentId") ?: return null
            val cookie = field(text, "cookie") ?: return null
            val at = field(text, "savedAt")?.toLongOrNull() ?: 0L
            Session(id, cookie, at)
        }.getOrNull()
    }

    fun clearSession() {
        runCatching { sessionFile.delete() }
    }

    // ------------------------------------------------------------ 学号记忆

    fun saveStudentId(id: String) {
        writeAtomically(accountFile, "{\"studentId\":${quote(id)}}")
    }

    fun loadStudentId(): String = runCatching {
        if (!accountFile.exists()) return ""
        field(accountFile.readText(), "studentId").orEmpty()
    }.getOrDefault("")

    /**
     * 本机出现过的**所有**学号：课表里带的、上次用的、记住密码时留下的。
     *
     * 崩溃报告脱敏时要把它们全抹掉，只抹课表里那一个是不够的
     * （登录失败时课表还是旧的，报告里却会出现刚输进去的学号）。
     */
    fun allStudentIds(): List<String> = runCatching {
        buildList {
            runCatching { loadSchedule()?.studentId }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { add(it) }
            for (file in listOf(accountFile, credentialsFile)) {
                if (!file.exists()) continue
                runCatching { field(file.readText(), "studentId") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.distinct()
    }.getOrDefault(emptyList())

    // -------------------------------------------------- 记住的账号密码（加密）

    /**
     * 保存学号 + 密码，密码经 [SecretStore] 用 Keystore 密钥加密后再落盘。
     *
     * 加密不可用时**返回 false 并且什么都不写** —— 绝不退化成存明文。
     * 界面拿到 false 就把开关弹回去并提示，而不是假装记住了。
     */
    fun saveCredentials(studentId: String, password: String): Boolean {
        val secret = SecretStore.encrypt(password) ?: return false
        return runCatching {
            writeAtomically(
                credentialsFile,
                "{\"studentId\":${quote(studentId)},\"secret\":${quote(secret)}}",
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 读取保存的凭据；解不开（换机 / 恢复出厂 / 密钥失效）时顺手删掉文件并返回 null。
     */
    fun loadCredentials(): Credentials? {
        val f = credentialsFile
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val id = field(text, "studentId") ?: return null
        val secret = field(text, "secret") ?: return null
        val password = SecretStore.decrypt(secret)
        if (password == null) {
            clearCredentials()
            return null
        }
        return Credentials(id, password)
    }

    fun hasCredentials(): Boolean = credentialsFile.exists()

    fun clearCredentials() {
        runCatching { credentialsFile.delete() }
    }

    /** 清空全部本地数据（设置页的「清除数据」）。 */
    fun clearAll() {
        runCatching { scheduleFile.delete() }
        runCatching { sessionFile.delete() }
        runCatching { accountFile.delete() }
        runCatching { credentialsFile.delete() }
        SecretStore.deleteKey()
    }

    data class Session(val studentId: String, val cookieHeader: String, val savedAt: Long)

    data class Credentials(val studentId: String, val password: String)

    // ----------------------------------------------------------------- 工具

    /**
     * 先写临时文件再改名 —— 避免写入过程被杀（导入时切走 App 很常见）留下半截 JSON，
     * 那会让下次启动读到损坏数据、看起来像「课表突然没了」。
     */
    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // rename 失败时退化为直接写，至少保证数据在
            target.writeText(content)
            runCatching { tmp.delete() }
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** 极简取值：这几个文件都是我们自己写的定形 JSON，不需要完整解析器。 */
    private fun field(text: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(text) ?: return null
        return m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
}
