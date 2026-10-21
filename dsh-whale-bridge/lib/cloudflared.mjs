/**
 * whale-bridge 精简版 CloudflaredManager —— 移植自 wenbin-wb/dsh-bridge lib/cloudflared-manager.mjs（MIT）
 *
 * 只保留 quick tunnel（临时 trycloudflare.com 域名）路径：
 *   - 自动下载/校验 cloudflared 二进制（linux-x64/darwin/win32 覆盖，钉死 2024.10.0）
 *   - spawn `cloudflared tunnel --no-autoupdate --url http://127.0.0.1:PORT`
 *   - stdout/stderr 正则解析 `https://xxx.trycloudflare.com` 即公网 URL
 *   - 就绪后 /ready 健康探活：连续 3 次失败=degraded、10 次强制重建（防假死）
 *   - 退避自愈 5s→5min；确定性失败（用法/配置错误）直接 error 不重试
 *   - 日志落盘 5MB 轮转（去 token）
 * 砍掉：named/token 隧道、网络参数能力探测、数据面探针、QrCache（二维码前端生成）。
 */

import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream, createReadStream, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync, unlinkSync } from 'node:fs';
import { chmod, unlink, rename } from 'node:fs/promises';
import { homedir, platform, arch } from 'node:os';
import { join, dirname } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { createHash } from 'node:crypto';
import { get as httpsGet } from 'node:https';
import { get as httpGet } from 'node:http';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

const IS_WINDOWS = process.platform === 'win32';
function execBinSync(bin, args, options) {
  const needsShell = IS_WINDOWS && /\.(cmd|bat)$/i.test(bin);
  return execFileSync(bin, args, { ...options, shell: needsShell });
}

const CLOUDFLARED_VERSION = '2026.7.0';
const DOWNLOAD_TIMEOUT = 5 * 60 * 1000;
const MIN_BINARY_SIZE = 5 * 1024 * 1024;
const HANDSHAKE_TIMEOUT_MS = 90 * 1000;
const RETRY_BASE_MS = 5 * 1000;
const RETRY_MAX_MS = 5 * 60 * 1000;
const DEFAULT_MAX_RETRIES = 12;
const HEALTH_PROBE_INTERVAL_MS = 30 * 1000;
const HEALTH_PROBE_TIMEOUT_MS = 5 * 1000;
const HEALTH_DEGRADED_THRESHOLD = 3;
const HEALTH_RESTART_THRESHOLD = 10;
const LOG_MAX_BYTES = 5 * 1024 * 1024;

