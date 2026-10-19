/**
 * dsh-whale-bridge —— 泡泡鲸桌宠桥接插件（P0：纯文字对话）
 *
 * 职责：
 *  1. 在 DSH Web Server 上挂 prefix 路由 /whale-pet：
 *     - GET  /whale-pet/health  —— 健康检查（HTTP，无鉴权，仅回 ok+版本）
 *     - GET  /whale-pet/ws      —— WebSocket 升级入口（Android 客户端连这里）
 *  2. WS 协议（全部 JSON 单行文本帧）：
 *     客户端 → 服务端：
 *       {type:"hello", token:"..."}                连接后必须先发，token 对不上即断开
 *       {type:"user.text", text:"...", id:"m1"}    发一句话（id 用于回执关联）
 *     服务端 → 客户端：
 *       {type:"hello.ok", model:"deepseek-chat"}   握手成功，附带当前模型名
 *       {type:"agent.delta", id:"m1", delta:"你"}  回复增量流（可拼接）
 *       {type:"agent.done",  id:"m1", text:"你好"} 回复完整文本
 *       {type:"agent.error", id:"m1", message:""}  出错
 *       {type:"action.play", name:"tap_happy"}     下发动画指令（P0 暂只发点击互动名）
 *  3. LLM 调用：照抄 dsh-pet 的 generateWith 模式（llm.stream + BlockAssembler），
 *     不走 agent 会话，无记忆持久化——P0 就是一次一答 + 进程内 20 条短记忆。
 *
 * 配置（cordis.patch.yml 里 config 段）：
 *   routePrefix —— 默认 /whale-pet
 *   token       —— 配对令牌，Android 端 hello 时必须带，默认 whale-pair-2026
 *   historyLen  —— 每连接保留的历史轮数，默认 20
 */

import { createHash, randomUUID } from "node:crypto";
import z from "@deepseek-ai/schemastery";
import { BlockAssembler, ReasoningEffortId, createAssistantMessage, createUserMessage } from "@deepseek-ai/dsh-llm";

const name = "whale-bridge";
const inject = ["webServer", "agentDefaultModel", "llm"];

/** 插件配置 schema（cordis 约定：导出 Config 后 ctx.config 才可用） */
const Config = z.object({
	routePrefix: z.string().default("/whale-pet"),
	token: z.string().default("whale-pair-2026"),
	historyLen: z.number().default(20),
});

const VERSION = "0.1.0";
const DEFAULT_PREFIX = "/whale-pet";
const DEFAULT_TOKEN = "whale-pair-2026";
const DEFAULT_HISTORY = 20;
const GEN_TIMEOUT_MS = 60000;

const SYSTEM_PROMPT = [
	"你是泡泡鲸「鲸宝」，住在用户安卓手机桌面上的小鲸鱼桌宠。",
	"性格：活泼、简短、口语化，像朋友聊天。",
	"要求：回复控制在 1~3 句话以内（你是在手机气泡里显示的，长了显示不下），",
	"不要用 markdown，不要用 emoji 堆砌（最多 1 个），不写列表。",
	"用户通过语音转文字跟你说话，可能有识别错误，看不懂就自然地问。",
].join("");

