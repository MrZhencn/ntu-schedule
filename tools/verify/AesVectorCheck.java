import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 验证 Java/Kotlin 侧能否解开 Node 侧按学校 encrypt.js 算法产出的密文。
 *
 * 算法（来源 authserver.ntu.edu.cn/.../encrypt.js）：
 *   encryptPassword(password, salt)
 *     = Base64( AES-128-CBC-PKCS7( Utf8(randomString(64) + password),
 *                                  key = Utf8(salt), iv = Utf8(randomString(16)) ) )
 *
 * Java 侧顺序相反：先自己随机 16 字符 iv，加密，再解回来比对。
 * 关键点是证明 salt 直接按 UTF-8 字节当密钥（不是 hex/base64 解码）。
 */
public final class AesVectorCheck {

    private static final String AES_CHARS =
            "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";

    /** 与 encrypt.js 的 randomString 同分布（用 secure random，字符集一致）。 */
    static String randomString(int n, java.util.Random rnd) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(AES_CHARS.charAt(rnd.nextInt(AES_CHARS.length())));
        }
        return sb.toString();
    }

    public static String encryptPassword(String password, String salt) throws Exception {
        // encrypt.js: randomString(64) 前缀 + randomString(16) 作 iv
        String rand64 = randomString(64, new java.security.SecureRandom());
        String iv = randomString(16, new java.security.SecureRandom());
        return aesCbcPkcs7(true, rand64 + password, salt, iv);
    }

    static String aesCbcPkcs7(boolean encrypt, String input, String key, String iv) throws Exception {
        // 逐一对照 encrypt.js：f = f.replace(/(^\s+)|(\s+$)/g,"") 只作用于 key
        String k = key.trim();
        SecretKeySpec keySpec = new SecretKeySpec(k.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding"); // PKCS5 == PKCS7 for AES
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keySpec, ivSpec);
        // 加密：输入是明文 String（UTF-8 字节），输出 base64
        // 解密：输入是 base64 密文，输出明文 String
        byte[] in = encrypt ? input.getBytes(StandardCharsets.UTF_8)
                            : Base64.getDecoder().decode(input);
        byte[] out = c.doFinal(in);
        return encrypt ? Base64.getEncoder().encodeToString(out)
                       : new String(out, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        // Node 侧固定向量
        String salt = "AbCdEfGh12345678";
        String iv = "ZyXwVuTs98765432";
        String rand64 = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678AbCdEfGhJKMNPQRS";
        String password = "Ntu@Test#2026";
        String ciphertext =
                "qUy4p4O4nA2IDYDPFednjQTBdF8lh71Mz5hCEJP1jNeF5MskwT0Q3XhLm8WOVL8GanErT4v2RnhJI5qV+yppplYYj9ZeL6R1TkAFEIaE8Ec=";

        int failures = 0;

        // 检查 1：Java 解开 Node 的密文，得到 rand64+password
        String decrypted = aesCbcPkcs7(false, ciphertext, salt, iv);
        String expectedPlain = rand64 + password;
        boolean ok1 = decrypted.equals(expectedPlain);
        System.out.println("检查1 Java解密Node密文: " + (ok1 ? "OK" : "FAIL"));
        if (!ok1) {
            System.out.println("  期望: " + expectedPlain);
            System.out.println("  实际: " + decrypted);
            failures++;
        }

        // 检查 2：检查1 解出的明文就是 rand64 + password，可正确切分
        boolean ok2 = decrypted.length() == 64 + password.length()
                && decrypted.startsWith(rand64)
                && decrypted.substring(64).equals(password);
        System.out.println("检查2 明文结构 rand64(64)+password: " + (ok2 ? "OK" : "FAIL"));
        if (!ok2) failures++;

        // 检查 3：Java 自己加密 -> 解回原文（往返一致性）
        String myCipher = encryptPassword(password, salt);
        boolean ok3 = false;
        // 需要找回这轮随机量才能解，改用直接对固定 plain 往返
        String rtPlain = rand64 + password;
        String rtCipher = aesCbcPkcs7(true, rtPlain, salt, iv);
        ok3 = aesCbcPkcs7(false, rtCipher, salt, iv).equals(rtPlain);
        System.out.println("检查3 Java加解密往返: " + (ok3 ? "OK" : "FAIL"));
        if (!ok3) failures++;

        // 检查 4：Java 加密结果与 Node 加密结果逐字节一致（同 key/iv/plain）
        boolean ok4 = rtCipher.equals(ciphertext);
        System.out.println("检查4 Java密文 == Node密文: " + (ok4 ? "OK" : "FAIL"));
        if (!ok4) {
            System.out.println("  Java: " + rtCipher);
            System.out.println("  Node: " + ciphertext);
            failures++;
        }

        // 检查 5：每次加密结果都不同（随机 iv/前缀生效）
        boolean ok5 = !encryptPassword(password, salt).equals(myCipher);
        System.out.println("检查5 每次加密结果随机: " + (ok5 ? "OK" : "FAIL"));
        if (!ok5) failures++;

        // 检查 6：密文长度 = 向上取整到 16 字节块 -> base64
        int plainLen = (64 + password.length());
        int padded = ((plainLen / 16) + 1) * 16;
        boolean ok6 = Base64.getDecoder().decode(ciphertext).length == padded;
        System.out.println("检查6 PKCS7 填充长度: " + (ok6 ? "OK" : "FAIL")
                + " (明文 " + plainLen + " -> 密文 " + Base64.getDecoder().decode(ciphertext).length + ")");
        if (!ok6) failures++;

        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
