# Allay Village 任务台账

> 由主控 agent 维护。每次派发子任务/执行里程碑时更新一行。
> 状态: pending(待办) / running(进行中) / done(完成) / failed(失败) / cancelled(已取消)

| ID | 任务 | 执行方 | 状态 | 产物/备注 |
|----|------|--------|------|-----------|
| T-001 | M0 骨架 14 文件写入 H 盘 + sha256 校验 | 主 agent | done | H:\mc-mod-dev\allay-village\ 全部落盘 |
| T-002 | tailnet 文件通道验证(list_allowed_directories) | 主 agent | done | 通道正常, ~1.2s/次, 根目录已收窄为 allay-village |
| T-003 | M0 本地构建验证(拷 gradle wrapper + gradlew build) | 用户 | pending | 需用户在 Windows 侧执行并回报报错 |
| T-004 | M1 共鸣台方块 + POI + 职业绑定 | 未派发 | pending | 等 T-003 通过后开工 |

## 取消规则
- 用户随时发消息打断 = 整轮中止, 正在跑的子智能体一并终止。
- 后台任务: 用户报 ID(如 T-004 或平台任务号), 主 agent 调用停止工具杀进程。
- 前台同步子智能体: 中途不可单独取消, 只能等它结束或打断整轮。
