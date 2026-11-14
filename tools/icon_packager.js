#!/usr/bin/env node
/**
 * 定制图标打包脚本：用户发图 → 生成 App 图标 → 打包 APK → 回信分发。
 *
 * 零 npm 依赖：邮件收发用 node:tls 裸协议实现（IMAP/SMTP），
 * 图像处理调用同目录的 IconResizer.java（JDK 自带 ImageIO/Java2D）。
 *
 * 两种收图方式：
 * 1. QQ 邮箱（--from-email）：读取未读邮件，抓取图片附件，按发件人分组，
 *    每个发件人打一个定制包并自动回信附上 APK；
 * 2. 本地目录（--from-dir）：微信收到的图手动保存到一个目录后运行
 *    （微信没有开放接口，收图这一步只能手动）。
 *
 * 用法：
 *   node tools/icon_packager.js --from-email
 *   node tools/icon_packager.js --from-dir D:\pics\张三 --name 张三
 *   node tools/icon_packager.js --from-dir D:\pics\张三 --name 张三 --reply-to zhangsan@qq.com
 *
 * 首次使用：复制 icon_packager.config.example.json 为 icon_packager.config.json，
 * 填入 QQ 邮箱地址与授权码（QQ 邮箱 → 设置 → 账户 → POP3/IMAP/SMTP 服务 → 生成授权码）。
 */

'use strict';

const tls = require('tls');
const fs = require('fs');
const path = require('path');
const { execFileSync, execSync } = require('child_process');

// ---------------------------------------------------------------------------
// 路径与常量
// ---------------------------------------------------------------------------

const SCRIPT_DIR = __dirname;
const PROJECT_ROOT = path.dirname(SCRIPT_DIR);
const CONFIG_PATH = path.join(SCRIPT_DIR, 'icon_packager.config.json');
const INBOX_DIR = path.join(SCRIPT_DIR, 'inbox');
const DIST_DIR = path.join(SCRIPT_DIR, 'dist');
const RES_DIR = path.join(PROJECT_ROOT, 'app', 'src', 'main', 'res');
const CUSTOM_ICONS_ASSET = path.join(PROJECT_ROOT, 'app', 'src', 'main', 'assets', 'custom_icons');
const APK_DIR_DEBUG = path.join(PROJECT_ROOT, 'app', 'build', 'outputs', 'apk', 'debug');
const APK_DIR_RELEASE = path.join(PROJECT_ROOT, 'app', 'build', 'outputs', 'apk', 'release');
const VERSION_PROPS = path.join(PROJECT_ROOT, 'version.properties');
const SIGNING_PROPS = path.join(PROJECT_ROOT, 'signing.properties');
const RESIZER_JAVA = path.join(SCRIPT_DIR, 'IconResizer.java');
const RESIZER_CLASS = path.join(SCRIPT_DIR, 'IconResizer.class');

/** 会被替换的既有图标资源（打包完成后原样还原，避免污染主工程） */
const EXISTING_ICON_FILES = [
  path.join(RES_DIR, 'mipmap-anydpi-v26', 'ic_launcher.xml'),
  path.join(RES_DIR, 'mipmap-anydpi-v26', 'ic_launcher_round.xml'),
];
for (const dpi of ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']) {
  EXISTING_ICON_FILES.push(path.join(RES_DIR, `mipmap-${dpi}`, 'ic_launcher.png'));
  EXISTING_ICON_FILES.push(path.join(RES_DIR, `mipmap-${dpi}`, 'ic_launcher_round.png'));
}

/** 打包时新建、完成后删除的文件/目录 */
const GENERATED_PATHS = [
  path.join(RES_DIR, 'drawable-nodpi', 'ic_custom_fg.png'),
  path.join(RES_DIR, 'values', 'icon_custom_colors.xml'),
  CUSTOM_ICONS_ASSET,
];

/**
 * 定制包额外替换的图标资源（原文件是 vector xml，打包时换成用户图片的 PNG）：
 * 让应用内默认图标、启动页 Logo、以及"我的图片 2/3/4"对应的风格入口图标一起变成用户图片。
 */