/** 解析 metrics 服务地址（探活唯一入口），解析不到则跳过探活 */
export function parseMetricsAddress(text) {
  if (!text) return null;
  const m = /Starting metrics server on (\S+?)\/metrics/.exec(text);
  if (!m) return null;
  const parts = /^([A-Za-z0-9._-]+|\[[0-9A-Fa-f:]+\]):(\d{1,5})$/.exec(m[1]);
  if (!parts) return null;
  const port = Number(parts[2]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  return m[1];
}

/** cloudflared 因配置/用法错误退出 → 重试无意义，直接 error */
function isFatalCloudflaredError(stderrTail) {
  if (!stderrTail) return false;
  return /Incorrect Usage|flag provided but not defined|invalid.*token|unauthorized/i.test(stderrTail);
}

export function parseCloudflaredVersion(output) {
  const m = /version\s+(\d{4}\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)/.exec(output || '');
  return m ? m[1] : null;
}

function getCloudflaredInfo() {
  const key = `${platform()}-${arch()}`;
  const map = {
    'win32-x64': { file: 'cloudflared-windows-amd64.exe', name: 'cloudflared.exe' },
    'win32-arm64': { file: 'cloudflared-windows-arm64.exe', name: 'cloudflared.exe' },
    'darwin-x64': { file: 'cloudflared-darwin-amd64.tgz', name: 'cloudflared' },
    'darwin-arm64': { file: 'cloudflared-darwin-arm64.tgz', name: 'cloudflared' },
    'linux-x64': { file: 'cloudflared-linux-amd64', name: 'cloudflared' },
    'linux-arm64': { file: 'cloudflared-linux-arm64', name: 'cloudflared' },
  };
  const info = map[key];
  if (!info) throw new Error(`不支持的平台: ${key}`);
  return { url: `https://github.com/cloudflare/cloudflared/releases/download/${CLOUDFLARED_VERSION}/${info.file}`, name: info.name };
}

async function downloadFile(url, dest, onProgress) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('下载超时（5分钟）')), DOWNLOAD_TIMEOUT);
    function doGet(targetUrl, redirects = 0) {
      if (redirects > 5) { clearTimeout(timer); return reject(new Error('重定向次数过多')); }
      httpsGet(targetUrl, (res) => {
        if (res.statusCode === 301 || res.statusCode === 302) { res.resume(); return doGet(res.headers.location, redirects + 1); }
        if (res.statusCode !== 200) { res.resume(); clearTimeout(timer); return reject(new Error(`下载失败: HTTP ${res.statusCode}`)); }
        const total = parseInt(res.headers['content-length'] ?? '0', 10);
        let downloaded = 0;
        res.on('data', (chunk) => { downloaded += chunk.length; if (onProgress && total > 0) onProgress(Math.round(downloaded / total * 100), downloaded, total); });
        const fileStream = createWriteStream(dest);
        pipeline(res, fileStream).then(() => { clearTimeout(timer); resolve(); }).catch((err) => { clearTimeout(timer); reject(err); });
      }).on('error', (err) => { clearTimeout(timer); reject(err); });
    }
    doGet(url);
  });
}

/** 找系统全局 cloudflared（存在性以 --version 成功为准） */
function findSystemCloudflared() {
  const candidates = IS_WINDOWS
    ? ['cloudflared.exe', 'cloudflared', 'C:\\Program Files (x86)\\cloudflared\\cloudflared.exe', 'C:\\Program Files\\cloudflared\\cloudflared.exe']
    : ['cloudflared', '/opt/homebrew/bin/cloudflared', '/usr/local/bin/cloudflared', '/usr/bin/cloudflared', '/bin/cloudflared'];
  for (const bin of candidates) {
    try {
      if (bin.includes('/') || bin.includes('\\')) { if (!existsSync(bin)) continue; }
      const out = execBinSync(bin, ['--version'], { encoding: 'utf8', timeout: 3000 });
      return { path: bin, version: parseCloudflaredVersion(String(out ?? '')) };
    } catch { /* try next */ }
  }
  return null;
}

export class CloudflaredManager {
  /**
   * @param {object} opts
   * @param {number} opts.port 本机被穿透的服务端口（DSH web 端口）
   * @param {string} [opts.home] 自管理二进制目录（默认 ~/.whale-bridge）
   * @param {string} [opts.logFilePath] cloudflared 日志落盘路径
   * @param {Function} [opts.onStateChange] ({phase, detail}) => void
   * @param {Function} [opts.logger] {info,warn,error,debug}
   */
  constructor({ port, home, logFilePath = null, onStateChange, logger }) {
    this.port = port;
    this.home = home || join(homedir(), '.whale-bridge');
    this.logFilePath = logFilePath;
    this.onStateChange = onStateChange;
    this.logger = logger;
    this.process = null;
    this.url = null;
    this.binaryPath = null;
    this.binarySource = null;
    this.binaryVersionActual = null;
    this._stopped = false;
    this._retryTimer = null;
    this._restartCount = 0;
    this._healthRecovering = false;
    this._metricsAddress = null;
    this._metricsParseTail = '';
    this._probeTimer = null;
    this._probeFailures = 0;
    this._probeInFlight = false;
    this._spawnSeq = 0;
    this._readyState = null;
    this._logStream = null;
    this._logBytesWritten = 0;
  }

