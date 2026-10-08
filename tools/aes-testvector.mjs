#!/usr/bin/env node
/**
 * 生成 AES 密码加密测试向量（固定 salt/iv/rand64）。
 * 算法来源：authserver.ntu.edu.cn/authserver/cusThemeNtuAutoXXX/static/common/encrypt.js
 *
 *   getAesString(plain, key, iv) = AES-128-CBC(Utf8.parse(plain), Utf8.parse(key), Utf8.parse(iv)) -> Base64(Pkcs7)
 *   encryptAES(password, salt)   = getAesString(randomString(64) + password, salt, randomString(16))
 *
 * 本脚本把随机量固定，输出密文，交给 Java 侧解密比对 —— 证明 Kotlin 实现与之等价。
 *
 * 用法: node aes-testvector.mjs            # 打印向量
 *       node aes-testvector.mjs --check    # 顺带自校验（解密回原文）
 */

import crypto from 'node:crypto';

// encrypt.js 中的字符集，原样搬运
const AES_CHARS = 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678';

export function aesEncrypt(plain, key, iv) {
  const c = crypto.createCipheriv('aes-128-cbc', Buffer.from(key, 'utf8'), Buffer.from(iv, 'utf8'));
  return Buffer.concat([c.update(Buffer.from(plain, 'utf8')), c.final()]).toString('base64');
}

export function encryptPassword(password, salt, rand64, iv) {
  return aesEncrypt(rand64 + password, salt, iv);
}

// 固定向量（salt 必须是 16 个 ASCII 字符；rand64 取自合法字符集）
export const VECTOR = {
  salt: 'AbCdEfGh12345678',
  iv: 'ZyXwVuTs98765432',
  rand64: 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678AbCdEfGhJKMNPQRS',
  password: 'Ntu@Test#2026',
};

if (import.meta.url === `file:///${process.argv[1].replace(/\\/g, '/')}` || process.argv[1].endsWith('aes-testvector.mjs')) {
  const { salt, iv, rand64, password } = VECTOR;
  const ciphertext = encryptPassword(password, salt, rand64, iv);
  const plain = rand64 + password;

  console.log(JSON.stringify({
    salt,
    iv,
    rand64,
    password,
    plain,
    plainUtf8Hex: Buffer.from(plain, 'utf8').toString('hex'),
    ciphertext,
    ciphertextHex: Buffer.from(ciphertext, 'base64').toString('hex'),
    charsetValid: [...rand64].every((ch) => AES_CHARS.includes(ch)),
    saltByteLength: Buffer.byteLength(salt, 'utf8'),
  }, null, 2));

  if (process.argv.includes('--check')) {
    const d = crypto.createDecipheriv('aes-128-cbc', Buffer.from(salt, 'utf8'), Buffer.from(iv, 'utf8'));
    const back = Buffer.concat([d.update(Buffer.from(ciphertext, 'base64')), d.final()]).toString('utf8');
    console.log(back === plain ? '\nSELF-CHECK OK: 解密回原文一致' : `\nSELF-CHECK FAIL: ${back}`);
    process.exit(back === plain ? 0 : 1);
  }
}