const STYLE_DRAWABLE_MAP = [
  { name: 'ic_launcher_art_whale_moon', imgIndex: 0 },
  { name: 'ic_splash_logo', imgIndex: 0 },
  { name: 'ic_icon_style_moon', imgIndex: 1 },
  { name: 'ic_icon_style_star', imgIndex: 2 },
  { name: 'ic_icon_style_violet', imgIndex: 3 },
];
for (const item of STYLE_DRAWABLE_MAP) {
  EXISTING_ICON_FILES.push(path.join(RES_DIR, 'drawable', item.name + '.xml'));
  GENERATED_PATHS.push(path.join(RES_DIR, 'drawable', item.name + '.png'));
}

const IMAGE_EXTS = new Set(['.jpg', '.jpeg', '.png', '.webp', '.bmp', '.gif']);
const CMD_TIMEOUT_MS = 60000;

// ---------------------------------------------------------------------------
// 工具函数
// ---------------------------------------------------------------------------

function log(msg) {
  console.log(`[${new Date().toLocaleTimeString()}] ${msg}`);
}

function loadConfig() {
  if (!fs.existsSync(CONFIG_PATH)) {
    console.error(`配置文件不存在：${CONFIG_PATH}\n请复制 icon_packager.config.example.json 并填入邮箱与授权码。`);
    process.exit(1);
  }
  const cfg = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  for (const key of ['email_account', 'email_auth_code']) {
    if (!cfg[key]) {
      if (key === 'email_auth_code') {
        console.error(`请打开配置文件填入 QQ 邮箱授权码：${CONFIG_PATH}\n`
          + '获取方法：QQ邮箱网页版 → 设置 → 账户 → 开启 IMAP/SMTP 服务 → 生成授权码（不是登录密码）');
      } else {
        console.error(`配置文件缺少字段：${key}`);
      }
      process.exit(1);
    }
  }
  return cfg;
}

function sanitizeFileName(name) {
  return (name.replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 40)) || 'user';
}

/** RFC 2047 编码词解码（=?UTF-8?B?...?= / =?UTF-8?Q?...?=），用于邮件头 */
function decodeHeaderValue(raw) {
  return raw.replace(/=\?([^?]+)\?([BbQq])\?([^?]*)\?=/g, (m, charset, enc, text) => {
    if (enc.toUpperCase() === 'B') {
      return Buffer.from(text, 'base64').toString('utf8');
    }
    return Buffer.from(text.replace(/_/g, ' ').replace(/=([0-9A-Fa-f]{2})/g,
      (mm, hex) => String.fromCharCode(parseInt(hex, 16))), 'binary').toString('utf8');
  });
}

function encodeWord(text) {
  return `=?UTF-8?B?${Buffer.from(text, 'utf8').toString('base64')}?=`;
}

// ---------------------------------------------------------------------------
// 极简 TLS 行协议客户端（IMAP / SMTP 共用底层）
// ---------------------------------------------------------------------------

class TlsLineClient {
  constructor(host, port) {
    this.host = host;
    this.port = port;
    this.buffer = Buffer.alloc(0);
    this.waiters = [];
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.socket = tls.connect(this.port, this.host, { rejectUnauthorized: true }, () => resolve());
      this.socket.setTimeout(CMD_TIMEOUT_MS, () => {
        this.socket.destroy(new Error(`连接 ${this.host}:${this.port} 超时`));
      });
      this.socket.on('data', (chunk) => {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        this.flush();
      });
      this.socket.on('error', reject);
    });
  }

  /** 等待满足条件的响应数据；matcher 返回非 null 时表示匹配成功 */
  expect(matcher) {
    return new Promise((resolve, reject) => {
      this.waiters.push({ matcher, resolve, reject });
      this.flush();
      setTimeout(() => reject(new Error(`${this.host} 响应超时`)), CMD_TIMEOUT_MS);
    });
  }

  flush() {
    if (this.waiters.length === 0) return;
    const { matcher, resolve } = this.waiters[0];
    const result = matcher(this.buffer);
    if (result !== null) {
      this.waiters.shift();
      this.buffer = this.buffer.slice(result.consumed);
      resolve(result.value);
    }
  }

  write(text) {
    this.socket.write(text + '\r\n');
  }

  end() {
    try { this.socket.end(); } catch (e) { /* 忽略 */ }
  }
}

