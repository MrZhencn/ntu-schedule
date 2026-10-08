package com.ntu.schedule.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ntu.schedule.core.AesPassword
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的硬件保护密钥加解密「记住的密码」。
 *
 * 为什么值得多写这一层：用户要求「保留账号密码在本地，下次统一课表方便登录」。
 * 直接写明文到 `files/` 是能跑，但那个目录在 root 设备、adb backup（本项目已关）、
 * 以及各种「手机助手」类工具面前基本等于公开。Keystore 的密钥**不出安全芯片**，
 * 落盘的是密文，拷走文件也解不开。
 *
 * 几个刻意的选择：
 * - **AES/GCM**，不是 CBC：GCM 自带完整性校验，密文被改动一个 bit 就解不开，
 *   而不是解出一段垃圾当密码去提交（那会踩到「连续 5 次错误锁定账号」）。
 * - **IV 随密文一起存**（12 字节前置），GCM 的 IV 必须每次不同，写死会直接崩安全性。
 * - **不给密钥设 userAuthenticationRequired**：设了就等于每次读密码都要指纹/密码解锁，
 *   导入课表这件小事不值得；而且改锁屏密码会让密钥失效、用户莫名其妙丢数据。
 * - 密钥**不进任何备份**：设备重置或卸载重装后解不开是正常的，此时当作「没保存过」处理。
 */
object SecretStore {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "ntu_schedule_credentials_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** 本机是否支持（极少数定制 ROM 会关掉 Keystore）。不支持时界面就把「记住密码」置灰。 */
    fun isAvailable(): Boolean = runCatching { ensureKey() != null }.getOrDefault(false)

    /** 加密后返回 Base64（IV + 密文 + 认证标签）；失败返回 null，调用方应放弃保存而不是退化成明文。 */
    fun encrypt(plain: String): String? = runCatching {
        val key = ensureKey() ?: return null
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        AesPassword.base64Encode(iv + body)
    }.getOrNull()

    /** 解密失败（换机、恢复出厂、密钥失效、密文损坏）返回 null，绝不抛异常给界面。 */
    fun decrypt(encoded: String): String? = runCatching {
        val raw = AesPassword.base64Decode(encoded) ?: return null
        if (raw.size <= IV_LEN) return null
        val key = ensureKey() ?: return null
        val iv = raw.copyOfRange(0, IV_LEN)
        val body = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /** 「清除本地数据」时把密钥也删掉，否则留着一条永远没人用的密钥。 */
    fun deleteKey() {
        runCatching {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }

    private fun ensureKey(): SecretKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
