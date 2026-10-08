# Lyrico Desktop 移植方案（Android → Windows）

> 上游：`CN-Grace/Lyrico`（Android，Kotlin + Jetpack Compose + Miuix）。
> 本仓库是硬分叉：**只做 Windows 桌面端**，不再维护 Android 端（上游更新需人工移植）。

## 1. 现状审计

| 项 | 数值 |
| --- | --- |
| Kotlin 文件 | 350（`lyrico-app`）+ `lyrico-audiotag` JNI 层 |
| 需改造的平台耦合文件 | **154**（`android.*` / `androidx.*`，排除 `androidx.compose.*` 等 CMP 通用项） |
| 分布 | data 46 · viewmodel 30 · screens 24 · utils 17 · ui 15 · worker 7 · plugin 6 · domain 3 · 其他 3 · platform 2 · di 1 |

耦合点排行（出现文件数）：`Context` 39 · `android.util.Log` 33 · `Uri` 31 · `ViewModel/viewModelScope` 30 · `toUri` 27 · `collectAsStateWithLifecycle` 19 · `Intent` 16 · `Room 注解` ~35 · `ActivityResultContracts` 11 · `Toast` 12。

**结论：没有真正“无法移植”的东西**，全是可替换的平台层。真正需要架构级决策的只有三处：

1. **`Uri` + SAF（存储访问框架）贯穿全局** → 桌面端改为 Windows 绝对路径模型，且 Windows 没有权限模型，`IntentSender` / `RecoverableSecurityException` / `MediaStore` 整块删除。
2. **ReplayGain 的 PCM 解码依赖 `MediaExtractor`+`MediaCodec`** → 桌面 JVM 无等价物（`javax.sound` 解不了 FLAC/Opus/M4A/APE）。**待定分叉**，见 §5。
3. **WorkManager 前台服务 / 通知** → 桌面端改为进程内协程任务队列 + 窗口内进度 UI。

## 2. 技术选型（已定）

| 层 | 选型 |
| --- | --- |
| 语言/构建 | Kotlin 2.4.20，Gradle 9.6.1，**去掉 AGP**，`kotlin("jvm")` + `org.jetbrains.compose` |
| UI | **Compose Multiplatform Desktop** + Miuix KMP 的 `-desktop` 产物（已有 `miuix-ui-desktop` / `miuix-preference-desktop` / `miuix-icons-desktop` / `miuix-blur-desktop`），UI 层几乎整层可留 |
| 数据库 | Room **JVM** + `androidx.sqlite:sqlite-bundled`（`BundledSQLiteDriver`），Dao/Entity/迁移链原样保留 |
| 偏好存储 | `androidx.datastore:datastore-preferences-core`（KMP，JVM 可用） |
| 图片加载 | Coil 3（自带 JVM/Desktop 目标） |
| DI | Koin（`koin-core` 替换 `koin-android`；`viewModel {}` 换桌面工厂） |
| 原生库 | **MSVC 14.44（VS2022 BuildTools @ `D:\dev\vstools\BuildTools`）+ Windows SDK 10.0.26100 + CMake 4.4.2 + Ninja**，产物 `taglib.dll` / `ebur128.dll` / `quickjs-ng.dll`，静态链接 CRT（`/MT`） |
| 归档/发布 | `jpackage` → MSI + portable zip，内置裁剪 JRE |

工具链已核对：JDK 21.0.2（`D:\dev\mise-data\installs\java\21.0.2`）、`cl.exe` 14.44.35207、`vcvars64.bat`、`MSBuild.exe`、ninja、cmake 4.4.2 均就位。

## 3. 模块结构（目标）

```
Lyrico/
├── lyrico-audiotag/   JVM 库 + src/main/cpp（TagLib + JNI）→ taglib.dll
└── lyrico-app/        Compose Desktop 应用
    ├── src/main/kotlin/（沿用现有 com.lonx.lyrico.* 包）
    ├── src/main/cpp/（ebur128 + quickjs-ng + JNI）→ ebur128.dll, quickjs-ng.dll
    └── src/main/composeResources/（CMP 资源：values*/ 与现有 5 语言 strings.xml 对齐）
```