// ---------------------------------------------------------------------------
// IMAP：收 QQ 邮件图片附件
// ---------------------------------------------------------------------------

class ImapClient {
  constructor(cfg) {
    this.cfg = cfg;
    this.tagSeq = 0;
  }

  async connect() {
    const host = this.cfg.imap_host || 'imap.qq.com';
    this.client = new TlsLineClient(host, 993);
    await this.client.connect();
    await this.client.expect(matchLine(/^\* OK/mi));
    await this.command(`LOGIN "${this.cfg.email_account}" "${this.cfg.email_auth_code}"`);
    await this.command('SELECT INBOX');
  }

  nextTag() {
    return `A${String(++this.tagSeq).padStart(4, '0')}`;
  }

  /** 发送命令并等待 tagged 响应（含字面量的多行响应也能正确处理） */
  command(cmd) {
    const tag = this.nextTag();
    const re = new RegExp(`${tag} (OK|NO|BAD)[^\\r\\n]*\\r\\n`, 'i');
    this.client.write(cmd);
    return this.client.expect((buf) => {
      const text = buf.toString('latin1');
      const m = text.match(re);
      if (!m) return null;
      // consumed 按 latin1 1:1 映射字节数
      const consumed = m.index + m[0].length;
      if (m[1].toUpperCase() !== 'OK') {
        throw new Error(`IMAP 命令失败: ${cmd.split(' ')[0]} → ${m[0].trim()}`);
      }
      return { consumed, value: buf.slice(0, consumed) };
    });
  }

  /** 搜索未读邮件 id 列表 */
  async searchUnseen() {
    const res = await this.command('SEARCH UNSEEN');
    const text = res.toString('latin1');
    const m = text.match(/\* SEARCH((?:\s+\d+)*)/i);
    return m ? m[1].trim().split(/\s+/).filter(Boolean) : [];
  }

  /** 拉取整封邮件原文（处理 {n} 字面量） */
  async fetchMessage(id) {
    const res = await this.command(`FETCH ${id} BODY[]`);
    // 提取字面量：...{n}\r\n<n 字节>
    const marker = res.toString('latin1').match(/\{(\d+)\}\r\n/);
    if (!marker) return null;
    const start = marker.index + marker[0].length;
    const size = parseInt(marker[1], 10);
    return res.slice(start, start + size);
  }

  async markSeen(id) {
    await this.command(`STORE ${id} +FLAGS (\\Seen)`);
  }

  async logout() {
    try { await this.command('LOGOUT'); } catch (e) { /* 忽略 */ }
    this.client.end();
  }
}

/** 匹配到某一行（含该行）即返回 */
function matchLine(re) {
  return (buf) => {
    const text = buf.toString('latin1');
    const m = text.match(re);
    if (!m) return null;
    const end = buf.indexOf('\r\n', m.index);
    const consumed = end >= 0 ? end + 2 : buf.length;
    return { consumed, value: text.slice(m.index, consumed) };
  };
}

// ---------------------------------------------------------------------------
// MIME 解析：提取图片附件与发件人
// ---------------------------------------------------------------------------

/** 解析邮件头块为键值对（小写键名，自动折行展开） */
function parseHeaders(headerText) {
  const headers = {};
  const lines = headerText.split(/\r?\n/);
  let lastKey = null;
  for (const line of lines) {
    if (/^[ \t]/.test(line) && lastKey) {
      headers[lastKey] += ' ' + line.trim();
    } else {
      const idx = line.indexOf(':');
      if (idx > 0) {
        lastKey = line.slice(0, idx).trim().toLowerCase();
        headers[lastKey] = line.slice(idx + 1).trim();
      }
    }
  }
  return headers;
}

