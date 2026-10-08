package com.ntu.schedule.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把崩溃报告 / 自检信息交到用户手里。
 *
 * 两条路都留着，因为它们的失败场景不一样：
 * 1. **分享**（`ACTION_SEND`）—— 最常用，微信、QQ、邮件、网盘都在这个选择器里。
 *    报告是 `.txt` 文件，所以走 `FileProvider` 出一个 `content://` URI 并临时授权；
 *    直接给 `file://` 从 Android 7 起会抛 `FileUriExposedException`。
 * 2. **复制到剪贴板** —— 微信里发文件比发文字麻烦，很多人就是直接粘一段文字。
 *    分享那条路被某个 ROM 拦掉时，这条通常还能走。
 *
 * 为什么不直接用 `MediaStore` 写进「下载」目录：那需要额外权限，而且 Android 10 前后
 * 的写法完全不同（`WRITE_EXTERNAL_STORAGE` vs 分区存储）。为一个「用户其实很少点」的
 * 兜底功能去加权限、再加一套分版本的文件写入，代价比收益大。
 */
object DiagnosticsSharing {

    /** 分享一段文本。返回是否成功唤起选择器。 */
    fun shareText(context: Context, subject: String, text: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return launchChooser(context, intent, subject)
    }

    /** 以附件形式分享一份文件（报告本体，比粘文字更好读）。 */
    fun shareFile(context: Context, file: File, subject: String): Boolean {
        if (!file.exists()) return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return shareText(context, subject, runCatching { file.readText() }.getOrDefault(""))

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            // 有些接收方只认 EXTRA_TEXT（比如某些微信版本），两条都给上，能救一次是一次。
            putExtra(Intent.EXTRA_TEXT, runCatching { file.readText() }.getOrDefault(""))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        }
        return launchChooser(context, intent, subject)
    }

    fun copyToClipboard(context: Context, label: String, text: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)

    private fun launchChooser(context: Context, intent: Intent, title: String): Boolean = runCatching {
        val chooser = Intent.createChooser(intent, title).apply {
            // 从非 Activity 上下文启动时必须带这个标志，否则直接崩。
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    }.getOrDefault(false)
}
