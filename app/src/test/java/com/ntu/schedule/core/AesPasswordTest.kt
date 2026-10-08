package com.ntu.schedule.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 把 Kotlin 的 AES 实现钉死在与学校真实 `encrypt.js` 完全一致的行为上。
 *
 * 期望密文由 Node 侧 `tools/aes-testvector.mjs` 用**固定**的 salt/iv/rand64 算出
 * （见 `tools/print-vector.mjs` 输出）。因为随机量被固定，这里的密文是确定值 ——
 * 只要 Kotlin 实现与 CryptoJS 有任何差异（hex 解码密钥、IV 位置、填充方式、
 * 明文前缀长度、字符集），本测试立刻失败。
 */
class AesPasswordTest {

    private val salt = "AbCdEfGh12345678"
    private val iv = "ZyXwVuTs98765432"
    private val rand64 = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678AbCdEfGhJKMNPQRS"
    private val password = "Ntu@Test#2026"
    private val plain = rand64 + password
    private val expectedCipher = "qUy4p4O4nA2IDYDPFednjQTBdF8lh71Mz5hCEJP1jNeF5MskwT0Q3XhLm8WOVL8GanErT4v2RnhJI5qV+yppplYYj9ZeL6R1TkAFEIaE8Ec="

    @Test
    fun `密文与学校 encrypt js 逐字节一致`() {
        assertEquals(expectedCipher, AesPassword.encryptAes(plain, salt, iv))
    }

    @Test
    fun `明文UTF8字节与JS侧一致`() {
        val hex = plain.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        assertEquals(
            "41424344454647484a4b4d4e50515253545758595a61626364656668696a6b6d6e7072" +
                "73747778797a3233343536373841624364456647684a4b4d4e505152534e747540546573742332303236",
            hex,
        )
    }

    @Test
    fun `密钥按原文UTF8字节解析而非hex或base64解码`() {
        // 防回归的关键：CryptoJS 的 `enc.Utf8.parse(salt)` 是把 salt 的 UTF-8 字节直接当密钥，
        // 不是 hex 解码。若按 hex 解码，同一个明文会得到完全不同的密文。
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val viaUtf8Bytes = AesPassword.base64Encode(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        val viaOurCode = AesPassword.encryptAes(plain, salt, iv)

        // 1) 直接用 UTF-8 字节当密钥，必须和我们实现的结果一致
        assertEquals("UTF-8 字节密钥应得到相同密文", viaUtf8Bytes, viaOurCode)
        // 2) 与 Node/CryptoJS 侧算出的期望密文一致
        assertEquals(expectedCipher, viaUtf8Bytes)

        // 3) 反向确认：把 salt 当 hex 解出来的密钥长度只有 8 字节，根本不是合法 AES-128 密钥
        assertEquals(16, salt.toByteArray(Charsets.UTF_8).size)
        assertEquals(8, salt.length / 2)
    }

    @Test
    fun `自实现Base64与JDK实现逐字节一致`() {
        // 覆盖 3 字节整除、余 1、余 2 三种填充情形。
        for (n in 0..40) {
            val bytes = ByteArray(n) { (it * 7 + 13).toByte() }
            val mine = AesPassword.base64Encode(bytes)
            val jdk = java.util.Base64.getEncoder().encodeToString(bytes)
            assertEquals("长度 $n 的 Base64 不一致", jdk, mine)
        }
    }

    @Test
    fun `salt为空时按学校脚本原样返回明文`() {
        assertEquals(password, AesPassword.encryptPassword(password, null))
        assertEquals(password, AesPassword.encryptPassword(password, ""))
    }

    @Test
    fun `加密结果前缀64字符随机串且以密码结尾`() {
        // encryptPassword 内部的 iv 是一次性随机的，无法从输出反推（学校脚本也没把 iv 放进密文），
        // 所以这里自己指定 iv 复现同一算法，再解回来检查明文结构。
        val localIv = "ZyXwVuTs98765432"
        val plainIn = AesPassword.randomString(AesPassword.PREFIX_LEN) + password
        val cipherText = AesPassword.encryptAes(plainIn, salt, localIv)

        val d = Cipher.getInstance("AES/CBC/PKCS5Padding")
        d.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(localIv.toByteArray(Charsets.UTF_8)),
        )
        val back = String(d.doFinal(java.util.Base64.getDecoder().decode(cipherText)), Charsets.UTF_8)

        // 明文 = 64 字符随机前缀 + 密码原文
        assertEquals(AesPassword.PREFIX_LEN + password.length, back.length)
        assertTrue("解密结果应以密码结尾", back.endsWith(password))

        // 前缀的每一位都必须落在学校那道字符集里 —— 这比断言一个长度数字更有价值：
        // 字符集、前缀长度、密码边界任何一处写错都会在这里露出来。
        val prefix = back.substring(0, AesPassword.PREFIX_LEN)
        assertTrue(
            "前缀应全部落在学校字符集内，实际: $prefix",
            prefix.all { it in AesPassword.AES_CHARS },
        )
        // 密码里含字符集外的字符（@ 与 #），正好用来证明前缀与密码没有错位
        assertTrue("密码应含字符集外的字符，否则本测试无法判定边界", password.any { it !in AesPassword.AES_CHARS })
    }

