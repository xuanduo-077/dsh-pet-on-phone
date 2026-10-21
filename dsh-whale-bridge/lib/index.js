/**
 * dsh-whale-bridge v2 —— 泡泡鲸桌宠桥接插件（Agent 版）
 *
 * 升级点（相对 v1 裸 llm.stream）：
 *   - 每条 WS 连接通过 ctx.agents.create() 建一个真正的 DSH Agent，
 *     拥有宿主全部工具（bash/文件/搜索/skill/subagent…），即「鲸宝 = 电脑端 DeepSeek 分身」。
 *   - 流式：监听 agent/assistant-stream（agent 域过滤），text-delta → agent.delta；
 *     agent.whenIdle() 后从 session 事件取最终 assistant/message 文本 → agent.done。
 *   - 会话持久：Agent 的 session 由宿主 sessionPersistence 持久化，手机断线重连 resume 同一 sessionId，记忆不丢。
 *   - 人设：通过 setup() 给 agent 注册 agent 域 prompt section（SYSTEM_PROMPT），不污染其他 agent。
 *   - 保留 v1 的 hello/token/协议不变，Android 端无需改动。
 */

import { createHash, randomUUID } from "node:crypto";
import { createUserMessage } from "@deepseek-ai/dsh-llm";
import { createRequire } from "node:module";
import z from "@deepseek-ai/schemastery";
import { CloudflaredManager } from "./cloudflared.mjs";

const require = createRequire(import.meta.url);
/** qrcode-generator（内嵌 vendored，无 npm 依赖） */
const qrcodeLib = require("./qrcode.min.js");

const name = "whale-bridge";
const inject = ["webServer", "agentDefaultModel", "llm", "agents"];
const injectExtra = ["sessionQuery"];

/** 插件配置 schema */
const Config = z.object({
	routePrefix: z.string().default("/whale-pet"),
	token: z.string().default("change-me-pair-code"),
	tunnelPort: z.number().default(28000),
	tunnelHome: z.string().default(""),
	remoteApproval: z.boolean().default(true),
});

const VERSION = "0.5.0";
const DEFAULT_PREFIX = "/whale-pet";
const DEFAULT_TOKEN = "change-me-pair-code";
const IDLE_TIMEOUT_MS = 120000; // whenIdle 兜底超时
const APPROVAL_TIMEOUT_MS = 120000; // 手机审批等待上限，超时交回桌面端 answerer

const SYSTEM_PROMPT = [
	"你是偶鲸鲸「鲸宝」，住在用户安卓手机桌面上的小鲸鱼桌宠。",
	"性格：活泼、简短、口语化，像朋友聊天。",
	"要求：最终回复控制在 1~3 句话以内（显示在手机小气泡里，长了显示不下），",
	"不要用 markdown，不要堆 emoji（最多 1 个），不写列表。",
	"你拥有宿主全部工具，用户问到你不确定的事（文件、时间、网络、系统状态）就去真的查，别编。",
	"用户通过语音转文字跟你说话，可能有识别错误，看不懂就自然地问。",
].join("");

