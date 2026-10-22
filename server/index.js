#!/usr/bin/env node
/**
 * QuizAssist 远程配置最小服务端（零依赖，Node 18+）
 *
 *   node server/index.js
 *
 * 接口：
 *   GET  /api/v1/client/config   客户端拉配置（支持 ETag / 304）
 *   POST /api/v1/client/report   匿名上报（追加到 data/reports.ndjson）
 *   GET  /healthz                健康检查
 *   POST /api/v1/admin/config    热更新配置（需要 ADMIN_TOKEN）
 *   GET  /api/v1/admin/config    读当前配置（需要 ADMIN_TOKEN）
 *
 * 配置存在 data/config.json，改完立刻生效，客户端下次请求就能拿到。
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || '';

const DATA_DIR = path.join(__dirname, 'data');
const CONFIG_FILE = path.join(DATA_DIR, 'config.json');
const REPORT_FILE = path.join(DATA_DIR, 'reports.ndjson');
const REPORT_MAX_BYTES = 20 * 1024 * 1024; // 20MB 后滚动

// APK 落盘目录，nginx 用 location /quizassist/ alias 到这里对外直出。
// 课表项目那边也是同一个套路：后台传文件、服务器直链、网盘另放一份。
const UPLOAD_DIR = process.env.UPLOAD_DIR || '/var/www/quizassist';
const STATS_CACHE_MS = 60 * 1000;          // 统计聚合缓存，避免每次请求都扫文件
const REPORT_ROLLING_READ = 3;             // 统计时最多回看几个滚动备份

let statsCache = { at: 0, data: null };

function readConfigRaw() {
  const raw = fs.readFileSync(CONFIG_FILE, 'utf8');
  return raw.replace(/^\uFEFF/, '');
}

function etagOf(text) {
  return '"' + crypto.createHash('sha1').update(text, 'utf8').digest('hex').slice(0, 20) + '"';
}

function json(res, status, body, extraHeaders = {}) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    ...extraHeaders,
  });
  res.end(payload);
}

function readBody(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function appendReport(line) {
  try {
    if (fs.existsSync(REPORT_FILE) && fs.statSync(REPORT_FILE).size > REPORT_MAX_BYTES) {
      fs.renameSync(REPORT_FILE, REPORT_FILE + '.' + Date.now() + '.bak');
    }
    fs.appendFileSync(REPORT_FILE, line + '\n', 'utf8');
  } catch (e) {
    console.error('[report] 写入失败:', e.message);
  }
}

/** 把时间戳归到本地时区的自然日（服务器是 CST，直接按本地算）。 */
function dayKey(ts) {
  const d = new Date(Number(ts) || Date.now());
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

/** 当前上报文件 + 最近的滚动备份。 */
function listReportFiles() {
  const files = [];
  if (fs.existsSync(REPORT_FILE)) files.push(REPORT_FILE);
  try {
    const prefix = path.basename(REPORT_FILE) + '.';
    fs.readdirSync(DATA_DIR)
      .filter((n) => n.startsWith(prefix) && n.endsWith('.bak'))
      .sort()
      .reverse()
      .slice(0, REPORT_ROLLING_READ)
      .forEach((n) => files.push(path.join(DATA_DIR, n)));
  } catch {
    /* 目录读不到就只统计主文件 */
  }
  return files;
}

/**
 * 聚合上报数据。指标口径：
 *   events   事件条数（同一台设备多次启动算多次）
 *   devices  去重后的 deviceId 数量 = 装过这个 App 的设备数
 *   launches app_launch 事件数 = 启动次数
 *   days     按自然日聚合，供后台画趋势图
 */
function computeStats() {
  const files = listReportFiles();
  const byEvent = {};
  const byVersion = {};
  const byDay = {};
  const devices = new Set();
  const recent = [];
  let total = 0;
  let badLines = 0;

  files.forEach((file) => {
    let text = '';
    try {
      text = fs.readFileSync(file, 'utf8');
    } catch {
      return;
    }
    text.split(String.fromCharCode(10)).forEach((line) => {
      if (!line.trim()) return;
      let row;
      try {
        row = JSON.parse(line);
      } catch {
        badLines++;
        return;
      }
      total++;
      const event = row.event || 'unknown';
      byEvent[event] = (byEvent[event] || 0) + 1;

      const version = row.versionName || String(row.versionCode || 'unknown');
      byVersion[version] = (byVersion[version] || 0) + 1;

      const device = String(row.deviceId || '');
      if (device) devices.add(device);

      const at = Number(row.receivedAt || row.at || 0);
      const day = dayKey(at);
      const bucket = byDay[day] || (byDay[day] = { day, events: 0, launches: 0, devices: new Set() });
      bucket.events++;
      if (event === 'app_launch') bucket.launches++;
      if (device) bucket.devices.add(device);

      recent.push({
        at,
        event,
        version,
        device: device ? device.slice(0, 8) : '',
        extra: row.extra || null,
      });
    });
  });

  recent.sort((a, b) => b.at - a.at);

  const days = Object.values(byDay)
    .sort((a, b) => a.day.localeCompare(b.day))
    .map((d) => ({ day: d.day, events: d.events, launches: d.launches, devices: d.devices.size }));

  const today = dayKey(Date.now());
  const todayRow = days.find((d) => d.day === today) || { day: today, events: 0, launches: 0, devices: 0 };

  return {
    generatedAt: Date.now(),
    sourceFiles: files.length,
    badLines,
    totals: {
      events: total,
      devices: devices.size,
      launches: byEvent.app_launch || 0,
    },
    today: todayRow,
    byEvent,
    byVersion,
    days: days.slice(-30),
    recent: recent.slice(0, 30),
  };
}

/** 只允许 xxx.apk，防止路径穿越。 */
function sanitizeApkName(raw) {
  const name = path.basename(String(raw || '').trim());
  return /^[A-Za-z0-9._-]+\.apk$/i.test(name) ? name : '';
}

/**
 * 接收 APK 原始字节（裸 body，不解析 multipart）。
 *
 * 抄的是课表项目 admin_server.py 的 _upload：边收边写边算 sha256，
 * 省掉 multipart 解析，前端也能拿到真实上传进度。
 */
function handleUpload(req, res, url) {
  const name = sanitizeApkName(url.searchParams.get('filename'));
  if (!name) {
    return json(res, 400, { error: '文件名需为 xxx.apk（只允许字母、数字、点、横线下划线）' });
  }
  const target = path.join(UPLOAD_DIR, name);
  const temp = target + '.uploading';
  try {
    fs.mkdirSync(UPLOAD_DIR, { recursive: true });
  } catch (e) {
    return json(res, 500, { error: '上传目录不可用: ' + e.message });
  }

  const hash = crypto.createHash('sha256');
  const out = fs.createWriteStream(temp);
  let size = 0;
  let failed = false;

  const cleanup = () => {
    try { fs.unlinkSync(temp); } catch { /* 已经删过 */ }
  };

  req.on('data', (chunk) => {
    hash.update(chunk);
    size += chunk.length;
    if (!out.write(chunk)) {
      req.pause();
      out.once('drain', () => req.resume());
    }
  });

  req.on('error', (e) => {
    failed = true;
    out.destroy();
    cleanup();
    json(res, 400, { error: '上传中断: ' + e.message });
  });

  req.on('end', () => {
    if (failed) return;
    out.end(() => {
      if (size <= 0) {
        cleanup();
        return json(res, 400, { error: '空文件' });
      }
      try {
        fs.renameSync(temp, target);
      } catch (e) {
        cleanup();
        return json(res, 500, { error: '落盘失败: ' + e.message });
      }
      const digest = hash.digest('hex');
      console.log('[upload] %s  %d 字节  sha256=%s', name, size, digest);
      return json(res, 200, {
        ok: true,
        name,
        sizeBytes: size,
        sha256: digest,
        url: 'https://xuhuohua.xyz/quizassist/' + name,
      });
    });
  });
}

function requireAdmin(req, res) {
  if (!ADMIN_TOKEN) {
    json(res, 503, { error: 'ADMIN_TOKEN 未配置，管理接口已禁用' });
    return false;
  }
  // 注意：这里不能只认 Authorization —— 前面 nginx 的 Basic Auth 也占这个头，
  // 浏览器会自动发 Authorization: Basic，直接覆盖掉页面 JS 设置的 Bearer。
  // 所以主用 X-Admin-Token 自定义头，Bearer 只作为 curl/脚本的兼容入口。
  const custom = (req.headers['x-admin-token'] || '').trim();
  const auth = req.headers['authorization'] || '';
  const bearer = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  const token = custom || bearer;
  if (token !== ADMIN_TOKEN) {
    json(res, 401, { error: 'unauthorized' });
    return false;
  }
  return true;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  // HEAD 当成 GET 处理：监控/CDN 常用 HEAD 探测，别回 404。
  // Node 对 HEAD 会自动丢掉响应体，这里不用额外处理。
  const method = req.method === 'HEAD' ? 'GET' : req.method;
  const route = `${method} ${url.pathname}`;

  try {
    switch (route) {
      case 'GET /healthz':
        return json(res, 200, { ok: true, at: Date.now() });

      case 'GET /api/v1/client/config': {
        let raw;
        try {
          raw = readConfigRaw();
        } catch (e) {
          return json(res, 500, { error: 'config.json 读取失败: ' + e.message });
        }
        // 校验是不是合法 JSON，坏了别把客户端带崩（客户端会自行降级，但最好别发）
        try {
          JSON.parse(raw);
        } catch (e) {
          return json(res, 500, { error: 'config.json 不是合法 JSON: ' + e.message });
        }
        const etag = etagOf(raw);
        const ifNoneMatch = req.headers['if-none-match'];
        const headers = {
          'Cache-Control': 'no-cache, must-revalidate',
          ETag: etag,
          'Access-Control-Allow-Origin': '*',
        };
        if (ifNoneMatch && ifNoneMatch.split(',').map((s) => s.trim()).includes(etag)) {
          res.writeHead(304, headers);
          return res.end();
        }
        return json(res, 200, raw, headers);
      }

      case 'POST /api/v1/client/report': {
        const body = await readBody(req, 16 * 1024);
        let parsed;
        try {
          parsed = JSON.parse(body);
        } catch {
          return json(res, 400, { error: 'invalid json' });
        }
        appendReport(JSON.stringify({ receivedAt: Date.now(), ...parsed }));
        return json(res, 200, { ok: true });
      }

      case 'POST /api/v1/admin/upload': {
        if (!requireAdmin(req, res)) return;
        return handleUpload(req, res, url);
      }

      case 'GET /api/v1/admin/stats': {
        if (!requireAdmin(req, res)) return;
        const now = Date.now();
        if (!statsCache.data || now - statsCache.at > STATS_CACHE_MS) {
          statsCache = { at: now, data: computeStats() };
        }
        return json(res, 200, statsCache.data, { 'Cache-Control': 'no-store' });
      }

      case 'GET /api/v1/admin/config': {
        if (!requireAdmin(req, res)) return;
        return json(res, 200, readConfigRaw());
      }

      case 'POST /api/v1/admin/config': {
        if (!requireAdmin(req, res)) return;
        const body = await readBody(req, 512 * 1024);
        let parsed;
        try {
          parsed = JSON.parse(body);
        } catch (e) {
          return json(res, 400, { error: 'invalid json: ' + e.message });
        }
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          return json(res, 400, { error: 'config 必须是 JSON 对象' });
        }
        // 原子写：先写临时文件再 rename，避免客户端读到半个 JSON
        const tmp = CONFIG_FILE + '.tmp';
        fs.writeFileSync(tmp, JSON.stringify(parsed, null, 2), 'utf8');
        fs.renameSync(tmp, CONFIG_FILE);
        return json(res, 200, { ok: true, etag: etagOf(readConfigRaw()) });
      }

      default:
        return json(res, 404, { error: 'not found', route });
    }
  } catch (e) {
    console.error('[error]', route, e);
    return json(res, 500, { error: e.message });
  }
});

if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
if (!fs.existsSync(CONFIG_FILE)) {
  console.error('缺少', CONFIG_FILE, '，请先把 quiz_assist_config.json 放进去');
  process.exit(1);
}

server.listen(PORT, HOST, () => {
  console.log(`QuizAssist config server: http://${HOST}:${PORT}`);
  console.log(`  GET  /api/v1/client/config`);
  console.log(`  POST /api/v1/client/report`);
  console.log(`  POST /api/v1/admin/config   (Authorization: Bearer $ADMIN_TOKEN)`);
  console.log(`  配置文件: ${CONFIG_FILE}`);
  if (!ADMIN_TOKEN) console.log('  ⚠ ADMIN_TOKEN 未设置，管理接口关闭');
});