    @Test
    fun `encryptPassword输出是合法Base64且每次不同`() {
        val a = AesPassword.encryptPassword(password, salt)
        val b = AesPassword.encryptPassword(password, salt)
        // 每次 iv 与 64 位前缀都随机，密文必然不同（除非随机源坏了）
        assertTrue("两次加密结果不应相同", a != b)

        // 长度全部由算法推导，不写死数字：
        // 明文 = 64 字符前缀 + 密码 -> PKCS7 补齐到 16 的整数倍 -> Base64
        val plainBytes = AesPassword.PREFIX_LEN + password.toByteArray(Charsets.UTF_8).size
        val paddedBytes = (plainBytes + 15) / 16 * 16
        val expectedBase64 = (paddedBytes + 2) / 3 * 4
        assertEquals(expectedBase64, a.length)

        val raw = java.util.Base64.getDecoder().decode(a)
        assertEquals(0, raw.size % 16)
        assertEquals(paddedBytes, raw.size)
        // 真实登录实测密文长度也是 108（明文 10 位的密码），与这里的量级一致
        assertEquals(108, expectedBase64)
    }

    @Test
    fun `randomString落在学校字符集内且长度正确`() {
        val allowed = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        val s = AesPassword.randomString(64)
        assertEquals(64, s.length)
        assertTrue("出现字符集之外的字符: $s", s.all { it in allowed })
        val iv = AesPassword.randomString(16)
        assertEquals(16, iv.length)
        assertTrue(iv.toByteArray(Charsets.UTF_8).size == 16)
    }

    @Test
    fun `base64Decode与JDK实现逐字节一致`() {
        // 保存到本地的密码密文要用自实现的 Base64 解回来，这里覆盖三种填充情形。
        for (n in 0..40) {
            val bytes = ByteArray(n) { ((it * 11 + 5) and 0xFF).toByte() }
            val text = java.util.Base64.getEncoder().encodeToString(bytes)
            val back = AesPassword.base64Decode(text)
            assertTrue("长度 $n 解出来是 null: $text", back != null)
            assertEquals("长度 $n 解出来不一致", bytes.toList(), back!!.toList())
        }
    }

    @Test
    fun `base64Decode忽略空白字符`() {
        val bytes = ByteArray(20) { it.toByte() }
        val text = java.util.Base64.getEncoder().encodeToString(bytes)
        val spaced = text.chunked(4).joinToString("\n")
        assertEquals(bytes.toList(), AesPassword.base64Decode(spaced)!!.toList())
    }

    @Test
    fun `base64Decode遇到非法字符返回null`() {
        // 密文被改动或截断时必须明确失败，绝不能解出垃圾字节 ——
        // 那会被当作密码提交给学校，直接消耗一次账号锁定机会。
        assertEquals(null, AesPassword.base64Decode("!!!not base64!!!"))
        assertEquals(null, AesPassword.base64Decode("AAAA===="))
        assertEquals(null, AesPassword.base64Decode("A"))
    }
}
