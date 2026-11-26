# 《我的世界盒子》模组工程（Fabric 1.20.1）

- 正式名称：**《我的世界盒子》**｜modid `livingcity`｜包名 `com.livingcity`｜版本 1.2.1-dev.2
- 底座：Minecraft 1.20.1 / Fabric Loader 0.16.10 / Fabric API 0.92.6+1.20.1 / Yarn 1.20.1+build.10 / Loom 1.7.4（版本值以 `gradle.properties` 为准）
- 定位：以城市模拟为内核、Minecraft 世界为舞台的氛围增强模组。玩家不是市长，城市自行发展。
- 本仓库包含城市模拟核心、MC 传感与表演层、网页地图和蓝图转换器。

> 旧版 README 是 Fabric 模板教程（Tutorial Mod），已归档至 `../90-历史归档/README-tutorialmod-旧版.md`，不作现状口径。

## V1.2.1 陆战测试

在离线地图点「加载陆战示例」，再用「单步」观察两国在无主地相遇及后续推进行动。点侧栏的野战军可定位，地图选中的无主区块中，虚线轮廓是军队的四区块视野。「最近战报」显示改道、双方投入、战损和城市易主。下载的离线快照可以从在途位置继续模拟。

国家统一填色，较深边线分出各城市。地图用金冠定位国王、银冠定位市长，旗帜定位将军。每座城保有一份常备军编制，停战或战败后回城整补。陆战占领完成时，城市连同领地与账本转交胜方，军队可从已夺取的城市补给，再找下一处战线。

游戏内用 `/city nation list` 查国家编号。房主或管理员可以在实时地图的「战事」页操作，也可用 `/city nation war declare 1 2` 宣战、`/city nation war truce 1 2` 停战撤军。常备军至少两人、粮草足够且敌城边界有可达入口时可以出征。停战期限为 80 个世界周期。

## V1.2 实时世界地图

在本机单人存档里，从创造物品栏的「工具」取得世界地图，也可用指南针与空地图合成。手持世界地图按 **Ctrl＋右键**，默认浏览器会打开当前主世界的实时地图。页面可查看城市、国家、领地、工程和在途拓殖队。附近已加载地形按 4×4 子格绘制。

用网页调节倍率、暂停或单步时，指令交给当前存档的城市时钟。手动打开 Minecraft 暂停菜单时，页面会显示菜单暂停。退出存档后，实时页面断开，进入下一份存档后可用道具重新打开。

游戏外的离线地图可用 `tools/start-offline.cmd 8769` 启动。导入的存档会另外运行一份模拟进度，适合脱离 Minecraft 试跑。

## V1.2 国家与陆路拓殖

玩家独立建立的城市各自立国，它也是该国首都。由拓殖队建立的城市继承母国，使用自己的领地和城市账本。手持城市手册 Shift 右键，打开「国家」页看国名、成员数、母城、拓殖坐标和剩余周期。新世界默认自动派队，旧档升级后可在这一页启用。

在测试世界内找一座附近有开阔陆地的城市。使用管理员命令准备母城，留四张以内空床。若要立即检查派队条件，站在这座城里运行下面四条命令。

```mcfunction
/city debug population 32
/city debug food 4000
/city debug materials 96 64
/city nation dispatch
```

用 `/city nation` 看目标 X、Z，保持城市时钟运行，去目标附近加载区块。验收通过时放下市政厅，新城获得六名居民及随行物资。用 `/city nation list` 比较各国成员数。母城可用 `/city nation cancel` 撤回在途队伍并收回人员和物资。

## V1.1 开发包实测

用测试存档副本启动。旧的单城数据首次保存时会迁移到多城格式，回到旧版时请使用原副本。此开发包支持主世界，沙漏倍率对同一存档内所有城市生效。

1. 在未归属区块放置市政厅并右键建城。用 `/city list` 查看编号，站在各自领地内用 `/city info` 查看当地账本。领地外的查询选择最近的市政厅。
2. 市民优先出现在已加载的本城原版土径上。无路时在市政厅附近寻找安全站位。市政厅及周围一圈保留给入口，拆除后原城保留。原址重建可以恢复入口；迁址时先放新市政厅，再执行 `/city relocate <城市编号> <x> <y> <z>`。
3. 保存世界后用现代浏览器打开 [游戏外调试地图](tools/city-map.html)，选择该世界的 `data/livingcity_city.dat`。色块标记领地，虚线框标记工程用地。放大地图可查看建筑图标和名称，侧栏按城市显示仓库与施工状态。
4. 要比较两次扩张，将第一次保存的 DAT 复制出来。第二次保存后，在地图里分别打开当前档和旧快照。V1.1 dev.1 的多城档可直接比较疆界。打开旧单城档时可以查看建筑和市政厅。

