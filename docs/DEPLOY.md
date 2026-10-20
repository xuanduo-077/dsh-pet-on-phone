# 从零到鲸宝上线：完整部署文书

写给第一次接触 DSH 和安卓编译的朋友。全流程 30~60 分钟。

## 你将得到什么

一只住在安卓手机桌面上的小鲸鱼：轻触有回应、长按说话它听、回复逐字显示在头顶气泡。它背后是电脑上的 DSH（DeepSeek 实体），能联网搜索、查资料、带着记忆聊天。手机端语音识别本地完成，不点不联网。

## 总览

```
[手机 APK] --WebSocket--> [DSH 桥接插件] --> [DSH Agent（工具+记忆）]
   语音转文字        28000 端口
```

三步：① 电脑装好 DSH 并装插件 → ② 编译 APK → ③ 手机填地址配对。

## 第一步：准备电脑/NAS 端

前提：已安装并运行 DSH（Web 界面 http://127.0.0.1:28000 可打开）。

1. 把本仓库 `dsh-whale-bridge/` 目录复制到 DSH 的 plugins 目录
2. 挂载插件（DSH profile 是 pnpm 项目）：

```bash
cd <你的 dsh profile 目录>
pnpm add link:<绝对路径>/plugins/dsh-whale-bridge
```

3. 编辑 profile 的 `package.json`，在 `dsh.profile.bundles` 数组加入 `"dsh-whale-bridge"`
4. 确认 `cordis.patch.yml` 里有插件配置（**务必改 token**）：

```yaml
- id: dsh-whale-bridge
  config:
    routePrefix: '/whale-pet'
    token: '改成一串只有你知道的配对码'
```

5. 重启 DSH，验证：

```bash
curl http://127.0.0.1:28000/whale-pet/health
# {"ok":true,"version":"0.4.0",...} 即成功
```

### 插件自带能力（v0.4.0）

| 接口 | 作用 |
|---|---|
| `/whale-pet/health` | 健康检查（含摘要缓存状态） |
| `/whale-pet/sessions` | 桌面端会话列表（只读） |
| `/whale-pet/sessions/<id>` | 会话详情（只读，截 8000 字） |
| `/whale-pet/tunnel` | Cloudflare 临时隧道管理页（一键开关+二维码） |

**鲸宝能感知桌面**：插件每 30 秒生成桌面活跃会话摘要，附在每轮对话里——你在手机上问"我电脑上在干嘛"，它答得出来。

## 第二步：编译 APK

需要 JDK 17 + Android SDK 36（不需要 Android Studio，命令行足够）。

```bash
git clone https://github.com/duoxichangan/whale-pet-Android   # 原项目（素材）
# 用本仓库 android/ 目录里的改动文件覆盖同名文件
cp -r whale-pet-Android/app/src/main/assets/pet android/src/main/assets/
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

编译环境三坑（NAS/容器用户注意）：
- `GRADLE_USER_HOME` 指到可写目录（/home 可能只读）
- debug.keystore 缺失时用 keytool 生成，`ANDROID_USER_HOME` 指到含 `.android/` 的目录
- cgroup v2 容器里构建若报 `ExceptionInInitializerError (CgroupV2Subsystem)`，加 `JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport"`

不想编译？Releases 页若有现成 APK 直接下载（自用签名，安装时允许未知来源）。

## 第三步：手机配对

1. 安装 APK，首次启动弹「连接鲸宝的家」
2. 填两个框：
   - **服务器地址**：`ws://<电脑局域网IP>:28000/whale-pet/ws`
   - **配对码**：与插件 token 一致
3. 点「测试连接」→ ✓ 即成功
4. 首页点「让她来陪你」，长按鲸鱼说话

### 网络场景速查

| 场景 | 地址 | 备注 |
|---|---|---|
| 同一 WiFi | `ws://192.168.x.x:28000/whale-pet/ws` | 90% 的情况，零配置 |
| Tailscale | `ws://100.x.y.z:28000/whale-pet/ws` | 两端装 Tailscale，免费 |
| 自有域名隧道 | `wss://你的域名/whale-pet/ws` | cloudflared named tunnel |
| 临时隧道 | 插件管理页 `/whale-pet/tunnel` 一键开 | 域名随机；部分国内网络拦 WS，连不上就换前两种 |

## 常见问题

**Q：点了没反应 / 连接失败？**
先在电脑浏览器开 `http://IP:28000/whale-pet/health` 确认插件活着；再检查地址是不是 `ws://` 开头、路径带 `/whale-pet/ws`。

**Q：识别不到语音？**
系统弹麦克风权限要允许；国产 ROM 到设置里给 App「后台麦克风」+电池白名单。

**Q：回复慢？**
瓶颈在服务端模型首字延迟。可在 DSH 里给鲸宝的 agent 配快的模型。

**Q：会话页（WebView）排版乱？**
App 设置里开「网页简洁模式」，抹掉主题皮肤插件在手机上的干扰。

**Q：重装/换手机记忆还在吗？**
在。鲸宝的 session 存在电脑上（`sessions/--root--/whale-*`），重连即恢复。

## 安全清单

- [ ] token 改成强随机值（这是让 Agent 执行工具的钥匙）
- [ ] 28000 端口不映射公网
- [ ] 跨网段用 Tailscale / Cloudflare Tunnel（wss）
- [ ] 分享截图前遮住地址栏里的 token
