# Agent 入口与项目红线

## 先读这一份

- **现状唯一口径：`CURRENT-STATE.md`**（最后核对 2026-10-25）。端口、隧道、常驻件、已验收/未完成都以它为准。
- 旧 `交接-*.md`、`规划-*.md`、`调研-*.md`、各 daily 日志、`.workbuddy/memory/*`、`.workbuddy-ai/memory/*` 都是**历史记录**，不能用来判断“现在在用的是什么”。
- 远程通道现在是 **Tailscale Funnel 固定域名**。`pinggy` / `serveo` / `localhost.run` 已退役；看到这些词的文档默认是历史，不要照着做。
- 旧的“路 B 尚未打通”“反代修复暂缓”等结论都已过时：路 B 已打通，桥的缺陷已按 `vibex-bridge/test-reports/BUGS.md` 修到 S0–S10 105/105。

## 红线

- 不手动 kill/停 **8787** 和计划任务 **`vibex-bridge-daemon`**；重启只能用 `node repair.js --restart`，先确认 `/health` 的 `inflight=0`，并征得用户同意。
- token / key / 公网 URL 不打印、不落文档、不落日志、不进 prompt；VibeX 的 `UserPromptSubmit` 钩子不能用改写措辞绕过。
- 面板只绑 `127.0.0.1`，绝不走 Funnel。
- 不手改 `bindings.json`；清理绑定用 `POST /admin/forget-binding`。
- 测试桥用 `VIBEX_BINDINGS` 指到 `replay/.tmp/`，禁止污染生产 bindings。
- `mcp__windows_mcp__*` 控制的是本机，不是 VM。
- `vm-mcp-tunnel` 计划任务已于 2026-10-25 停用，禁止重新启用；`vm-mcp/mcp-tunnel.js` 是 pinggy 时代遗留，禁止运行。

## 工作方式

- 动 `vibex-bridge/server.js` 回合循环前，先跑 `node replay/run.js`。
- 架构/端口/隧道/常驻件变化时，只更新 `CURRENT-STATE.md` + 当天 daily 一行；不要新建第二份“交接总结”。
- 历史文档只加顶部横幅，不改正文。