/** 从原始邮件字节中提取发件人与图片附件 */
function extractImages(raw, saveDir) {
  // 头与正文以空行分隔（字节级）
  const sep = raw.indexOf('\r\n\r\n');
  if (sep < 0) return null;
  const headerText = raw.slice(0, sep).toString('latin1');
  const headers = parseHeaders(headerText);

  const fromRaw = decodeHeaderValue(headers.from || '');
  const addrMatch = fromRaw.match(/[\w.+-]+@[\w-]+(?:\.[\w-]+)+/);
  const sender = addrMatch ? addrMatch[0].toLowerCase() : null;

  const contentType = headers['content-type'] || '';
  const boundaryMatch = contentType.match(/boundary="?([^";]+)"?/i);
  const images = [];

  if (boundaryMatch) {
    const boundary = '--' + boundaryMatch[1];
    const body = raw.slice(sep + 4);
    // 按边界切分（字节级，boundary 只含 ASCII，latin1 安全）
    const parts = body.toString('latin1').split(boundary);
    let imgSeq = 0;
    for (const partText of parts) {
      if (!partText || partText.startsWith('--')) continue;
      const partBuf = Buffer.from(partText, 'latin1');
      const pSep = partBuf.indexOf('\r\n\r\n');
      if (pSep < 0) continue;
      const pHeaders = parseHeaders(partBuf.slice(0, pSep).toString('latin1'));
      const pType = (pHeaders['content-type'] || '').toLowerCase();
      const pName = pHeaders['content-disposition'] || pHeaders['content-type'] || '';
      const fileMatch = pName.match(/filename="?([^";\r\n]+)"?/i);
      const fileName = fileMatch ? decodeHeaderValue(fileMatch[1]) : '';
      const isImage = pType.startsWith('image/')
        || (fileName && IMAGE_EXTS.has(path.extname(fileName).toLowerCase()));
      if (!isImage) continue;

      let payload = partBuf.slice(pSep + 4);
      // 去掉结尾的 \r\n
      if (payload.length >= 2 && payload[payload.length - 2] === 13 && payload[payload.length - 1] === 10) {
        payload = payload.slice(0, payload.length - 2);
      }
      const encoding = (pHeaders['content-transfer-encoding'] || '').toLowerCase();
      let data;
      if (encoding === 'base64') {
        data = Buffer.from(payload.toString('latin1').replace(/\s+/g, ''), 'base64');
      } else {
        data = payload; // binary / 8bit 直接落盘
      }
      if (data.length < 1024) continue; // 过小的多半是签名档小图标，跳过

      fs.mkdirSync(saveDir, { recursive: true });
      const ext = path.extname(fileName).toLowerCase() || '.jpg';
      const savePath = path.join(saveDir, `${Date.now()}_${imgSeq++}${ext}`);
      fs.writeFileSync(savePath, data);
      images.push(savePath);
    }
  }
  return { sender, images };
}

/** 收 QQ 邮件：返回 [{sender, images, mailId}] */
async function collectFromEmail(cfg) {
  const imap = new ImapClient(cfg);
  await imap.connect();
  const ids = await imap.searchUnseen();
  log(`未读邮件 ${ids.length} 封`);

  const mails = [];
  for (const id of ids) {
    try {
      const raw = await imap.fetchMessage(id);
      if (!raw) continue;
      const result = extractImages(raw, path.join(INBOX_DIR, 'mail_' + id));
      if (!result || !result.sender) {
        log(`  邮件 ${id}: 无发件人，跳过`);
        continue;
      }
      if (result.images.length === 0) {
        log(`  ${result.sender}: 无图片附件，跳过`);
        continue;
      }
      log(`  ${result.sender}: ${result.images.length} 张图片`);
      mails.push({ sender: result.sender, images: result.images, mailId: id });
    } catch (e) {
      log(`  邮件 ${id} 解析失败：${e.message}`);
    }
  }

  // 按发件人聚合（一人多封合并成一个定制包）
  const grouped = new Map();
  for (const mail of mails) {
    if (!grouped.has(mail.sender)) {
      grouped.set(mail.sender, { sender: mail.sender, images: [], mailIds: [] });
    }
    const entry = grouped.get(mail.sender);
    entry.images.push(...mail.images);
    entry.mailIds.push(mail.mailId);
  }

  return { imap, grouped: [...grouped.values()] };
}

