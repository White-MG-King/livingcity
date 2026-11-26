# bpconv — 蓝图转换器（素材管线 · 第一批）

把开源建筑蓝图（`.litematic` / 原版结构 `.nbt` / MCEdit `.schematic` / Axiom `.bp`）转换成
《我的世界盒子》模组（modid `livingcity`）的蓝图资产格式（契约见仓库内 `../../sim/BLUEPRINT-FORMAT.md` V1）。本机原有 `03-代码/blueprint-converter/` 目录通过 Windows 目录联接指向这里。

- **纯 Java 17，零第三方依赖**（NBT 解析手写，构建 = 单条 javac）
- 任务单：`02-项目笔记\00-总览\04-规范与决策\蓝图转换器任务单.md`（T1–T5 逐项对应）
- 验收闸门：18 项断言全绿（黄金回归逐字节 diff、确定性、拓扑序、许可证门、V1–V5 校验、Axiom 多段合并、自家 .bp 回环）

## 构建与测试

```sh
sh build.sh        # javac 直编 → out/
sh run_tests.sh    # 构建 + 全部回归断言
```

Windows（PowerShell，工程根目录下）：

```powershell
javac -encoding UTF-8 -d out src/bpconv/*.java test/bpconv/*.java
java -cp out bpconv.TestHarness        # 主闸门：18 项断言
powershell -File build.ps1             # 打 jar（入口 bpconv.Main）
powershell -File test.ps1              # Windows 侧补充闸门（GenSamples 样本 + 分格式黄金）
```

> **单一正统裁决（2026-11-09）**：本目录唯一实现 = 根包 `bpconv.*`。曾并存一版未接线的子包重构（`bpconv.classify/format/model/...`，入口从未调用、内嵌分类表违反单一来源），经用户拍板已删除；其 `build.ps1` / `test.ps1` / `testdata/GenSamples.java` / 分格式黄金文件保留为补充测试——两套测试驱动的都是同一个 `bpconv.Main`。

黄金期望文件在 `testdata/expected/`（已人工评审入库）。重新生成：`java -cp out bpconv.TestHarness --bless`（生成后必须人工评审再提交）。

## 用法

```
java -cp out bpconv.Main <input.litematic|input.nbt|input.schematic|input.bp> \
    -o <outdir> --kind <HOUSE|WELL|PLAZA|ROAD|WALL|GATE> --id <id> \
    --author "来源作者" --license <CC0|CC-BY-4.0> \
    [--entrance x,y,z] [--beds auto|N] [--classes block-classes.txt] [--force]
```

- 退出码：`0` 成功｜`1` 校验/IO 失败｜`2` 许可证门拒绝（白名单只有 CC0 / CC-BY-4.0，来源不明的蓝图不进管线）
- 输出：`<outdir>/<kind小写>/<id>.bp`（纯文本 LF，依赖序 = 放置序）+ `<id>.report.txt`（分类计数/警告/许可回显）
- `--entrance`：HOUSE/WELL/PLAZA 必填，必须落在 footprint 边缘（放置时贴道路，见《城市形态与道路》§2）。**坐标用归一化输出坐标系**（与输出 .bp 方块清单同口径，footprint 原点 0,0,0；2026-11-10 真实素材端到端拍板——源世界坐标是搭建者站位细节，不作为入口口径）
- `--beds auto`（默认）：按床方块 `part=head` 半格数统计
- `.bp` 输入按**文件头自动识别**：前 4 字节 = magic(182827830) → Axiom 容器；否则按自家文本格式 V1 解析（回环转写，CLI 参数优先于 @meta）

## 目录

```
src/bpconv/          转换器本体（Main/NbtReader/四种输入 Reader/分类/排序/校验/输出）
test/bpconv/         测试台（NbtWrite 手写序列化、MakeTestData 样本生成、TestHarness 断言）
testdata/expected/   黄金期望文件（逐字节回归基准，已评审）
block-classes.txt    S/D/C 分类表 —— 与模组运行时共用的单一来源，改表须过评审
```

## 关键约定（契约摘录）

1. **依赖序**：清单顺序 = 放置顺序；y 升序逐层、同层非依附块在前依附块（D 类）在后，同层同类按 (z,x)——确定性全序，运行时按行序回放不重排。
2. **地基平面**：y=0 层必须完整（V1 硬校验）；体素自动归一化到原点。
3. **S/D/C 语义**：S 进完整性账；D 账本忽略；C 保留在清单但运行时放置跳过（玩家私塞物品不动）。
4. **未知方块**：默认归 S + 报告警告（保守进完整性账，人工复核补表）。
5. **方块状态**：`id[prop=value,...]` 后缀（键字典序规范化）——BLUEPRINT-FORMAT §2 增补语法。
6. **`.bp` 双格式识别**：Axiom 容器与自家文本格式同用 `.bp` 扩展名，按文件头 magic 自动分流（2026-11-10 拍板：不改自家扩展名，不动 BLUEPRINT-FORMAT 契约）。

## V1 已知局限（如实声明）

- `.schematic` 通道：数字 id 为 pre-flattening 口径，内置映射表只覆盖常用建筑方块；除床（part/facing）与箱子/陷阱箱（facing）外方块状态一律丢失；床色一律 red_bed；未知数字 id 跳过并进警告。
- `.bp`（Axiom）通道：缩略图/BlockEntities/Entities 一律丢弃（进警告计数）；`structure_void` 视作空位；在高版本 MC（DataVersion≠3465）里搭建的蓝图可能出现 1.20.1 不存在的方块 id——只警告不映射，建议直接在 1.20.1 的 Axiom 里搭建。
- `.litematic` 多区域按区域名字典序合并；区域重叠时后者覆盖前者不做检测。
- 重力方块（沙/砾/铁砧）不做下落模拟，依赖 y 升序放置兜底。
- 生物实体/NBT 数据（箱内物品、告示牌文字）一律丢弃——容器本来就跳过放置，符合"整蓝图回放无副作用"前提。