5. 工程付足工时与材料后进入现场施工队列。到工地区块附近观察四床住宅、水井和广场逐步落地。采石场使用空蓝图，耗 12 木材和 8 工时，完工后随每周期实际拨给的工时产石。

6. 用创造物品栏里的城市手册（或 /give @s livingcity:city_handbook），手持时 Shift 右键。「世界规则」里默认开启定时扩张，每座有居民的城市每 20 个城市周期最多认领一个相邻区块，受领地上限约束。关闭定时扩张后，按工程用地需要认领。表演设置控制全服市民实体上限和施工批量。调试页可以查看最近城市的周期和工地进度，也可以暂停、单步和调速。
7. 双击 tools/start-offline.cmd，在浏览器打开 http://127.0.0.1:8766/。在「从存档继续」里选择同一世界根目录的 level.dat 和 data/livingcity_city.dat，然后点「导入并暂停」。也可以用种子创建新模拟。测试会话在内存运行，地形预览使用原版 1.20.1 主世界采样。导入时读取存档里的定时扩张设置，旧档和新种子默认开启。调试地图也支持直接读 DAT 快照。

8. 手持城市查看器右键。点方块查看该区块归属城市，在空气中右键查看脚下所属城市。网页可右键城市领土或点击城市卡片上的「查看需求」。
9. 要拆除有快照的建筑，站在所属城市里，用 `/city info` 查建筑编号，再执行 `/city demolish <编号>`。快照从 dev.7 开始记录，只恢复蓝图覆盖前、整地后的方块。拆除共用施工预算，暂停城市时钟时也会执行。
10. 在「城市手册 → 世界规则」开启「村民自动入籍」。首次开启和原版村民求职都会请求检查。已加载且站在任一城市领地内的未交易成年村民会分批入籍，每轮后共用 100 服务器刻冷却。用 `/city info` 查看人口，用 `/city chronicle` 查看入籍批次。
11. 查看器和地图将可用床位、待落地床位分别列出。现场完工后床位和公共设施服务才生效。已有的已付工单在载入时按建筑锚点区分，保留项目进度。查看器和手册会显示现场施工状态，服务器每刻达到 45 毫秒时标记暂缓。

## 港口贸易离线试验

打开离线调试地图，点击「加载三城贸易示例」。用「单步」或「1 周期/秒」观察缺石材的城市向两家港口比价、冻结货款、装船和交付。也可导入自己的存档，选中城内岸边的陆地子格并点击「在所选子格建港」。缩放可看清 4×4 子格，点击子格可查看水陆、群系和方块坐标。用「下载离线快照」和「恢复离线快照」保留航程与现金。

当前贸易只在离线模拟运行，浏览器的岸线是用种子采样的地表估算。港口使用空蓝图，船由地图标记表现。Minecraft 中的建筑和船实体接线留待后续试验。

## 一、先读哪里（上下文入口）

设计与契约的唯一真源在笔记库，不在本目录：

1. `../02-项目笔记/00-总览/从这里开始.md` → `00-V1系列设计稿/先读我.md`（V1 是什么，四篇按三层架构）
2. `01-模组核心设计稿/04-版本管理/V1/V1设计稿总纲.md`（v0.2 范围冻结 + 数值冻结 + 验收闸门 + **§4.1 移交包分层 checklist：该读哪几篇、不该读哪几篇**）
3. `04-规范与决策/扩展纪律.md`（回调投递事件、决策在核心层、依赖纪律、命名纪律）
4. `开发工具环境.md`（本机工具链、编译纪律、IDEA 启动事故根因、素材管线）

## 二、城市模拟核心

城市模拟核心在 `sim/`，使用纯 Java 17，通过 `build.gradle` 直接编入本工程。克隆仓库后即可构建完整模组和离线地图。

```gradle
sourceSets { main { java {
    // 无头模拟核心（纯 Java 17，零 MC 依赖）直接参与编译，保持单一源码
    srcDir file('sim/src')
} } }
```

核心可脱离 Minecraft 独立编译与跑回归。MC 侧提供传感输入并放置蓝图。材料生产和扣费在核心账本结算。

