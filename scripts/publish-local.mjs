#!/usr/bin/env node
/**
 * 把当前分支构建成「设备能被更新到的版本」，并在局域网里托管。
 *
 * 为什么需要它：官方更新源发的是 release 包（`com.studyontv.mobile` + 正式签名），
 * 而本分支装的是 debug 包（`com.studyontv.mobile.debug` + debug 签名）。
 * ApkInstaller 会校验「包名一致 + versionCode 更高 + 签名交集非空」，
 * 官方包一条都过不了，所以点「更新」只会失败。这里做的事就是：
 * 用同一台机器的 debug keystore 重新打包 → 抬高 versionCode → 生成一份
 * 指向局域网地址的 update.json → 起静态服务，让设备把它当更新源。
 *
 * 用法：
 *   node scripts/publish-local.mjs                        # 两个 flavor 都打，版本 0.3.19-dev.1 / code+1
 *   node scripts/publish-local.mjs --flavor mobile        # 只打手机版
 *   node scripts/publish-local.mjs --notes "修了 XX" --force
 *   node scripts/publish-local.mjs --no-build             # 复用已有产物，只做清单+托管
 *   node scripts/publish-local.mjs --flavor mobile --push http://192.168.31.174:8099 --password '家长密码'
 *   node scripts/publish-local.mjs --push http://192.168.31.174:8099 --password 'xx' --clear --no-build --no-serve
 *
 * 选项：
 *   --flavor mobile|tv|both   默认 both
 *   --version x.y.z[-tag]     默认 <build.gradle.kts 里的版本>-dev.1
 *   --code N                  默认 build.gradle.kts 里的 versionCode + 1
 *   --notes "..."             更新说明，显示在设备提示里；含 [force] 等同 --force
 *   --force                   强制更新（全屏，返回键无效）
 *   --port N                  静态服务端口，默认 8787
 *   --base URL                清单里写的对外基地址（如 http://xxx.xiaomiqiu.com），默认 http://局域网IP:端口
 *   --dir PATH                APK 与清单的输出目录，默认 dist/
 *   --no-build                跳过 gradle，直接取 build/outputs 里现有 APK
 *   --no-serve                不起静态服务（配合 --push 只做配置）
 *   --push URL                构建完把更新地址写进设备（URL 是设备上的面板地址）
 *   --password PWD            家长密码，脚本自己走挑战-响应登录换会话令牌
 *   --token TOKEN             已有会话令牌（64 位 hex）；集成令牌 sotv_ 没有 /api/update 权限
 *   --clear                   把设备的更新地址清空，回到官方渠道
 */
import {createHash, createHmac} from 'node:crypto';
import {createServer} from 'node:http';
import {spawnSync} from 'node:child_process';
import {copyFileSync, createReadStream, existsSync, mkdirSync, readdirSync, readFileSync, statSync, unlinkSync, writeFileSync} from 'node:fs';
import {basename, dirname, extname, join, normalize, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {networkInterfaces} from 'node:os';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);

if (argv.includes('--help') || argv.includes('-h')) {
  console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 32).join('\n').replace(/^ \* ?/gm, ''));
  process.exit(0);
}

/** 解析 `--k v` / `--k=v` / `--flag` */
const opts = {};
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (!a.startsWith('--')) continue;
  const eq = a.indexOf('=');
  if (eq > 0) { opts[a.slice(2, eq)] = a.slice(eq + 1); continue; }
  const key = a.slice(2);
  const next = argv[i + 1];
  if (next !== undefined && !next.startsWith('--')) { opts[key] = next; i++; } else { opts[key] = true; }
}

const gradleFile = join(root, 'app/native/app/build.gradle.kts');
const gradleText = readFileSync(gradleFile, 'utf8');
// versionName = versionNameOverride ?: "0.3.19" —— 取默认值那一侧
const baseVersion = gradleText.match(/versionName\s*=\s*[^\n]*?"([0-9][^"]*)"/)?.[1];
const baseCode = Number(gradleText.match(/versionCode\s*=\s*[^\n]*?(\d+)/)?.[1] ?? 0);
if (!baseVersion || !baseCode) throw new Error('无法从 build.gradle.kts 读出当前版本号');

