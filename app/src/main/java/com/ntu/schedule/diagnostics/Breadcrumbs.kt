package com.ntu.schedule.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃之前发生了什么 —— 一个定长的内存环形缓冲。
 *
 * 为什么要有它：光有堆栈只够定位「哪一行炸了」，不够解释「为什么会走到那一行」。
 * 用户报上来的崩溃十有八九是「我点了一下刷新就闪退了」，而堆栈里一个中文字都没有。
 * 记下最近几十步操作，报告才有上下文。
 *
 * 为什么只放内存：崩溃发生在**同一个进程**里，`UncaughtExceptionHandler` 在进程死之前
 * 跑，所以内存里的东西还在。反过来，如果每记一条就写盘，正常使用时也要一直写盘 ——
 * 为了一个几乎不会发生的场景，去拖累每一次点击，不值。
 *
 * 线程安全：`onUpdate`、提醒广播、主线程都往里写，所以所有访问都加锁。
 */
object Breadcrumbs {

    /** 只留最近这么多条。太多了报告读不完，而且崩溃报告是给用户复制的，越短越好发。 */
    const val CAPACITY = 40

    private val lock = Any()
    private val buffer = ArrayDeque<Crumb>(CAPACITY)
    private var installedAt = 0L

    /** 记录一条。**绝不抛异常** —— 记日志把 App 搞崩是最讽刺的 bug。 */
    fun add(tag: String, text: String) {
        runCatching {
            val crumb = Crumb(System.currentTimeMillis(), tag, text)
            synchronized(lock) {
                if (buffer.size >= CAPACITY) buffer.removeFirst()
                buffer.addLast(crumb)
            }
        }
    }

    /** 新 → 旧。 */
    fun snapshot(): List<Crumb> = runCatching {
        synchronized(lock) { buffer.toList() }
    }.getOrDefault(emptyList())

    fun clear() {
        runCatching { synchronized(lock) { buffer.clear() } }
    }

    /** 进程启动到现在，用于报告里那行「进程活了」。 */
    fun markStart() {
        installedAt = System.currentTimeMillis()
    }

    fun startMillis(): Long = installedAt

    /** 给「复制自检信息」用的一行行文本。 */
    fun dump(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val list = snapshot()
        if (list.isEmpty()) return "（还没有记录）"
        return list.asReversed().joinToString("\n") { c ->
            "${fmt.format(Date(c.atMillis))}  ${c.tag}  ${c.text}"
        }
    }
}