function collectFromDir(dir) {
  const files = fs.readdirSync(dir)
    .filter((f) => IMAGE_EXTS.has(path.extname(f).toLowerCase()))
    .sort()
    .map((f) => path.join(dir, f));
  if (files.length === 0) {
    console.error(`目录里没有图片：${dir}`);
    process.exit(1);
  }
  log(`目录图片 ${files.length} 张`);
  return files;
}

// ---------------------------------------------------------------------------
// 图标生成（调用 IconResizer.java）
// ---------------------------------------------------------------------------

function ensureResizerCompiled() {
  if (fs.existsSync(RESIZER_CLASS)
    && fs.statSync(RESIZER_CLASS).mtimeMs >= fs.statSync(RESIZER_JAVA).mtimeMs) {
    return;
  }
  log('编译 IconResizer.java …');
  execFileSync('javac', ['-encoding', 'UTF-8', '-d', SCRIPT_DIR, RESIZER_JAVA], { stdio: 'inherit' });
}

function applyIcons(images) {
  ensureResizerCompiled();
  // 第一张图 → 桌面图标资源
  execFileSync('java', ['-cp', SCRIPT_DIR, 'IconResizer', 'launcher', images[0], RES_DIR],
    { stdio: 'inherit' });
  // 全部图片 → assets/custom_icons 图标集（定制包里图标选项只含用户图片）
  fs.rmSync(CUSTOM_ICONS_ASSET, { recursive: true, force: true });
  fs.mkdirSync(CUSTOM_ICONS_ASSET, { recursive: true });
  const manifest = [];
  images.forEach((img, i) => {
    const name = `icon_${i + 1}.png`;
    execFileSync('java', ['-cp', SCRIPT_DIR, 'IconResizer', 'asset', img,
      path.join(CUSTOM_ICONS_ASSET, name)], { stdio: 'inherit' });
    manifest.push(name);
  });
  fs.writeFileSync(path.join(CUSTOM_ICONS_ASSET, 'icons.json'),
    JSON.stringify(manifest), 'utf8');
  // 记录原始文件名，App 内作为图标默认名称展示
  fs.writeFileSync(path.join(CUSTOM_ICONS_ASSET, 'names.json'),
    JSON.stringify(images.map((p) => path.basename(p, path.extname(p)))), 'utf8');

  // 应用内默认图标、启动页 Logo、预设风格入口图标也换成用户图片
  for (const item of STYLE_DRAWABLE_MAP) {
    const img = images[item.imgIndex];
    if (!img) {
      continue;
    }
    const xml = path.join(RES_DIR, 'drawable', item.name + '.xml');
    fs.rmSync(xml, { force: true });
    execFileSync('java', ['-cp', SCRIPT_DIR, 'IconResizer', 'square', img,
      path.join(RES_DIR, 'drawable', item.name + '.png'), '512'], { stdio: 'inherit' });
  }
}

function backupExisting() {
  const backup = new Map();
  for (const file of EXISTING_ICON_FILES) {
    backup.set(file, fs.existsSync(file) ? fs.readFileSync(file) : null);
  }
  return backup;
}

function restoreIcons(backup) {
  for (const [file, content] of backup) {
    if (content === null) {
      fs.rmSync(file, { force: true });
    } else {
      fs.writeFileSync(file, content);
    }
  }
  for (const p of GENERATED_PATHS) {
    fs.rmSync(p, { recursive: true, force: true });
  }
  // assets 目录本就是定制打包临时创建的，清空后顺手移除空目录
  try { fs.rmdirSync(path.dirname(CUSTOM_ICONS_ASSET)); } catch (e) { /* 非空则忽略 */ }
  log('工程图标资源已还原');
}

// ---------------------------------------------------------------------------
// 打包
// ---------------------------------------------------------------------------

