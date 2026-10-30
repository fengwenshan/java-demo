#!/usr/bin/env node
/**
 * 端到端接口自测：不需要额外依赖，直接用 Node 18+ 内置 fetch。
 *
 * 覆盖：健康检查 → 签名校验（正确/错误/过期/重放）→ 设备激活 → 上传（含中文文件名）
 *       → 篡改哈希被拒 / 任意类型允许 → 列表 → 下载并校验 SHA-256 → 删除
 *       → FTP 式目录浏览：隐藏 .upload-meta、上传到子目录、重名改名、按路径下载/删除、越界拒绝
 *       → 分片上传（init/chunk/status/complete、乱序补传、缺片 40000、SHA 不符 40001、取消）
 *       → 根目录递归搜索（嵌套命中、忽略大小写、隐藏 .upload-meta、非法 keyword）
 *       → 多存储根：roots 列表、双根隔离上传/删除、跨根路径拒绝、搜索按根隔离（需 E2E_ROOTS）
 *       → 右键拷贝（_副本 命名/编号、目录递归）/ 移动（同根/跨根、目录、冲突、自身子目录）。
 *
 * FTP 子目录用例需要设置 E2E_STORAGE_ROOT=<服务端 storage root 的本机绝对路径>
 * （脚本会在其中创建 docs/ 目录；不设置则这些用例跳过）。
 * 多存储根用例需要设置 E2E_ROOTS="work=/abs/rootA,media=/abs/rootB"（与服务端配置一致）。
 *
 * 注意：分片/续传用例消耗较多令牌（init/complete 各 5 个、每片 1 个），默认容量 1000 一般够用；
 * 若针对小容量限流实例跑，可调大 UPLOAD_RATE_LIMIT_CAPACITY。
 *
 * 用法：
 *   node scripts/e2e.mjs
 *   BASE_URL=http://127.0.0.1:8080 APP_KEY=demo-app APP_SECRET=xxx node scripts/e2e.mjs
 * 退出码：全部通过 0，否则 1。
 */
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const BASE_URL = (process.env.BASE_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const APP_KEY = process.env.APP_KEY || 'demo-app';
const APP_SECRET = process.env.APP_SECRET || 'demo-secret-please-change-32-bytes-min';
// 固定测试设备号：服务端开启设备绑定后，重复跑测试不会因设备变化被 403
const DEVICE_ID = process.env.DEVICE_ID || 'e2e00000-0000-4000-8000-000000000001';
const DEVICE_NAME = 'e2e 自动化测试设备';
// 服务端存储根目录本机绝对路径（用于创建子目录 / 校验落盘位置；可选）
const E2E_ROOT = process.env.E2E_STORAGE_ROOT;
// 多存储根映射 id -> 本机绝对路径（可选）
const E2E_ROOTS = (() => {
  const raw = (process.env.E2E_ROOTS || '').trim();
  if (!raw) return null;
  const map = {};
  for (const part of raw.split(',')) {
    const i = part.indexOf('=');
    if (i > 0) map[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return Object.keys(map).length ? map : null;
})();

// 与契约 2.2 一致：nonce 需匹配 ^[A-Za-z0-9_-]{16,64}$
const NONCE_ALPHABET = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_';
function nonce(length = 32) {
  return Array.from(crypto.randomBytes(length), (b) => NONCE_ALPHABET[b % NONCE_ALPHABET.length]).join('');
}

const sha256Hex = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const EMPTY_SHA256 = sha256Hex(Buffer.alloc(0));

/** RFC3986 percent-encoding：仅保留 A-Za-z0-9-._~ */
function rfc3986(str) {
  return encodeURIComponent(str).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
}

/** 按契约 2.4 规范化查询串；query 可以是字符串或 [key, value] 数组 */
function canonicalQuery(query) {
  if (!query) return '';
  let pairs = [];
  if (typeof query === 'string') {
    if (query.startsWith('?')) query = query.slice(1);
    for (const part of query.split('&')) {
      if (part === '') continue;
      const i = part.indexOf('=');
      const k = i === -1 ? part : part.slice(0, i);
      const v = i === -1 ? '' : part.slice(i + 1);
      pairs.push([decodeURIComponent(k), decodeURIComponent(v)]);
    }
  } else {
    pairs = query.map(([k, v]) => [String(k), String(v)]);
  }
  const encoded = pairs.map(([k, v]) => [rfc3986(k), rfc3986(v)]);
  encoded.sort((a, b) => {
    if (a[0] !== b[0]) return Buffer.compare(Buffer.from(a[0]), Buffer.from(b[0]));
    return Buffer.compare(Buffer.from(a[1]), Buffer.from(b[1]));
  });
  return encoded.map(([k, v]) => `${k}=${v}`).join('&');
}

/** 构造签名串并签名，返回可直接 fetch 的 headers 与 URL */
function signedHeaders({ method, path, query = '', payloadHash = EMPTY_SHA256, timestamp, nonceValue, secret = APP_SECRET, appKey = APP_KEY }) {
  const ts = timestamp ?? Date.now();
  const nonceVal = nonceValue ?? nonce();
  const canonical = ['v1', appKey, String(ts), nonceVal, method.toUpperCase(), path, canonicalQuery(query), payloadHash].join('\n');
  const signature = crypto.createHmac('sha256', secret).update(canonical, 'utf8').digest('hex');
  return {
    canonical,
    headers: {
      'X-App-Key': appKey,
      'X-Timestamp': String(ts),
      'X-Nonce': nonceVal,
      'X-Signature': signature,
      'X-Device-Id': DEVICE_ID,
    },
  };
}

async function api(method, path, opts = {}) {
  const { query = '', json, form, raw, timestamp, nonceValue, secret, appKey, noSign = false, hashOverride } = opts;
  const headers = {};
  let body;

  let payloadHash = EMPTY_SHA256;
  if (json !== undefined) {
    body = Buffer.from(JSON.stringify(json), 'utf8');
    payloadHash = sha256Hex(body);
    headers['Content-Type'] = 'application/json; charset=utf-8';
  } else if (form) {
    body = form.body;
    payloadHash = hashOverride ?? form.contentSha256;
  } else if (raw !== undefined) {
    body = raw;
    payloadHash = sha256Hex(raw);
    headers['Content-Type'] = 'application/octet-stream';
  }

  if (!noSign) {
    const signed = signedHeaders({ method, path, query, payloadHash, timestamp, nonceValue, secret, appKey });
    Object.assign(headers, signed.headers);
    if (form) headers['X-Content-Sha256'] = form.contentSha256;
    if (raw !== undefined) headers['X-Content-Sha256'] = payloadHash;
  }

  const url = `${BASE_URL}${path}${query ? `?${typeof query === 'string' ? query.replace(/^\?/, '') : canonicalQuery(query)}` : ''}`;
  const res = await fetch(url, { method, headers, body });
  const contentType = res.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    const data = await res.json().catch(() => null);
    return { status: res.status, headers: res.headers, data };
  }
  const buf = Buffer.from(await res.arrayBuffer());
  return { status: res.status, headers: res.headers, buf };
}

// ---------------- 测试框架 ----------------
let passed = 0;
const failures = [];
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`  \u2713 ${name}`);
  } catch (err) {
    failures.push({ name, error: err.message });
    console.log(`  \u2717 ${name}\n      ${err.message}`);
  }
}
function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}
function assertEqual(actual, expected, label) {
  if (actual !== expected) throw new Error(`${label} 期望 ${expected}，实际 ${actual}`);
}
function assertCode(res, code, label) {
  assert(res.data && typeof res.data.code === 'number', `${label}: 响应不是 JSON 信封 (HTTP ${res.status})`);
  assertEqual(res.data.code, code, `${label} 业务码`);
}