**P2 过渡期源码布局**（`lyrico-app` 已并入构建，但 Android 源码树尚未消费）：

| 目录 | 状态 |
| --- | --- |
| `src/main/kotlin` | ✅ 参与编译（桌面源码；P2 只有应用壳 `Main.kt`） |
| `src/main/java` | ⛔ 暂不参与编译（原 Android 350 个文件，P3/P4 用 `git mv` 逐个迁到 `src/main/kotlin`） |
| `src/test/kotlin` | ✅ 参与编译（暂空） |
| `src/test/java`、`src/androidTest`、`src/main/res`、`AndroidManifest.xml` | ⛔ 暂不消费，P4 末随 Android 专属文件一起删除 |

`lyrico-app/build.gradle.kts` 里对应两行开关：`kotlin { sourceSets { main { srcDir(...) } } }`（**只能用 `srcDir` 追加**）+ `sourceSets { main { java.setSrcDirs(emptyList()) } }`。**每迁完一个子树不要改回整目录**，而是继续 `git mv` 到 `src/main/kotlin` —— 这样构建永远是绿的。

> ⚠️ **不要用 `kotlin.setSrcDirs(listOf(...))` 覆盖源集**：Compose 资源插件把生成的可访问器源码目录（`build/generated/compose/resourceGenerator/...`）**注册进同一个源集**，覆盖列表会把它一起丢掉，报错是每个 `Res.string.*` 处的 `Unresolved reference: resources`（不是文件找不到，容易误判成资源没生成）。要收紧源集就用 `srcDir()` 追加、`java.setSrcDirs` 清空。

### P3 数据层（已完成部分，2026-10-09）

| 项 | 状态 | 证据 |
| --- | --- | --- |
| `LyricoDatabase` + 11 个实体 + 7 个 DAO 迁到 `src/main/kotlin`，Room JVM + `BundledSQLiteDriver` | ✅ | `./gradlew :lyrico-app:compileKotlin` 通过（KSP 校验全部 SQL） |
| schema 与 Android v21 完全一致（数据库文件可直接拷过来） | ✅ | `SchemaFidelityTest`：11 张表的字段/索引/外键/`createSql` 全等，**`identityHash` 也相同**（`77afa67bce8d8a3244f76314923be261`） |
| 歌词 FTS4 索引（Room 无法声明虚拟表） | ✅ | `LyricoDatabaseTest`：`unicode61` 分词 + CJK 逐字 token 的 `MATCH '"中 文"'` 命中，拉丁前缀 `Hello*` 命中，`isIgnored` 过滤生效 |
| `@RawQuery` → `RoomRawQuery`（动态排序/搜索的唯一通路） | ✅ | 上面两项测试经真实 raw query（含 42 列 `LocalLyricSearchRow` 映射与占位符绑定） |
| schema 目录分离 | ✅ | 桌面库历史在 `lyrico-app/schemas/`（现为 v1），继承的 Android 历史 `git mv` 到 `lyrico-app/schemas-android/`（只读参考） |
| 路径模型 | 🚧 约定已定：`songs.uri` 列存 **Windows 绝对路径**（唯一/权威），`filePath` 同值镜像；实体上的 `SongEntity.getUri: Uri` 改为 `SongEntity.path: Path`（无 `Uri` 类型）。31 处 `Uri`/27 处 `toUri` 的调用点随 P3/P4 逐个改造 |
| 音频标签读写（TagLib JNI，路径式 ABI） | ✅ | `AudioTagRepositoryTest`（7 项）在真实 `bladeenc.mp3`(ID3v2) / `silence-44-s.flac`(Vorbis comments) 上走完整链路（mutation → resolver → `TagMapBuilder` → JNI → 文件 → 读回）：覆盖写入后重读一致、`patch` 不动未提及字段、封面字节往返、缺失文件与遗留 `content://` 行降级（lenient 不抛 / strict 抛）、写失败落 `AppLogType.METADATA` |
| 文件层去 SAF | ✅ | `AudioFileAccess` 重写为 `java.nio.file`（显示名/存在性/删除/重命名/读写字节，原子替换写）；`ParcelFileDescriptor`、`IntentSender` 授权流、`MediaStore` 分支全删 |
| `Uri` 退出标签链 | ✅ | `PictureSource.UriSource(Uri)` → `PictureSource.FileSource(Path)`（`AudioTagMutation`/`AudioTagMutationFactory`/`ImageBytesFetcher`）；`AudioTagRepositoryImpl` 内 `content://` 这类非路径值一律降级 |
| `LyricFtsIndexer` / 歌词解码链（`LyricDecoder` → `LyricsDocumentPipeline` → LRC/TTML 格式，约 3000 行） | ⛔ 未迁（属歌词子系统），暂由测试内的 `toFtsIndexText()` 镜像其分词规则 |