function runAssemble(release) {
  const gradlew = path.join(PROJECT_ROOT, 'gradlew.bat');
  const task = release ? 'assembleRelease' : 'assembleDebug';
  try {
    execSync(`"${gradlew}" ${task} --offline -q`, { cwd: PROJECT_ROOT, stdio: 'inherit' });
    return true;
  } catch (e) {
    return false;
  }
}

/**
 * 打包前先 clean：避免 Gradle 因输入未变化判定 UP-TO-DATE、复用旧 APK。
 * 每次定制都强制重新生成，代价是多花一点构建时间。
 */
function runClean() {
  const gradlew = path.join(PROJECT_ROOT, 'gradlew.bat');
  try {
    log('清理上次构建产物（clean）…');
    execSync(`"${gradlew}" clean --offline -q`, { cwd: PROJECT_ROOT, stdio: 'inherit' });
    return true;
  } catch (e) {
    // clean 失败不阻断打包，交给后续 assemble 与产物检查兜底
    log('clean 失败，将继续尝试直接打包：' + e.message);
    return false;
  }
}

/** 读取 version.properties 中的 VERSION_NAME，用于输出文件名 */
function readVersionName() {
  try {
    const text = fs.readFileSync(VERSION_PROPS, 'utf8');
    const m = text.match(/^VERSION_NAME\s*=\s*(.+)$/m);
    return m ? m[1].trim() : 'unknown';
  } catch (e) {
    return 'unknown';
  }
}

