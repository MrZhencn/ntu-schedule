#!/usr/bin/env node
/**
 * 南通大学教务系统 —— 课表抓取 / 协议验证工具（零依赖）
 *
 * 用途：
 *   1. 验证 CAS 统一身份认证流程 + 密码 AES 加密算法
 *   2. 抓到真实课表 JSON，确定字段结构，作为 Android App 解析器的依据
 *   3. 同时保存课表页面 HTML，供 App 的 HTML 兜底解析器离线测试
 *
 * 用法：
 *   node scrape-schedule.mjs --user 学号 --pass 密码 [--out out]
 *   node scrape-schedule.mjs --cookie "JSESSIONID=xxx"      # 跳过登录，复用已有会话
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// ---------------------------------------------------------------- 常量

const AUTH_BASE = 'https://authserver.ntu.edu.cn';
const JW_BASE = 'https://tdjw.ntu.edu.cn';
const SERVICE = `${JW_BASE}/sso/jziotlogin`;

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const TIMEOUT_MS = 30000;

// ---------------------------------------------------------------- HTTP 客户端

/** 极简 cookie jar：只按 domain/path 粗略匹配，够用 */
class CookieJar {
  constructor() {
    /** @type {Map<string,string>} */
    this.store = new Map();
  }

  #key(domain, name) {
    return `${domain}\t${name}`;
  }

  absorb(domain, setCookieHeaders) {
    for (const raw of setCookieHeaders) {
      const [pair] = raw.split(';');
      const eq = pair.indexOf('=');
      if (eq < 0) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      if (!name) continue;
      if (value === '' || /Max-Age=0/i.test(raw) || /Expires=Thu, 01 Jan 1970/i.test(raw)) {
        this.store.delete(this.#key(domain, name));
      } else {
        this.store.set(this.#key(domain, name), value);
      }
    }
  }

  /** 导出时打码：会话票据不得落盘。 */
  dumpMasked() {
    const out = {};
    for (const [k, v] of this.store) {
      const [d, name] = k.split('\t');
      out[d] = out[d] || {};
      out[d][name] = `${v.slice(0, 4)}…<${String(v).length}chars>`;
    }
    return out;
  }

  headerFor(domain) {
    const parts = [];
    for (const [k, v] of this.store) {
      const [d, name] = k.split('\t');
      if (domain === d || domain.endsWith(`.${d}`) || d.endsWith(`.${domain}`)) {
        parts.push(`${name}=${v}`);
      }
    }
    return parts.join('; ');
  }

  /** 供外部复用（--cookie 模式） */
  seed(domain, cookieString) {
    for (const pair of cookieString.split(';')) {
      const eq = pair.indexOf('=');
      if (eq < 0) continue;
      this.store.set(this.#key(domain, pair.slice(0, eq).trim()), pair.slice(eq + 1).trim());
    }
  }

  dump() {
    const out = {};
    for (const [k, v] of this.store) {
      const [d, name] = k.split('\t');
      out[d] = out[d] || {};
      out[d][name] = v;
    }
    return out;
  }
}

const jar = new CookieJar();
/** @type {{url:string,method:string,status:number,location:string|null}[]} */
const trace = [];

/** 产物目录，main() 里确定后供各步骤写文件用 */
let OUT_DIR = path.join(process.cwd(), 'out');

function hostOf(url) {
  return new URL(url).hostname;
}

async function request(url, { method = 'GET', body, headers = {}, redirect = 'manual', tag } = {}) {
  const u = new URL(url);
  const h = {
    'User-Agent': UA,
    Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9',
    ...headers,
  };
  const cookie = jar.headerFor(hostOf(url));
  if (cookie) h.Cookie = cookie;

  let payload;
  if (body instanceof URLSearchParams) {
    payload = body.toString();
    h['Content-Type'] = h['Content-Type'] || 'application/x-www-form-urlencoded;charset=UTF-8';
  } else if (typeof body === 'string' || body instanceof Uint8Array) {
    payload = body;
  }

  if (payload) h['Content-Length'] = String(Buffer.byteLength(payload));

  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
  let res;
  try {
    res = await fetch(url, { method, headers: h, body: payload, redirect, signal: ac.signal });
  } finally {
    clearTimeout(timer);
  }

  const setCookies = typeof res.headers.getSetCookie === 'function'
    ? res.headers.getSetCookie()
    : [res.headers.get('set-cookie')].filter(Boolean);
  jar.absorb(hostOf(url), setCookies);

  trace.push({ tag: tag || '-', method, url: u.pathname + u.search, status: res.status, location: res.headers.get('location') });
  return res;
}

/** 跟随 302 直到拿到 200，返回最终 {url, res, text} */
async function follow(url, opts = {}, maxHops = 10) {
  let current = url;
  let method = opts.method || 'GET';
  let body = opts.body;
  for (let hop = 0; hop < maxHops; hop++) {
    const res = await request(current, { ...opts, method, body, redirect: 'manual', tag: opts.tag });
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get('location');
      if (!loc) throw new Error(`重定向缺少 Location: ${current}`);
      current = new URL(loc, current).href;
      method = 'GET';
      body = undefined;
      continue;
    }
    const text = await res.text();
    return { url: current, res, text };
  }
  throw new Error(`重定向次数超过上限: ${url}`);
}

// ---------------------------------------------------------------- HTML 工具

function decodeEntities(s) {
  return String(s)
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&amp;/gi, '&');
}