## 4. 阶段与门禁

| 阶段 | 内容 | 完成门禁（必须实测） |
| --- | --- | --- |
| **P0** | 审计 + 本方案 | ✅ 本文 |
| **P1** | ✅ 三个原生库编 Windows x64 DLL，改掉 `android`/`log` 链接与 GCC 专用旗标，写最小 JVM JNI 冒烟程序 | ✅ `System.load` 成功；7 种格式（FLAC/MP3/M4A/OGG/OPUS/APE/WAV）实测**读出并写回**标签、封面，含 CJK 路径；91 项检查 0 失败 |
| **P2** | ✅ Gradle 骨架（去 AGP、JVM + CMP），Miuix desktop，`Main.kt` | ✅ Windows 上窗口弹出（`Lyrico 1.6.0 (d14b032)`，1166×773），Miuix 主题正常渲染；应用进程内 `taglib.dll` 实际加载成功（见 P2 复现命令） |
| **P3** | 数据层：路径模型 / Room JVM / DataStore / 扫描 / 标签读写 | 扫描一个真实音乐目录 → 入库 → 列表出歌 → 改标签落盘（**进展**：库+DAO+FTS 已通、标签读写已在真实音频上落盘读回；待扫扫描器/Settings） |
| **P4** | UI 层 + 三栏桌面布局 + 导航 + 文件对话框/右键菜单/拖放/快捷键 | 全流程鼠标可操作，覆盖主要页面 |
| **P5** | 批量任务（进程内队列替代 WorkManager）、ReplayGain、导出、插件 quickjs 运行时、更新检查、5 语言资源迁移 | 每项功能端到端跑通 |
| **P6** | jpackage 打包 MSI/portable、图标、文件关联、许可声明 | 干净 Windows 机器上安装后可用 |

## 5. 待定分叉（到 P5 前必须由用户裁决）

**ReplayGain 的 PCM 解码方案**（Android 端 `ReplayGainScanner.kt` 用 `MediaExtractor`+`MediaCodec`，桌面无等价物）：

- **A. 捆绑 ffmpeg**（sidecar `ffmpeg.exe` 经 stdin/stdout 管道喂 f32le，或 JavaCPP `ffmpeg-platform` 直接调 libav*）：覆盖全部格式（含 APE/AIFF/DSF/Opus），需要额外约 50–150 MB 二进制与 LGPL/GPL 许可声明；与现有 `ebur128` JNI 对接最直接。
- **B. 自带解码源码**（dr_libs + stb_vorbis + libopus 等）：体积小、无外部许可包袱，但**覆盖不全**（APE/AAC/DSF 缺），需要为缺失格式降级。
- **C. 解析声道响度仅走 TagLib 已读标签 + 跳过无解码格式**：最省事，但功能不对齐，与「全功能对齐」目标冲突。