// ---- 计算这次要发的版本 ----
let version = String(opts.version ?? `${baseVersion}-dev.1`).replace(/^v/, '');
if (!/^\d+\.\d+\.\d+([.-][0-9A-Za-z.-]+)?$/.test(version)) throw new Error(`版本号格式不对：${version}`);
let versionCode = Number(opts.code ?? baseCode + 1);
if (!Number.isInteger(versionCode) || versionCode <= 0) throw new Error(`versionCode 不对：${opts.code}`);
if (versionCode <= baseCode) {
  console.warn(`⚠️  versionCode ${versionCode} 不大于仓库里的 ${baseCode}，设备会判定"不比当前新"并拒绝安装。`);
}

const flavors = (opts.flavor ?? 'both') === 'both' ? ['tv', 'mobile'] : [String(opts.flavor)];
for (const f of flavors) if (f !== 'tv' && f !== 'mobile') throw new Error(`--flavor 只能是 mobile / tv / both`);

const distDir = resolve(root, opts.dir ?? 'dist');
const apkDir = join(distDir, 'apk');
const port = Number(opts.port ?? 8787);

// ---- 1. 构建 ----
if (!opts['no-build']) {
  const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  const tasks = flavors.map(f => `:app:assemble${f === 'tv' ? 'Tv' : 'Mobile'}Debug`);
  const cmd = [gradlew, ...tasks, `-PsotvVersionName=${version}`, `-PsotvVersionCode=${versionCode}`, '--no-daemon', '-q'];
  console.log(`▶ ${cmd.join(' ')}`);
  const r = spawnSync(cmd[0], cmd.slice(1), {
    cwd: join(root, 'app/native'),
    stdio: 'inherit',
    shell: process.platform === 'win32',
    env: process.env,
  });
  if (r.status !== 0) {
    console.error('\n构建失败。常见原因：JAVA_HOME 没指向 JDK 17（Gradle 8.13 不支持 JDK 25）。');
    process.exit(r.status ?? 1);
  }
}

// ---- 2. 收集 APK ----
/** 用 aapt2 读出 APK 里真实写的版本，避免"清单写 0.3.20、包里是 0.3.19"这种最难查的错 */
const sdkRoot = (() => {
  const fromEnv = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (fromEnv) return fromEnv;
  // 环境变量没设时退回 local.properties 的 sdk.dir（Studio 会自动写）
  for (const p of [join(root, 'app/native/local.properties'), join(root, 'local.properties')]) {
    if (!existsSync(p)) continue;
    const m = readFileSync(p, 'utf8').match(/^sdk\.dir\s*=\s*(.+)$/m);
    if (m) return m[1].trim().replace(/\\:/g, ':').replace(/\\\\/g, '\\');
  }
  return null;
})();

function aapt2() {
  const home = sdkRoot;
  if (!home) return null;
  const bt = join(home, 'build-tools');
  if (!existsSync(bt)) return null;
  const exe = process.platform === 'win32' ? 'aapt2.exe' : 'aapt2';
  const dirs = readdirSync(bt).sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
  for (const d of dirs) {
    const p = join(bt, d, exe);
    if (existsSync(p)) return p;
  }
  return null;
}

function apkVersion(path) {
  const tool = aapt2();
  if (!tool) return null;
  const r = spawnSync(tool, ['dump', 'badging', path], {encoding: 'utf8'});
  if (r.status !== 0) return null;
  const name = r.stdout.match(/versionName='([^']*)'/)?.[1];
  const code = Number(r.stdout.match(/versionCode='(\d+)'/)?.[1]);
  const pkg = r.stdout.match(/package: name='([^']*)'/)?.[1];
  return name ? {name, code, pkg} : null;
}