function apply(ctx, config) {
	const cfg = config ?? {};
	const prefix = String(cfg.routePrefix ?? DEFAULT_PREFIX).replace(/\/+$/, "") || DEFAULT_PREFIX;
	const token = String(cfg.token ?? DEFAULT_TOKEN);
	const log = (level, msg) => ctx.logger?.[level]?.(`[whale-bridge] ${msg}`);

	const connections = new Set();
	const remoteApproval = cfg.remoteApproval !== false;

	//#region Agent 生命周期（每连接一个）

	/** agent 域 prompt section：只影响这个 agent（scoped systemPrompt.section） */
	function makeSetup() {
		return (agentCtx) => {
			const sp = agentCtx.systemPrompt;
			if (sp && typeof sp.section === "function") {
				sp.section({ name: "whale:persona", order: 9000, text: SYSTEM_PROMPT, interpolate: false });
				if (typeof sp.context === "function") {
					// 动态上下文：每次组装时读取桌面端活跃会话快照（text 为函数，每请求求值）
					sp.context({
						name: "whale:desktop-context",
						order: 9100,
						text: () => digestCache,
					});
					log("warn", "desktop-context registered via context()");
				} else {
					log("warn", `desktop-context: context() unavailable (type=${typeof sp.context}), fallback section`);
					sp.section({ name: "whale:desktop-context", order: 9100, text: () => digestCache, interpolate: false });
				}
			} else {
				log("warn", "agentCtx.systemPrompt 不可用，人设未注入");
			}
		};
	}

	/** 桌面动态摘要：鲸宝能"看见"电脑上正在发生的会话与任务 */
	// sessionQuery 不能写进顶层 inject（会卡整个插件加载：waiting for service: config），
	// 改用运行时 ctx.inject 惰性获取；拿不到就返回空摘要，不影响主链路。
	let sessionQueryRef;
	try {
		ctx.inject(["sessionQuery"], (childCtx) => { sessionQueryRef = childCtx.sessionQuery; });
	} catch (e) {
		log("warn", `sessionQuery 运行时注入失败：${e?.message ?? e}`);
	}
	let digestLogged = false;
	let digestCache = "";
	async function refreshDigest() { digestCache = await desktopDigest(); }
	setInterval(() => { refreshDigest().catch(() => {}); }, 30000).unref?.();
	refreshDigest().catch(() => {});
	let digestError = null;
	async function desktopDigest() {
		try {
			if (!sessionQueryRef) { if (!digestLogged) { log("warn", "desktopDigest: sessionQueryRef 未绑定"); digestLogged = true; } return ""; }
			const records = await sessionQueryRef.listSessions();
			const live = records
				.filter((r) => r.header?.id && !String(r.header.id).startsWith("whale-"))
				.sort((a, b) => (b.header?.createdAt ?? 0) - (a.header?.createdAt ?? 0))
				.slice(0, 12);
			if (!live.length) return "（桌面端当前没有活跃会话）";
			const lines = ["以下是电脑端 DSH 当前活跃的会话（仅供聊天时自然提及，不要主动罗列）："];
			for (const r of live) {
				const h = r.header;
				const age = h.createdAt ? ` · 开始于 ${new Date(h.createdAt).toLocaleString("zh-CN")}` : "";
				lines.push(`- [${h.id}]${h.cwd ? ` (${h.cwd})` : ""}${age}`);
			}
			const out = lines.join("\n");
			if (!digestLogged) { log("warn", `desktopDigest OK: ${live.length} 会话, ${out.length} 字符`); digestLogged = true; }
			return out;
		} catch (e) {
			digestError = String(e?.stack ?? e).slice(0, 300);
			return "";
		}
	}

	async function getAgent(conn) {
		if (conn.agent) return conn.agent;
		const sel = safeSelection();
		if (!sel) throw new Error("没有可用的模型");
		const sessionId = `whale-${randomUUID().slice(0, 8)}`;
		const { agent } = await ctx.agents.create({
			sessionId,
			meta: { cwd: process.cwd() },
			agentOptions: { provider: sel.provider, model: sel.model },
			setup: makeSetup(),
		});
		conn.sessionId = sessionId;
		// 流式订阅：dsh 的 ctx.on 是事件总线，agent 域过滤 subject===agent
		conn.disposeStream = ctx.on("agent/assistant-stream", ({ agent: subject, frame }) => {
			if (subject !== agent || !conn.ws?.open) return;
			if (frame.type !== "chunk") return;
			const c = frame.chunk;
			if (c.type === "text-delta" && c.text) conn.onDelta?.(c.text);
		});
		log("info", `agent created: ${sessionId} (${sel.provider}/${sel.model})`);
		conn.agent = agent;
		return agent;
	}

	/** 从 session 事件里取最新一条 assistant 消息的纯文本 */
	function lastAssistantText(agent) {
		let text = "";
		try {
			const s = agent.session;
			for (let seq = 0; seq < s.seq; seq++) {
				const ev = s.eventAt?.(seq);
				const body = ev?.event ?? ev;
				if (body?.type !== "assistant/message") continue;
				const blocks = body.message?.content ?? body.content ?? [];
				text = blocks.filter((b) => b.type === "text").map((b) => b.text ?? "").join("");
			}
		} catch (e) { log("warn", `read session failed: ${e?.message ?? e}`); }
		return text.trim();
	}

	async function runTurn(conn, id, text) {
		const agent = await getAgent(conn);
		let full = "";
		conn.onDelta = (d) => {
			full += d;
			conn.ws.send({ type: "agent.delta", id, delta: d });
		};
		// 桌面上下文：直接附在用户消息里（systemPrompt.context() 的 text 函数在本部署未求值，弃用该路径）
		const digest = digestCache || (await desktopDigest().catch(() => ""));
		const sendText = digest ? `${text}\n\n[系统附注·桌面上下文]\n${digest}` : text;
		agent.followup(createUserMessage({ content: [{ type: "text", text: sendText }], source: { kind: "user" } }));
		await Promise.race([
			agent.whenIdle(),
			new Promise((_, rej) => setTimeout(() => rej(new Error("回复超时")), IDLE_TIMEOUT_MS)),
		]);
		conn.onDelta = null;
		let final = lastAssistantText(agent);
		if (!final) final = full.trim();
		if (!final) return { ok: false, message: "模型未返回文本" };
		return { ok: true, text: final };
	}

	//#endregion

	//#region WebSocket（与 v1 相同的极简 RFC6455 实现）

	function makeWs(socket) {
		const state = { buf: Buffer.alloc(0), open: true, onMessage: null, onClose: null };
		function encodeFrame(payload, opcode = 0x1) {
			const len = payload.length;
			let header;
			if (len < 126) header = Buffer.from([0x80 | opcode, len]);
			else if (len < 65536) header = Buffer.alloc(4), (header[0] = 0x80 | opcode), (header[1] = 126), header.writeUInt16BE(len, 2);
			else header = Buffer.alloc(10), (header[0] = 0x80 | opcode), (header[1] = 127), header.writeBigUInt64BE(BigInt(len), 2);
			return Buffer.concat([header, payload]);
		}
		function decodeFrames() {
			while (true) {
				if (state.buf.length < 2) return;
				const b0 = state.buf[0], b1 = state.buf[1];
				const opcode = b0 & 0x0f;
				const masked = (b1 & 0x80) !== 0;
				let len = b1 & 0x7f;
				let off = 2;
				if (len === 126) { if (state.buf.length < 4) return; len = state.buf.readUInt16BE(2); off = 4; }
				else if (len === 127) { if (state.buf.length < 10) return; len = Number(state.buf.readBigUInt64BE(2)); off = 10; }
				let mask = null;
				if (masked) { if (state.buf.length < off + 4) return; mask = state.buf.subarray(off, off + 4); off += 4; }
				if (state.buf.length < off + len) return;
				let payload = state.buf.subarray(off, off + len);
				if (mask) payload = Buffer.from(payload.map((b, i) => b ^ mask[i % 4]));
				state.buf = state.buf.subarray(off + len);
				if (opcode === 0x8) { try { socket.write(encodeFrame(Buffer.alloc(0), 0x8)); } catch {} state.open = false; socket.end(); state.onClose?.(); return; }
				if (opcode === 0x9) { socket.write(encodeFrame(payload, 0xA)); continue; }
				if (opcode === 0xA) continue;
				if (opcode === 0x1 || opcode === 0x2) state.onMessage?.(payload.toString("utf8"));
			}
		}
		socket.on("data", (chunk) => { if (!state.open) return; state.buf = Buffer.concat([state.buf, chunk]); decodeFrames(); });
		socket.on("close", () => { if (state.open) { state.open = false; state.onClose?.(); } });
		socket.on("error", () => { if (state.open) { state.open = false; state.onClose?.(); } });
		return {
			send(obj) { if (!state.open) return; try { socket.write(encodeFrame(Buffer.from(JSON.stringify(obj), "utf8"))); } catch {} },
			close() { if (state.open) { try { socket.write(encodeFrame(Buffer.alloc(0), 0x8)); } catch {} state.open = false; socket.end(); } },
			get open() { return state.open; },
			set onMessage(fn) { state.onMessage = fn; },
			set onClose(fn) { state.onClose = fn; },
		};
	}

	function handleUpgrade(req, socket, head) {
		const key = req.headers["sec-websocket-key"];
		if (!key) { socket.destroy(); return; }
		const accept = createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
		socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
		if (head?.length) socket.unshift(head);

		const ws = makeWs(socket);
		const conn = { ws, helloed: false, busy: false, agent: null, sessionId: null, disposeStream: null, onDelta: null };
		connections.add(conn);

		ws.onClose = () => {
			connections.delete(conn);
			try { conn.disposeStream?.(); } catch {}
			// Agent/session 留给宿主持久化；空闲 agent 可被宿主回收。不主动 dispose，保留记忆。
		};

		ws.onMessage = (raw) => {
			let msg;
			try { msg = JSON.parse(raw); } catch { ws.send({ type: "error", message: "bad json" }); return; }
			if (!msg || typeof msg.type !== "string") return;

			if (msg.type === "hello") {
				if (String(msg.token ?? "") !== token) {
					ws.send({ type: "hello.fail", message: "token 不匹配" });
					ws.close();
					return;
				}
				conn.helloed = true;
				const sel = safeSelection();
				ws.send({ type: "hello.ok", model: sel ? `${sel.provider}/${sel.model}` : "", version: VERSION });
				return;
			}
			if (!conn.helloed) { ws.send({ type: "error", message: "请先发 hello" }); return; }
			if (msg.type === "user.text") { handleUserText(conn, String(msg.id ?? randomUUID()), String(msg.text ?? "")); return; }
			if (msg.type === "ping") { ws.send({ type: "pong" }); return; }
			if (msg.type === "watch") {
				conn.watch = String(msg.sessionId ?? "");
				ws.send({ type: "watch.ok", sessionId: conn.watch, status: agentStatus(conn.watch) });
				return;
			}
			if (msg.type === "session.message") {
				sendToSession(String(msg.sessionId ?? ""), String(msg.text ?? ""))
					.then((r) => ws.send({ type: "session.message.ok", sessionId: msg.sessionId, ...r }))
					.catch((e) => ws.send({ type: "session.message.fail", sessionId: msg.sessionId, message: e?.message ?? String(e) }));
				return;
			}
			if (msg.type === "session.stop") {
				const ok = stopSession(String(msg.sessionId ?? ""));
				ws.send({ type: "session.stop.ack", sessionId: msg.sessionId, ok });
				return;
			}
			if (msg.type === "approval.answer") {
				const ok = answerApproval(String(msg.id ?? ""), String(msg.outcome ?? ""));
				ws.send({ type: "approval.ack", id: msg.id, ok });
				return;
			}
		};
	}

	function safeSelection() {
		try {
			const sel = ctx.agentDefaultModel?.currentSelection();
			return sel && sel.provider && sel.model ? sel : null;
		} catch { return null; }
	}

	async function handleUserText(conn, id, text) {
		if (!text.trim()) return;
		if (conn.busy) { conn.ws.send({ type: "agent.error", id, message: "上一条还在回复中" }); return; }
		conn.busy = true;
		try {
			const r = await runTurn(conn, id, text);
			if (r.ok) conn.ws.send({ type: "agent.done", id, text: r.text });
			else conn.ws.send({ type: "agent.error", id, message: r.message ?? "生成失败" });
		} catch (e) {
			log("warn", `turn failed: ${e?.stack ?? e}`);
			conn.ws.send({ type: "agent.error", id, message: e instanceof Error ? e.message : String(e) });
		} finally {
			conn.busy = false;
		}
	}

	//#endregion

	//#region Cloudflare 隧道（公网接入，移植自 wenbin-wb/dsh-bridge）

	const tunnelHome = String(cfg.tunnelHome ?? "").trim() || `${process.env.HOME ?? "/root"}/.whale-bridge`;
	const tunnelPort = Number(cfg.tunnelPort ?? 28000);
	let cfManager = null;
	let cfState = { phase: "idle", detail: "" };

	function cfStatus() {
		return {
			active: !!cfManager && ["ready", "connecting", "downloading", "reconnecting"].includes(cfState.phase),
			phase: cfState.phase,
			detail: cfState.detail,
			url: cfManager?.url ?? null,
			version: VERSION,
		};
	}

	function cfQrSvg(text) {
		try {
			const qr = qrcodeLib(0, "M");
			qr.addData(text);
			qr.make();
			return qr.createSvgTag({ cellSize: 4, margin: 2 });
		} catch (e) { log("warn", `qr failed: ${e?.message ?? e}`); return null; }
	}

	async function tunnelStartHandler(req, res) {
		if (cfManager && cfStatus().active) {
			// 已在运行：直接回当前状态
			return json(res, 200, { ok: true, ...cfStatus(), qr: cfManager.url ? cfQrSvg(cfManager.url) : null });
		}
		if (cfManager) { try { cfManager.stop(); } catch { /* ignore */ } cfManager = null; }
		cfState = { phase: "connecting", detail: "正在初始化..." };
		cfManager = new CloudflaredManager({
			port: tunnelPort,
			home: tunnelHome,
			logFilePath: `${tunnelHome}/cloudflared.log`,
			onStateChange: (s) => {
				cfState = s;
				log("info", `tunnel state: ${s.phase} ${s.detail ?? ""}`);
			},
			logger: { info: (m) => log("info", m), warn: (m) => log("warn", m), error: (m) => log("error", m), debug: () => {} },
		});
		cfManager.start();
		return json(res, 200, { ok: true, ...cfStatus(), qr: null });
	}

	function tunnelStopHandler(req, res) {
		if (cfManager) { try { cfManager.stop(); } catch { /* ignore */ } }
		cfManager = null;
		cfState = { phase: "idle", detail: "" };
		return json(res, 200, { ok: true, ...cfStatus(), qr: null });
	}

	function tunnelStatusHandler(req, res) {
		return json(res, 200, { ok: true, ...cfStatus(), qr: cfManager?.url ? cfQrSvg(cfManager.url) : null });
	}

	/** 极简 token 鉴权：header x-whale-token 或 query ?token= 与配对码比对 */
	function checkAdmin(req, urlObj) {
		const provided = String(req.headers["x-whale-token"] ?? urlObj.searchParams.get("token") ?? "");
		return provided === token;
	}

	function json(res, code, obj) {
		res.writeHead(code, { "content-type": "application/json; charset=utf-8" });
		res.end(JSON.stringify(obj));
	}

	function tunnelPage() {
		return `<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>鲸宝 · 公网连接</title>
<style>
body{font-family:system-ui,sans-serif;max-width:520px;margin:0 auto;padding:24px;background:#f6f8fb;color:#1a2233}
h1{font-size:20px} .card{background:#fff;border:1px solid #e3e8f0;border-radius:14px;padding:18px;margin:14px 0;box-shadow:0 1px 4px rgba(20,40,80,.06)}
button{border:0;border-radius:10px;padding:11px 20px;font-size:15px;cursor:pointer;color:#fff;background:#2f6bff}
button.off{background:#8a93a6} button:disabled{opacity:.5}
#state{font-weight:600} #url{word-break:break-all;font-family:monospace;font-size:14px;background:#eef2fa;border-radius:8px;padding:10px;margin-top:8px}
#qr{display:flex;justify-content:center;margin-top:12px} #qr svg{border:8px solid #fff;border-radius:8px}
.hint{color:#68738a;font-size:13px;line-height:1.6}
label{font-size:13px;color:#68738a}
#appUrl{width:100%;box-sizing:border-box;border:1px solid #cdd6e8;border-radius:8px;padding:9px;font-size:14px;font-family:monospace}
</style></head><body>
<h1>🐳 鲸宝公网连接</h1>
<div class="card">
  <div>状态：<span id="state">未知</span> <span id="detail" class="hint"></span></div>
  <div id="url" style="display:none"></div>
  <div id="qr"></div>
  <p>
    <button id="btn-on">开启隧道</button>
    <button id="btn-off" class="off">关闭</button>
  </p>
  <p class="hint">开启后 Cloudflare 会分配一个临时公网地址（每次开启都会变）。
  手机和电脑不在同一网络时用它连接；同一 WiFi 下仍建议直连内网地址，更快更稳。</p>
</div>
<div class="card">
  <label>鲸宝 App 里要填的地址（自动生成，可直接复制）</label>
  <input id="appUrl" readonly placeholder="先开启隧道">
  <p class="hint">在 App 的 设置 → 连接地址 里粘贴，配对码不变。</p>
</div>
<script>
const TOKEN = new URLSearchParams(location.search).get("token") || "";
async function api(p){const r=await fetch(p,{headers:{"x-whale-token":TOKEN}});return r.json();}
function render(d){
  const s=document.getElementById("state"),dt=document.getElementById("detail"),u=document.getElementById("url"),q=document.getElementById("qr"),a=document.getElementById("appUrl");
  s.textContent=d.active?(d.phase==="ready"?"✅ 已连接":"⏳ "+d.phase):"未开启";
  dt.textContent=d.detail||"";
  if(d.url){u.style.display="block";u.textContent=d.url;a.value=d.url.replace(/^https/,"wss")+"/whale-pet/ws";}
  else{u.style.display="none";a.value="";}
  q.innerHTML=d.qr||"";
  document.getElementById("btn-on").disabled=d.active;
  document.getElementById("btn-off").disabled=!d.active;
}
async function refresh(){try{render(await api("/whale-pet/tunnel/status"));}catch(e){}}
document.getElementById("btn-on").onclick=async()=>{document.getElementById("btn-on").disabled=true;render(await api("/whale-pet/tunnel/start"));setTimeout(refresh,3000);setTimeout(refresh,8000);};
document.getElementById("btn-off").onclick=async()=>{render(await api("/whale-pet/tunnel/stop"));};
refresh();setInterval(refresh,5000);
</script></body></html>`;
	}

	//#endregion

	//#region App 原生接口（v0.5.0：会话列表/详情/发消息/中止/审批）
	const resumeAgents = new Map(); // sessionId -> Agent（resume 出来的冷会话，绝不 dispose：dispose 会把它从 session store 移除）
	const approvalPending = new Map(); // approvalId -> resolve(outcome|null)
	let approvalSeq = 0;

	function readBody(req, limit = 131072) {
		return new Promise((resolve, reject) => {
			let size = 0;
			const chunks = [];
			req.on("data", (c) => {
				size += c.length;
				if (size > limit) { reject(new Error("请求体过大")); try { req.destroy(); } catch {} return; }
				chunks.push(c);
			});
			req.on("end", () => {
				const raw = Buffer.concat(chunks).toString("utf8").trim();
				if (!raw) return resolve({});
				try { resolve(JSON.parse(raw)); } catch { reject(new Error("不是合法 JSON")); }
			});
			req.on("error", reject);
		});
	}

	function blocksText(msg) {
		const blocks = msg?.content ?? msg?.message?.content ?? [];
		if (!Array.isArray(blocks)) return "";
		return blocks.filter((b) => b && b.type === "text").map((b) => b.text ?? "").join("");
	}

	function mapSessionEvent(seq, b) {
		const t = b?.type;
		// 事件载荷在 b.data 里（v4 日志格式），兜底兼容平铺形状
		const d = b?.data ?? b ?? {};
		if (t === "user/message") {
			const src = d.source?.kind ?? d.role ?? "user";
			if (src !== "user") return null; // 隐藏运行时上下文 / system-reminder 注入
			const text = blocksText(d).slice(0, 8000);
			return text ? { seq, role: "user", text } : null;
		}
		if (t === "assistant/message") {
			const text = blocksText(d.message ?? d).slice(0, 8000);
			return text ? { seq, role: "assistant", text } : null;
		}
		if (t === "tool/call") {
			return {
				seq, role: "tool",
				name: String(d.name ?? d.toolName ?? "tool"),
				args: String(d.arguments ?? "").slice(0, 700),
			};
		}
		if (t === "tool/result") {
			const msg = d.message ?? d;
			const isError = !!msg.isError || !!d.error;
			return {
				seq, role: "toolResult",
				text: blocksText(msg).slice(0, 1500),
				error: isError ? String(msg.error?.message ?? d.error?.message ?? "工具报错").slice(0, 400) : null,
			};
		}
		if (t === "approval/asked") {
			return { seq, role: "approval", name: String(d.toolName ?? ""), reason: String(d.reason ?? "") };
		}
		return null;
	}

	function agentOf(sid) {
		try { const a = ctx.agents.get(sid); if (a) return a; } catch {}
		return resumeAgents.get(sid) ?? null;
	}

	function agentStatus(sid) {
		const a = agentOf(sid);
		if (!a) return "idle";
		const s = a.status;
		return typeof s === "string" ? s : String(s?.kind ?? s?.state ?? "idle");
	}

	const titleCache = { at: 0, titles: new Map(), times: new Map() };
	async function listSessionsForApp() {
		if (!sessionQueryRef) throw new Error("sessionQuery 未就绪");
		const records = await sessionQueryRef.listSessions();
		// 标题折叠要读日志，较贵：只给最新 24 个会话取标题，并缓存 60 秒
		if (Date.now() - titleCache.at > 60000) {
			const ids = records.slice(0, 24).map((r) => r.header?.id).filter(Boolean);
			const titles = new Map();
			const times = new Map();
			try {
				const snaps = await sessionQueryRef.readTitleSnapshots(ids);
				for (const r of snaps) {
					if (r?.status !== "fulfilled") continue;
					const snap = r.value?.title;
					if (snap?.title) { titles.set(String(r.sessionId), String(snap.title)); times.set(String(r.sessionId), snap.updatedAt ?? null); }
				}
				titleCache.at = Date.now(); titleCache.titles = titles; titleCache.times = times;
			} catch (e) { log("warn", `readTitleSnapshots 失败：${e?.message ?? e}`); }
		}
		const titles = titleCache.titles;
		const times = titleCache.times;
		return records.map((r) => {
			const h = r.header ?? {};
			const id = String(h.id ?? "");
			return {
				id,
				title: titles.get(id) ?? null,
				live: !!r.live,
				busy: agentStatus(id) === "running",
				updatedAt: times.get(id) ?? h.updatedAt ?? h.createdAt ?? null,
				cwd: h.cwd ?? null,
				whale: id.startsWith("whale-"),
				clawbot: id.startsWith("clawbot-"),
			};
		});
	}

	async function sendToSession(sid, text) {
		const msg = () => createUserMessage({ content: [{ type: "text", text }], source: { kind: "user" } });
		const live = ctx.agents.get(sid);
		if (live) { live.followup(msg()); return { mode: "live", status: agentStatus(sid) }; }
		// 冷会话：resume 后投递；句柄只保留不 dispose（dispose 会把会话从 store 移除）
		let a = resumeAgents.get(sid);
		if (!a) {
			const sel = safeSelection();
			const handle = await ctx.agents.resume({
				resumeSessionId: sid,
				...(sel ? { agentOptions: { provider: sel.provider, model: sel.model } } : {}),
			});
			a = handle?.agent ?? handle;
			resumeAgents.set(sid, a);
			log("info", `resume 冷会话：${sid}`);
		}
		a.followup(msg());
		return { mode: "resume", status: agentStatus(sid) };
	}

	function stopSession(sid) {
		const a = agentOf(sid);
		if (!a) return false;
		a.cancel({ kind: "user" });
		return true;
	}

	function broadcast(obj) {
		for (const c of connections) { try { if (c.ws?.open) c.ws.send(obj); } catch {} }
	}

	// 远程审批：把 approval/request waterfall 转发给手机；超时或无人连接则放行给后续 answerer
	ctx.effect(() => {
		const off = ctx.on("approval/request", async (req, next) => {
			const phones = [...connections].filter((c) => c.ws?.open && c.helloed);
			if (!phones.length || !remoteApproval) return next();
			const id = `ap-${++approvalSeq}`;
			const sid = String(req?.agent?.session?.id ?? req?.agent?.sessionId ?? "");
			const payload = { type: "approval.ask", id, sessionId: sid, toolName: String(req?.toolName ?? ""), reason: String(req?.reason ?? "") };
			const decision = await new Promise((resolve) => {
				approvalPending.set(id, resolve);
				broadcast(payload);
				setTimeout(() => { if (approvalPending.delete(id)) resolve(null); }, APPROVAL_TIMEOUT_MS);
			});
			approvalPending.delete(id);
			if (decision === "allowed-once" || decision === "rejected") {
				log("info", `远程审批 ${id} → ${decision}（${payload.toolName}）`);
				return decision;
			}
			return next();
		});
		return () => { try { off?.(); } catch {} };
	}, "whale-bridge: remote-approval");

	function answerApproval(id, outcome) {
		const resolve = approvalPending.get(id);
		if (!resolve) return false;
		approvalPending.delete(id);
		resolve(outcome === "allowed-once" || outcome === "allowed" ? "allowed-once" : "rejected");
		return true;
	}

	//#endregion
	//#region 挂路由

	ctx.effect(() => {
		const disposers = [];
		// 会话只读 API：App/网页可读桌面端会话列表与内容（打通"手机看桌面任务"）
		disposers.push(ctx.webServer.register({
			kind: "prefix",
			path: `${prefix}/sessions`,
			handler: async (req, res) => {
				const urlObj = new URL(req.url ?? "/", "http://localhost");
				const sid = urlObj.pathname.slice(`${prefix}/sessions/`.length);
				const json = (code, body) => { res.writeHead(code, { "content-type": "application/json; charset=utf-8" }); res.end(JSON.stringify(body)); };
				try {
					if (!sid || sid.endsWith("/")) {
						const records = await sessionQueryRef.listSessions();
						const items = records.map((r) => ({
							id: r.header?.id, title: r.header?.title ?? null, live: !!r.live,
							updatedAt: r.header?.updatedAt ?? null, agent: r.header?.agent ?? null,
						}));
						json(200, { ok: true, sessions: items });
					} else {
						const detail = await sessionQueryRef.readSession(decodeURIComponent(sid));
						const events = (detail.events ?? []).map((e) => {
							const b = e.event ?? e;
							if (b?.type === "user/message" || b?.type === "assistant/message") {
								const blocks = b.message?.content ?? b.content ?? [];
								return { type: b.type, text: blocks.filter((x) => x.type === "text").map((x) => x.text ?? "").join("").slice(0, 8000) };
							}
							return { type: b?.type ?? "other" };
						}).filter((e) => e.text || e.type === "user/message");
						json(200, { ok: true, session: detail.session, events });
					}
				} catch (e) { json(500, { ok: false, message: e instanceof Error ? e.message : String(e) }); }
			},
		}));
		// App 原生接口（会话列表/详情/发消息/中止），全部要求 token
		disposers.push(ctx.webServer.register({
			kind: "prefix",
			path: `${prefix}/api`,
			handler: async (req, res) => {
				const urlObj = new URL(req.url ?? "/", "http://localhost");
				if (!checkAdmin(req, urlObj)) return json(res, 403, { ok: false, message: "token 不匹配" });
				const rest = urlObj.pathname.slice(`${prefix}/api`.length).replace(/^\/+/, "").replace(/\/+$/, "");
				const parts = rest ? rest.split("/") : [];
				try {
					if (req.method === "GET" && parts[0] === "sessions" && parts.length === 1) {
						const sessions = await listSessionsForApp();
						return json(res, 200, { ok: true, sessions });
					}
					if (req.method === "GET" && parts[0] === "sessions" && parts.length === 2) {
						const sid = decodeURIComponent(parts[1]);
						const detail = await sessionQueryRef.readSession(sid);
						const all = [];
						for (const e of detail.events ?? []) {
							const b = e.event ?? e;
							const m = mapSessionEvent(Number(e.seq ?? b?.seq ?? all.length), b);
							if (m) all.push(m);
						}
						const qLimit = Number(urlObj.searchParams.get("limit") ?? 60) || 60;
						const limit = Math.min(200, Math.max(10, qLimit));
						const qBefore = urlObj.searchParams.get("before");
						const end = Math.min(all.length, Math.max(0, qBefore == null ? all.length : Number(qBefore) || 0));
						const start = Math.max(0, end - limit);
						return json(res, 200, { ok: true, id: sid, total: all.length, from: start, to: end, hasMore: start > 0, status: agentStatus(sid), events: all.slice(start, end) });
					}
					if (req.method === "POST" && parts[0] === "sessions" && parts[2] === "message") {
						const sid = decodeURIComponent(parts[1]);
						const body = await readBody(req);
						const text = String(body.text ?? "").trim();
						if (!text) return json(res, 400, { ok: false, message: "text 为空" });
						const r = await sendToSession(sid, text);
						return json(res, 200, { ok: true, ...r });
					}
					if (req.method === "POST" && parts[0] === "sessions" && parts[2] === "stop") {
						const sid = decodeURIComponent(parts[1]);
						const stopped = stopSession(sid);
						return json(res, stopped ? 200 : 409, { ok: stopped, message: stopped ? "" : "会话当前没有运行中的 agent" });
					}
					return json(res, 404, { ok: false, message: `未知接口 ${req.method} ${rest}` });
				} catch (e) {
					return json(res, 500, { ok: false, message: e instanceof Error ? e.message : String(e) });
				}
			},
		}));
		disposers.push(ctx.webServer.register({
			kind: "exact",
			path: `${prefix}/health`,
			handler: async (_req, res) => {
				res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
				res.end(JSON.stringify({ ok: true, version: VERSION, connections: connections.size, digestLen: digestCache.length, sessionQuery: !!sessionQueryRef, digestError, tunnel: cfStatus() }));
			},
		}));
		// 公网隧道管理（本机管理页 + API；读接口带 token 才回详情，页面 query token 透传）
		disposers.push(ctx.webServer.register({
			kind: "exact",
			path: `${prefix}/tunnel`,
			handler: async (req, res) => {
				res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
				res.end(tunnelPage());
			},
		}));
		for (const [ep, handler, admin] of [
			["tunnel/start", tunnelStartHandler, true],
			["tunnel/stop", tunnelStopHandler, true],
			["tunnel/status", tunnelStatusHandler, false],
		]) {
			disposers.push(ctx.webServer.register({
				kind: "exact",
				path: `${prefix}/${ep}`,
				handler: async (req, res) => {
					const urlObj = new URL(req.url ?? "/", "http://localhost");
					if (admin && !checkAdmin(req, urlObj)) return json(res, 403, { ok: false, message: "token 不匹配" });
					return handler(req, res);
				},
			}));
		}
		// 监听会话流式输出：把被 watch 的会话增量推给手机（桌面任务实时进度）
		disposers.push(ctx.on("agent/assistant-stream", ({ agent: subject, frame }) => {
			if (frame?.type !== "chunk") return;
			const ch = frame.chunk;
			if (ch?.type !== "text-delta" || !ch.text) return;
			const sid = String(subject?.session?.id ?? "");
			if (!sid) return;
			for (const c of connections) {
				if (c.ws?.open && c.watch === sid) c.ws.send({ type: "session.delta", sessionId: sid, delta: ch.text });
			}
		}));
		disposers.push(ctx.webServer.registerUpgrade({
			path: `${prefix}/ws`,
			handler: (req, socket, head) => handleUpgrade(req, socket, head),
		}));
		log("info", `路由已挂载：${prefix}/health + ${prefix}/ws（agent 版 v${VERSION}），token=${token.slice(0, 6)}***`);
		return () => disposers.forEach((d) => d());
	}, "whale-bridge: routes");

	//#endregion
}

export { apply, inject, name, Config };
