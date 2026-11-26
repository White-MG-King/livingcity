# livingcity-sim —— 无头城市模拟核心

《我的世界盒子》模组（modid `livingcity`）的模拟核心。使用纯 Java 17，可独立编译运行。城市需求、生产、计划者、国家拓殖、港口贸易和陆战模拟在这里计算，仓库根目录的 MC 工程负责传感输入与世界中的表现。

仓库根目录的 `build.gradle` 使用 `sourceSets.main.java.srcDir file('sim/src')` 编译本目录源码。`gradlew simTest` 运行 `RegressionTests`，也会随 `check` 执行。设计稿仍在本机笔记库的 `02-项目笔记/00-总览/00-V1系列设计稿/` 四篇中持续更新。本机原有 `03-代码/livingcity-sim/` 目录通过 Windows 目录联接指向这里。

## 编译与运行

```bash
cd sim
javac -encoding UTF-8 -d out src/citysim/*.java
java -cp out citysim.RegressionTests   # 退出码 0 = 全绿
java -cp out citysim.Debug             # 用例 D 场景的逐周期诊断输出
```

V1.2.1-dev.2 已通过 81 项核心回归。下文的 M1 模块地图与开发记录保留早期实现背景，当前功能与启动方式见仓库根目录 README。

## 模块地图

| 文件                | 职责                                                                     | 对应笔记                     |
| ----------------- | ---------------------------------------------------------------------- | ------------------------ |
| `LayerId`         | 层号统一 ID：0温饱/1住房/2安全/3卫生/4社交/5精神/6秩序；HARD={0,1,2}；词典序温饱→住房→安全           | 城市篇 §1、Kimi #6/#7        |
| `CityConfig`      | 调参表外置（Properties 可覆盖任意字段），全部起调值集中于此                                    | 计划者篇 §9                  |
| `CityLedger`      | 城市账本：人口/满意度/性格 bias/建筑条目/库存/解锁集/编年史/物化池/工时账                            | 城市篇 §3                   |
| `BuildingEntry`   | 账本建筑条目：bed_count 挂靠、完整性位集（totalStructure+missingBits）、状态机（在建/运营/停摆/废墟） | Kimi #8、事件系统篇 §3         |
| `BuildingKind`    | 建筑类型 = **账本角色的中世纪实例集**（V1 实现 HOUSE/WELL/PLAZA，ROAD/WALL/GATE 占位）+ 基础量。schema 主键是五角色 SHELTER/SUPPLY/PRODUCE/CIRCULATE/INFRA，时代 = 变体皮肤（2026-11-19 裁决） | 范围红线、解锁DAG §8         |
| `Thermometer`     | 需求温度计：逐层缺口 + 两级判定 + 硬事件词典序                                             | 计划者篇 §5                  |
| `MeansSelection`  | 三笔账：TCO 统一公式、N=⌈gap/capacity⌉ 全候选取最小、竞价权重 w=1/(s+0.1)×(1+bias)         | 计划者篇 §6、Kimi #11/#13/#17 |
| `PlannerCycle`    | 8 步分配循环（时序契约：手段选择→预算分配→施工/生产结算）+ 日终结算（饱食度制/繁殖闸门/移民迁出阀门）                | 计划者篇 §4/§4.5、Kimi #16    |
| `Chronicle`       | 城市编年史                                                                  | 缺口对照 #11                 |
| `SimEvent`        | 事件通道（V1 桩：严冬已接通；火灾/瘟疫/袭击 M3）                                           | 事件系统篇 §1                 |
| `SimHarness`      | 无头测试台入口：run/injectEvent/materializeUpTo/killCitizen                    | 模拟器与投影层 §2               |
| `RegressionTests` | 首批回归用例 5 个（A 稳态/B 成长链/C 住满不报警/D 硬事件暂停软工程/E 收藏死亡永不复用）                   | 计划者篇 §9                  |

## MC 侧对接契约（传感层/表演层只准调这些）

```
输入（MC → 模拟核心，每周期批量消费，回调里禁止业务逻辑——《扩展纪律》§2）：
  SimHarness.injectEvent(SimEvent)      // 灾害/严冬等
  CityLedger.killCitizen(id, cause)     // 死亡事件（收藏栏联动在 MC 侧）
  CityLedger.placeExisting(kind, beds)  // 市政厅注册时的存量登记 / register housing
  BuildingEntry.markMissing/clearMissing// 方块变化事件翻译后的完整性记账（施工上下文自豁免在 MC 侧拦）
  CityConfig.apply(Properties)          // 配置文件调参

输出（模拟核心 → MC，表演层每周期读取）：
  CityLedger.activeProject / buildings  // 物化队列与建筑状态
  CityLedger.hardEvent / satisfaction / chronicle  // GUI 面板与编年史展示
  CityLedger.materializationPool        // 物化/回收市民实体的目标集合
```

## 已知占位（实现前需拍板的口径都标了 TODO）

- 安全层恒无缺口（M3 接光照审计/袭击/守军——《城防与铁傀儡》）
- 秩序层恒无缺口（待市政厅覆盖裁决）
- 精神层 = 广场兼供 50%（`spiritFromPlazaRatio`，待拍板）
- 铁傀儡锻造档未进手段库（M3，《城防与铁傀儡》§2）
- 道路/城墙无建造逻辑（形态学笔记已定规格，等 M2 区块系统）
- 序列化已由 MC 侧 `CityLedgerCodec` + `CityData`（PersistentState）交付（传感 S1）；schema 版本号与迁移函数纪律见笔记《传感层》§5.1

## 扩展纪律（多模型协作必读）

改这里之前先读笔记《扩展纪律》：新功能三问（插哪个扩展点/加哪些账本字段/带哪个测试用例），事件回调禁业务逻辑，MC 侧只当手脚不当脑子。任何改动必须保持 `RegressionTests` 全绿才能进主干。

## M1 开发期修掉的三个设计 bug（2026-11-08，测试台价值的直接证明）

1. **危机日内闪烁**：温饱缺口若按「当前库存 + 全日产量」预测，日内库存累积会把缺口冲成负数 → 危机每天爆发/解除振荡，工地反复暂停复工。修正：预测只计「今日剩余周期产量」，缺口在一天内恒定。
2. **换档时机违规**：换档检查每周期都跑，违反「换档只在验收点」（Kimi #18）。修正：只有付满一个完整切片（4 工时）到达验收点才允许 ε 比较换档。
3. **停摆缺口误触发新建**：水井因预算停摆 → 卫生缺口 → 手段选择要新建第二口水井，而正解是救援复工。修正：存在同类型停摆建筑时候选跳过，走步骤 3b 救援通道。
