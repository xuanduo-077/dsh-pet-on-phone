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
import z from "@deepseek-ai/schemastery";

const name = "whale-bridge";
const inject = ["webServer", "agentDefaultModel", "llm", "agents"];

/** 插件配置 schema */
const Config = z.object({
	routePrefix: z.string().default("/whale-pet"),
	token: z.string().default("whale-pair-2026"),
});

const VERSION = "0.2.0";
const DEFAULT_PREFIX = "/whale-pet";
const DEFAULT_TOKEN = "whale-pair-2026";
const IDLE_TIMEOUT_MS = 120000; // whenIdle 兜底超时

const SYSTEM_PROMPT = [
	"你是泡泡鲸「鲸宝」，住在用户安卓手机桌面上的小鲸鱼桌宠。",
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

	//#region Agent 生命周期（每连接一个）

	/** agent 域 prompt section：只影响这个 agent（scoped systemPrompt.section） */
	function makeSetup() {
		return (agentCtx) => {
			const sp = agentCtx.systemPrompt;
			if (sp && typeof sp.section === "function") {
				sp.section({ name: "whale:persona", order: 9000, text: SYSTEM_PROMPT, interpolate: false });
			} else {
				log("warn", "agentCtx.systemPrompt 不可用，人设未注入");
			}
		};
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
		agent.followup(createUserMessage({ content: [{ type: "text", text }], source: { kind: "user" } }));
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

	//#region 挂路由

	ctx.effect(() => {
		const disposers = [];
		disposers.push(ctx.webServer.register({
			kind: "exact",
			path: `${prefix}/health`,
			handler: async (_req, res) => {
				res.writeHead(200, { "content-type": "application/json; charset=utf-8" });
				res.end(JSON.stringify({ ok: true, version: VERSION, connections: connections.size }));
			},
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