/** 判断 release 签名配置是否就绪（signing.properties 或 YUELAN_* 环境变量） */
function hasReleaseSigningConfig() {
  const env = (name) => (process.env[name] || '').trim();
  const props = {};
  try {
    const text = fs.readFileSync(SIGNING_PROPS, 'utf8');
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([^#=\s][^=]*)=(.*)$/);
      if (m) props[m[1].trim()] = m[2].trim();
    }
  } catch (e) {
    // 没有 signing.properties 时只依赖环境变量
  }
  const pick = (key, envName) => env(envName) || props[key] || '';
  const storeFile = pick('storeFile', 'YUELAN_STORE_FILE');
  const storePassword = pick('storePassword', 'YUELAN_STORE_PASSWORD');
  const keyAlias = pick('keyAlias', 'YUELAN_KEY_ALIAS');
  const keyPassword = pick('keyPassword', 'YUELAN_KEY_PASSWORD');
  if (!storeFile || !storePassword || !keyAlias || !keyPassword) {
    return false;
  }
  return fs.existsSync(path.isAbsolute(storeFile)
    ? storeFile
    : path.join(PROJECT_ROOT, storeFile));
}

/** 在指定构建目录里取最新生成的 APK */
function findLatestApk(dir, startedAt) {
  if (!fs.existsSync(dir)) return null;
  const apks = fs.readdirSync(dir)
    .filter((f) => f.toLowerCase().endsWith('.apk'))
    .map((f) => path.join(dir, f))
    .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  // 优先取本次构建新写出的 APK；若 Gradle 因输入未变化判定 UP-TO-DATE
  // 而没有重写文件，则回退到目录中最新的 APK（构建已成功，该产物仍然有效）
  const fresh = apks.filter((p) => fs.statSync(p).mtimeMs >= startedAt - 2000);
  if (fresh.length > 0) return fresh[0];
  return apks.length > 0 ? apks[0] : null;
}

function buildApk(release) {
  if (release && !hasReleaseSigningConfig()) {
    log('release 打包需要签名配置：请复制 signing.properties.example 为 signing.properties 并填写 keystore，');
    log('或设置 YUELAN_STORE_FILE / YUELAN_STORE_PASSWORD / YUELAN_KEY_ALIAS / YUELAN_KEY_PASSWORD 环境变量。');
    return null;
  }
  log(`开始打包（${release ? 'assembleRelease' : 'assembleDebug'}）…`);
  // 每次定制打包都先 clean，确保本次一定重新生成 APK，不复用上次产物
  runClean();
  const startedAt = Date.now();
  if (!runAssemble(release)) {
    // aapt2 守护进程偶发起不来（通常残留进程/杀软干扰），清掉守护进程后重试一次
    log('首次打包失败，清理 Gradle 守护进程后重试一次…');
    try {
      execSync(`"${path.join(PROJECT_ROOT, 'gradlew.bat')}" --stop`, { cwd: PROJECT_ROOT, stdio: 'ignore' });
    } catch (e) {
      // 清理失败不影响后续重试
    }
    if (!runAssemble(release)) {
      log('打包失败');
      return null;
    }
  }
  const apkDir = release ? APK_DIR_RELEASE : APK_DIR_DEBUG;
  const apk = findLatestApk(apkDir, startedAt);
  if (!apk) {
    log(`打包失败：${apkDir} 下未生成 APK`);
    return null;
  }
  if (release && /unsigned/i.test(path.basename(apk))) {
    log('打包失败：release APK 未签名，无法安装。请检查 signing.properties 配置。');
    return null;
  }
  log(`打包完成：${apk}`);
  return apk;
}

// ---------------------------------------------------------------------------
// SMTP：回信分发 APK
// ---------------------------------------------------------------------------

class SmtpClient {
  constructor(cfg) {
    this.cfg = cfg;
  }

  async connect() {
    const host = this.cfg.smtp_host || 'smtp.qq.com';
    this.client = new TlsLineClient(host, 465);
    await this.client.connect();
    await this.expectCode('220');
    await this.cmd(`EHLO ${host}`, '250');
    await this.cmd('AUTH LOGIN', '334');
    await this.cmd(Buffer.from(this.cfg.email_account).toString('base64'), '334');
    await this.cmd(Buffer.from(this.cfg.email_auth_code).toString('base64'), '235');
  }

  expectCode(code) {
    const re = new RegExp(`(^|\\r\\n)${code} [^\\r\\n]*\\r\\n`);
    return this.client.expect((buf) => {
      const text = buf.toString('latin1');
      const m = text.match(re);
      if (!m) return null;
      const consumed = m.index + m[0].length;
      return { consumed, value: text.slice(m.index, consumed) };
    });
  }

  async cmd(text, expectCode) {
    this.client.write(text);
    return this.expectCode(expectCode);
  }

  /** 发送带 APK 附件的邮件 */
  async sendApk(toAddr, apkPath) {
    const boundary = '----yuelan_custom_pack_boundary';
    const apkName = path.basename(apkPath);
    const body = [
      '你好！',
      '',
      '附件是使用你提供的图片定制的安装包：',
      '· 桌面图标已换成你的第一张图片；',
      '· 设置 → 自定义图标 里可以切换你提供的其它图片（本定制包只含你的图片）；',
      '',
      '安装方法：把附件保存到手机，点击安装即可（需允许“安装未知来源应用”）。',
      '如果之前装过普通版，定制版会直接覆盖安装，数据保留。',
      '',
      '温馨提示：',
      '· 你上传的图片仅用于生成本次定制 APK，不会用于其他用途；',
      '· APK 打包为人工手动处理，若三天内未收到回复邮件，麻烦请再次发送申请，谢谢！',
    ].join('\r\n');

    const apkB64 = fs.readFileSync(apkPath).toString('base64')
      .replace(/(.{76})/g, '$1\r\n');

    const message = [
      `From: ${this.cfg.email_account}`,
      `To: ${toAddr}`,
      `Subject: ${encodeWord('你的月澜记录定制版安装包')}`,
      'MIME-Version: 1.0',
      `Content-Type: multipart/mixed; boundary="${boundary}"`,
      '',
      `--${boundary}`,
      'Content-Type: text/plain; charset=utf-8',
      'Content-Transfer-Encoding: base64',
      '',
      Buffer.from(body, 'utf8').toString('base64'),
      `--${boundary}`,
      `Content-Type: application/vnd.android.package-archive; name="${encodeWord(apkName)}"`,
      'Content-Transfer-Encoding: base64',
      `Content-Disposition: attachment; filename="${encodeWord(apkName)}"`,
      '',
      apkB64,
      `--${boundary}--`,
      '',
    ].join('\r\n');

    await this.cmd(`MAIL FROM:<${this.cfg.email_account}>`, '250');
    await this.cmd(`RCPT TO:<${toAddr}>`, '250');
    await this.cmd('DATA', '354');
    // DATA 阶段：消息原文 + \r\n.\r\n 结束
    this.client.write(message + '\r\n.');
    await this.expectCode('250');
    await this.cmd('QUIT', '221');
    this.client.end();
  }
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

async function processPack(images, name, cfg, replyTo, release) {
  log(`===== 处理 ${name}（${images.length} 张图片，${release ? 'release' : 'debug'} 包）=====`);
  const backup = backupExisting();
  try {
    applyIcons(images);
    const apk = buildApk(release);
    if (!apk) return null;

    fs.mkdirSync(DIST_DIR, { recursive: true });
    const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 12);
    const version = readVersionName();
    const out = path.join(DIST_DIR,
      `月澜定制_${sanitizeFileName(name)}_v${version}_${stamp}.apk`);
    fs.copyFileSync(apk, out);
    log(`定制包输出：${out}`);

    if (replyTo) {
      try {
        const smtp = new SmtpClient(cfg);
        await smtp.connect();
        await smtp.sendApk(replyTo, out);
        log(`已回信给 ${replyTo}（附件 ${path.basename(out)}）`);
      } catch (e) {
        log(`回信失败（APK 已生成，可手动发送）：${e.message}`);
      }
    } else {
      log('未指定收件邮箱，请手动把 APK 发给用户');
    }
    return out;
  } finally {
    restoreIcons(backup);
  }
}

async function main() {
  const args = process.argv.slice(2);
  const fromEmail = args.includes('--from-email');
  const dirIdx = args.indexOf('--from-dir');
  const nameIdx = args.indexOf('--name');
  const replyIdx = args.indexOf('--reply-to');
  // --release 显式切换 release；否则读配置文件的 build_type（缺省 debug）
  const releaseFlag = args.includes('--release');

  if (fromEmail) {
    const cfg = loadConfig();
    const release = releaseFlag || cfg.build_type === 'release';
    const { imap, grouped } = await collectFromEmail(cfg);
    if (grouped.length === 0) {
      log('没有带图片的未读邮件');
      await imap.logout();
      return;
    }
    let success = 0;
    for (const entry of grouped) {
      const out = await processPack(entry.images, entry.sender, cfg, entry.sender, release);
      if (out) {
        success++;
        // 只把处理成功的邮件标记为已读，失败的下次运行还会再试
        for (const id of entry.mailIds) {
          try { await imap.markSeen(id); } catch (e) { log(`标记已读失败：${e.message}`); }
        }
      }
    }
    await imap.logout();
    log(`全部完成，成功 ${success}/${grouped.length} 个发件人`);
    if (success < grouped.length) {
      process.exitCode = 1;
    }
    return;
  }

  if (dirIdx >= 0 && args[dirIdx + 1]) {
    const dir = args[dirIdx + 1];
    const name = nameIdx >= 0 ? args[nameIdx + 1] : path.basename(dir);
    const replyTo = replyIdx >= 0 ? args[replyIdx + 1] : '';
    const cfg = replyTo ? loadConfig() : null;
    const release = releaseFlag || (cfg != null && cfg.build_type === 'release');
    const out = await processPack(collectFromDir(dir), name, cfg, replyTo || null, release);
    if (!out) {
      // 打包失败时返回非零退出码，让外层脚本显示失败而不是误报成功
      process.exit(1);
    }
    return;
  }

  console.log('用法：');
  console.log('  node tools/icon_packager.js --from-email [--release]');
  console.log('  node tools/icon_packager.js --from-dir <目录> --name <标识> [--reply-to <邮箱>] [--release]');
  console.log('说明：--release 使用 release 签名打包（需先配置 signing.properties）；不传则打 debug 包。');
}

main().catch((e) => {
  console.error('执行失败：', e);
  process.exit(1);
});