function apply(ctx, config) {
	const cfg = config ?? {};
	const prefix = String(cfg.routePrefix ?? DEFAULT_PREFIX).replace(/\/+$/, "") || DEFAULT_PREFIX;
	const token = String(cfg.token ?? DEFAULT_TOKEN);
	const historyLen = Number(cfg.historyLen ?? DEFAULT_HISTORY) || DEFAULT_HISTORY;

	const log = (level, msg) => ctx.logger?.[level]?.(`[whale-bridge] ${msg}`);

	/** 每条 WS 连接一份状态 */
	const connections = new Set();

	//#region LLM（照抄 dsh-pet 模式）

	async function supportsReasoningOff(provider, model) {
		const llm = ctx.llm;
		if (!llm || typeof llm.resolveModelInfo !== "function") return false;
		try {
			const info = await llm.resolveModelInfo(provider, model);
			return info?.reasoning?.efforts?.some((e) => e.id === "off") ?? false;
		} catch {
			return false;
		}
	}

	/**
	 * 生成回复。history: [{role:"user"|"assistant", content:string}]（不含本轮 userText）。
	 * onDelta(delta) 逐段回调文本增量。
	 */
	async function generateReply(sel, history, userText, onDelta) {
		const llm = ctx.llm;
		if (!llm || typeof llm.stream !== "function") {
			return { ok: false, message: "宿主没有可用的 llm 服务" };
		}
		const historyMessages = history.map((m) =>
			m.role === "user"
				? createUserMessage({ content: [{ type: "text", text: m.content }], source: { kind: "user" } })
				: createAssistantMessage({
						content: [{ type: "text", text: m.content }],
						source: { provider: sel.provider, model: sel.model },
					}),
		);
		const supportsOff = await supportsReasoningOff(sel.provider, sel.model);
		const options = {
			provider: sel.provider,
			model: sel.model,
			messages: [
				...historyMessages,
				createUserMessage({ content: [{ type: "text", text: userText }], source: { kind: "user" } }),
			],
			system: SYSTEM_PROMPT,
			temperature: 1,
			...(supportsOff ? { reasoningEffort: ReasoningEffortId("off") } : {}),
			signal: AbortSignal.timeout(GEN_TIMEOUT_MS),
		};
		const assembler = new BlockAssembler();
		try {
			for await (const chunk of llm.stream(options)) {
				assembler.push(chunk);
				// 增量回调：取 assembler 目前累积的 text 长度差
				const full = assembler.blocks().filter((b) => b.type === "text").map((b) => ("text" in b ? b.text : "")).join("");
				if (full.length > 0) onDelta(full);
			}
		} catch (e) {
			return { ok: false, message: e instanceof Error ? e.message : String(e) };
		}
		const text = assembler.blocks().filter((b) => b.type === "text").map((b) => ("text" in b ? b.text : "")).join("").trim();
		if (!text) return { ok: false, message: "模型未返回文本", retryable: true };
		return { ok: true, text };
	}

	//#endregion

	//#region WebSocket

	/**
	 * 极简 WebSocket 服务端实现（RFC6455 帧编解码，只支持文本帧 + close + ping/pong）。
	 * 不引第三方依赖：宿主 peerDeps 里没有 ws 包，自己写 ~120 行最稳。
	 */
	function makeWs(socket) {
		let sendRaw;
		const state = { buf: Buffer.alloc(0), open: true, onMessage: null, onClose: null };
		const guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

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
				if (opcode === 0x8) { // close
					try { socket.write(encodeFrame(Buffer.alloc(0), 0x8)); } catch {}
					state.open = false;
					socket.end();
					state.onClose?.();
					return;
				}
				if (opcode === 0x9) { socket.write(encodeFrame(payload, 0xA)); continue; } // ping→pong
				if (opcode === 0xA) continue; // pong
				if (opcode === 0x1 || opcode === 0x2) state.onMessage?.(payload.toString("utf8"));
				// 0x0 continuation / 分片：P0 不支持，客户端保证整帧发送
			}
		}

		socket.on("data", (chunk) => {
			if (!state.open) return;
			state.buf = Buffer.concat([state.buf, chunk]);
			decodeFrames();
		});
		socket.on("close", () => { if (state.open) { state.open = false; state.onClose?.(); } });
		socket.on("error", () => { if (state.open) { state.open = false; state.onClose?.(); } });

		return {
			send(obj) {
				if (!state.open) return;
				try { socket.write(encodeFrame(Buffer.from(JSON.stringify(obj), "utf8"))); } catch {}
			},
			close() { if (state.open) { try { socket.write(encodeFrame(Buffer.alloc(0), 0x8)); } catch {} state.open = false; socket.end(); } },
			get open() { return state.open; },
			set onMessage(fn) { state.onMessage = fn; },
			set onClose(fn) { state.onClose = fn; },
		};
	}

	/** 处理一次 WS 升级请求 */
	function handleUpgrade(req, socket, head) {
		const key = req.headers["sec-websocket-key"];
		if (!key) { socket.destroy(); return; }
		const accept = createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
		socket.write(
			"HTTP/1.1 101 Switching Protocols\r\n" +
			"Upgrade: websocket\r\n" +
			"Connection: Upgrade\r\n" +
			`Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
		);
		if (head?.length) socket.unshift(head);

		const ws = makeWs(socket);
		const conn = { ws, helloed: false, history: [], busy: false };
		connections.add(conn);

		ws.onClose = () => connections.delete(conn);

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

			if (msg.type === "user.text") {
				handleUserText(conn, String(msg.id ?? randomUUID()), String(msg.text ?? ""));
				return;
			}
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
		const sel = safeSelection();
		if (!sel) { conn.ws.send({ type: "agent.error", id, message: "没有可用的模型" }); conn.busy = false; return; }

		let lastLen = 0;
		const onDelta = (full) => {
			if (full.length > lastLen) {
				conn.ws.send({ type: "agent.delta", id, delta: full.slice(lastLen) });
				lastLen = full.length;
			}
		};
		// 空文本（模型偶发只回 reasoning 块）自动重试一次；其余错误不重试
		let result = await generateReply(sel, conn.history, text, onDelta);
		if (!result.ok && result.retryable && lastLen === 0) {
			result = await generateReply(sel, conn.history, text, onDelta);
		}

		if (result.ok) {
			conn.ws.send({ type: "agent.done", id, text: result.text });
			conn.history.push({ role: "user", content: text });
			conn.history.push({ role: "assistant", content: result.text });
			while (conn.history.length > historyLen * 2) conn.history.shift();
		} else {
			conn.ws.send({ type: "agent.error", id, message: result.message ?? "生成失败" });
		}
		conn.busy = false;
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
		log("info", `路由已挂载：${prefix}/health（HTTP）+ ${prefix}/ws（WebSocket），token=${token.slice(0, 6)}***`);
		return () => disposers.forEach((d) => d());
	}, "whale-bridge: routes");

	//#endregion
}

export { apply, inject, name, Config };
