> **历史文档（最后核对 2026-10-25），标题「唯一口径」已失效**（2026-11-20 标注）：本文描述 vibex-bridge / 文件 broker / Tailscale Funnel 沙箱链路期的状态，其中工程根 `H:\mc-mod-dev` 已迁至 `H:\我的世界盒子项目\03-模组工程`（旧快照与旧产物归档于项目根 `90-历史归档/`）。现行进度与裁决口径 = 笔记库《项目总览》§10；工具与接入路 = 《开发工具环境》；上下文入口 = `../README.md`。正文保留作追溯。

# 当前状态（唯一口径）

> 最后核对：**2026-10-25 19:46**（本机实测，不是从旧文档抄的）
> **交接 AI 先读本文，再动任何东西。**
> 其他 `交接-*.md`、`规划-*.md`、`调研-*.md`、`vibex-bridge/交接文档-*`、各 daily 日志、`.workbuddy/memory/*`、`.workbuddy-ai/memory/*` 全部是**历史记录**：只能用来追溯“为什么”，不能用来判断“现在是什么”。
> 本文不记录任何 URL / token / key。真值只在 `panel/token.txt`、`C:\Users\Administrator\.vibex-keys\`（`auth-key.txt` / `api-key.txt` / `gui-key.txt`）、平台配置和环境变量里。`vm-mcp/creds.js` 会回落到 `vm-mcp/auth-key.txt`，但当前该回落文件不存在。

---

## 0. 一句话

本机 Windows 常驻两套链路：

1. **Qoder / WorkBuddy → 本机 OpenAI 兼容桥（8787）→ VibeX 网页 AI**；
2. **VibeX 云沙箱 → Tailscale Funnel 固定域名 → 本机文件 broker / VM 受限 GUI**。

远程通道是 **Tailscale Funnel 固定域名**。`pinggy` / `serveo` / `localhost.run` 已全部退役，**禁止再按旧文档启用**。

## 1. 实时拓扑（2026-10-25 实测）

```
Qoder / WorkBuddy ── http://127.0.0.1:8787/v1 ──► vibex-bridge ──WS──► VibeX 网页 AI

VibeX 云沙箱 ──HTTPS──► Tailscale Funnel 固定域名
                          ├─ /filesystem/mcp ─► 127.0.0.1:8005 审计代理 ─► 127.0.0.1:8001 文件 broker
                          └─ :8443 ──────────► 主机 :18802 ─► VM 受限 windows-mcp
```

| 端口 | 角色 | 绑定 | 实测状态 |
|---|---|---|---|
| 8001 | 文件 broker（supergateway + filesystem MCP） | 127.0.0.1 | UP，端点前缀 `/filesystem/mcp` |
| 8005 | 审计代理（JSONL 落 `panel/audit.jsonl`） | 127.0.0.1 | UP |
| 8443 | Funnel GUI 通道 → VM 受限 windows-mcp | Tailscale 地址（非回环） | **ON**（用户 2026-10-22 决定保留） |
| 8787 | vibex-bridge 生产反代 | 127.0.0.1 | UP，`/health` 200，bindings=63 |
| 9100 | 值守面板 | 127.0.0.1 | UP，页面 200，无 token 401 |
| 18801 | VM 管理口（host→VM 端口转发） | 127.0.0.1 | UP |
| 18802 | VM GUI（host→VM 端口转发） | 127.0.0.1 | UP |

面板当前根目录：**`H:\mc-mod-dev`**（2026-10-25 19:45 切换，无 junction；broker 只暴露这一个目录）。

## 2. 常驻件（2026-10-25 实测状态）

| 名称 | 类型 | 状态 | 说明 |
|---|---|---|---|
| `vibex-fs-broker` | 计划任务 AtLogon | Running | 起 8001 |
| `vibex-host-watchdog` | 计划任务 AtLogon | Ready（进程在跑） | 每 10s 探 8001/8005，面板显示累计恢复 9 次 |
| `vibex-panel` | 计划任务 AtLogon | Running | 9100；桌面快捷方式“远程 MCP 控制台”可一键打开 |
| `vibex-reverse-tunnel` | 计划任务 | Ready | 宿主机 ↔ VM 反向隧道 |
| `vibex-bridge-daemon` | 计划任务 AtLogon | Ready（桥在跑） | 8787 的看门狗；**不要手动停** |
| `vm-mcp-tunnel` | 计划任务 | **Disabled（2026-10-25 停用）** | pinggy 时代遗留，禁止重新启用 |

关键状态文件：`mcp-fs-broker/root-state.json`、`panel/watchdog-interval.json`、`panel/token.txt`、`panel/audit.jsonl`、`panel/panel.log`。

## 3. 远程通道（最容易记错的地方）

- 现用：**Tailscale Funnel 固定域名**（文件通道 + 8443 GUI 通道）。Funnel 就绪需要 30–60s。
- 已退役：`pinggy`（免费 60 分钟换地址）、`serveo.net`、`localhost.run`。三者的脚本、经验、坑都只保留在历史文档里。
- `vm-mcp/mcp-tunnel.js` 与计划任务 `vm-mcp-tunnel` 属于 pinggy 时代产物，**不要运行**；计划任务已于 **2026-10-25 停用（Disabled）**，无残留 tunnel 进程。`C:\Users\Administrator\.vibex-keys\auth-key.txt` 里若出现 `pinggy` 域名即为过期凭证。
- 平台侧凭证只从 `C:\Users\Administrator\.vibex-keys\` 读取后手填（`auth-key.txt` = 公网 URL + Bearer key，`api-key.txt` = 文件 broker，`gui-key.txt` = VM GUI）；文档、日志、prompt 一律不落 URL/key。

## 4. 客户端接法

- **Qoder CN**：BYOK 自定义 provider 指到 `http://127.0.0.1:8787/v1`，模型名 `vibex-*`。
- **WorkBuddy**：`C:\Users\Administrator\.workbuddy\models.json` 里的 `vibex-*` 同样指到 8787。
- 桥已处理的 Qoder 专属行为：内联图片转发、回答建议块 → 输入框建议、上下文压缩后的绑定重建计数、技能进化隐藏请求短路。细节与证据见 `vibex-bridge/test-reports/BUGS.md`。
- VibeX 沙箱只负责“重炮”任务；不要让它直接操控日常主机，VM 内走受限 windows-mcp。