/** 返回表单内所有 input 的 name -> value（含 hidden） */
function collectInputs(html, formHtml) {
  const scope = formHtml || html;
  const out = {};
  const re = /<input\b[^>]*>/gi;
  let m;
  while ((m = re.exec(scope))) {
    const tag = m[0];
    const name = /\bname\s*=\s*["']([^"']*)["']/i.exec(tag)?.[1];
    if (!name) continue;
    const value = /\bvalue\s*=\s*["']([^"']*)["']/i.exec(tag)?.[1] ?? '';
    out[name] = decodeEntities(value);
  }
  return out;
}

/** 截取 id 匹配的表单 HTML；匹配不到则返回整页 */
function extractForm(html, idPattern) {
  const idx = html.search(new RegExp(`<form[^>]*id\\s*=\\s*["']${idPattern}["']`, 'i'));
  if (idx < 0) return null;
  const end = html.indexOf('</form>', idx);
  return end < 0 ? html.slice(idx) : html.slice(idx, end + 6);
}

function extractSalt(html) {
  for (const re of [
    /id\s*=\s*["']pwdEncryptSalt["'][^>]*value\s*=\s*["']([^"']*)["']/i,
    /value\s*=\s*["']([^"']*)["'][^>]*id\s*=\s*["']pwdEncryptSalt["']/i,
    /pwdEncryptSalt['"]\s*[:=]\s*['"]([^'"]+)['"]/i,
  ]) {
    const m = re.exec(html);
    if (m) return m[1];
  }
  return null;
}

// ---------------------------------------------------------------- 密码加密

const SALT_CHARS = 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678';

function randomString(n) {
  let s = '';
  for (let i = 0; i < n; i++) s += SALT_CHARS[crypto.randomInt(SALT_CHARS.length)];
  return s;
}

/**
 * 等价于正方 encrypt.js: AES-128-CBC / PKCS7 / 明文 = 随机64字符 + 密码
 * key = pwdEncryptSalt (16 ASCII 直接 UTF-8)，iv = 随机16字符，输出 base64
 */
function encryptPassword(password, salt) {
  const key = Buffer.from(salt, 'utf8');
  if (key.length !== 16) throw new Error(`pwdEncryptSalt 长度不是 16: ${key.length} (${salt})`);
  const iv = Buffer.from(randomString(16), 'utf8');
  const plain = Buffer.from(randomString(64) + password, 'utf8');
  const cipher = crypto.createCipheriv('aes-128-cbc', key, iv);
  return Buffer.concat([cipher.update(plain), cipher.final()]).toString('base64');
}

// ---------------------------------------------------------------- 流程

function log(...args) {
  console.log(...args);
}

function step(n, title) {
  log(`\n===== [${n}] ${title} =====`);
}

/** 直达 CAS 登录页；不复用新鲜度未知的会话，避免 execution 计数错位。 */
async function fetchLoginPage() {
  const url = `${AUTH_BASE}/authserver/login?service=${encodeURIComponent(SERVICE)}`;
  return follow(url, { tag: 'CAS登录页' });
}

async function loadLoginPage() {
  const fetched = await fetchLoginPage();
  return parseLoginPage(fetched);
}

/** 把“已取到的登录页响应”解析成提交所需的一切（响应必须新鲜，否则 execution 会过期）。 */
function parseLoginPage({ res, text, url: finalUrl }) {
  // 账号密码表单的 id 是 pwdFromId（不是 casLoginForm）。
  // 页面上另有 loginFromId(fido) / phoneFromId(动态码) / qrLoginForm，必须精确取用。
  const form = extractForm(text, 'pwdFromId');
  if (!form) {
    throw new Error(`未找到 pwdFromId 密码登录表单 (${finalUrl}, ${res.status}, ${text.length}B)`);
  }

  const fields = collectInputs(text, form);
  const salt = extractSalt(form) ?? extractSalt(text);

  // Spring CAS 必须知道要认证哪个 service，否则 500。
  // 页面里 action 是裸的 /authserver/login，靠 login.js 的
  // utils.setUrlParam("pwdFromId", "?service", ...) 补上参数，这里等价补上。
  const action = /\baction\s*=\s*["']([^"']*)["']/i.exec(form)?.[1];
  const service = new URL(finalUrl).searchParams.get('service');
  const postUrl = (() => {
    const u = new URL(action ? decodeEntities(action) : finalUrl, finalUrl);
    if (service && !u.searchParams.has('service')) u.searchParams.set('service', service);
    return u.href;
  })();

  log(`登录页 OK (${finalUrl}, ${text.length}B)`);
  log(`密码表单字段: ${Object.keys(fields).join(', ')}`);
  log(`execution: ${fields.execution ?? '(无)'}   pwdEncryptSalt: ${salt ?? '(无)'}`);
  log(`POST 目标: ${postUrl}`);
  return { fields, salt, finalUrl, html: text, postUrl, form };
}

/**
 * 服务端在登录前告知该账号本次是否需要验证码。
 * 必须先查：无法自动识别的图形验证码一旦提交失败会计入 5 次锁定。
 */
async function checkNeedCaptcha(username, referer) {
  const url = `${AUTH_BASE}/authserver/checkNeedCaptcha.htl?username=${encodeURIComponent(username)}&_=${Date.now()}`;
  const res = await request(url, {
    headers: { 'X-Requested-With': 'XMLHttpRequest', Referer: referer, Accept: 'application/json, text/javascript, */*; q=0.01' },
    tag: '验证码检查',
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON */ }
  log(`checkNeedCaptcha.htl -> HTTP ${res.status} ${text.slice(0, 200)}`);
  return { json, isNeed: json?.isNeed === true, isNeedActive: json?.isNeedActive === true };
}

async function doLogin(username, password, prefetched) {
  const page = prefetched ? parseLoginPage(prefetched) : await loadLoginPage();
  const { fields, salt, finalUrl, postUrl } = page;

  const need = await checkNeedCaptcha(username, finalUrl);
  if (need.isNeed) {
    throw new Error(
      '服务端要求图形验证码 (isNeed=true)。本工具不会盲试——' +
      '再次提交错误密码会计入 5 次锁定。请先用浏览器登录一次该账号，或稍后重试。',
    );
  }
  if (need.isNeedActive) {
    log('⚠️ 该账号需要在统一身份认证平台激活，但 isNeed=false，继续尝试登录。');
  }
  log('验证码: 不需要');

  const body = new URLSearchParams();
  // 先铺表单自带字段（_eventId / cllt / dllt / lt / execution ...）
  for (const [k, v] of Object.entries(fields)) {
    // passwordText 是明文输入框，提交流程里会被 disable 掉，不能提交
    if (k === 'username' || k === 'password' || k === 'passwordText' || k === 'captcha') continue;
    body.set(k, v);
  }
  if (!body.has('_eventId')) body.set('_eventId', 'submit');
  if (!body.has('cllt')) body.set('cllt', 'userNameLogin');
  if (!body.has('dllt')) body.set('dllt', 'generalLogin');
  if (!body.has('lt')) body.set('lt', '');
  if (!body.has('execution')) body.set('execution', 'e1s1');
  // captcha 字段存在时留空（服务端已确认不需要）
  if (Object.prototype.hasOwnProperty.call(fields, 'captcha')) body.set('captcha', '');

  body.set('username', username);
  // 关键：只有隐藏字段 password 携带 AES 密文；明文 passwordText 不提交
  body.set('password', salt ? encryptPassword(password, salt) : password);

  log(`提交字段: ${[...body.keys()].join(', ')}`);
  log(`password 密文长度: ${body.get('password').length} (明文 ${password.length})`);

  // 完整请求体落盘（密码一律打码），便于对照 500 排查
  const dumpBody = new URLSearchParams(body);
  dumpBody.set('password', `<AES:${body.get('password').length}chars>`);
  fs.writeFileSync(path.join(OUT_DIR, 'login-request.json'), JSON.stringify({
    postUrl,
    referer: finalUrl,
    salt,
    bodyFields: Object.fromEntries(dumpBody),
  }, null, 2), 'utf8');

  const res = await request(postUrl, {
    method: 'POST',
    body,
    redirect: 'manual',
    headers: {
      Referer: finalUrl,
      Origin: AUTH_BASE,
      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
      'Upgrade-Insecure-Requests': '1',
    },
    tag: 'CAS提交',
  });

  const location = res.headers.get('location');
  log(`POST 结果: HTTP ${res.status}  Location=${location ?? '(无)'}`);

  if (res.status === 401) throw new Error('登录失败：账号或密码错误 (HTTP 401) —— 已停止，不再重试');
  if (res.status >= 500) {
    const text = await res.text();
    fs.writeFileSync(path.join(OUT_DIR, 'login-error.html'), text, 'utf8');
    const title = /<title>([^<]*)<\/title>/i.exec(text)?.[1]?.trim();
    const msg = /(?:exception|message|错误|失败)[^<>]{0,200}/i.exec(text.replace(/\s+/g, ' '))?.[0];
    log(`错误响应已存 login-error.html (${text.length}B)  title=${title ?? '(无)'}`);
    log(`片段: ${text.replace(/\s+/g, ' ').slice(0, 800)}`);
    throw new Error(`服务端错误 HTTP ${res.status}${title ? ` (${title})` : ''}${msg ? ` ${msg}` : ''}`);
  }
  if (res.status === 200 || !location) {
    const text = await res.text();
    fs.writeFileSync(path.join(OUT_DIR, 'login-error.html'), text, 'utf8');
    const msgRe = /id\s*=\s*["']showErrorTip["'][^>]*>([^<]*)</i;
    const msg = msgRe.exec(text)?.[1] || /msg\s*=\s*["']([^"']+)["']/i.exec(text)?.[1] || '(未知)';
    throw new Error(`登录未跳转：HTTP ${res.status} 提示=${decodeEntities(msg).trim()}`);
  }
  return location;
}

async function enterJwglxt(location) {
  // location 形如 https://tdjw.ntu.edu.cn/sso/jziotlogin?ticket=ST-xxx
  const { res, text, url } = await follow(location, { tag: 'SSO回跳' });
  log(`最终页面: ${url}  HTTP ${res.status}  ${text.length} bytes`);
  const title = /<title>([^<]*)<\/title>/i.exec(text)?.[1]?.trim();
  log(`标题: ${title ?? '(无)'}`);
  if (/login_slogin|统一身份认证/.test(text) && text.length < 40000) {
    if (/统一身份认证/.test(title || '')) throw new Error('回跳后又回到登录页，SSO 未建立会话');
  }
  return { url, text };
}

async function fetchScheduleHtml() {
  const url = `${JW_BASE}/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default`;
  const { res, text, url: finalUrl } = await follow(url, { tag: '课表页面' });
  log(`课表页: ${finalUrl}  HTTP ${res.status}  ${text.length} bytes`);
  return { url: finalUrl, html: text };
}

/** 从课表页 HTML 里抠出 xnm / xqm 的候选值 + 当前选中项 */
function extractSelectOptions(html) {
  const pick = (id) => {
    const m = new RegExp(`<select[^>]*id\\s*=\\s*["']${id}["'][\\s\\S]*?</select>`, 'i').exec(html);
    if (!m) return [];
    const opts = [];
    const re = /<option\b([^>]*)>([\s\S]*?)<\/option>/gi;
    let o;
    while ((o = re.exec(m[0]))) {
      const attrs = o[1];
      const value = /\bvalue\s*=\s*["']([^"']*)["']/i.exec(attrs)?.[1] ?? '';
      opts.push({
        value: decodeEntities(value),
        label: decodeEntities(o[2]).replace(/\s+/g, ' ').trim(),
        selected: /\bselected\b/i.test(attrs),
      });
    }
    return opts;
  };
  const xnm = pick('xnm');
  const xqm = pick('xqm');
  return {
    xnm,
    xqm,
    // 页面自带的选中值最可信，优先用它
    selectedXnm: xnm.find((o) => o.selected)?.value,
    selectedXqm: xqm.find((o) => o.selected)?.value,
  };
}

async function postForm(pathAndQuery, body, referer) {
  const url = pathAndQuery.startsWith('http') ? pathAndQuery : `${JW_BASE}${pathAndQuery}`;
  const res = await request(url, {
    method: 'POST',
    body: new URLSearchParams(body),
    redirect: 'manual',
    headers: {
      'X-Requested-With': 'XMLHttpRequest',
      Accept: 'application/json, text/javascript, */*; q=0.01',
      Referer: referer || `${JW_BASE}/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151`,
      Origin: JW_BASE,
    },
    tag: '接口',
  });
  const text = await res.text();
  return { status: res.status, text, contentType: res.headers.get('content-type') };
}

// ---------------------------------------------------------------- 主流程

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next && !next.startsWith('--')) { out[key] = next; i++; } else { out[key] = true; }
    }
  }
  return out;
}

async function main() {
  const args = parseArgs(process.argv);
  const outDir = path.resolve(args.out || path.join(process.cwd(), 'out'));
  fs.mkdirSync(outDir, { recursive: true });
  OUT_DIR = outDir;

  let username = args.user;
  let password = args.pass;
  if (!username && process.env.NTU_USER) username = process.env.NTU_USER;
  if (!password && process.env.NTU_PASS) password = process.env.NTU_PASS;

  if (args.cookie) {
    step(2, '复用已有会话 Cookie');
    jar.seed('tdjw.ntu.edu.cn', String(args.cookie));
    log('已注入 tdjw Cookie，跳过登录');
  } else {
    if (!username || !password) {
      console.error('\n缺少账号密码。用法: node scrape-schedule.mjs --user 学号 --pass 密码');
      process.exit(2);
    }
    step(2, 'CAS 登录');
    // 注意：不再先用 /jwglxt 探测。CAS 的 execution 是会话级递增的 CSRF token，
    // 任何一次额外的登录页 GET 都会把它推到 e2s1/e3s1…，导致提交时校验失败。
    // 这里只走一次官方入口链（与浏览器地址栏访问 jziotlogin 完全一致），
    // 用同一份响应取出表单字段后立刻提交。
    const lp = await fetchLoginPage();
    log(`入口(经 jziotlogin 直达): ${lp.url}  HTTP ${lp.res.status}  ${lp.text.length}B`);
    const ticketUrl = await doLogin(username, password, lp);
    step(3, 'SSO 回跳教务');
    await enterJwglxt(ticketUrl);
  }

  step(4, '打开个人课表页面');
  const page = await fetchScheduleHtml();
  fs.writeFileSync(path.join(outDir, '课表页面.html'), page.html, 'utf8');
  log(`已保存 ${path.join(outDir, '课表页面.html')}`);

  const opts = extractSelectOptions(page.html);
  log(`学年选项 (${opts.xnm.length}): ${opts.xnm.map((o) => `${o.value}=${o.label}${o.selected ? '*' : ''}`).join(' | ')}`);
  log(`学期选项 (${opts.xqm.length}): ${opts.xqm.map((o) => `${o.value}=${o.label}${o.selected ? '*' : ''}`).join(' | ')}`);
  log(`当前选中: xnm=${opts.selectedXnm ?? '(无)'}  xqm=${opts.selectedXqm ?? '(无)'}   (* 标记为页面选中项)`);

  const formAction = /id\s*=\s*["']ajaxForm["'][^>]*action\s*=\s*["']([^"']*)["']/i.exec(page.html)?.[1]
    || /action\s*=\s*["']([^"']*xskbcx[^"']*)["']/i.exec(page.html)?.[1]
    || '';
  log(`ajaxForm action: ${formAction || '(未找到，将用默认接口)'}`);

  // 优先用页面自己选中的学年/学期；缺失时才回退
  const xnm = args.xnm || opts.selectedXnm || opts.xnm.at(-1)?.value || new Date().getFullYear();
  const xqm = args.xqm || opts.selectedXqm || opts.xqm.at(-1)?.value || '3';
  // 正方 xqm 编码：3=第一学期 12=第二学期 16=第三学期
  log(`\n使用 xnm=${xnm}  xqm=${xqm}`);

  step(5, '请求个人课表接口');
  const candidateBodies = [
    { xnm, xqm, kzlx: 'ck' },
    { xnm, xqm, kzlx: 'ck', xsdm: '', kclbdm: '' },
  ];
  const candidatePaths = [
    '/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151',
    '/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151',
  ];

  let success = null;
  for (const p of candidatePaths) {
    for (const b of candidateBodies) {
      const r = await postForm(p, b, page.url);
      const preview = r.text.slice(0, 120).replace(/\s+/g, ' ');
      log(`POST ${p} body=${JSON.stringify(b)} -> HTTP ${r.status} ct=${r.contentType} len=${r.text.length}`);
      log(`   preview: ${preview}`);
      if (r.status === 200 && r.text.trim().startsWith('{')) {
        success = { path: p, body: b, ...r };
        break;
      }
    }
    if (success) break;
  }

  if (!success) {
    log('\n⚠️  课表接口未返回 JSON。可能原因：未进入选课/课表轮次、参数不符、或需要不同的接口路径。');
  } else {
    let parsed;
    try { parsed = JSON.parse(success.text); } catch (e) { parsed = null; }
    fs.writeFileSync(path.join(outDir, '课表接口响应.json'), JSON.stringify(parsed ?? success.text, null, 2), 'utf8');
    const list = parsed?.kbList || parsed?.datas?.kbList || parsed?.rows;
    log(`\n✅ 接口成功: ${success.path}`);
    log(`   顶层字段: ${parsed ? Object.keys(parsed).join(', ') : '(非JSON)'}`);
    log(`   课程条数: ${Array.isArray(list) ? list.length : '(未识别)'}`);
    if (Array.isArray(list) && list.length) {
      log(`   单条字段: ${Object.keys(list[0]).join(', ')}`);
      log(`\n   前 3 条原始数据:`);
      log(JSON.stringify(list.slice(0, 3), null, 2));
      const weekFields = new Set();
      for (const it of list) for (const k of Object.keys(it)) weekFields.add(k);
      log(`\n   全部字段并集 (${weekFields.size}): ${[...weekFields].join(', ')}`);
    }
    log(`\n   已保存 ${path.join(outDir, '课表接口响应.json')}`);
  }

  step(6, '请求校历/开学日期接口');
  const zc = await postForm('/jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N2154', { xnm, xqm }, page.url);
  log(`HTTP ${zc.status} len=${zc.text.length}`);
  log(`preview: ${zc.text.slice(0, 600).replace(/\s+/g, ' ')}`);
  try {
    fs.writeFileSync(path.join(outDir, '校历接口响应.json'), JSON.stringify(JSON.parse(zc.text), null, 2), 'utf8');
    log(`已保存 ${path.join(outDir, '校历接口响应.json')}`);
  } catch { /* 非 JSON 就不存 */ }

  // 只写请求轨迹；cookie 一律打码，会话票据不落盘
  fs.writeFileSync(
    path.join(outDir, '请求轨迹.json'),
    JSON.stringify({ trace, cookiesMasked: jar.dumpMasked() }, null, 2),
    'utf8',
  );
  step(7, '请求轨迹');
  for (const t of trace) log(`${String(t.status).padEnd(4)} ${t.method.padEnd(4)} ${t.url}${t.location ? `  ->  ${t.location}` : ''}`);
  log(`\n产物目录: ${outDir}`);
}

main().catch((e) => {
  console.error(`\n❌ 失败: ${e.message}`);
  if (trace.length) {
    console.error('\n请求轨迹:');
    for (const t of trace) console.error(`  ${t.status} ${t.method} ${t.url}${t.location ? ` -> ${t.location}` : ''}`);
  }
  process.exit(1);
});
