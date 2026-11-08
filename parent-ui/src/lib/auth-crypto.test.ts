import {createHash, createHmac} from 'node:crypto';
import {sha256Hex, hmacHex} from './auth-crypto.ts';

let fail = 0;
const eq = (name: string, got: string, want: string) => {
  if (got !== want) { fail++; console.error(`✗ ${name}\n   got  ${got}\n   want ${want}`); }
  else console.log(`✓ ${name}`);
};

// 1) sha256 基础向量
eq('sha256("")', sha256Hex(''), createHash('sha256').update('').digest('hex'));
eq('sha256("abc")', sha256Hex('abc'), createHash('sha256').update('abc').digest('hex'));
// 2) 跨块边界（55/56/64/65 字节，padding 分支各不相同）
for (const n of [55, 56, 63, 64, 65, 119, 120, 128, 200]) eq(`sha256(${n}B)`, sha256Hex('a'.repeat(n)), createHash('sha256').update('a'.repeat(n)).digest('hex'));
// 3) 中文与 emoji（多字节 UTF-8）
for (const s of ['管理密码', '密码:盐值', '😀🔒', 'p@ss 中文 mix']) eq(`sha256(${JSON.stringify(s)})`, sha256Hex(s), createHash('sha256').update(s, 'utf8').digest('hex'));
// 4) HMAC：含 key 超 64 字节的分支（先 sha256 收缩）
const cases: Array<[string, string]> = [
  ['k', 'm'], ['', ''], ['a'.repeat(64), 'msg'], ['a'.repeat(65), 'msg'], ['a'.repeat(200), 'challenge'],
  [createHash('sha256').update('pwd:salty').digest('hex'), 'a1b2c3d4e5f6'],  // 真实登录形态
  ['管理密码', '中文挑战']
];
for (const [k, m] of cases) eq(`hmac(${k.length}B key)`, hmacHex(k, m), createHmac('sha256', Buffer.from(k, 'utf8')).update(m, 'utf8').digest('hex'));

console.log(fail === 0 ? '\n全部通过' : `\n${fail} 项不一致`);
process.exit(fail === 0 ? 0 : 1);