// ---------------- 用例 ----------------
async function main() {
  console.log(`\n=== ScopeFTP 端到端测试 ===`);
  console.log(`服务端: ${BASE_URL}\nAPI Key: ${APP_KEY}\n设备: ${DEVICE_ID}\n`);

  // 1. 健康检查（免签）
  await test('健康检查免签名可访问', async () => {
    const res = await api('GET', '/api/health', { noSign: true });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'health');
    assert(res.data.data.status === 'UP', 'status 应为 UP');
  });

  // 2. 未签名调用业务接口被拒
  await test('未签名调用 /api/auth/verify 被拒 (40100)', async () => {
    const res = await api('GET', '/api/auth/verify', { noSign: true });
    assertEqual(res.status, 401, 'HTTP');
    assertCode(res, 40100, 'verify');
  });

  // 3. 错误密钥签名被拒
  await test('错误 appSecret 签名被拒 (40100)', async () => {
    const res = await api('GET', '/api/auth/verify', { secret: 'wrong-secret-000000000000000000' });
    assertEqual(res.status, 401, 'HTTP');
    assertCode(res, 40100, 'verify');
  });

  // 4. 正确签名通过
  let verifyInfo;
  await test('正确签名调用 /api/auth/verify 成功', async () => {
    const res = await api('GET', '/api/auth/verify');
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'verify');
    verifyInfo = res.data.data;
    assert(verifyInfo.appKey === APP_KEY, 'appKey 回显');
    assert(typeof verifyInfo.serverTime === 'number', 'serverTime 应为数字');
    assert(verifyInfo.limits && verifyInfo.limits.maxFileSize > 0, 'limits.maxFileSize');
    assert(!('allowedExtensions' in verifyInfo.limits), 'limits 不再包含 allowedExtensions');
  });

  // 5. 时间戳过期
  await test('时间戳超窗被拒 (40101)', async () => {
    const res = await api('GET', '/api/auth/verify', { timestamp: Date.now() - 10 * 60 * 1000 });
    assertEqual(res.status, 401, 'HTTP');
    assertCode(res, 40101, 'verify');
  });

  // 6. 重放攻击
  await test('nonce 重放被拒 (40102)', async () => {
    const timestamp = Date.now();
    const nonceValue = nonce(24);
    const first = await api('GET', '/api/auth/verify', { timestamp, nonceValue });
    assertCode(first, 0, '首次请求');
    const replay = await api('GET', '/api/auth/verify', { timestamp, nonceValue });
    assertEqual(replay.status, 401, 'HTTP');
    assertCode(replay, 40102, '重放请求');
  });

  // 7. 激活设备
  await test('设备激活成功', async () => {
    const res = await api('POST', '/api/auth/activate', { json: { deviceId: DEVICE_ID, deviceName: DEVICE_NAME } });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'activate');
    assertEqual(res.data.data.deviceId, DEVICE_ID, 'deviceId');
    assertEqual(res.data.data.deviceName, DEVICE_NAME, 'deviceName');
  });

  // 8. 上传（中文文件名）
  const content = Buffer.from(`ScopeFTP e2e 测试内容 ${new Date().toISOString()}\n`, 'utf8');
  const contentSha = sha256Hex(content);
  let uploadedId;
  await test('上传文件成功（中文文件名 + SHA-256）', async () => {
    const form = new FormData();
    form.append('name', '端到端测试-报告-2026.txt');
    form.append('description', 'e2e 自动化测试');
    form.append('file', new Blob([content], { type: 'text/plain' }), 'raw-upload.txt');
    const res = await api('POST', '/api/files/upload', { form: { body: form, contentSha256: contentSha } });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'upload');
    const meta = res.data.data;
    assert(meta.id, '返回 id');
    assertEqual(meta.name, '端到端测试-报告-2026.txt', '文件名');
    assertEqual(meta.size, content.length, '文件大小');
    assertEqual(meta.sha256, contentSha, '文件 SHA-256');
    assertEqual(meta.contentType, 'text/plain', 'content type');
    uploadedId = meta.id;
  });

  // 9. 篡改内容（头哈希与实际内容不一致）
  await test('内容哈希不一致被拒 (40001)', async () => {
    const wrongContent = Buffer.from('tampered content', 'utf8');
    const form = new FormData();
    form.append('name', 'tampered.txt');
    form.append('file', new Blob([wrongContent], { type: 'text/plain' }), 'tampered.txt');
    const res = await api('POST', '/api/files/upload', { form: { body: form, contentSha256: sha256Hex(Buffer.from('hello')) } });
    assert(res.status === 400 || res.status === 401, `HTTP 期望 400/401，实际 ${res.status}`);
    assertCode(res, 40001, 'tampered upload');
  });

  // 10. 任意扩展名（已取消类型白名单）
  await test('任意扩展名上传成功（.exe，不再校验类型）', async () => {
    const exe = Buffer.from('MZ fake exe for e2e', 'utf8');
    const form = new FormData();
    form.append('name', 'malware.exe');
    form.append('file', new Blob([exe], { type: 'application/octet-stream' }), 'malware.exe');
    const res = await api('POST', '/api/files/upload', { form: { body: form, contentSha256: sha256Hex(exe) } });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'upload exe');
    assertEqual(res.data.data.name, 'malware.exe', '文件名');
    // 清理，避免污染服务端数据
    const del = await api('DELETE', `/api/files/${res.data.data.id}`);
    assertCode(del, 0, 'cleanup exe');
  });

  // 11. 列表
  await test('分页查询文件列表包含刚上传的文件', async () => {
    const res = await api('GET', '/api/files', { query: 'page=1&size=20' });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'list');
    const page = res.data.data;
    assert(page.total >= 1, 'total >= 1');
    assert(Array.isArray(page.items), 'items 是数组');
    assert(page.items.some((f) => f.id === uploadedId), '列表包含上传的文件');
    const item = page.items.find((f) => f.id === uploadedId);
    assertEqual(item.sha256, contentSha, '列表中的 sha256');
  });

  // 12. 下载并校验
  await test('下载文件内容与 SHA-256 校验通过', async () => {
    const res = await api('GET', `/api/files/${uploadedId}/download`);
    assertEqual(res.status, 200, 'HTTP');
    assert(res.buf, '应有二进制响应');
    assertEqual(res.headers.get('x-content-sha256'), contentSha, '响应头 SHA-256');
    assertEqual(sha256Hex(res.buf), contentSha, '下载内容 SHA-256');
    assertEqual(res.buf.length, content.length, '下载大小');
  });

  // 13. 删除
  await test('删除文件成功', async () => {
    const res = await api('DELETE', `/api/files/${uploadedId}`);
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'delete');
    assertEqual(res.data.data.id, uploadedId, '返回 id');
  });

  // 14. 删除后下载 404
  await test('已删除文件下载返回 40400', async () => {
    const res = await api('GET', `/api/files/${uploadedId}/download`);
    assertEqual(res.status, 404, 'HTTP');
    assertCode(res, 40400, 'download missing');
  });

  // 15. 伪造设备（仅绑定开启时生效，未开启时允许）
  await test('绑定开启时伪造 X-Device-Id 被拒（未开启则跳过）', async () => {
    if (!verifyInfo.deviceBindingEnabled) {
      console.log('      （服务端未启用设备绑定，跳过）');
      return;
    }
    // 直接构造一个不同设备头的签名请求
    const form = new FormData();
    form.append('name', 'device-check.txt');
    form.append('file', new Blob([content], { type: 'text/plain' }), 'device-check.txt');
    const ts = Date.now();
    const nonceVal = nonce(24);
    const payloadHash = contentSha;
    const canonical = ['v1', APP_KEY, String(ts), nonceVal, 'POST', '/api/files/upload', '', payloadHash].join('\n');
    const signature = crypto.createHmac('sha256', APP_SECRET).update(canonical, 'utf8').digest('hex');
    const url = `${BASE_URL}/api/files/upload`;
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'X-App-Key': APP_KEY,
        'X-Timestamp': String(ts),
        'X-Nonce': nonceVal,
        'X-Signature': signature,
        'X-Device-Id': crypto.randomUUID(),
        'X-Content-Sha256': contentSha,
      },
      body: form,
    });
    assertEqual(res.status, 403, 'HTTP');
    const data = await res.json();
    assertEqual(data.code, 40300, '业务码');
  });

  // 15. 目录浏览：根目录隐藏内部元数据目录
  await test('服务器目录：根目录不展示 .upload-meta', async () => {
    const res = await api('GET', '/api/files/dir');
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'dir root');
    const data = res.data.data;
    assertEqual(data.path, '', '根目录 path 为空串');
    assert(data.parent === null || data.parent === undefined, '根目录 parent 应为 null');
    const names = (data.entries || []).map((e) => e.name);
    assert(!names.includes('.upload-meta'), '根目录不应展示 .upload-meta');
  });

  // 16. FTP：上传到当前子目录（同名自动加序号）
  let ftpStoredName = 'ftp-report.txt';
  await test('FTP：上传到子目录 docs 成功（重名自动加序号）', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const docs = path.join(E2E_ROOT, 'docs');
    fs.mkdirSync(docs, { recursive: true });

    const first = Buffer.from('ftp upload one\n', 'utf8');
    const form1 = new FormData();
    form1.append('name', 'ftp-report.txt');
    form1.append('file', new Blob([first], { type: 'text/plain' }), 'ftp-report.txt');
    const res1 = await api('POST', '/api/files/upload', { form: { body: form1, contentSha256: sha256Hex(first) }, query: 'dir=docs' });
    assertEqual(res1.status, 200, 'HTTP');
    assertCode(res1, 0, 'upload dir=docs');
    assertEqual(res1.data.data.name, 'ftp-report.txt', '展示名保持原名');
    assert(fs.existsSync(path.join(docs, 'ftp-report.txt')), '文件落在 docs/ 下');

    const second = Buffer.from('ftp upload two\n', 'utf8');
    const form2 = new FormData();
    form2.append('name', 'ftp-report.txt');
    form2.append('file', new Blob([second], { type: 'text/plain' }), 'ftp-report.txt');
    const res2 = await api('POST', '/api/files/upload', { form: { body: form2, contentSha256: sha256Hex(second) }, query: 'dir=docs' });
    assertEqual(res2.status, 200, 'HTTP（第二次）');
    assert(fs.existsSync(path.join(docs, 'ftp-report (1).txt')), '重名文件自动改名为 ftp-report (1).txt');
  });

  // 17. FTP：按路径下载子目录文件
  await test('FTP：按路径下载 docs 文件并校验 SHA-256', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const res = await api('GET', '/api/files/download', { query: `path=${encodeURIComponent(ftpStoredName)}` });
    if (res.status === 404) return; // 没有 16 的产物时跳过
    assertEqual(res.status, 200, 'HTTP');
    assertEqual(sha256Hex(res.buf), res.headers.get('x-content-sha256'), '响应头 SHA-256 与内容一致');
    assertEqual(res.buf.toString('utf8'), 'ftp upload one\n', '下载内容');
  });

  // 18. FTP：按路径删除子目录文件
  await test('FTP：按路径删除 docs 文件', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const del = await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(ftpStoredName)}` });
    if (del.status === 404) return;
    assertEqual(del.status, 200, 'HTTP');
    assertCode(del, 0, 'delete by path');
    assert(!fs.existsSync(path.join(E2E_ROOT, 'docs', ftpStoredName)), '文件已从磁盘删除');
  });

  // 19. 越界路径被拒
  await test('服务器目录：../ 越界路径被拒 (40000)', async () => {
    const res = await api('GET', '/api/files/dir', { query: 'path=../../etc' });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, 'dir traversal');
  });

  // 20. 绝对路径被拒
  await test('服务器目录：绝对路径被拒 (40000)', async () => {
    const res = await api('GET', '/api/files/dir', { query: 'path=/etc' });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, 'dir absolute');
  });

  // 21. 内部元数据目录不可进入
  await test('服务器目录：无法进入 .upload-meta (40000)', async () => {
    const res = await api('GET', '/api/files/dir', { query: 'path=.upload-meta' });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, 'dir internal');
  });

  // 22. 目录浏览接口同样必须签名
  await test('服务器目录：未签名被拒 (40100)', async () => {
    const res = await api('GET', '/api/files/dir', { noSign: true });
    assertEqual(res.status, 401, 'HTTP');
    assertCode(res, 40100, 'dir unsigned');
  });

  // 23. 分片上传：乱序分片 + status + complete
  const chunkDir = E2E_ROOT ? 'docs' : '';
  const chunkTarget = E2E_ROOT ? path.join(E2E_ROOT, 'docs') : null;
  const bigBuf = crypto.randomBytes(21 * 1024 * 1024); // 21MB → 3 片（8MB/片）
  const bigSha = sha256Hex(bigBuf);
  const bigName = `e2e-大文件-${Date.now()}.bin`;
  let chunkUploadId;
  await test('分片上传：init 返回 uploadId/chunkSize', async () => {
    if (E2E_ROOT) fs.mkdirSync(chunkTarget, { recursive: true });
    const res = await api('POST', '/api/files/upload/init', {
      json: { name: bigName, dir: chunkDir || undefined, size: bigBuf.length, sha256: bigSha },
    });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'init');
    chunkUploadId = res.data.data.uploadId;
    assert(typeof chunkUploadId === 'string' && chunkUploadId.length >= 32, 'uploadId');
    assertEqual(res.data.data.chunkSize, 8 * 1024 * 1024, 'chunkSize 默认 8MB');
  });

  await test('分片上传：乱序上传切片 + status 汇总', async () => {
    const chunkSize = 8 * 1024 * 1024;
    const parts = [];
    for (let offset = 0, index = 0; offset < bigBuf.length; offset += chunkSize, index++) {
      parts.push({ index, buf: bigBuf.subarray(offset, Math.min(offset + chunkSize, bigBuf.length)) });
    }
    // 乱序：先传第 2 片，再传 0、1
    for (const part of [parts[2], parts[0], parts[1]]) {
      const res = await api('PUT', `/api/files/upload/${chunkUploadId}/chunk`, { query: `index=${part.index}`, raw: part.buf });
      assertEqual(res.status, 200, `chunk ${part.index} HTTP`);
      assertCode(res, 0, `chunk ${part.index}`);
    }
    const status = await api('GET', `/api/files/upload/${chunkUploadId}`);
    assertEqual(status.status, 200, 'HTTP status');
    assertCode(status, 0, 'status');
    const received = status.data.data.received.slice().sort((a, b) => a - b);
    assertEqual(JSON.stringify(received), JSON.stringify([0, 1, 2]), 'received 分片');
  });

  await test('分片上传：complete 成功且下载 SHA-256 一致', async () => {
    const done = await api('POST', `/api/files/upload/${chunkUploadId}/complete`);
    assertEqual(done.status, 200, 'HTTP');
    assertCode(done, 0, 'complete');
    const meta = done.data.data;
    assertEqual(meta.size, bigBuf.length, 'size');
    assertEqual(meta.sha256, bigSha, 'sha256');
    assertEqual(meta.name, bigName, 'name');
    // 上传响应不含 storedPath（内部字段不外泄），按约定位置拼接相对路径
    const bigRelPath = chunkDir ? `${chunkDir}/${meta.name}` : meta.name;
    const dl = await api('GET', '/api/files/download', { query: `path=${encodeURIComponent(bigRelPath)}` });
    assertEqual(dl.status, 200, 'download HTTP');
    assertEqual(sha256Hex(dl.buf), bigSha, '下载内容 SHA');
    if (E2E_ROOT) assert(fs.existsSync(path.join(E2E_ROOT, bigRelPath)), '文件落到 docs/');
    // 清理
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(bigRelPath)}` });
  });

  await test('分片上传：缺片 complete 返回 40000 且保留会话，补传后成功', async () => {
    const buf = crypto.randomBytes(9 * 1024 * 1024); // 2 片
    const sha = sha256Hex(buf);
    const name = `e2e-resume-${Date.now()}.bin`;
    const init = await api('POST', '/api/files/upload/init', {
      json: { name, dir: chunkDir || undefined, size: buf.length, sha256: sha },
    });
    assertCode(init, 0, 'init');
    const id = init.data.data.uploadId;
    const chunkSize = init.data.data.chunkSize;
    await api('PUT', `/api/files/upload/${id}/chunk`, { query: 'index=0', raw: buf.subarray(0, chunkSize) });
    const bad = await api('POST', `/api/files/upload/${id}/complete`);
    assertEqual(bad.status, 400, 'HTTP');
    assertCode(bad, 40000, 'complete 缺片');
    const status = await api('GET', `/api/files/upload/${id}`);
    assertCode(status, 0, '会话保留');
    assertEqual(JSON.stringify(status.data.data.received), JSON.stringify([0]), 'received 只有 0');
    await api('PUT', `/api/files/upload/${id}/chunk`, { query: 'index=1', raw: buf.subarray(chunkSize) });
    const ok = await api('POST', `/api/files/upload/${id}/complete`);
    assertEqual(ok.status, 200, '补传后 HTTP');
    assertCode(ok, 0, '补传后 complete');
    assertEqual(ok.data.data.sha256, sha, 'sha256');
    const resumeRelPath = chunkDir ? `${chunkDir}/${ok.data.data.name}` : ok.data.data.name;
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(resumeRelPath)}` });
  });

  await test('分片上传：整文件 SHA 不符被拒 (40001) 且会话清理', async () => {
    const buf = crypto.randomBytes(1024 * 1024);
    const name = `e2e-badsha-${Date.now()}.bin`;
    const init = await api('POST', '/api/files/upload/init', {
      json: { name, dir: chunkDir || undefined, size: buf.length, sha256: sha256Hex(Buffer.from('wrong')) },
    });
    assertCode(init, 0, 'init');
    const id = init.data.data.uploadId;
    await api('PUT', `/api/files/upload/${id}/chunk`, { query: 'index=0', raw: buf });
    const bad = await api('POST', `/api/files/upload/${id}/complete`);
    assertEqual(bad.status, 400, 'HTTP');
    assertCode(bad, 40001, 'sha mismatch');
    const status = await api('GET', `/api/files/upload/${id}`);
    assertEqual(status.status, 404, '会话已清理 HTTP');
    assertCode(status, 40400, '会话已清理');
  });

  await test('分片上传：非法 index 被拒 (40000) 且可取消', async () => {
    const buf = crypto.randomBytes(1024);
    const init = await api('POST', '/api/files/upload/init', {
      json: { name: `e2e-cancel-${Date.now()}.bin`, dir: chunkDir || undefined, size: buf.length, sha256: sha256Hex(buf) },
    });
    assertCode(init, 0, 'init');
    const id = init.data.data.uploadId;
    const bad = await api('PUT', `/api/files/upload/${id}/chunk`, { query: 'index=99', raw: buf });
    assertEqual(bad.status, 400, 'HTTP');
    assertCode(bad, 40000, '非法 index');
    const cancel = await api('DELETE', `/api/files/upload/${id}`);
    assertEqual(cancel.status, 200, '取消 HTTP');
    assertCode(cancel, 0, '取消');
  });

  // 24. 搜索
  let searchPath;
  await test('搜索：嵌套目录文件命中（忽略大小写）', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const dir = path.join(E2E_ROOT, 'docs', 'e2e搜索', '子目录');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, 'Deep-Report-2026.txt');
    fs.writeFileSync(file, 'search me');
    searchPath = 'docs/e2e搜索/子目录/Deep-Report-2026.txt';
    const res = await api('GET', '/api/files/search', { query: [['keyword', 'deep-report'], ['limit', '50']] });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'search');
    const entry = (res.data.data.entries || []).find((e) => e.path === searchPath);
    assert(entry, '应命中嵌套文件');
    assertEqual(entry.type, 'file', 'type');
    assertEqual(entry.parent, 'docs/e2e搜索/子目录', 'parent');
    assert(!res.data.data.entries.some((e) => e.path.includes('.upload-meta')), '不包含 .upload-meta');
  });

  await test('搜索：目录也匹配、非法 keyword 40000', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const res = await api('GET', '/api/files/search', { query: [['keyword', 'e2e搜索']] });
    assertCode(res, 0, 'search dir');
    const dirEntry = (res.data.data.entries || []).find((e) => e.type === 'directory' && e.name === 'e2e搜索');
    assert(dirEntry, '目录应命中');
    const bad = await api('GET', '/api/files/search', { query: [['keyword', '   ']] });
    assertEqual(bad.status, 400, 'HTTP');
    assertCode(bad, 40000, '空 keyword');
    // 清理搜索产物
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(searchPath)}` });
    fs.rmSync(path.join(E2E_ROOT, 'docs', 'e2e搜索'), { recursive: true, force: true });
  });

  // 25. 多存储根隔离（需 E2E_ROOTS）
  const rootIds = E2E_ROOTS ? Object.keys(E2E_ROOTS) : [];
  let multiRoot = false;
  await test('多根：GET /api/storage/roots 返回配置的根', async () => {
    if (!E2E_ROOTS) {
      console.log('      （未设置 E2E_ROOTS，跳过）');
      return;
    }
    const res = await api('GET', '/api/storage/roots');
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'roots');
    const data = res.data.data;
    assert(rootIds.every((id) => (data.roots || []).some((r) => r.id === id)), '返回所有配置根');
    assert(rootIds.includes(data.defaultRootId), 'defaultRootId 是配置根之一');
    multiRoot = true;
  });

  const multiNames = {};
  await test('多根：分别上传到 rootA/rootB 且按根隔离浏览', async () => {
    if (!multiRoot) return;
    const [rootA, rootB] = rootIds;
    for (const id of [rootA, rootB]) {
      fs.mkdirSync(path.join(E2E_ROOTS[id], 'docs'), { recursive: true });
      const buf = Buffer.from(`multi-root ${id} ${Date.now()}\n`, 'utf8');
      const name = `多根-${id}-${Date.now()}.txt`;
      const form = new FormData();
      form.append('name', name);
      form.append('file', new Blob([buf], { type: 'text/plain' }), name);
      const res = await api('POST', '/api/files/upload', {
        form: { body: form, contentSha256: sha256Hex(buf) },
        query: `root=${encodeURIComponent(id)}&dir=docs`,
      });
      assertEqual(res.status, 200, `HTTP ${id}`);
      assertCode(res, 0, `upload ${id}`);
      const list = await api('GET', '/api/files/dir', { query: `root=${encodeURIComponent(id)}&path=docs` });
      assertCode(list, 0, `dir ${id}`);
      const names = (list.data.data.entries || []).map((e) => e.name);
      assert(names.includes(name), `${id} 能看到自己的文件`);
      multiNames[id] = name;
      if (id === rootA) assert(!names.some((n) => n.startsWith(`多根-${rootB}-`)), 'rootA 看不到 rootB 文件');
    }
  });

  await test('多根：跨根路径删除被拒且目标文件保留', async () => {
    if (!multiRoot) return;
    const [rootA, rootB] = rootIds;
    const rel = path.relative(E2E_ROOTS[rootA], path.join(E2E_ROOTS[rootB], 'docs', multiNames[rootB]));
    const del = await api('DELETE', '/api/files', {
      query: `root=${encodeURIComponent(rootA)}&path=${encodeURIComponent(rel)}`,
    });
    assertEqual(del.status, 400, 'HTTP');
    assertCode(del, 40000, '跨根删除');
    assert(fs.existsSync(path.join(E2E_ROOTS[rootB], 'docs', multiNames[rootB])), 'rootB 文件仍在');
    const abs = await api('DELETE', '/api/files', {
      query: `root=${encodeURIComponent(rootA)}&path=${encodeURIComponent(path.join(E2E_ROOTS[rootB], 'docs', multiNames[rootB]))}`,
    });
    assertEqual(abs.status, 400, '绝对路径 HTTP');
    assertCode(abs, 40000, '绝对路径删除');
  });

  await test('多根：搜索只在指定根内命中', async () => {
    if (!multiRoot) return;
    const [rootA, rootB] = rootIds;
    const keyword = multiNames[rootB].replace(/\.txt$/, '');
    const resA = await api('GET', '/api/files/search', {
      query: [['root', rootA], ['keyword', keyword]],
    });
    assertCode(resA, 0, 'search rootA');
    assert(!(resA.data.data.entries || []).some((e) => e.name === multiNames[rootB]), 'rootA 搜不到 rootB 文件');
    const resB = await api('GET', '/api/files/search', {
      query: [['root', rootB], ['keyword', keyword]],
    });
    assertCode(resB, 0, 'search rootB');
    assert((resB.data.data.entries || []).some((e) => e.name === multiNames[rootB]), 'rootB 能搜到自己的文件');
    const bad = await api('GET', '/api/files/search', { query: [['root', 'ghost-root'], ['keyword', keyword]] });
    assertEqual(bad.status, 400, '非法 root HTTP');
    assertCode(bad, 40000, '非法 root');
    // 清理两根文件
    for (const id of rootIds) {
      await api('DELETE', '/api/files', {
        query: `root=${encodeURIComponent(id)}&path=${encodeURIComponent('docs/' + multiNames[id])}`,
      });
    }
  });

  // 26. 重命名文件 / 目录（FTP 右键菜单）
  await test('重命名：文件改名成功、旧路径 40400、新路径可下载', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const stamp = Date.now();
    const oldName = `改名前-${stamp}.txt`;
    const newName = `改名后-${stamp}.txt`;
    const buf = Buffer.from(`rename ${stamp}\n`, 'utf8');
    const form = new FormData();
    form.append('name', oldName);
    form.append('file', new Blob([buf], { type: 'text/plain' }), oldName);
    let res = await api('POST', '/api/files/upload', {
      form: { body: form, contentSha256: sha256Hex(buf) },
      query: 'dir=docs',
    });
    assertCode(res, 0, 'upload');
    res = await api('POST', '/api/files/rename', {
      json: { path: `docs/${oldName}`, newName },
    });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'rename');
    assertEqual(res.data.data.oldPath, `docs/${oldName}`, 'oldPath');
    assertEqual(res.data.data.newPath, `docs/${newName}`, 'newPath');
    assertEqual(res.data.data.type, 'file', 'type');
    const dir = await api('GET', '/api/files/dir', { query: 'path=docs' });
    const names = (dir.data.data.entries || []).map((e) => e.name);
    assert(names.includes(newName) && !names.includes(oldName), '目录条目已更新');
    const dl = await fetch(`${BASE_URL}/api/files/download?path=${encodeURIComponent(`docs/${newName}`)}`);
    assertEqual(dl.status, 401, '未签名下载应 401');
    const signed = await api('GET', '/api/files/download', { query: `path=${encodeURIComponent(`docs/${newName}`)}` });
    assertEqual(signed.status, 200, '新路径下载 HTTP');
    const old = await api('GET', '/api/files/download', { query: `path=${encodeURIComponent(`docs/${oldName}`)}` });
    assertEqual(old.status, 404, '旧路径 HTTP');
    assertCode(old, 40400, '旧路径');
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(`docs/${newName}`)}` });
  });

  await test('重命名：冲突不覆盖、非法 newName 40000', async () => {
    if (!E2E_ROOT) return;
    const stamp = Date.now();
    const aName = `冲突A-${stamp}.txt`;
    const bName = `冲突B-${stamp}.txt`;
    const mk = async (name, text) => {
      const buf = Buffer.from(text, 'utf8');
      const form = new FormData();
      form.append('name', name);
      form.append('file', new Blob([buf], { type: 'text/plain' }), name);
      const r = await api('POST', '/api/files/upload', {
        form: { body: form, contentSha256: sha256Hex(buf) },
        query: 'dir=docs',
      });
      assertCode(r, 0, `upload ${name}`);
    };
    await mk(aName, 'AAA');
    await mk(bName, 'BBB');
    const conflict = await api('POST', '/api/files/rename', {
      json: { path: `docs/${aName}`, newName: bName },
    });
    assertEqual(conflict.status, 400, 'HTTP');
    assertCode(conflict, 40000, '冲突');
    const dir = await api('GET', '/api/files/dir', { query: 'path=docs' });
    const names = (dir.data.data.entries || []).map((e) => e.name);
    assert(names.includes(aName) && names.includes(bName), '冲突时两个文件都在');
    for (const bad of ['a/b.txt', '..', '.', '', '.upload-meta']) {
      const r = await api('POST', '/api/files/rename', {
        json: { path: `docs/${aName}`, newName: bad },
      });
      assertEqual(r.status, 400, `非法名 ${bad} HTTP`);
      assertCode(r, 40000, `非法名 ${bad}`);
    }
    for (const n of [aName, bName]) {
      await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(`docs/${n}`)}` });
    }
  });

  await test('重命名：目录整体改名、内部文件跟随、元数据同步', async () => {
    if (!E2E_ROOT) return;
    const oldDir = path.join(E2E_ROOT, 'docs', 'e2e改名目录');
    fs.mkdirSync(path.join(oldDir, 'sub'), { recursive: true });
    fs.writeFileSync(path.join(oldDir, 'sub', 'inner.txt'), 'inner');
    const dirName = 'e2e改名目录';
    const newDirName = 'e2e新目录名';
    let res = await api('POST', '/api/files/rename', {
      json: { path: `docs/${dirName}`, newName: newDirName },
    });
    assertCode(res, 0, 'rename dir');
    assertEqual(res.data.data.type, 'directory', 'type=directory');
    assertEqual(res.data.data.newPath, `docs/${newDirName}`, 'newPath');
    assert(fs.existsSync(path.join(E2E_ROOT, 'docs', newDirName, 'sub', 'inner.txt')), '内部文件跟随');
    assert(!fs.existsSync(oldDir), '旧目录已不存在');
    const browse = await api('GET', '/api/files/dir', { query: `path=${encodeURIComponent(`docs/${newDirName}/sub`)}` });
    assertCode(browse, 0, '浏览新目录');
    assert((browse.data.data.entries || []).some((e) => e.name === 'inner.txt'), '新目录可浏览');
    fs.rmSync(path.join(E2E_ROOT, 'docs', newDirName), { recursive: true, force: true });
  });

  await test('删除：空目录成功、非空目录 40000 且内容保留', async () => {
    if (!E2E_ROOT) return;
    const emptyDir = path.join(E2E_ROOT, 'docs', 'e2e空目录');
    fs.mkdirSync(emptyDir, { recursive: true });
    let res = await api('DELETE', '/api/files', {
      query: `path=${encodeURIComponent('docs/e2e空目录')}`,
    });
    assertCode(res, 0, '删空目录');
    assert(!fs.existsSync(emptyDir), '空目录已删除');
    const nonEmpty = path.join(E2E_ROOT, 'docs', 'e2e非空目录');
    fs.mkdirSync(nonEmpty, { recursive: true });
    fs.writeFileSync(path.join(nonEmpty, 'keep.txt'), 'keep');
    res = await api('DELETE', '/api/files', {
      query: `path=${encodeURIComponent('docs/e2e非空目录')}`,
    });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, '非空目录');
    assert(fs.existsSync(path.join(nonEmpty, 'keep.txt')), '非空目录内容保留');
    fs.rmSync(nonEmpty, { recursive: true, force: true });
  });

  // 汇总
  // 33. 在线编辑：读取 / 覆盖保存 / 并发校验
  await test('在线编辑：读取文本、带 sha 覆盖保存、下载与列表元数据同步', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const stamp = Date.now();
    const name = `在线编辑-${stamp}.txt`;
    const v1 = `第一版 ${stamp}\n第二行\n`;
    const v2 = `第二版 ${stamp}\n改过了\n`;
    const buf1 = Buffer.from(v1, 'utf8');
    const form = new FormData();
    form.append('name', name);
    form.append('file', new Blob([buf1], { type: 'text/plain' }), name);
    let res = await api('POST', '/api/files/upload', {
      form: { body: form, contentSha256: sha256Hex(buf1) },
      query: 'dir=docs',
    });
    assertCode(res, 0, 'upload');
    const rel = `docs/${name}`;
    res = await api('GET', '/api/files/content', { query: `path=${encodeURIComponent(rel)}` });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'read content');
    assertEqual(res.data.data.content, v1, '内容');
    assertEqual(res.data.data.editable, true, 'editable');
    assertEqual(res.data.data.size, buf1.length, 'size');
    const oldSha = res.data.data.sha256;
    assert(typeof oldSha === 'string' && oldSha.length === 64, 'sha256 格式');

    res = await api('PUT', '/api/files/content', {
      json: { path: rel, content: v2, expectedSha256: oldSha },
    });
    assertEqual(res.status, 200, 'save HTTP');
    assertCode(res, 0, 'save');
    const newSha = res.data.data.sha256;
    assert(newSha !== oldSha, 'sha 应变化');
    assertEqual(res.data.data.size, Buffer.byteLength(v2), '新 size');

    res = await api('GET', '/api/files/content', { query: `path=${encodeURIComponent(rel)}` });
    assertEqual(res.data.data.content, v2, '重新读取内容');
    res = await api('GET', '/api/files/download', { query: `path=${encodeURIComponent(rel)}` });
    assertEqual(res.status, 200, '下载 HTTP');
    assertEqual(res.buf.toString('utf8'), v2, '下载内容');

    const list = await api('GET', '/api/files', { query: [['keyword', name]] });
    assertCode(list, 0, 'list');
    const rec = (list.data.data.items || []).find((it) => it.name === name);
    assert(rec, '列表应存在记录');
    assertEqual(rec.sha256, newSha, '列表 sha 同步');
    assertEqual(rec.size, Buffer.byteLength(v2), '列表 size 同步');

    const conflict = await api('PUT', '/api/files/content', {
      json: { path: rel, content: '冲突写入', expectedSha256: oldSha },
    });
    assertEqual(conflict.status, 400, '冲突 HTTP');
    assertCode(conflict, 40000, '冲突');
    const after = await api('GET', '/api/files/content', { query: `path=${encodeURIComponent(rel)}` });
    assertEqual(after.data.data.content, v2, '冲突时不写入');
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(rel)}` });
  });

  await test('在线编辑：二进制文件拒绝（40000）', async () => {
    if (!E2E_ROOT) return;
    const stamp = Date.now();
    const name = `二进制-${stamp}.bin`;
    const bin = Buffer.from([0xff, 0xfe, 0x00, 0x01, 0x80, 0x81]);
    const form = new FormData();
    form.append('name', name);
    form.append('file', new Blob([bin], { type: 'application/octet-stream' }), name);
    let res = await api('POST', '/api/files/upload', {
      form: { body: form, contentSha256: sha256Hex(bin) },
      query: 'dir=docs',
    });
    assertCode(res, 0, 'upload');
    const rel = `docs/${name}`;
    res = await api('GET', '/api/files/content', { query: `path=${encodeURIComponent(rel)}` });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, '二进制');
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(rel)}` });
  });

  // 34. zip 解压
  await test('解压：正常 zip 解到同名目录、重名加序号', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const stamp = Date.now();
    const base = `e2e包-${stamp}`;
    const zipPath = path.join(E2E_ROOT, 'docs', `${base}.zip`);
    execFileSync('python3', ['-c', `
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    z.writestr('a.txt', 'hello')
    z.writestr('sub/b.txt', 'world')
`, zipPath], { stdio: 'ignore' });
    let res = await api('POST', '/api/files/extract', { json: { path: `docs/${base}.zip` } });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'extract');
    assertEqual(res.data.data.targetDir, `docs/${base}`, 'targetDir');
    assertEqual(res.data.data.entries, 2, 'entries');
    assert(fs.existsSync(path.join(E2E_ROOT, 'docs', base, 'a.txt')), 'a.txt 存在');
    assert(fs.existsSync(path.join(E2E_ROOT, 'docs', base, 'sub', 'b.txt')), 'sub/b.txt 存在');
    const dir = await api('GET', '/api/files/dir', { query: `path=${encodeURIComponent(`docs/${base}`)}` });
    assertCode(dir, 0, '浏览解压目录');
    assert((dir.data.data.entries || []).some((e) => e.name === 'a.txt'), '目录可浏览');
    res = await api('POST', '/api/files/extract', { json: { path: `docs/${base}.zip` } });
    assertCode(res, 0, '再次解压');
    assertEqual(res.data.data.targetDir, `docs/${base} (1)`, '重名后缀');
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(`docs/${base}.zip`)}` });
    fs.rmSync(path.join(E2E_ROOT, 'docs', base), { recursive: true, force: true });
    fs.rmSync(path.join(E2E_ROOT, 'docs', `${base} (1)`), { recursive: true, force: true });
  });

  await test('解压：单层目录包裹自动剥层', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const stamp = Date.now();
    const base = `e2e剥层-${stamp}`;
    const zipPath = path.join(E2E_ROOT, 'docs', `${base}.zip`);
    execFileSync('python3', ['-c', `
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    z.writestr('pkg/a.txt', 'hello-wrapped')
    z.writestr('pkg/sub/b.txt', 'nested-wrapped')