## 6. 主要风险

1. ~~**Room KMP 在纯 `kotlin("jvm")` 模块下的 KSP 配置**~~ ✅ 已解决：`ksp(libs.androidx.room.compiler)` + 官方 `androidx.room` Gradle 插件 + `room { schemaDirectory(...) }`，`kotlin("jvm")` 模块下工作正常（11 实体/7 DAO 全部生成）。另注：`kotlinx.serialization` 的 `@Serializable` 还需要 `alias(libs.plugins.kotlin.serialization)`（只有运行库依赖不够，报错是 `Unresolved reference 'serializer'`）。
2. ~~**原生库 CMake 适配**~~ ✅ 已解决：TagLib 3.x 在 CMake 4.4.2 下可配置；`-flto`/`--pack-dyn-relocs`/`android`+`log` 链接已替换；ebur128 缺 `<sys/queue.h>`（自带 `compat/sys/queue.h`）与 `M_PI`（加 `_USE_MATH_DEFINES`）两处已补。复用 `/MT` 静态 CRT，DLL 无第三方运行时依赖。
3. **compose-destinations 的 KSP 代码生成**是否支持 CMP Desktop（不支持则退化为手写 `when` 导航，screens 共 24 个，可控）。
4. **Miuix desktop 与 Android 版的行为差异**（`BackHandler`、`TopAppBar`、滚动条、窗口拖拽区）——逐屏过。
5. **`androidx.lifecycle.ViewModel` 30 处在桌面端的生命周期**——CMP 自带 `lifecycle-viewmodel-compose`，但要确认 Koin 的 `viewModel {}` 在桌面可用。
6. **非 ASCII 工程路径 + Gradle 参数文件编码**（已踩中并修复，勿回退）：工程位于 `H:\VibeCoding\03-应用\Lyrico-Desktop`。Gradle 用**守护进程默认字符集**（`ArgWriter` → `new PrintWriter(File)`）把 worker JVM 的 classpath 写进临时 `@argfile`，而 `java.exe` 用 **Windows ANSI 代码页（936/GBK）** 解析该文件；`gradle.properties` 里原本的 `-Dfile.encoding=UTF-8` 会把含中文的工程路径写成乱码 → worker 报 `ClassNotFoundException`（每个测试类都找不到，甚至 `GradleWorkerMain`）。修复：`org.gradle.jvmargs` 用 `-Dfile.encoding=GBK`（= 本机 ANSI 代码页）。**换机器时该值必须等于该机 ANSI 代码页**；`run`/`JavaExec` 任务同样走这条路径，所以 P2 之后不要再改回 UTF-8。

## 7. 约定