## 5. 已验收（不要重复怀疑）

- 2026-10-24：桥回放 **S0–S10 105/105 PASS**，资源卫生 CLEAN（`node replay/run.js`）。
- 2026-10-24：Qoder 内联图片、建议块桥接、resume 去重、技能复核短路、全量重建计数全部上线并实测。
- 2026-10-25：Funnel、broker、审计代理、VM 管理/GUI、面板、桥全部 UP（面板自检数据）。
- 2026-10-25：遗留计划任务 `vm-mcp-tunnel` 已停用（Disabled），确认无残留 tunnel 进程。
- 2026-10-25 19:45：面板切根到 `H:\mc-mod-dev`，六步全 PASS（MCP 写读往返一致）。同时修掉 broker 重启残留旧进程占 8001 的问题：`start-broker.cmd` 按端口清旧进程树，面板等监听 PID 变掉再探健康。
- 路 B（VibeX agent 操控 VM/本机受限能力）**已端到端打通**，不是“尚未验证”。

## 6. 已知未完成（不要当成已完成）

- #16 VibeX 服务端会话 GC；#19 沙箱残留 `artifact-demo*.txt` 清理；T3 多轮上下文绑定 + 多模型切换；T4 长回合产物回传；T5 边界与异常。
- Qoder 自身遥测不可见；若不想让技能进化请求产生，优先在 Qoder 设置里关掉自动技能建议。

## 7. 红线

- **8787 与 `vibex-bridge-daemon` 禁止手动 kill/停**。需要重启只能用 `node repair.js --restart`，且先确认 `/health` 的 `inflight=0`、并征得用户同意。
- token / key / 公网 URL 不打印、不落文档、不落日志、不进 prompt。VibeX 沙箱有 `UserPromptSubmit` 钩子，**不要改写措辞绕过**。
- 面板只绑 `127.0.0.1`，绝不走 Funnel。
- `bindings.json` 是活状态：清理用 `POST /admin/forget-binding`（回环 + Bearer token），不要手改文件。
- 测试桥必须用 `VIBEX_BINDINGS` 指到 `replay/.tmp/`，禁止污染生产 bindings。
- `mcp__windows_mcp__*` 控制的是**本机**，不是 VM；VM 走 8443 / 18801 / 18802。
- 动 `server.js` 回合循环前，先跑 `node replay/run.js`。

## 8. 文档地图

| 文件 | 性质 | 现在还能不能用 |
|---|---|---|
| `CURRENT-STATE.md` | **唯一现状口径** | ✅ 先读这个 |
| `AGENTS.md` / `CLAUDE.md` | Agent 入口与红线 | ✅ 自动读 |
| `vibex-bridge/test-reports/BUGS.md` | 缺陷台账 + 证据 | ✅ 当前权威 |
| `vibex-bridge/replay/CONTRACT.md` | 回放契约 | ✅ 当前 |
| `vibex-bridge/README.md` | 桥使用说明 | ✅ 使用层面 |
| `交接-远程MCP与本机值守面板-2026-10-22.md` | 面板/broker 实现细节 | ⚠️ 细节有效，状态以本文为准 |
| `交接-Win11隧道MCP-给QoDer.md` | 2026-10-21 路 B 交接 | 🕓 历史；pinggy 段与“未打通”结论已作废 |
| `规划-项目重构与开源替代-2026-10-23.md` | 历史规划 | 🕓 历史；pinggy 保留项作废 |
| `调研-开源替代方案-2026-10-23.md` | 历史调研快照 | 🕓 历史 |
| `vibex-bridge/交接文档-问题复盘与技术沉淀.md` | 2026-10-20 复盘 | 🕓 历史教训 |
| `.workbuddy/memory/2026-*.md`、`.workbuddy-ai/memory/*` | 各 AI 的历史日志 | 🕓 只追溯，不作现状 |
| Qoder 记忆 `win11-tunnel-path-b.md` | 历史 + 最新混排 | ⚠️ 顶部已标横幅；现状以本文为准 |
| Qoder 记忆 `workbuddy-daily-*.md` | 历史日志 | 🕓 只追溯 |

## 9. 更新规则（防止再长出第二套口径）

1. 端口、隧道、常驻件、验收状态变化时：**只改本文**，并在当天 daily 里留一条带日期的记录。
2. 不要新建第二份“交接总结”；历史文档只在顶部加横幅，不改正文。
3. 任何文档出现 `pinggy` / `serveo` / `localhost.run`，默认按历史处理；先对照本文再行动。
4. 新写记忆时用“日期 + 事实 + 证据”，不要写“目前/现在”这类会过期的词而不带日期。

## 10. 快速自检

- 桥：`http://127.0.0.1:8787/health`（看 `inflight` / `bindings` / `rebuilds`）。
- 面板：双击桌面“远程 MCP 控制台”，或 `http://127.0.0.1:9100/`；令牌由启动脚本从 `panel/token.txt` 自动带入。
- 端口：`netstat -ano | findstr "8001 8005 8443 8787 9100 18801 18802"`。
- 计划任务：`Get-ScheduledTask | ? TaskName -like 'vibex*'`。