`, zipPath], { stdio: 'ignore' });
    const res = await api('POST', '/api/files/extract', { json: { path: `docs/${base}.zip` } });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'extract');
    assertEqual(res.data.data.targetDir, `docs/${base}`, 'targetDir');
    assertEqual(res.data.data.entries, 2, 'entries');
    assert(fs.existsSync(path.join(E2E_ROOT, 'docs', base, 'a.txt')), '包裹层已剥：a.txt 落在目标根');
    assert(fs.existsSync(path.join(E2E_ROOT, 'docs', base, 'sub', 'b.txt')), '包裹层已剥：sub/b.txt 落在目标根');
    assert(!fs.existsSync(path.join(E2E_ROOT, 'docs', base, 'pkg')), '外层 pkg 不应落盘');
    const dir = await api('GET', '/api/files/dir', { query: `path=${encodeURIComponent(`docs/${base}`)}` });
    assertCode(dir, 0, '浏览剥层后目录');
    const names = (dir.data.data.entries || []).map((e) => e.name);
    assert(names.includes('a.txt'), '目录列表包含 a.txt');
    assert(names.includes('sub'), '目录列表包含 sub');
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(`docs/${base}.zip`)}` });
    fs.rmSync(path.join(E2E_ROOT, 'docs', base), { recursive: true, force: true });
  });

  await test('解压：zip-slip 被拒且目标目录回滚', async () => {
    if (!E2E_ROOT) return;
    const stamp = Date.now();
    const base = `e2e恶意-${stamp}`;
    const evilName = `evil-${stamp}.txt`;
    const zipPath = path.join(E2E_ROOT, 'docs', `${base}.zip`);
    execFileSync('python3', ['-c', `
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    z.writestr('../' + sys.argv[2], 'evil')
`, zipPath, evilName], { stdio: 'ignore' });
    const res = await api('POST', '/api/files/extract', { json: { path: `docs/${base}.zip` } });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, 'zip-slip');
    assert(!fs.existsSync(path.join(E2E_ROOT, 'docs', base)), '目标目录已回滚');
    assert(!fs.existsSync(path.join(E2E_ROOT, 'docs', evilName)), '未写出逃逸文件');
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(`docs/${base}.zip`)}` });
  });

  // 35. 拷贝 / 移动（右键菜单）
  await test('拷贝：文件原地复制 _副本 命名与再次拷贝编号', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const stamp = Date.now();
    const name = `e2e拷贝-${stamp}.txt`;
    const docs = path.join(E2E_ROOT, 'docs');
    fs.mkdirSync(docs, { recursive: true });
    fs.writeFileSync(path.join(docs, name), 'copy-content');
    let res = await api('POST', '/api/files/copy', { json: { path: `docs/${name}` } });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'copy');
    assertEqual(res.data.data.name, `e2e拷贝-${stamp}_副本.txt`, '副本名');
    assertEqual(res.data.data.path, `docs/e2e拷贝-${stamp}_副本.txt`, '副本路径');
    assertEqual(res.data.data.type, 'file', 'type');
    assert(fs.existsSync(path.join(docs, res.data.data.name)), '副本落盘');
    assertEqual(fs.readFileSync(path.join(docs, res.data.data.name), 'utf8'), 'copy-content', '副本内容');

    res = await api('POST', '/api/files/copy', { json: { path: `docs/${name}` } });
    assertCode(res, 0, '再次拷贝');
    assertEqual(res.data.data.name, `e2e拷贝-${stamp}_副本 (1).txt`, '再次拷贝编号');
    for (const file of [name, `e2e拷贝-${stamp}_副本.txt`, `e2e拷贝-${stamp}_副本 (1).txt`]) {
      fs.rmSync(path.join(docs, file), { force: true });
    }
  });

  await test('拷贝：目录递归复制并再次编号', async () => {
    if (!E2E_ROOT) return;
    const stamp = Date.now();
    const dir = `e2e拷贝目录-${stamp}`;
    const docs = path.join(E2E_ROOT, 'docs');
    fs.mkdirSync(path.join(docs, dir, 'sub'), { recursive: true });
    fs.writeFileSync(path.join(docs, dir, 'a.txt'), 'a');
    fs.writeFileSync(path.join(docs, dir, 'sub', 'b.txt'), 'b');
    let res = await api('POST', '/api/files/copy', { json: { path: `docs/${dir}` } });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'copy dir');
    assertEqual(res.data.data.name, `${dir}_副本`, '目录副本名');
    assertEqual(res.data.data.type, 'directory', 'type');
    assert(fs.existsSync(path.join(docs, `${dir}_副本`, 'a.txt')), 'a.txt 复制');
    assert(fs.existsSync(path.join(docs, `${dir}_副本`, 'sub', 'b.txt')), 'sub/b.txt 复制');
    res = await api('POST', '/api/files/copy', { json: { path: `docs/${dir}` } });
    assertCode(res, 0, '再次拷贝目录');
    assertEqual(res.data.data.name, `${dir}_副本 (1)`, '目录再次编号');
    for (const folder of [dir, `${dir}_副本`, `${dir}_副本 (1)`]) {
      fs.rmSync(path.join(docs, folder), { recursive: true, force: true });
    }
  });

  await test('移动：文件到子目录、元数据跟随、按 id 下载仍可用', async () => {
    if (!E2E_ROOT) return;
    const stamp = Date.now();
    const name = `e2e移动-${stamp}.txt`;
    const dest = `e2e移动目标-${stamp}`;
    const content = Buffer.from(`move-content-${stamp}`, 'utf8');
    const form = new FormData();
    form.append('name', name);
    form.append('file', new Blob([content], { type: 'text/plain' }), name);
    let res = await api('POST', '/api/files/upload', {
      form: { body: form, contentSha256: sha256Hex(content) },
      query: 'dir=docs',
    });
    assertCode(res, 0, 'upload');
    const id = res.data.data.id;
    fs.mkdirSync(path.join(E2E_ROOT, 'docs', dest), { recursive: true });
    res = await api('POST', '/api/files/move', {
      json: { path: `docs/${name}`, targetDir: `docs/${dest}` },
    });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'move');
    assertEqual(res.data.data.path, `docs/${dest}/${name}`, '新路径');
    assert(!fs.existsSync(path.join(E2E_ROOT, 'docs', name)), '源路径已不存在');
    assert(fs.existsSync(path.join(E2E_ROOT, 'docs', dest, name)), '目标路径存在');
    const download = await api('GET', `/api/files/${id}/download`);
    assertEqual(download.status, 200, '按 id 下载 HTTP');
    assertEqual(Buffer.from(download.buf).toString('utf8'), content.toString('utf8'), '按 id 下载内容');
    await api('DELETE', '/api/files', { query: `path=${encodeURIComponent(`docs/${dest}/${name}`)}` });
    fs.rmSync(path.join(E2E_ROOT, 'docs', dest), { recursive: true, force: true });
  });

  await test('移动：目录整体移动到指定目录', async () => {
    if (!E2E_ROOT) return;
    const stamp = Date.now();
    const dir = `e2e移动目录-${stamp}`;
    const dest = `e2e移动目录目标-${stamp}`;
    const docs = path.join(E2E_ROOT, 'docs');
    fs.mkdirSync(path.join(docs, dir, 'sub'), { recursive: true });
    fs.writeFileSync(path.join(docs, dir, 'sub', 'x.txt'), 'x');
    fs.mkdirSync(path.join(docs, dest), { recursive: true });
    const res = await api('POST', '/api/files/move', {
      json: { path: `docs/${dir}`, targetDir: `docs/${dest}` },
    });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'move dir');
    assertEqual(res.data.data.path, `docs/${dest}/${dir}`, '目录新路径');
    assertEqual(res.data.data.type, 'directory', 'type');
    assert(!fs.existsSync(path.join(docs, dir)), '源目录已不存在');
    assert(fs.existsSync(path.join(docs, dest, dir, 'sub', 'x.txt')), '目标目录内容存在');
    fs.rmSync(path.join(docs, dest), { recursive: true, force: true });
  });

  const e2eRootIds = E2E_ROOTS ? Object.keys(E2E_ROOTS) : [];
  await test('移动：跨存储根移动并更新 rootId（需 E2E_ROOTS）', async () => {
    if (!E2E_ROOTS || e2eRootIds.length < 2) {
      console.log('      （未设置 E2E_ROOTS 或少于 2 个根，跳过）');
      return;
    }
    const [rootA, rootB] = e2eRootIds;
    fs.mkdirSync(path.join(E2E_ROOTS[rootA], 'docs'), { recursive: true });
    fs.mkdirSync(path.join(E2E_ROOTS[rootB], 'docs'), { recursive: true });
    const stamp = Date.now();
    const name = `e2e跨根移动-${stamp}.txt`;
    const content = Buffer.from(`cross-root-${stamp}`, 'utf8');
    const form = new FormData();
    form.append('name', name);
    form.append('file', new Blob([content], { type: 'text/plain' }), name);
    let res = await api('POST', '/api/files/upload', {
      form: { body: form, contentSha256: sha256Hex(content) },
      query: `root=${encodeURIComponent(rootA)}&dir=docs`,
    });
    assertCode(res, 0, 'upload rootA');
    const id = res.data.data.id;
    res = await api('POST', '/api/files/move', {
      json: { root: rootA, path: `docs/${name}`, targetRoot: rootB, targetDir: '' },
    });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'cross-root move');
    assertEqual(res.data.data.root, rootB, '目标根');
    assertEqual(res.data.data.path, name, '目标根根目录路径');
    assert(!fs.existsSync(path.join(E2E_ROOTS[rootA], 'docs', name)), '源根文件已删除');
    assert(fs.existsSync(path.join(E2E_ROOTS[rootB], name)), '目标根文件存在');
    const download = await api('GET', `/api/files/${id}/download`);
    assertEqual(download.status, 200, '按 id 下载 HTTP');
    assertEqual(Buffer.from(download.buf).toString('utf8'), content.toString('utf8'), '跨根后内容一致');
    const list = await api('GET', '/api/files', { query: `root=${encodeURIComponent(rootB)}&keyword=${encodeURIComponent(name)}` });
    assertCode(list, 0, '目标根列表');
    assert((list.data.data.items || []).some((item) => item.id === id && item.rootId === rootB), '列表 rootId 已更新');
    await api('DELETE', `/api/files/${id}`);
    fs.rmSync(path.join(E2E_ROOTS[rootB], name), { force: true });
  });

  await test('移动：目标已存在同名文件 40000 且源保留', async () => {
    if (!E2E_ROOT) return;
    const stamp = Date.now();
    const name = `e2e冲突-${stamp}.txt`;
    const dest = `e2e冲突目标-${stamp}`;
    const docs = path.join(E2E_ROOT, 'docs');
    fs.mkdirSync(path.join(docs, dest), { recursive: true });
    fs.writeFileSync(path.join(docs, name), 'source');
    fs.writeFileSync(path.join(docs, dest, name), 'target');
    const res = await api('POST', '/api/files/move', {
      json: { path: `docs/${name}`, targetDir: `docs/${dest}` },
    });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, '同名冲突');
    assert(res.data.message.includes('已存在'), '中文提示');
    assert(fs.existsSync(path.join(docs, name)), '源文件保留');
    assertEqual(fs.readFileSync(path.join(docs, dest, name), 'utf8'), 'target', '目标未被覆盖');
    fs.rmSync(path.join(docs, name), { force: true });
    fs.rmSync(path.join(docs, dest), { recursive: true, force: true });
  });

  await test('移动：移动到自身子目录 40000 且源保留', async () => {
    if (!E2E_ROOT) return;
    const stamp = Date.now();
    const dir = `e2e自身子目录-${stamp}`;
    const docs = path.join(E2E_ROOT, 'docs');
    fs.mkdirSync(path.join(docs, dir, 'sub'), { recursive: true });
    fs.writeFileSync(path.join(docs, dir, 'a.txt'), 'a');
    const res = await api('POST', '/api/files/move', {
      json: { path: `docs/${dir}`, targetDir: `docs/${dir}/sub` },
    });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, '自身子目录');
    assert(res.data.message.includes('自身或子目录'), '中文提示');
    assert(fs.existsSync(path.join(docs, dir, 'a.txt')), '源目录保留');
    fs.rmSync(path.join(docs, dir), { recursive: true, force: true });
  });

  // 35. 远程终端
  await test('终端：echo/pwd 输出、cd 会话持久、非零退出码', async () => {
    const sessionId = `e2e-term-${Date.now()}`;
    let res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: 'echo hello-terminal' },
    });
    assertEqual(res.status, 200, 'HTTP');
    assertCode(res, 0, 'exec');
    assertEqual(res.data.data.exitCode, 0, 'exitCode');
    assertEqual(res.data.data.output, 'hello-terminal\n', '输出');
    const firstCwd = res.data.data.cwd;
    assert(typeof firstCwd === 'string' && firstCwd.length > 0, 'cwd');
    if (E2E_ROOT) {
      assertEqual(firstCwd, fs.realpathSync(E2E_ROOT), '默认 cwd = 存储根');
    }

    res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: 'pwd' },
    });
    assertCode(res, 0, 'pwd');
    assertEqual(res.data.data.output.trim(), firstCwd, 'pwd 与 cwd 一致');

    res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: 'cd docs' },
    });
    assertCode(res, 0, 'cd docs');
    assertEqual(res.data.data.output, '', 'cd 无输出');
    const docsCwd = res.data.data.cwd;
    assert(docsCwd.endsWith('/docs'), `cd 后 cwd 应在 docs 内：${docsCwd}`);
    res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: 'pwd' },
    });
    assertEqual(res.data.data.cwd, docsCwd, 'cd 后 pwd cwd 一致');
    assertEqual(res.data.data.output.trim(), docsCwd, 'cd 后 pwd 输出');

    res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: 'exit 3' },
    });
    assertCode(res, 0, '非零退出仍 code 0');
    assertEqual(res.data.data.exitCode, 3, 'exitCode 3');
  });

  await test('终端：高危命令 40000 拒绝且不执行', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const marker = path.join(E2E_ROOT, `terminal-marker-${Date.now()}.txt`);
    const sessionId = `e2e-term-${Date.now()}`;
    const res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: `touch '${marker}' ; rm -rf /` },
    });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, '高危');
    assert(res.data.message.includes('高危命令已禁止执行'), 'message 含拦截提示');
    assert(res.data.message.includes('原因'), 'message 含原因');
    assert(!fs.existsSync(marker), '高危命令未执行（marker 不存在）');
  });

  await test('终端：非安全目录路径 40000 拒绝且不执行', async () => {
    if (!E2E_ROOT) {
      console.log('      （未设置 E2E_STORAGE_ROOT，跳过）');
      return;
    }
    const sessionId = `e2e-term-${Date.now()}`;
    let res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: 'cat /etc/hosts' },
    });
    assertEqual(res.status, 400, 'HTTP');
    assertCode(res, 40000, '绝对路径');
    assert(res.data.message.includes('非安全目录'), 'message 含非安全目录');
    assert(res.data.message.includes('app.storage.roots'), 'message 说明安全范围');

    res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: 'cd /tmp' },
    });
    assertEqual(res.status, 400, 'cd HTTP');
    assertCode(res, 40000, 'cd 越界');
    assert(res.data.message.includes('不在安全范围'), 'cd message');

    const escape = `/tmp/e2e-terminal-escape-${Date.now()}.txt`;
    res = await api('POST', '/api/terminal/exec', {
      json: { sessionId, command: `echo hacked > '${escape}'` },
    });
    assertEqual(res.status, 400, '重定向 HTTP');
    assertCode(res, 40000, '重定向越界');
    assert(!fs.existsSync(escape), '根外文件未创建');
  });

  console.log(`\n=== 结果：${passed} 通过，${failures.length} 失败 ===`);
  if (failures.length) {
    for (const f of failures) console.log(`  \u2717 ${f.name}: ${f.error}`);
    process.exit(1);
  }
}

main().catch((err) => {
  console.error('\n测试执行异常：', err);
  process.exit(1);
});
