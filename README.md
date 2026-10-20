# dsh-pet-on-phone 🐋

把 [泡泡鲸桌宠 whale-pet-Android](https://github.com/duoxichangan/whale-pet-Android)（v2.3.0 fork）接入 [DeepSeek 实体（DSH）](https://github.com/anthropics/claude-code)，实现：

- **长按鲸鱼说话**（Android 系统 SpeechRecognizer，zh-CN）→ 转文字发到电脑上的 DSH
- **气泡流式回复**：逐字显示在鲸鱼头顶气泡，说话时播 `happy` 动画，气泡按文字自动撑高（最多 6 行）
- **完整 DSH Agent 能力（v0.2.0 插件）**：鲸宝不再只是裸聊天——它接的是真正的 DSH Agent，可以调用工具（联网搜索等）、回答「现在几点/帮我查查…」会真去查；session 持久化，断线重连记忆不丢
- **通知栏打字聊天**（输入法聚焦修复版）
- 原项目全部养成玩法保留（轻触回应、饱食/心情/精力、106 段动画、完全离线）

> 架构：DSH 侧跑一个 **桥接插件**（WebSocket 服务），手机端 APK 是 **WS 客户端**。
> 语音识别与合成全部在手机本地完成，DSH 只收发文本——不点不联网，保住原项目离线定位。

## 仓库结构

```
dsh-whale-bridge/     DSH 桥接插件 v0.2.0（Agent 版：agents.create 创建真 agent + 手写 RFC6455 WS）
android/              安卓端源码（fork 自 whale-pet-Android v2.3.0，改动的文件）
docs/                 APK 交付说明、更新日志
```

## DSH 侧：安装桥接插件

1. 把 `dsh-whale-bridge/` 放到 `$DSH_HOME/plugins/`
2. 在 DSH profile 里挂载（pnpm）：

```bash
cd <你的 dsh profile 目录>
pnpm add link:<绝对路径>/dsh-whale-bridge
# 然后在 profile package.json 的 dsh.profile.bundles 数组里加 "dsh-whale-bridge"
```

3. `cordis.patch.yml` 已含默认配置：路由前缀 `/whale-pet`，配对 token `whale-pair-2026`（**务必改成自己的**）
4. 重启 DSH，验证：`curl http://<nas>:28000/whale-pet/health` → `{"ok":true}`

## 手机侧：连接配置（v4 连接向导）

APK 首次启动会自动弹出「连接鲸宝的家」设置面板，填两项即可：

| 项 | 说明 |
|---|---|
| **服务器地址** | 完整 WS 地址，见下表按你的场景选 |
| **配对码** | 与插件 `config.token` 一致，默认 `whale-pair-2026` |

点「**测试连接**」→ 显示 ✓ 连接成功即自动保存并连上。之后随时可从**通知栏 → 连接设置**修改。

### 三种网络场景怎么填地址

| 场景 | 地址形式 | 用户需要准备 |
|---|---|---|
| **同一 WiFi（最常见）** | `ws://<电脑或NAS的局域网IP>:28000/whale-pet/ws`，如 `ws://192.168.1.10:28000/whale-pet/ws` | 查电脑/NAS 内网 IP（ipconfig / 路由器后台）；DSH 端口 28000 未被防火墙拦 |
| **不在同一网络，会 Tailscale** | `ws://<Tailscale主机名或100.x.y.z>:28000/whale-pet/ws` | 两端装 [Tailscale](https://tailscale.com) 并登录同一账号（免费档够用，零配置打洞） |
| **不在同一网络，要给所有人连** | `wss://xxx.trycloudflare.com/whale-pet/ws` | 自建 Cloudflare Tunnel：`cloudflared tunnel --url http://localhost:28000`（quick tunnel 免费但域名随机重启会变；正式用法绑定自己的域名），且 Cloudflare 自动带 TLS → 用 `wss://` |

> 注意：`ws://` 用于局域网明文，`wss://` 用于有 TLS 的隧道/域名。端口 `28000` 是 DSH 默认 Web 端口，如你改过请同步改地址。
> 不要把 28000 端口直接映射到公网路由器——要远程就用隧道/Tailscale。

### 编译说明

本仓库不含 182MB 动画素材，需先取原项目素材：

```bash
git clone https://github.com/duoxichangan/whale-pet-Android
cp -r whale-pet-Android/app/src/main/assets/pet android/src/main/assets/
```

需要 JDK 17 + Android SDK 36，`./gradlew :app:assembleDebug` 即可。

### 改动清单（相对原项目）

| 文件 | 改动 |
|---|---|
| `WhaleBridge.java` | 新增。OkHttp WS 客户端：hello 握手、user.text、agent.delta 流式、agent.done/error、5s 自动重连 |
| `PetService.java` | 接线桥接：connectBridge / streamMessage（流式只更新气泡文本）/ 长按说话 startListening/endListening / TTS / 通知栏「打字聊天」「连接设置」/ 输入法聚焦修复 / **v4 连接向导 + 测试连接** |
| `PetVoice.java` | 新增。系统 SpeechRecognizer 封装（zh-CN，partial results，音量回调） |
| `PetEngine.java` | 加 `playClip(id)`：播动画不影响养成数值 |
| `PetAnimationView.java` | Gesture 接口加 `holdEnd()`，长按松手正确结束聆听 |
| `MainActivity.java` | 启动时申请 RECORD_AUDIO |
| `AndroidManifest.xml` | + INTERNET / ACCESS_NETWORK_STATE / RECORD_AUDIO，usesCleartextTraffic |
| `app/build.gradle` | + okhttp 4.12.0（唯一第三方依赖） |

服务器地址与 token 存在 SharedPreferences（`bridge-url` / `bridge-token`）。**v4 起首次启动弹连接向导，无默认开发者地址**；也可由分发者预置（adb 写入或改默认值后自行编译）。

## 版本记录

- **插件 v0.2.0（Agent 版）**：每条连接通过 `ctx.agents.create()` 创建真正的 DSH Agent——拥有宿主工具（实测可调用 domain_search 联网检索）、agent 域人设注入（鲸宝人设只影响自己）、`agent/assistant-stream` 流式转发、session 持久化（`sessions/--root--/whale-*`，重连记忆不丢）。WS 协议不变，APK 无需更新
- **v4**：首次启动连接向导（地址+配对码+测试连接）、通知栏「连接设置」入口、通知栏显示连接状态、去掉硬编码默认地址
- **v3**：长按说话反馈（音量音符跳动、迟到错误过滤）、回复时 happy 动画
- **v2**：长按语音输入（系统识别 zh-CN）、TTS 朗读、输入法弹出不修复不弹的 bug、通知栏打字聊天
- **v1**：文字桥接打通（WS 握手/流式/断线重连）

## WS 协议

```
→ {"type":"hello","token":"..."}          ← 握手
← {"type":"hello.ok","model":"..."}
→ {"type":"user.text","text":"..."}       ← 说话
← {"type":"agent.delta","text":"..."}     ← 流式增量
← {"type":"agent.done"} / {"type":"agent.error","message":"..."}
```

> **Agent 版说明**：插件为每条 WS 连接创建一个独立 DSH Agent（模型跟随 DSH 默认模型）。回复前模型可自由调用宿主注册的工具（搜索、检索等）；人设约束它输出 1~3 句短句适配气泡。换手机重连或杀进程后，只要 sessionId 对应的 session 还在，历史记忆仍然保留。

## 安全提示

- **Agent 版意味着鲸宝能操作你的 DSH 宿主**（执行工具）：token 就是钥匙，配对码务必改成强随机值，不要把带 token 的地址分享给别人

- 不要把 DSH 的 28000 端口暴露公网；跨网段建议 Tailscale / SSH 隧道 / Cloudflare Tunnel（wss）
- token 仅是简单配对凭据，非加密保证

## License

MIT（沿用原项目）。素材版权归 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 原作者。
