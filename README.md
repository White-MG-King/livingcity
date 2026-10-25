# Tutorial Mod — Fabric 1.20.1 开发环境

## 一、环境清单（本机已配置完成）

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | Zulu OpenJDK 17.0.16 | `C:\Program Files\Zulu\zulu-17` |
| JAVA_HOME | 用户变量，已指向上面 | `C:\Program Files\Zulu\zulu-17` |
| PATH | Zulu\bin 已提到系统 PATH 最前（原 Java 8 条目仍在，但不再优先） | — |
| IDE | IntelliJ IDEA Community 2025.2.6.2 | `E:\IntelliJ IDEA Community Edition 2025.2\bin\idea64.exe` |
| Gradle | 8.8（wrapper 自动管理） | `~/.gradle/wrapper` |
| Minecraft | 1.20.1 | loom 缓存 |
| Yarn 映射 | 1.20.1+build.10 | — |
| Fabric Loader | 0.16.10 | — |
| Fabric API | 0.92.6+1.20.1 | — |
| Fabric Loom | 1.7.4 | — |

> 改环境变量后必须**重启终端 / 重启 IDEA** 才生效。

## 一点五、启动 IDEA（ZIP 版，无开始菜单项）

三种方式任选：
1. `Win + R` → 输入 `idea64` → 回车（PATH 已包含其 bin 目录）；
2. 双击 `E:\IntelliJ IDEA Community Edition 2025.2\bin\idea64.exe`；
3. 首次启动后可右键任务栏图标 → 固定到任务栏。

若启动报 `AccessDeniedException ... \.port`：删掉 `C:\Users\<你>\AppData\Local\JetBrains\IdeaIC2025.2\.port` 再启动（本机已清理，正常不会再出现）。

## 二、目录结构

```
H:\mc-mod-dev\
├── build.gradle              # Java 17、loom、split sources、datagen 配置
├── settings.gradle           # 插件仓库（Fabric maven）
├── gradle.properties         # MC/yarn/loader/api 版本 + 内存
├── gradlew / gradlew.bat     # Gradle wrapper（8.8）
├── build\datagen\            # datagen 运行目录（必须存在，否则 datagen 启动报错）
└── src\
    ├── main\java\com\example\tutorialmod\
    │   ├── TutorialMod.java              # 主入口（ModInitializer）
    │   └── datagen\TutorialModDataGenerator.java  # 数据生成入口
    ├── main\resources\
    │   ├── fabric.mod.json               # 模组元数据 / entrypoint
    │   └── assets\tutorialmod\
    │       ├── icon.png                  # 128x128 图标
    │       └── lang\{en_us,zh_cn}.json   # 语言文件
    └── client\java\com\example\tutorialmod\
        └── TutorialModClient.java        # 客户端入口（ClientModInitializer）
```

## 三、用 IDEA 打开

1. 启动 IDEA → `Open` → 选 `H:\mc-mod-dev`（不要选到 `src`）。
2. 等右下角 Gradle 同步完成（首次会下载依赖，视网络 3~15 分钟）。
3. `File → Project Structure → Project`：SDK 选 **17**，Language level 选 **17**。
   `Project Structure → Modules → tutorialmod.main / tutorialmod.client`：Module SDK 也选 **17**。
4. 中文界面：`Settings → Appearance & Behavior → System Settings → Language and Region` 选简体中文（2024.3+ 内置语言包）。

## 四、常用 Gradle 任务（IDEA 右侧 Gradle 面板）

| 任务 | 作用 |
|---|---|
| `Tasks → fabric → genSources` | 下载/反编译 Minecraft 源码，之后 Ctrl+点击类名可跳源码 |
| `Tasks → fabric → runClient` | 启动开发客户端（验证环境） |
| `Tasks → fabric → runServer` | 启动开发服务端 |
| `Tasks → fabric → runDatagen` | 跑数据生成，输出到 `src/main/generated` |
| `Tasks → build → build` | 打包 jar，产物在 `build/libs/` |

命令行等价：
```bat
cd /d H:\mc-mod-dev
gradlew genSources
gradlew runClient
gradlew build
```

## 五、验证标准（本次已跑通的部分已标注 ✅）

| 检查项 | 结果 |
|---|---|
| `gradlew build` 编译打包 | ✅ BUILD SUCCESSFUL，产物 `build/libs/tutorialmod-1.0.0.jar` |
| `gradlew genSources` 反编译源码 | ✅ 3568 个 common 类 + 1218 个 client 类已反编译，Ctrl+点击可跳源码 |
| `gradlew runDatagen` 模组加载 | ✅ Loader 0.16.10 + Fabric API 0.92.6 全部加载，`tutorialmod 1.0.0` 出现在模组列表，主入口日志已打印 |
| `runClient` 启动游戏并进世界 | ⬜ 需在 IDEA 里点 `runClient` 手动确认（会弹游戏窗口） |

`runClient` 的验证要点：

1. `runClient` 后 Minecraft 窗口正常打开，无崩溃；
2. 能点“单人游戏”进入世界；
3. 日志中出现 `[TutorialMod] main entrypoint initialized (common side)` 与 `[TutorialMod] client entrypoint initialized`（日志刻意用英文，避免 Windows 控制台 GBK 乱码）；
4. 控制台颜色：红=error，黄=warn，绿/白=正常信息。

## 六、常见问题

- **构建报奇怪的错**：删掉 `C:\Users\<你>\.gradle`（整个缓存目录）与项目内 `.gradle`、`build`，再重新 sync。缓存重下会慢，但能解决大部分玄学问题。
- **提示 Data Generation 工作目录不存在**：手动确保 `H:\mc-mod-dev\build\datagen` 存在（本仓库已建好）。
- **JDK 版本不对**：`Project Structure` 里 SDK 与每个 Module 都要是 17，缺一不可。
- **依赖下载卡住**：可在 `build.gradle` 的 `repositories {}` 里加阿里云镜像：
  ```groovy
  maven { url 'https://maven.aliyun.com/repository/public' }
  ```
  注意：Minecraft 本体、yarn、loom 必须走 `maven.fabricmc.net`，镜像不覆盖。
- **想换 modid / 包名**：改 `fabric.mod.json` 的 `id`、`assets/<modid>/` 目录名、Java 包路径、`build.gradle` 里 `mods { "xxx" }` 与 `archivesBaseName`、`gradle.properties` 的 `maven_group`。

## 七、下一步（写代码）

1. 在 `TutorialMod.onInitialize()` 里注册物品/方块/物品栏；
2. 资源放 `src/main/resources/assets/tutorialmod/`，数据（配方、战利品表）建议用 datagen 生成到 `src/main/generated`；
3. 客户端专属逻辑（渲染、按键、HUD）写在 `src/client/java/...`，服务端不加载。