  /** 异步启动，立即返回 */
  start() {
    this._stopped = false;
    this._restartCount = 0;
    this._healthRecovering = false;
    if (this._retryTimer) { clearTimeout(this._retryTimer); this._retryTimer = null; }
    this._setState('connecting', '正在初始化...');
    this._run().catch((err) => {
      this._log('error', `cloudflared 启动失败: ${err.message}`);
      if (err && err.fatal) { this._setState('error', err.message); return; }
      this._scheduleRestart(`cloudflared 启动失败: ${err.message}`);
    });
  }

  async _run() {
    await this._ensureBinary();
    if (this._stopped) return;
    await this._startProcess();
  }

  _scheduleRestart(reason) {
    if (this._stopped) return;
    if (this._retryTimer) return;
    this._restartCount++;
    if (this._restartCount > DEFAULT_MAX_RETRIES) {
      if (!this._healthRecovering) { this._setState('error', `${reason}（已自动重试 ${DEFAULT_MAX_RETRIES} 次仍失败，请检查网络，或点击「关闭」停止）`); return; }
      // 健康重建链路永不进入终态
    }
    const delay = Math.min(RETRY_BASE_MS * 2 ** (this._restartCount - 1), RETRY_MAX_MS);
    const delayText = delay >= 1000 ? `${Math.round(delay / 1000)}s` : `${delay}ms`;
    const beyondCap = this._restartCount > DEFAULT_MAX_RETRIES;
    this._setState('reconnecting', beyondCap
      ? `${reason}，${delayText} 后继续自动重连（已重试 ${this._restartCount} 次）`
      : `${reason}，${delayText} 后自动重连（第 ${this._restartCount}/${DEFAULT_MAX_RETRIES} 次）`);
    this._retryTimer = setTimeout(() => {
      this._retryTimer = null;
      if (this._stopped) return;
      this._setState('connecting', '正在自动重连...');
      this._run().catch((err) => {
        this._log('error', `cloudflared 自动重连失败: ${err.message}`);
        if (err && err.fatal) { this._setState('error', err.message); return; }
        this._scheduleRestart(`cloudflared 启动失败: ${err.message}`);
      });
    }, delay);
  }

  _terminateProcess() {
    const p = this.process;
    if (!p) return;
    try {
      if (IS_WINDOWS) spawn('taskkill', ['/pid', String(p.pid), '/f', '/t'], { stdio: 'ignore' });
      else p.kill('SIGTERM');
    } catch { /* ignore */ }
  }

  _checkManagedBinaryVersion(binPath) {
    try {
      const out = execBinSync(binPath, ['--version'], { encoding: 'utf8', timeout: 3000 });
      const ver = parseCloudflaredVersion(out);
      if (!ver) return { ok: false, reason: `无法解析版本输出: ${(out || '').trim().slice(0, 80)}` };
      if (ver !== CLOUDFLARED_VERSION) return { ok: false, reason: `版本不匹配: 期望 ${CLOUDFLARED_VERSION}，实际 ${ver}` };
      return { ok: true, version: ver };
    } catch (e) { return { ok: false, reason: e.message }; }
  }

