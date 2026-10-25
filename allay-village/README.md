# 悦灵村庄 Allay Village（Minecraft 1.20.1 · Fabric）

让悦灵（Allay）像村民一样拥有自己的村庄：职业、工作站点、交易、日程与声望。

本工程是 `H:\mc-mod-dev` 根目录（tutorialmod 开发环境）旁的**独立子工程**，互不影响。
JDK 17 / IDEA / 版本组合沿用根工程 README 里已验证的环境（loader 0.16.10 · loom 1.7.4 · Fabric API 0.92.6 · yarn 1.20.1+build.10）。

## 首次准备：补 Gradle Wrapper

本工程不含二进制 wrapper jar，从根工程复制一次即可（PowerShell）：

```powershell
Copy-Item H:\mc-mod-dev\gradlew, H:\mc-mod-dev\gradlew.bat H:\mc-mod-dev\allay-village\
Copy-Item H:\mc-mod-dev\gradle H:\mc-mod-dev\allay-village\gradle -Recurse
```

## 常用命令

```powershell
cd H:\mc-mod-dev\allay-village
.\gradlew build        # 产物 build\libs\allay-village-0.1.0.jar
.\gradlew runClient    # 开发客户端验证（日志出现 [悦灵村庄] 即 M0 通过）
.\gradlew datagen      # 数据生成（M1 起启用）
```

或用 IDEA `Open` 选 `H:\mc-mod-dev\allay-village`（SDK 17，同根工程说明）。

## 工程结构

```
src/main/java/com/lumemind/allayvillage/
├── AllayVillageMod.java                    # 主入口（注册钩子按里程碑补齐）
├── entity/AllayVillageData.java            # 悦灵附加数据：职业/村庄id/交易经验（已可持久化）
├── mixin/AllayEntityMixin.java             # 注入 AllayEntity：NBT 读写 + initGoals 扩展位
└── datagen/AllayVillageDataGenerator.java  # 数据生成入口
src/client/java/com/lumemind/allayvillage/
└── client/AllayVillageClient.java          # 客户端入口（split source sets）
src/main/resources/
├── fabric.mod.json  allayvillage.mixins.json
└── assets/allayvillage/lang/{zh_cn,en_us}.json
```

## 里程碑（1.20.1 Fabric · Yarn 映射）

| 阶段 | 内容 | 关键技术点 |
|---|---|---|
| M0 ✅ | 工程骨架、悦灵数据可持久化 | `AllayEntity#writeCustomDataToNbt/readCustomDataFromNbt` Mixin |
| M1 | 站点方块（共鸣台/悦灵巢穴）+ 职业绑定 | `Block`/`BlockEntityType` 注册；`PointOfInterestTypes` 注册 POI；datagen 出战利品表/配方 |
| M2 | AI 日程：工作/送货/社交/休息 | `Schedule` + `Goal`（`MoveToTargetPosGoal` 派生）；`initGoals` Mixin 追加 |
| M3 | 交易界面与交易表 | 自建 `ScreenHandler` + `ExtendedScreenHandlerFactory`，不硬啃原版 Merchant GUI |
| M4 | 村庄：巢穴核心、边界、存档 | 每维度 `PersistentState`；`ServerTickEvents.END_WORLD_TICK` 低频扫描（每 100 tick，分帧） |
| M5 | 村庄内繁殖 | Mixin 原版唱片机+紫水晶分裂逻辑，成功后登记人口；巢穴数决定上限 |
| M6 | 声望影响价格 | `AllayVillageData` 扩展 per-player 声望 |
| M7 | 世界生成悦灵村落 | `Structure` + jigsaw + nbt 模板；`fabric-biome-api-v1` 注入群系 |
| M8 | 打磨发布 | lang 补全、icon.png、配置项、兼容测试 |

## 约定

- 全工程使用 Yarn 映射（`AllayEntity`、`PointOfInterestTypes`、`PersistentState`）。
- 数据附加走 Mixin + 原版 NBT，零额外依赖；数据变复杂后再评估 Cardinal Components。
- 包名 `com.lumemind` / modid `allayvillage`，如需调整全局替换即可。