- 旧的 Android 代码以 commit 形式留在 git 历史（`origin/master`）中，不再保留在本分支源码树内。
- 平台无关代码优先「原地改造」，不做复制；只有确定要删的 Android 专属文件才删除。
- 每阶段结束必须留下可复现的验证命令（见 §4 门禁）。
- **P1 复现命令**：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-native.ps1`（产出 `build/native/windows-x64/{taglib,ebur128,quickjs-ng}.dll`）→ `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/native-smoke.ps1`（纯 Java 冒烟，无需 Gradle，失败返回非 0）。冒烟源码在 `tools/native-smoke/`，其中的 `com/lonx/audiotag/{TagLib,model/*}.java` 是**临时替身**，等 P2 的 Kotlin JVM 库建好后应改为直接依赖真实 Kotlin 类。
- **P1 复现命令（Gradle 侧，真 Kotlin 绑定）**：`./gradlew :lyrico-audiotag:test`（13 项检查 0 失败，覆盖 7 种格式的标签/封面读写、CJK 路径端到端）。跑之前确保 `build/native/windows-x64/*.dll` 已由 `scripts/build-native.ps1` 产出。
- **P2 复现命令**：`./gradlew :lyrico-app:run` 弹出窗口（标题 `Lyrico <版本> (<commit>)`）；取证用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-window.ps1 -TitleLike "Lyrico 1.6.0" -OutputPath docs/port-evidence/p2-miuix-window.png`（截的是窗口自身矩形；**别用模糊标题匹配**——终端窗口标题里也含 “Lyrico-Desktop”）。截图非空白的客观校验在 `docs/port-evidence/p2-miuix-window.analysis.txt`（561 色；白底 `255,255,255` + 卡片底 `247,247,247`；2906 个文字暗像素分布在 96 行）。
- **P2 版本锁定**：Kotlin 2.4.20 + Compose Multiplatform **1.12.0** + Miuix **0.9.4**。不是随手写的：Miuix `-desktop` 产物的 pom 显示它是用 CMP 1.12.0 / Kotlin 2.4.20 编的，Kotlin 版本又要跟仓库原有 2.4.20 对齐，三者必须同进同退。
- **P3 复现命令（数据层）**：`./gradlew :lyrico-app:test`（现 18 项：库读写/FTS/raw query/重开持久化/schema 保真 11 项 + 标签读写 7 项，另有 P2 的壳测试 3 项）。测试任务注入的系统属性：`lyrico.schema.dir` / `lyrico.android.schema.dir`（schema 比对）、`lyrico.audiotag.fixtures.dir`（音频夹具，指向 `lyrico-audiotag/src/main/cpp/taglib/tests/data`），换机器无需改测试代码。
- **数据库 schema 目录约定**：桌面分支的 schema 历史写在 `lyrico-app/schemas/`（**从 v1 起**），继承来的 Android 历史 `1..21` 在 `lyrico-app/schemas-android/`，只读参考、不要再写入 —— 否则将来桌面端的 v2 会覆盖 Android 的 `2.json`，两边历史互相污染。
- **路径模型约定**：`songs.uri` 列 = Windows 绝对路径（唯一键/权威），`filePath` 同值镜像；实体扩展属性用 `SongEntity.path: Path`，**不引入 `Uri` 类型**，`content://`/`MediaStore`/SAF 相关整块删除。Android 库文件拷到 Windows 后无需迁移即可打开（schema 相同），但 `uri` 列里若存的是 `content://`，需要一次导入期重写（P3 扫描/导入时处理）。
- **`kotlin.test` + `runBlocking` 的陷阱**：本仓库的 `kotlin.test` 落回 JUnit4，测试方法必须返回 void，而 `= runBlocking { ... }` 会把最后一个表达式的值当返回值（如 `assertFailsWith` 返回 `Throwable`），报错是 `Method ... should be void`。写法统一用 `= runBlocking<Unit> { ... }`。
- **音频测试夹具不能随便挑**：`lyrico-audiotag/src/main/cpp/taglib/tests/data` 里有很多退化/畸形样本（如 `w000.mp3` 512 字节、`mpeg-sync-flac.flac`），它们能读但写不了标签、也没有时长 —— 在上面写标签会“看似成功但读不回”。能用的真实样本：`bladeenc.mp3`、`silence-44-s.flac`、`test.ogg`、`alaw.wav`（与 `scripts/native-smoke.ps1` 用的是同一批）。
- **`BuildConfig` 已由生成任务取代**：`:lyrico-app:generateBuildInfo` 产出 `com.lonx.lyrico.BuildInfo`（`VERSION_NAME` / `VERSION_CODE` / `COMMIT` / `BUILD_TYPE` / `DEBUG`），P4 迁 UI 时那 11 处 `BuildConfig.` 直接改这个。
- **原生库在应用内的查找路径**：`NativeLibraryLoader` 依次看系统属性 `lyrico.native.dir` → 环变量 `LYRICO_NATIVE_DIR` → 相对 `native/`（jpackage 布局）→ 从当前目录向上找 `build/native/<os>-<arch>`（开发布局）。`compose.desktop.application.run` 已注入 `-Dlyrico.native.dir=<repo>/build/native/windows-x64`。