  async _ensureBinary() {
    if (this.binaryPath && existsSync(this.binaryPath)) {
      // 已就绪（start 幂等重入时）
      return;
    }
    const systemBin = findSystemCloudflared();
    if (systemBin) {
      this.binaryPath = systemBin.path;
      this.binarySource = 'system';
      this.binaryVersionActual = systemBin.version;
      this._log('info', `使用系统全局 cloudflared: ${systemBin.path} (version=${systemBin.version ?? '未知'})`);
      return;
    }
    const { url, name } = getCloudflaredInfo();
    const binDir = join(this.home, 'bin');
    const binPath = join(binDir, name);
    this.binaryPath = binPath;
    if (existsSync(binPath)) {
      try {
        if (readFileSync(binPath).length > MIN_BINARY_SIZE) {
          const check = this._checkManagedBinaryVersion(binPath);
          if (check.ok) {
            this.binarySource = 'managed';
            this.binaryVersionActual = check.version;
            this._log('info', `cloudflared 已存在且验证通过 (version=${check.version})`);
            return;
          }
          this._log('warn', `现有 cloudflared 验证失败（${check.reason}），重新下载`);
        }
      } catch (e) { this._log('warn', `现有 cloudflared 验证失败 (${e.message})，重新下载`); }
      await unlink(binPath).catch(() => {});
    }
    this._setState('downloading', '正在下载 cloudflared (~30MB)...');
    this._log('info', `从 ${url} 下载 cloudflared`);
    mkdirSync(binDir, { recursive: true });
    const tempPath = `${binPath}.tmp`;
    try {
      try {
        await downloadFile(url, tempPath, (percent, downloaded, total) => {
          if (this._stopped) return;
          this._setState('downloading', `下载 cloudflared: ${(downloaded / 1048576).toFixed(1)}/${(total / 1048576).toFixed(1)} MB (${percent}%)`);
        });
      } catch (directErr) {
        // 直连失败（国内网络常见）：回落到系统代理 curl
        this._log('warn', `直连下载失败（${directErr.message}），尝试代理 curl`);
        this._setState('downloading', '直连失败，走代理重新下载...');
        await new Promise((resolve, reject) => {
          const { execFile } = require('node:child_process');
          const args = ['-sL', '--max-time', '280', '-o', tempPath, url];
          const proxies = [process.env.HTTPS_PROXY, process.env.https_proxy, 'http://192.168.31.35:7897'].filter(Boolean);
          const attempt = (i) => {
            if (i >= proxies.length) return reject(new Error(`代理下载也失败（最后错误: ${directErr.message}）`));
            execFile('curl', ['-x', proxies[i], ...args], { timeout: 290000 }, (err) => {
              if (err) return attempt(i + 1);
              resolve();
            });
          };
          attempt(0);
        });
      }
      if (url.endsWith('.tgz') || url.endsWith('.tar.gz')) {
        execBinSync('tar', ['-xzf', tempPath, '-C', binDir]);
        await unlink(tempPath).catch(() => {});
      } else {
        if (existsSync(binPath)) await unlink(binPath).catch(() => {});
        await rename(tempPath, binPath);
      }
      if (!IS_WINDOWS) await chmod(binPath, 0o755).catch(() => {});
      const check = this._checkManagedBinaryVersion(binPath);
      if (!check.ok) throw new Error(`下载的 cloudflared 版本校验失败: ${check.reason}`);
      this.binarySource = 'managed';
      this.binaryVersionActual = check.version;
      this._log('info', `cloudflared 下载完成 (version=${check.version})`);
    } catch (err) {
      await unlink(tempPath).catch(() => {});
      throw new Error(`准备 cloudflared 失败: ${err.message}`, { cause: err });
    }
  }

