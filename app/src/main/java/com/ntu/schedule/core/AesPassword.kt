package com.ntu.schedule.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 南通大学统一身份认证（authserver.ntu.edu.cn）的密码加密。
 *
 * 逐字对应学校真实脚本 `cusThemeNtuAutoXXX/static/common/encrypt.js`：
 * ```
 * function getAesString(n,f,c){f=f.replace(/(^\s+)|(\s+$)/g,"");f=CryptoJS.enc.Utf8.parse(f);
 *   c=CryptoJS.enc.Utf8.parse(c);return CryptoJS.AES.encrypt(n,f,
 *   {iv:c,mode:CryptoJS.mode.CBC,padding:CryptoJS.pad.Pkcs7}).toString()}
 * function encryptAES(n,f){return f?getAesString(randomString(64)+n,f,randomString(16)):n}
 * var $aes_chars="ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
 * ```
 *
 * 三个容易写错、但已被测试向量钉死的点：
 * 1. `CryptoJS.enc.Utf8.parse(key)` 直接把 16 个 ASCII 字符当作**原文密钥字节**，
 *    **不是** hex 解码。写成 hex 解码会得到完全不同的密文。
 * 2. IV 是每次随机生成的 16 个字符，随密文一起使用，服务端从 Base64 前 16 字节取回。
 * 3. 明文体是 `randomString(64) + 密码`（UTF-8），密钥两侧空白会被 trim。
 *
 * 输出 Base64，内部含 IV 前缀 —— 与 CryptoJS 的 `toString()` 格式一致。
 */
object AesPassword {

    /** 学校脚本里的随机字符集，去掉了容易混淆的 0/1/l/o/9/I 等。 */
    // internal 而非 private：单元测试要拿它断言「64 位前缀的每一位都落在这个字符集里」，
    // 这样测试就不用再抄一遍字面量（抄一遍就等于没测）。
    internal const val AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    internal const val PREFIX_LEN = 64
    internal const val IV_LEN = 16

    private val random = SecureRandom()

    /** 与 JS `randomString(n)` 等价。 */
    fun randomString(n: Int): String {
        val sb = StringBuilder(n)
        repeat(n) { sb.append(AES_CHARS[random.nextInt(AES_CHARS.length)]) }
        return sb.toString()
    }

    /**
     * 与 JS `encryptPassword(password, salt)` 等价；salt 为空时按学校脚本原样返回明文。
     *
     * @param salt 登录页隐藏字段 `pwdEncryptSalt`，每次会话都会变，必须实时抓取。
     */
    fun encryptPassword(password: String, salt: String?): String {
        if (salt.isNullOrEmpty()) return password
        return encryptAes(randomString(PREFIX_LEN) + password, salt, randomString(IV_LEN))
    }

    /**
     * 与 JS `getAesString(plain, key, iv)` 等价：AES/CBC/PKCS7，输出 Base64。
     *
     * 注：PKCS7 与 PKCS5 对 AES（块长 16）是同一件事，Java 侧用 `AES/CBC/PKCS5Padding`
     * 即等价，无需自行填充。
     */
    fun encryptAes(plain: String, key: String, iv: String): String {
        val keyBytes = key.trim().toByteArray(Charsets.UTF_8)
        val ivBytes = iv.toByteArray(Charsets.UTF_8)
        require(keyBytes.size == 16) { "AES 密钥必须是 16 字节（Utf8.parse 后），实际 ${keyBytes.size}" }
        require(ivBytes.size == 16) { "AES IV 必须是 16 字节，实际 ${ivBytes.size}" }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes),
        )
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return base64Encode(out)
    }

    /**
     * 标准 Base64（带 `=` 填充）。
     *
     * 刻意不依赖 `android.util.Base64`：那样会让本类在 JVM 单元测试里返回 null
     * （Android 桩方法默认抛异常/返回默认值），而「AES 与学校 encrypt.js 是否逐字节一致」
     * 正是本项目最需要被测试钉死的一环。也不依赖 `java.util.Base64`，
     * 因为它在 Android 上需要 API 26，而本项目 minSdk 是 24。
     */
    internal fun base64Encode(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(table[n ushr 18 and 0x3F])
                .append(table[n ushr 12 and 0x3F])
                .append(table[n ushr 6 and 0x3F])
                .append(table[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                sb.append(table[n ushr 18 and 0x3F])
                    .append(table[n ushr 12 and 0x3F])
                    .append("==")
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                sb.append(table[n ushr 18 and 0x3F])
                    .append(table[n ushr 12 and 0x3F])
                    .append(table[n ushr 6 and 0x3F])
                    .append('=')
            }
        }
        return sb.toString()
    }

    /**
     * 标准 Base64 解码，忽略空白；遇到非法字符返回 null。
     *
     * 与 [base64Encode] 配套，同样刻意不依赖 `android.util.Base64`（JVM 测试里是桩）
     * 和 `java.util.Base64`（Android 上需 API 26）。本地保存的凭据密文要靠它读回来。
     */
    internal fun base64Decode(text: String): ByteArray? {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = text.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)

        // 结构校验：`=` 只能出现在末尾且最多两个，去掉填充后总长必须是 4 的倍数。
        // 这三条是为了让「密文被截断/改动」明确失败，而不是解出一段垃圾字节
        // 当密码去提交（那会白耗一次账号锁定机会）。
        val firstPad = clean.indexOf('=')
        val body = if (firstPad < 0) clean else clean.substring(0, firstPad)
        val pad = clean.length - body.length
        if (pad > 2) return null
        if ((body.length + pad) % 4 != 0) return null
        if (body.length % 4 == 1) return null

        val out = java.io.ByteArrayOutputStream(body.length / 4 * 3 + 3)
        var buffer = 0
        var bits = 0
        for (c in body) {
            val v = table.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