蓝图转换器位于 `tools/blueprint-converter/`，可独立将 Axiom/Litematica 等素材转换为 `.bp`。本机原有的 `../03-代码/livingcity-sim/` 和 `../03-代码/blueprint-converter/` 已改为指向仓库内对应目录的 Windows 目录联接，旧路径与新路径编辑同一份源码。

## 三、目录结构（实际）

```
03-模组工程/
├── build.gradle / settings.gradle / gradle.properties / gradlew(.bat)
├── sim/src/citysim/              # 无头城市模拟核心与回归入口
├── tools/city-map.html           # 游戏实时地图与离线模拟共用页面
├── tools/blueprint-converter/    # 独立蓝图转换器与素材格式测试
├── start-client.cmd              # 进游戏验收首选启动器（ASCII、%~dp0 中文路径安全）
├── src/main/java/com/livingcity/
│   ├── LivingCityMod.java        # 主入口
│   ├── block/                    # 市政厅 TownHallBlock、电梯 ElevatorBlock、注册
│   ├── entity/                   # 自定义市民 CitizenEntity（不继承原版 VillagerEntity）
│   ├── sensor/                   # CitySensorBus（回调只 offer）、CitySimTicker（周期边界消费）
│   ├── data/                     # CityData（PersistentState，MC 侧空间数据）、CityLedgerCodec
│   ├── stage/                    # MaterializationManager（物化 LOD 96/128）
│   ├── ai/                       # HomeManager（回家/电梯/兜底）
│   ├── command/                  # /city 指令族
│   └── datagen/
├── src/main/resources/           # fabric.mod.json、assets/livingcity（blockstates/models/lang）
├── src/client/java/com/livingcity/  # 客户端源集（渲染等，splitEnvironmentSourceSets）
├── src/main/generated/           # datagen 输出，同时作为资源目录
├── allay-village/                # 早期悦灵村庄子工程，已归档，不参与主构建
├── _context/                     # 早期沙箱交接上下文（已过期，见文件内标注）
└── run/                          # 开发客户端运行目录与存档
```

## 四、常用命令

```bat
rem 本机环境示例，其他电脑设置为自己的 JDK 17 和 Gradle 缓存路径
set JAVA_HOME=C:\Program Files\Zulu\zulu-17
set GRADLE_USER_HOME=H:\.gradle-home

gradlew.bat build --console=plain --no-daemon   rem 必须 --no-daemon（本机并存 Gradle 9.x）
gradlew.bat simTest --console=plain --no-daemon rem 核心回归，退出码 0 = 全绿
gradlew.bat runClient --console=plain           rem 或直接双击 start-client.cmd
gradlew.bat genSources                          rem 反编译 MC 源码供跳转
gradlew.bat runDatagen                          rem 输出到 src/main/generated
```

- **`--no-daemon` 是硬纪律**：daemon 模式下 Gradle 9.x tooling provider 混入 8.8 daemon，配置阶段炸 `NoClassDefFoundError`。
- **进游戏验收首选 `start-client.cmd`**：它先 `gradlew --stop` 释放 IDEA 独占的 journal 锁，再 runClient。
- **IDEA 运行配置 `shortenClasspath` 必须是 `NONE`**：中文路径 × ARGS_FILE × GBK 会让 fabric-api 与本模组整体掉线；MANIFEST 方案已作废（撞 DLI 拆分类路径）。根因与证据见笔记《开发工具环境》§4。
- 回归闸门保留 G0 的 33 项基线并随功能扩展。V1.2.1-dev.2 已通过 81 项核心回归及 NBT 存档测试。

## 五、已归档 / 已失效的东西

- `../mc-mod-dev`（转移包旧工作树快照，8 java，停在 2026-10-27）与 `../04-构建产物`（0.1.0 旧 jar）已移至 `../90-历史归档/`。**现行工程只有本目录**。
- `start-runclient-dump.cmd` / `-nohud.cmd` / `-sodium.cmd` / `-test.cmd` 是 allay-village 期脚本，`cd /d C:\mc-mod-dev\allay-village` 指向的路径**已不存在**，属死脚本，勿用。
- 项目外 `H:\mc-mod-dev` 只剩 `mc设计笔记` 空壳（0 java、无 git）；笔记与 `_context/` 里以它为「源工程」的记载均已过期。
- 笔记库侧归档在 `../02-项目笔记/00-总览/90-历史归档/`，与项目根的代码归档分开。