mkdirSync(apkDir, {recursive: true});
const assets = {};
let detected = null;
for (const f of flavors) {
  const src = join(root, `app/native/app/build/outputs/apk/${f}/debug/app-${f}-debug.apk`);
  if (!existsSync(src)) throw new Error(`找不到产物：${src}（先去掉 --no-build 跑一次构建）`);
  const real = apkVersion(src);
  if (real) detected ??= real;
  if (!real) {
    // 读不出真实版本时，清单就只能靠推算值，等于把"清单说 25、包里是 24"的风险放回来
    if (opts['no-build']) {
      throw new Error('读不出 APK 里的版本号（找不到 aapt2）：请设置 ANDROID_HOME，或去掉 --no-build 让脚本自己构建一次');
    }
    console.warn('⚠️  读不出 APK 版本号（找不到 aapt2），清单按请求值写入，请自行核对包内版本。');
  }
  if (real && (real.name !== version || real.code !== versionCode)) {
    console.warn(`⚠️  APK 实际是 v${real.name}(${real.code})，与请求的 v${version}(${versionCode}) 不一致，按 APK 实际值写清单。`);
    version = real.name;
    versionCode = real.code;
  }
  const marker = f === 'tv' ? 'TV' : 'Mobile';
  const fileName = `StudyOnTV-${marker}-v${version}.apk`;
  copyFileSync(src, join(apkDir, fileName));
  assets[f] = {
    name: fileName,
    url: `__BASE__/apk/${encodeURIComponent(fileName)}`,
    sha256: createHash('sha256').update(readFileSync(src)).digest('hex'),
    size: statSync(src).size,
  };
  console.log(`✅ ${f}: ${fileName}  ${(assets[f].size / 1048576).toFixed(1)} MB`);
}

// 旧版本的包留在目录里会被穿透域名一起暴露，手动下载时也容易下错，只保留本次清单引用的
const keep = new Set(Object.values(assets).map(a => a.name));
for (const old of readdirSync(apkDir)) {
  if (!/^StudyOnTV-.*\.apk$/i.test(old) || keep.has(old)) continue;
  try { unlinkSync(join(apkDir, old)); console.log(`🧹 清理旧包 ${old}`); } catch (_) { }
}

// ---- 3. 局域网地址 ----
function lanIp() {
  const list = [];
  for (const [name, addrs] of Object.entries(networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      const p = a.address.split('.').map(Number);
      const priv = p[0] === 10 || (p[0] === 192 && p[1] === 168) || (p[0] === 172 && p[1] >= 16 && p[1] <= 31);
      list.push({name, address: a.address, priv});
    }
  }
  const pick = list.find(x => x.priv) ?? list[0];
  return pick?.address ?? '127.0.0.1';
}
const ip = opts.host ?? lanIp();
// 对外地址：优先 --base（穿透域名等），否则用「局域网 IP + 端口」
const base = String(opts.base ?? `http://${ip}:${port}`).replace(/\/+$/, '');

// ---- 4. 生成 update.json ----
const notes = String(opts.notes ?? '');
const force = Boolean(opts.force) || /\[force\]/i.test(notes);
const manifest = {
  version,
  versionCode,
  force,
  notes,
  publishedAt: new Date().toISOString(),
  assets: Object.fromEntries(Object.entries(assets).map(([k, v]) => [k, {...v, url: v.url.replace('__BASE__', base)}])),
};
writeFileSync(join(distDir, 'update.json'), JSON.stringify(manifest, null, 2) + '\n');

// ---- 5. 托管 ----
const types = {'.json': 'application/json; charset=utf-8', '.apk': 'application/vnd.android.package-archive'};
const server = createServer((req, res) => {
  const rawPath = decodeURIComponent((req.url || '/').split('?')[0]);
  const rel = normalize(rawPath).replace(/^[/\\]+/, '');
  const file = join(distDir, rel);
  // 目录穿越防护：拼接后必须仍在 dist 内
  if (file !== distDir && !file.startsWith(distDir + sep)) { res.writeHead(403).end('forbidden'); return; }
  if (!existsSync(file) || !statSync(file).isFile()) { res.writeHead(404).end('not found'); return; }
  const size = statSync(file).size;
  res.writeHead(200, {
    'Content-Type': types[extname(file).toLowerCase()] ?? 'application/octet-stream',
    'Content-Length': size,
    // 清单必须每次重新取：设备缓存 6 小时，靠 no-store 让面板的"检查更新"立刻生效
    'Cache-Control': extname(file).toLowerCase() === '.json' ? 'no-store' : 'public, max-age=60',
  });
  if (req.method === 'HEAD') { res.end(); return; }
  createReadStream(file).pipe(res);
});
server.on('error', e => {
  if (e.code === 'EADDRINUSE') console.error(`\n端口 ${port} 被占用，换一个：--port 8788`);
  else console.error(e);
  process.exit(1);
});

