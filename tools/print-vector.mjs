// 临时：导出固定测试向量的期望密文，供 Kotlin 单元测试断言。
import { VECTOR, encryptPassword, aesEncrypt } from './aes-testvector.mjs';

const plain = VECTOR.rand64 + VECTOR.password;
const fromPlain = aesEncrypt(plain, VECTOR.salt, VECTOR.iv);
const fromPassword = encryptPassword(VECTOR.password, VECTOR.salt, VECTOR.rand64, VECTOR.iv);

console.log(JSON.stringify({
  salt: VECTOR.salt,
  iv: VECTOR.iv,
  rand64: VECTOR.rand64,
  password: VECTOR.password,
  plain,
  plainUtf8Hex: Buffer.from(plain, 'utf8').toString('hex'),
  cipherFromEncryptPassword: fromPassword,
  cipherFromAesEncrypt: fromPlain,
  match: fromPassword === fromPlain,
}, null, 2));
