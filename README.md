# 偶鲸鲸 · 口袋里的海 × DeepSeek

> 一只住在安卓手机桌面上的小鲸鱼桌宠，背后连着你电脑上的 DeepSeek（DSH）。
> 在手机上跟它打字、说话；也能随时查看、操控电脑上正在跑的 DeepSeek 会话和任务。

偶鲸鲸 = 泡泡鲸桌宠（完全离线的养成桌宠） × DeepSeek 桌面端（DSH）。
一个安卓原生 App + 一个 DSH 桥接插件，两者通过 WebSocket 通信。

---

## 功能总览

### 📱 安卓 App（原生 Java，无 WebView、无第三方 UI 库）

| 页面 | 功能 |
|---|---|
| **任务** | 直读电脑端 DSH 的会话列表（标题 / 在线·运行中状态 / 时间 / 工作目录），点进详情看完整对话（含工具调用与结果），可**给任意会话发消息**、**中止正在运行的任务** |
| **陪伴** | 和鲸宝聊天：打字或**长按麦克风说话**（系统语音识别 zh-CN，松手发送），回复逐字流式显示；顶部状态灯显示连接状态；聊天记录本地保存 |
| **互动** | 喂食、玩耍等养成互动（饱食 / 心情 / 精力三围），带动画弹窗 |
| **足迹** | 养成记录时间线 |
| **设置** | 连接配置（地址自动补全、配对码、测试连接）、桌宠开关、**宠物大小滑条**（拖动即时生效）、深色模式、关于偶鲸鲸 |

### 🐳 桌面宠物（悬浮窗 + 前台服务）

- 轻触回应、拖动定位、106 段透明角色动画、关屏自动暂停省电
- **长按鲸鱼 = 说话**：系统语音识别 → 发给 DeepSeek → 头顶气泡逐字流式回复 + 表情动画
- 大小、位置、深浅色均可调；养成数据完全本地存档

### 🔌 DSH 桥接插件（dsh-whale-bridge/）

装在电脑端 DSH 里，提供：

- **WebSocket**（`/whale-pet/ws`，配对码鉴权）：`hello` 握手 → `user.text` → `agent.delta` 流式 → `agent.done`；另支持 `watch`（订阅桌面会话流式输出）、`session.message`、`session.stop`、`approval.answer`
- **HTTP API**（`/whale-pet/api/*`，token 鉴权）：
  - `GET /api/sessions` — 会话列表（标题、live/busy、cwd）
  - `GET /api/sessions/:id` — 会话详情（分页）
  - `POST /api/sessions/:id/message` — 给会话发消息（在线直接投递；冷会话自动 resume）
  - `POST /api/sessions/:id/stop` — 中止任务
- **远程审批**：电脑端 DSH 请求工具授权时转发到手机，在手机上点允许/拒绝（120 秒无响应回落桌面端）
- **桌面上下文**：鲸宝每 30 秒「看一眼」电脑上正在跑的会话，能回答「我现在电脑上在跑什么」
- **公网隧道（可选）**：管理页 `http://电脑IP:28000/whale-pet/tunnel`，一键开 Cloudflare 临时隧道 + 二维码

每个 WS 连接对应一个真实的 DSH Agent（完整工具链：文件读写、搜索、skill、subagent……）。鲸宝不只是聊天机器人，是**能干活的 DeepSeek 分身**。

---

## 系统要求

- 手机：Android 9+（API 28+）
- 电脑：已安装 DeepSeek 桌面端（DSH）
- 同一局域网，或自备 Cloudflare 隧道做公网穿透

## 安装

### 1. 电脑端：安装插件

把 `dsh-whale-bridge/` 放到 `$DSH_HOME/plugins/`，在 profile 里挂载：

```bash
cd <你的 dsh profile 目录>     # profiles/web
pnpm add link:<绝对路径>/plugins/dsh-whale-bridge
# 然后在 profile package.json 的 dsh.profile.bundles 数组里加 "dsh-whale-bridge"
# cordis.patch.yml 示例见仓库内文件；默认配对码是占位符 change-me-pair-code，务必改掉
```

重启 DSH，浏览器打开 `http://电脑IP:28000/whale-pet/health`，返回 `{"ok":true,...}` 即成功。

### 2. 手机端：安装 APK

从 [Releases](../../releases) 下载 APK（或自行编译，见下文），需允许「安装未知来源应用」。

首次使用：

1. 打开 App 自动进入设置页。服务器地址**只需填 IP 或域名，会自动补全**：
   - 同一 WiFi：填 `192.168.x.x:28000` → 自动补成 `ws://192.168.x.x:28000/whale-pet/ws`
   - 公网：填 `你的域名` → 自动补成 `wss://你的域名/whale-pet/ws`
