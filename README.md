# dsh-pet-on-phone 🐋

把 [泡泡鲸桌宠 whale-pet-Android](https://github.com/duoxichangan/whale-pet-Android)（v2.3.0 fork）接入 [DeepSeek 实体（DSH）](https://github.com/anthropics/claude-code)，实现：

- **长按鲸鱼说话**（Android 系统 SpeechRecognizer，zh-CN）→ 转文字发到电脑上的 DSH
- **气泡流式回复**：DeepSeek 逐字回信直接显示在鲸鱼头顶气泡，说话时播 `happy` 动画
- **TTS 朗读回复**（系统 TextToSpeech）
- **通知栏打字聊天**（输入法聚焦修复版）
- 原项目全部养成玩法保留（轻触回应、饱食/心情/精力、106 段动画、完全离线）

> 架构：DSH 侧跑一个 **桥接插件**（WebSocket 服务），手机端 APK 是 **WS 客户端**。
> 语音识别与合成全部在手机本地完成，DSH 只收发文本——不点不联网，保住原项目离线定位。

## 仓库结构

```
dsh-whale-bridge/     DSH 桥接插件（Cordis 插件，手写 RFC6455 WS，零第三方依赖）
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

## 安卓侧：自己编译 APK

本仓库不含 182MB 动画素材，需先取原项目素材：

```bash
git clone https://github.com/duoxichangan/whale-pet-Android
cp -r whale-pet-Android/app/src/main/assets/pet android/src/main/assets/
# 下载原仓库的 app/build.gradle、app/src/main/assets/native-*.json 覆盖（本仓库已含）
```

需要 JDK 17 + Android SDK 36，`./gradlew :app:assembleDebug` 即可。

### 改动清单（相对原项目）

| 文件 | 改动 |
|---|---|
| `WhaleBridge.java` | 新增。OkHttp WS 客户端：hello 握手、user.text、agent.delta 流式、agent.done/error、5s 自动重连 |
| `PetService.java` | 接线桥接：connectBridge / streamMessage（流式只更新气泡文本）/ 长按说话 startListening/endListening / TTS / 通知栏「打字聊天」/ 输入法聚焦修复 |
| `PetVoice.java` | 新增。系统 SpeechRecognizer 封装（zh-CN，partial results，音量回调） |
| `PetEngine.java` | 加 `playClip(id)`：播动画不影响养成数值 |
| `PetAnimationView.java` | Gesture 接口加 `holdEnd()`，长按松手正确结束聆听 |
| `MainActivity.java` | 启动时申请 RECORD_AUDIO |
| `AndroidManifest.xml` | + INTERNET / ACCESS_NETWORK_STATE / RECORD_AUDIO，usesCleartextTraffic |
| `app/build.gradle` | + okhttp 4.12.0（唯一第三方依赖） |

服务器地址与 token 存在 SharedPreferences（`bridge-url` / `bridge-token`），默认 `ws://192.168.31.55:28000/whale-pet/ws`。

## WS 协议

```
→ {"type":"hello","token":"..."}          ← 握手
← {"type":"hello.ok","model":"..."}
→ {"type":"user.text","text":"..."}       ← 说话
← {"type":"agent.delta","text":"..."}     ← 流式增量
← {"type":"agent.done"} / {"type":"agent.error","message":"..."}
```

## 安全提示

- 不要把 DSH 的 28000 端口暴露公网；跨网段建议 Tailscale / SSH 隧道 / Cloudflare Tunnel（wss）
- token 仅是简单配对凭据，非加密保证

## License

MIT（沿用原项目）。素材版权归 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 原作者。
