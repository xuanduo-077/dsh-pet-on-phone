# 泡泡鲸语音版 APK 交付说明

## 这是什么

基于泡泡鲸 v2.3.0 官方源码，加入了「接 DeepSeek（DSH）对话」能力的定制版：

- **长按鲸鱼** → 打开「陪伴时光」菜单 → 底部多了一行「跟鲸宝说点什么…」输入框
- 打字发送 → 鲸鱼头顶气泡**逐字流式显示** AI 回复（由 NAS 上的 DSH whale-bridge 插件转发给 DeepSeek 模型）
- 连接断开会自动重连（5 秒一次）
- 其余功能（106 段动画、喂食、玩耍、养成、拖拽、待机）与原版完全一致

## 安装步骤

1. 把 `whale-pet-bridge-debug.apk` 传到手机（微信文件传输助手 / 网盘 / 数据线都行）
2. 点开 APK → 系统提示「未知来源应用」→ 允许 → 安装
3. 打开「泡泡鲸」App → 授权悬浮窗权限 → 开启陪伴

> 如果手机上已装官方原版，**先卸载原版再装这个**（签名不同，不能覆盖安装）。养成存档会清零。

## 连接配置（已内置默认值，一般不用改）

| 项 | 默认值 |
|---|---|
| 服务器地址（家里） | `ws://192.168.31.55:28000/whale-pet/ws` |
| 服务器地址（外面） | `wss://ds.shadow077.dpdns.org/whale-pet/ws` |
| 配对码 | `whale-pair-2026` |

- **在家用**：手机连上和 NAS 同一个 Wi-Fi，直接用
- **在外面用**：需要把 APK 里默认地址换成 wss 那个（下一版做成设置项；当前版本如需切换，告诉我重新编译即可）
- 前提：NAS 上 dsh-whale-bridge 插件在跑（已部署并测试通过）

## 常见问题

| 现象 | 原因/处理 |
|---|---|
| 气泡显示「还没连上鲸宝的家」 | 手机和 NAS 不在同一网段；或 NAS 插件没启动 |
| 显示「配对码不对」 | token 不匹配，NAS 端 cordis.patch.yml 的 token 值要对上 |
| 能连上但回复报错 | 看 NAS `/vol2/@appdata/dsh/dsh.log` |
| 悬浮窗不显示 | 系统设置里给泡泡鲸「显示在其他应用上层」权限 |

## 与原版的差异（源码级）

- 新增 `app/src/main/java/com/whale/pet/WhaleBridge.java`（WS 客户端）
- `PetService.java`：+connectBridge()、+addChatRow()、+streamMessage()，onCreate 连接/onDestroy 关闭
- `AndroidManifest.xml`：+INTERNET / +ACCESS_NETWORK_STATE 权限，usesCleartextTraffic 允许局域网 ws://
- `app/build.gradle`：+okhttp 4.12.0