2. 填配对码 → 「测试连接」→ 「保存并连接」
3. 打开「让她到桌面陪我」→ 按提示依次授权悬浮窗、麦克风、通知权限

### 3.（可选）公网访问

| 场景 | 方式 |
|---|---|
| 同一 WiFi（推荐） | 内网直连，零配置 |
| 出门也要用 | 自建 Cloudflare Tunnel（named tunnel，免费、地址固定）：`cloudflared tunnel --url http://127.0.0.1:28000`，手机填 `wss://你的域名/whale-pet/ws` |
| 临时应急 | 插件管理页点「开启隧道」扫二维码。注意：部分国内网络会拦截临时隧道（trycloudflare.com）的 WebSocket，HTTP 能通但 WS 被掐 |

**安全提醒**：不要把 28000 端口直接暴露公网；改掉默认配对码；公网一律走 wss + 隧道。

---

## 编译 APK（可选）

```bash
# JDK 17 + Android SDK 36，项目零第三方依赖（仅 OkHttp）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

容器/无桌面环境构建已知坑：设置 `GRADLE_USER_HOME`、`ANDROID_USER_HOME` 到可写目录；cgroup v2 需 `JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport"`（否则 `validateSigningDebug` 报 `ExceptionInInitializerError`）；建议 `--no-daemon`。详见 [docs/DEPLOY.md](docs/DEPLOY.md)。

## 项目结构

```
android/                 # 安卓 App 源码（com.whale.pet）
  MainActivity.java      #   App 外壳：五页导航 + 设置 + 聊天页
  TasksPage.java         #   任务页（会话列表/详情/发消息/中止）
  WhaleApi.java          #   插件 HTTP API 客户端
  WhaleBridge.java       #   WS 客户端（自动重连）
  WhaleChat.java         #   聊天记录本地存储
  PetService.java        #   桌宠前台服务 + 悬浮窗 + 气泡
  PetEngine.java         #   养成引擎（饱食/心情/精力）
  PetVoice.java          #   系统语音识别封装
  PetPanel.java          #   互动页（喂食/玩耍）
  PetJournal.java        #   足迹
dsh-whale-bridge/        # DSH 桥接插件（Cordis 插件，纯 Node 无第三方依赖）
  lib/index.js           #   WS + HTTP API + 远程审批 + 桌面上下文
  lib/cloudflared.mjs    #   Cloudflare 隧道管理器
  cordis.patch.yml       #   安装 patch 示例
docs/                    # 部署文档 / 交付说明 / 更新日志
```

## WS 协议速览

```
→ {"type":"hello","token":"配对码","petId":"..."}
← {"type":"hello.ok","model":"...","version":"0.5.0"}
→ {"type":"user.text","id":"m1","text":"你好"}
← {"type":"agent.delta","id":"m1","delta":"你"}     （逐字流式）
← {"type":"agent.done","id":"m1","text":"你好呀"}
→ {"type":"watch","sessionId":"session-xxx"}         （订阅桌面会话流式输出）
← {"type":"session.delta","sessionId":"...","delta":"..."}
→ {"type":"approval.answer","id":"...","outcome":"allowed-once"}
```

## 致谢 / 参考项目

本项目建立在以下开源项目之上，感谢原作者：

| 项目 | 用途 |
|---|---|
| [duoxichangan/whale-pet-Android](https://github.com/duoxichangan/whale-pet-Android) | **泡泡鲸桌宠本体**：106 段角色动画、养成引擎、悬浮窗前台服务、纯原生零依赖架构，本项目的基座（fork 自 v2.3.0，MIT） |
| [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) | 桌宠美术素材来源；其 DSH 插件实现（开放 HTTP API、头顶气泡、工作状态联动）是本插件的设计参考 |
| [wenbin-wb/dsh-bridge](https://github.com/wenbin-wb/dsh-bridge) | Cloudflare 隧道管理（cloudflared 生命周期 / 健康检查 / 日志轮转）与二维码方案参考；其手机页面适配思路启发了本项目的原生重写 |
| [LIN428924379/baifanyu](https://github.com/LIN428924379/baifanyu) | 桌宠原生动画渲染的早期参考 |
| [kazuhikoarase/qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) | 隧道页二维码（vendored，MIT） |
| [DeepSeek](https://github.com/deepseek-ai) | DSH 桌面端本体与插件生态（Cordis） |

## 许可证

MIT（延续上游项目许可）。泡泡鲸动画素材版权归上游项目所有，请遵守其许可使用。