/** 家长登录：挑战-响应，密码不上网（与服务端 AdminAuth 的协议一致） */
async function sessionToken(panel) {
  if (opts.token) {
    const t = String(opts.token);
    if (/^sotv_/.test(t)) throw new Error('集成令牌没有 /api/update 权限（白名单只放行 state/device/remote），请改用 --password');
    return t;
  }
  const pwd = opts.password === undefined ? '' : String(opts.password);
  if (!pwd) throw new Error('需要 --password（家长密码）或 --token（会话令牌）');
  const ch = await fetch(`${panel}/api/auth/challenge`);
  if (!ch.ok) throw new Error(`取挑战失败：HTTP ${ch.status}`);
  const info = await ch.json().catch(() => ({}));
  if (!info.challenge || !info.salt) throw new Error('设备还没设置管理密码，或 auth_key 未生成；先在设备上登录一次面板');
  // 服务端存的是 auth_key = sha256("密码:盐") 的十六进制串，HMAC 的 key 就是这串字符本身
  const key = createHash('sha256').update(`${pwd}:${info.salt}`).digest('hex');
  const proof = createHmac('sha256', key).update(info.challenge).digest('hex');
  const r = await fetch(`${panel}/api/auth`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({challenge: info.challenge, proof, remember: false}),
  });
  if (!r.ok) {
    const b = await r.json().catch(() => ({}));
    throw new Error(b.message || b.error || `登录失败：HTTP ${r.status}`);
  }
  const {token} = await r.json();
  if (!token) throw new Error('登录成功但没拿到令牌');
  return token;
}

async function pushToPanel() {
  const panel = String(opts.push).replace(/\/+$/, '');
  const token = await sessionToken(panel);
  const headers = {'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json'};
  const api = opts.clear ? '' : `${base}/update.json`;
  const put = await fetch(`${panel}/api/update`, {method: 'PUT', headers, body: JSON.stringify({api})});
  console.log(`▶ 写入更新地址${api ? `（${api}）` : '（已清空，回到官方渠道）'}：HTTP ${put.status}`);
  if (!put.ok) throw new Error((await put.text()).slice(0, 200));
  if (opts.clear) return;

  const chk = await fetch(`${panel}/api/update/check`, {method: 'POST', headers, body: '{}'});
  console.log(`▶ 触发检查：HTTP ${chk.status}`);
  for (let i = 0; i < 15; i++) {
    await new Promise(r => setTimeout(r, 1000));
    const st = await (await fetch(`${panel}/api/update`, {headers})).json();
    if (st.checking) continue;
    console.log(`   设备当前 v${st.current}，来源 ${st.source || '—'}，最新 v${st.latest || '—'}，有更新：${st.hasUpdate ? '是' : '否'}`);
    return;
  }
  console.log('   检查超时，稍后在面板里点「检查更新」看结果');
}

async function main() {
  if (opts.clear && !opts.push) throw new Error('--clear 需要配合 --push <面板地址>');

  if (!opts['no-serve']) {
    await new Promise((res, rej) => {
      server.once('error', rej);
      server.listen(port, '0.0.0.0', res);
    });
    console.log(`\n📡 更新源已就绪：${base}/update.json  （Ctrl+C 停止）`);
    console.log(`   版本 v${version} (${versionCode})${force ? '  [force]' : ''}`);
    console.log('   清单里的下载地址：');
    for (const [k, a] of Object.entries(manifest.assets)) console.log(`     ${k}: ${a.url}`);
  }

  if (opts.push) {
    await pushToPanel();
  } else if (!opts['no-serve']) {
    console.log(`\n下一步：在设备的面板里把更新地址设为 ${base}/update.json`);
    console.log(`     或加 --push http://<面板地址>:8099 --password '<家长密码>' 让脚本自己写进去。`);
  }

  if (opts['no-serve']) process.exit(0);
}

main().catch(e => { console.error('\n❌ ' + e.message); process.exit(1); });

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { server.close(); process.exit(0); });
