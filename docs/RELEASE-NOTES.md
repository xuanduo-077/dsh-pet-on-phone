# 泡泡鲸 2.3.0

完全离线的 Android 桌宠，支持 Android 9 及以上。

- 106 段角色动画，轻触互动、拖动定位、长按打开菜单。
- 日常 / 休息、饱食 / 心情 / 精力、一起吃东西和玩乐。
- 统一深浅蓝色界面，紧凑横向筛选和居中的动作确认页。
- 陪伴手账直接回看动画，不重复结算；通知静默常驻。
- 原生悬浮窗与关屏暂停 / 解锁恢复。

下载 APK 安装；旧版用户可使用同签名直接覆盖更新。首次使用需允许悬浮窗与陪伴通知。APK 校验值见 SHA256SUMS.txt。

代码清理保留原角色素材与养成逻辑，32 项原生回归通过。参考与致谢见仓库 README 底部的 dsh-pet 和 baifanyu。

---

## 插件 v0.2.0（Agent 版）

鲸宝从「裸 LLM 聊天」升级为「真 DSH Agent」：

- 每条 WS 连接 `ctx.agents.create()` 创建独立 Agent，拥有宿主全部已注册工具
- 人设经 `agentCtx.systemPrompt.section` 注入 agent 域，不污染其他 agent
- 流式走 `agent/assistant-stream` 事件（text-delta → agent.delta），最终文本从 session 事件取
- session 持久化于 `sessions/--root--/whale-*`，断线重连记忆不丢
- `inject = ["webServer","agentDefaultModel","llm","agents","tools","sessions","sessionQuery"]`
- 踩坑记录：session `meta` 带自定义 `origin` 会被校验拒绝（must be "subagent"）；inject 含 "config" 会卡服务等待