  _startProcess() {
    return new Promise((resolve, reject) => {
      if (this._stopped) return reject(new Error('已取消'));
      this._setState('connecting', '正在连接 Cloudflare...');
      // --metrics 显式启用（2024.10.0 quick tunnel 默认不启 metrics，健康探活会跳过）
      // --protocol http2：QUIC(UDP) 在部分家用网络/NAS 上打洞失败会空转（CPU 99%、边缘连不上），
      // http2 走 TCP 443 更稳（与系统里 com.dustinky.tunnel 同款选择）
      const metricsPort = 41000 + Math.floor(Math.random() * 20000);
      const args = ['tunnel', '--no-autoupdate', '--protocol', 'http2', '--edge-ip-version', '4', '--metrics', `127.0.0.1:${metricsPort}`, '--url', `http://127.0.0.1:${this.port}`];
      this._log('info', `启动 cloudflared: ${this.binaryPath} ${args.join(' ')}`);
      const proc = spawn(this.binaryPath, args, { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, NO_AUTOUPDATE: 'true' } });
      this.process = proc;
      this._metricsAddress = null;
      this._metricsParseTail = '';
      this._spawnSeq++;
      this._openLogStream();

      let resolved = false;
      let timeoutTimer = setTimeout(() => {
        if (resolved) return;
        this._terminateProcess();
        reject(new Error(`等待隧道就绪超时（${HANDSHAKE_TIMEOUT_MS / 1000}s）`));
      }, HANDSHAKE_TIMEOUT_MS);

      const tryResolve = () => {
        if (resolved) return;
        resolved = true;
        clearTimeout(timeoutTimer);
        timeoutTimer = null;
        // 迟到就绪撤回已排定的重试（只撤本次 spawn 的、且进程仍活着）
        const procAlive = proc.exitCode === null && proc.signalCode === null;
        if (this._retryTimer && this.process === proc && procAlive) { clearTimeout(this._retryTimer); this._retryTimer = null; }
        this._restartCount = 0;
        this._healthRecovering = false;
        this._startHealthProbe();
        resolve();
      };

      const parseUrl = (text) => {
        const match = text.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/);
        if (match && !resolved) {
          this.url = match[0];
          this._setState('ready', `临时隧道已建立：${this.url}`);
          this._log('info', `cloudflared 临时隧道就绪: ${this.url}`);
          tryResolve();
        }
      };

      const captureMetricsAddress = (text) => {
        if (this._metricsAddress) return;
        const buffered = (this._metricsParseTail + text).slice(-512);
        this._metricsParseTail = buffered;
        const addr = parseMetricsAddress(buffered);
        if (!addr) return;
        this._metricsAddress = addr;
        if (resolved && !this._stopped && !this._probeTimer) this._startHealthProbe();
      };

      proc.stdout.on('data', (d) => { const t = d.toString(); this._appendLog(t); captureMetricsAddress(t); parseUrl(t); });
      proc.stderr.on('data', (d) => { const t = d.toString(); this._appendLog(t); captureMetricsAddress(t); parseUrl(t); });

      proc.on('exit', (code, signal) => {
        if (timeoutTimer) { clearTimeout(timeoutTimer); timeoutTimer = null; }
        this._stopHealthProbe();
        this._closeLogStream();
        const stillCurrent = this.process === proc;
        if (stillCurrent) this.process = null;
        this.url = null;
        if (!resolved) {
          reject(new Error(`cloudflared 退出，code=${code ?? ''} signal=${signal ?? ''}`));
        } else if (!this._stopped && stillCurrent) {
          this._scheduleRestart(`cloudflared 意外退出（code=${code ?? ''}）`);
        }
      });
      proc.on('error', (err) => {
        if (!resolved) { clearTimeout(timeoutTimer); reject(new Error(`cloudflared 启动异常: ${err.message}`)); }
      });
    });
  }

  //#region 健康探活（防假死：进程活着但边缘连接全掉）

  _startHealthProbe() {
    this._stopHealthProbe();
    if (!this._metricsAddress) {
      this._log('warn', '未识别到 cloudflared metrics 地址，跳过健康探活（假死将无法自动发现）');
      return;
    }
    this._probeTimer = setInterval(() => {
      this._probeOnce().catch((err) => this._log('warn', `健康探活异常（已忽略）: ${err?.message ?? err}`));
    }, HEALTH_PROBE_INTERVAL_MS);
    this._probeTimer.unref?.();
  }

  _stopHealthProbe() {
    if (this._probeTimer) { clearInterval(this._probeTimer); this._probeTimer = null; }
  }

  async _probeOnce() {
    if (this._probeInFlight) return;
    const addr = this._metricsAddress;
    if (!addr) return;
    const seq = this._spawnSeq;
    this._probeInFlight = true;
    let result;
    try { result = await this._checkTunnelReady(addr); }
    catch (err) { result = { healthy: false, reason: `探针内部异常: ${err?.message ?? err}` }; }
    finally { this._probeInFlight = false; }
    if (this._stopped || this._spawnSeq !== seq) return;

    if (result.healthy) {
      if (this._probeFailures >= HEALTH_DEGRADED_THRESHOLD) {
        this._log('info', `隧道健康已自行恢复（此前连续失败 ${this._probeFailures} 次）`);
        if (this._readyState) this._setState(this._readyState.phase, this._readyState.detail);
      }
      this._probeFailures = 0;
      return;
    }
    this._probeFailures++;
    const fails = this._probeFailures;
    if (fails === HEALTH_DEGRADED_THRESHOLD) {
      this._setState('reconnecting', `健康探活连续 ${fails} 次失败：连接器已无健康边缘连接，观察能否自行恢复（${fails}/${HEALTH_RESTART_THRESHOLD}）`);
    }
    if (fails < HEALTH_RESTART_THRESHOLD) return;
    // 判定假死 → 强制重建（重建链路永不进入终态）
    this._log('error', `连接器连续 ${fails} 次探活失败（约 ${Math.round(fails * HEALTH_PROBE_INTERVAL_MS / 60000)} 分钟）：判定假死，强制重建`);
    this._stopHealthProbe();
    this._healthRecovering = true;
    this._terminateProcess();
  }

  /** /ready 判据：HTTP 200 且 readyConnections > 0 */
  _checkTunnelReady(addr) {
    return new Promise((resolve) => {
      let settled = false;
      let deadline = setTimeout(() => { settled = true; try { req?.destroy(); } catch { /* ignore */ } resolve({ healthy: false, reason: `探活超过 ${HEALTH_PROBE_TIMEOUT_MS}ms` }); }, HEALTH_PROBE_TIMEOUT_MS);
      let req = null;
      const done = (healthy, reason) => {
        if (settled) return;
        settled = true;
        clearTimeout(deadline);
        try { req?.destroy(); } catch { /* ignore */ }
        resolve({ healthy, reason });
      };
      try {
        req = httpGet(`http://${addr}/ready`, { timeout: HEALTH_PROBE_TIMEOUT_MS }, (res) => {
          let body = '';
          res.setEncoding('utf8');
          res.on('data', (c) => { body += c; });
          res.on('end', () => {
            if (res.statusCode !== 200) return done(false, `HTTP ${res.statusCode}`);
            try {
              const parsed = JSON.parse(body);
              const conns = Number(parsed?.readyConnections);
              done(conns > 0, conns > 0 ? '' : `HTTP 200 但 readyConnections=${parsed?.readyConnections}`);
            } catch (err) { done(false, `响应无法解析: ${err.message}`); }
          });
        });
      } catch (err) { done(false, `无法发起探活: ${err.message}`); return; }
      req.on('timeout', () => done(false, 'socket 不活动超时'));
      req.on('error', (err) => done(false, `请求失败: ${err.message}`));
    });
  }

  //#endregion

  //#region 日志落盘（脱敏 + 5MB 轮转）

  _openLogStream() {
    this._closeLogStream();
    if (!this.logFilePath) return;
    try {
      mkdirSync(dirname(this.logFilePath), { recursive: true });
      this._logStream = createWriteStream(this.logFilePath, { flags: 'a' });
      this._logBytesWritten = existsSync(this.logFilePath) ? readFileSync(this.logFilePath).length : 0;
    } catch (e) { this._logStream = null; }
  }

  _closeLogStream() {
    if (this._logStream) { try { this._logStream.end(); } catch { /* ignore */ } this._logStream = null; }
  }

  _appendLog(text) {
    if (!this._logStream || !text) return;
    if (this._logBytesWritten + Buffer.byteLength(text) >= LOG_MAX_BYTES) {
      this._closeLogStream();
      try { renameSync(this.logFilePath, `${this.logFilePath}.1`); } catch { /* ignore */ }
      this._openLogStream();
      if (!this._logStream) return;
    }
    this._logStream.write(text);
    this._logBytesWritten += Buffer.byteLength(text);
  }

  //#endregion

  _setState(phase, detail, { record = true } = {}) {
    if (phase === 'ready' && record) this._readyState = { phase, detail };
    this.onStateChange?.({ phase, detail });
  }

  _log(level, msg) { this.logger?.[level]?.(`[whale-bridge/cloudflared] ${msg}`); }

  stop() {
    this._stopped = true;
    if (this._retryTimer) { clearTimeout(this._retryTimer); this._retryTimer = null; }
    this._restartCount = 0;
    this._stopHealthProbe();
    this._closeLogStream();
    if (this.process) { this._terminateProcess(); this.process = null; }
    this.url = null;
    this._setState('idle', '');
  }
}
