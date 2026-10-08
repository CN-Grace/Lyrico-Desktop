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
| 路径模型 | ✅ | `SongPaths`：`canonicalize`（存在则 `toRealPath()` 拿真实大小写，不存在则 `absolute().normalize()`）、`identityKey`（大小写不敏感比较）、拒收 `content://`/相对/空白值；`SongEntity.path: Path` 扩展属性；**无 `Uri` 类型**。`SongPathsTest`（6 项，断言比的是 `Path` 对象，不依赖分隔符）。存库形状统一为 `Path.toString()`（Windows 上是反斜杠）——它是 `Path.of()` 的不动点，且与 `songs.uri`/`filePath`/`folders.path` 三处逐字符一致（`LibraryScanIntegrationTest` 钉住 folders.path = 父目录字串） |
| 音频标签读写（TagLib JNI，路径式 ABI） | ✅ | `AudioTagRepositoryTest`（7 项）在真实 `bladeenc.mp3`(ID3v2) / `silence-44-s.flac`(Vorbis comments) 上走完整链路（mutation → resolver → `TagMapBuilder` → JNI → 文件 → 读回）：覆盖写入后重读一致、`patch` 不动未提及字段、封面字节往返、缺失文件与遗留 `content://` 行降级（lenient 不抛 / strict 抛）、写失败落 `AppLogType.METADATA` |
| 文件层去 SAF | ✅ | `AudioFileAccess` 重写为 `java.nio.file`（显示名/存在性/删除/重命名/读写字节，原子替换写）；`ParcelFileDescriptor`、`IntentSender` 授权流、`MediaStore` 分支全删 |
| `Uri` 退出标签链 | ✅ | `PictureSource.UriSource(Uri)` → `PictureSource.FileSource(Path)`（`AudioTagMutation`/`AudioTagMutationFactory`/`ImageBytesFetcher`）；`AudioTagRepositoryImpl` 内 `content://` 这类非路径值一律降级 |
| DataStore 设置层（JVM） | ✅ | `SettingsRepository`(132 行)/`SettingsRepositoryImpl`(1269 行) 去 `Context` 与 `preferencesDataStore` 委托，改为 `createSettingsDataStore(file: Path)`（`PreferenceDataStoreFactory.create`，用 JVM `File` 重载，不引入 okio 类型）。`SettingsRepositoryTest`（4 项）跑**真实文件** `settings.preferences_pb`：默认值、各类设置写回读、**关掉再重开 store 后仍在**（= 重启后不丢）、清空海报目录不清空 revision |
| 应用日志（durable log） | ✅ | `AppLogRepositoryImpl`(150 行，零 Android 依赖) + `AppLogRepository` 迁入；`AppLogRepositoryTest`（6 项）跑真实 Room 库：保留策略为 `NONE` 时拒记、倒序读取、异常栈入 `detail`、按天删除过期行、导出文本、清空 |
| 设置层的 `Uri`/`Parcelable` 清理 | ✅ | `setArtistPosterFolder(uri)` → `(path)`（存绝对路径）；`BatchMatchConfig`/`Lyrics.kt`(9 个类)/`SongSearchResult` 去 `@Parcelize`/`Parcelable`；`EditFieldConfigRepository` 改收 `DataStore<Preferences>`；`R.string.*` → `Res.string.*` 共 13 个文件 |
| 歌曲库/索引/搜索/歌词四个子系统 + mapper 迁入 | ✅ | `SongLibraryRepositoryImpl`(11 项)/`LibraryIndexRepositoryImpl`(7 项)/`SongSearchRepositoryImpl`(11 项)/`SongMetadataMapper`(5 项) 全部跑真实库文件 |
| `LyricFtsIndexer` / 歌词解码链（`LyricDecoder` → `LyricsDocumentPipeline` → LRC/TTML 格式，约 3000 行） | ✅ | **原 Android 测试套件整体搬迁并通过**：`LyricsDocumentPipelineTest`(31)/`LyricsColumnSorterTest`(18)/`LyricEncoderTest`(10) —— 同一个测试、同一份源码，在 JVM 上跑绿，是这次移植最强的证据 |
| `withTransaction`（Android 专有）替换 | ✅ | `RoomDatabase.inTransaction`（`useWriterConnection` + `immediateTransaction`，BEGIN IMMEDIATE 对齐 Android 写事务语义）；`SongLibraryRepositoryTest` 逐条断言事务两侧（歌曲行 + FTS 行）都落地：upsert 后索引存在、update 后旧行消失、delete 后索引随行删除 |
| 拼音依赖替换 `tinypinyin` → `com.github.houbb:pinyin:0.4.0` | ✅ | 前者是 jcenter 专供的 Android AAR，Maven Central 无此坐标、无法解析；后者纯 JVM、同生态（与已用的 opencc4j 同作者）。`SortKeyUtilsTest`(7 项) 覆盖数字/拉丁/CJK/多音字词组（`重庆森林` → `1_CHONGQINGSENLIN`，词组词典生效）/假名无拼音回落 |
| `String.format` 本地化隐患 | ✅ | `LyricFormatter`、`LyricEncoder.shiftLyricsOffset` 的时间戳格式化改绑 `Locale.ROOT`：这些格式串写进歌词文件（LRC/TTML），阿拉伯/土耳其语区默认数字会破坏文件结构 |
| 文件级操作（重命名 / 删除，去 SAF/MediaStore） | ✅ | `SongFileRepositoryImpl` 重写为「文件 + 数据库」一个操作：`renameSong` 一次事务里改 `songs.uri`/`filePath`/`fileName`/扩展名/排序键 + 迁移歌词 FTS 行 + 迁移 `song_custom_tag_keys` + 重建艺人/专辑索引 + 刷新文件夹计数，事务失败则把文件改回原名（宁可改名失败，也不能让库指向不存在的路径）；`deleteSongs` 逐首删除、失败不中断，行、FTS、自定义标签键、空文件夹一起清理。`SongFileRepositoryTest`（13 项）跑真实文件 + 真实库：真音频改名后仍能用 TagLib 读回、旧 uri 不再可查、旧 FTS/键**必须为空**、改名不碰别的歌的索引、重名→`NameConflict` 且磁盘与库都不动、文件已被外部删除→`Failed` 且保留行、路径式文件名（`..\x.mp3`、`nested/x.mp3`、`C:\...`、`song:name.mp3`、空白）全拒绝、无扩展名保留原扩展名、**仅改大小写的改名在 NTFS 上真的改掉大小写**、被拒绝的删除（非空目录，确定性失败）保留文件与行并落日志、批量删除越过后继续。另：`AudioFileAccess` 的 `delete`/`move` 改为抛异常（Android 返回 `false`/`null` 把失败原因丢了），`SongCustomTagKeyDao` 新增 `getKeysForSong`（迁移键用）|
| 媒体扫描器（去 SAF，走 `java.nio.file`） | ✅ | `MediaScanner` 全部重写：`SongFileSystem` 可注入接缝（真实 walk 用 `NioSongFileSystem`，失败路径用假文件系统），迭代式目录栈替代递归。`MediaScannerTest`（9 项）：扩展名集合、隐藏项/`$RECYCLE.BIN`/`System Volume Information` 跳过而 `data`/`cache`/`tmp` **不跳**（Windows 上是普通目录名）、根缺失→`missingFolderIds`、根不可读→`failedFolderIds`（子目录不可读只跳过）、同名根去重、**junction 成环终止**（真造 junction，`mklink /J` 不可用时 skip 而非假绿）、`dateAdded` 用创建时间并回落修改时间 |
| 库扫描仓库（`LibraryScanRepositoryImpl`） | ✅ | `LibraryScanIntegrationTest`（9 项）跑端到端：真实音乐目录（TagLib 自带音频）→ 扫描 → 入库 → 经 `SongQueryBuilder`/`@RawQuery` 列出来 → 改标签**落盘**并在库里同步。覆盖：二次扫描只读变化文件（0 insert/0 update/全部 skip）、改 mtime 只重读那一个、`fullRescan` 全量重读、删文件连同 FTS 行与自定义标签键级联清理、`ignoreShortAudio` 只在开启时跳过、开启歌词索引后歌词进 FTS 且能被搜到、只扫指定根不动其他根、根消失时**默认保歌+报失败**（`removeUnavailableFolders=true` 才删树） |

## 4. 阶段与门禁

| 阶段 | 内容 | 完成门禁（必须实测） |
| --- | --- | --- |
| **P0** | 审计 + 本方案 | ✅ 本文 |
| **P1** | ✅ 三个原生库编 Windows x64 DLL，改掉 `android`/`log` 链接与 GCC 专用旗标，写最小 JVM JNI 冒烟程序 | ✅ `System.load` 成功；7 种格式（FLAC/MP3/M4A/OGG/OPUS/APE/WAV）实测**读出并写回**标签、封面，含 CJK 路径；91 项检查 0 失败 |
| **P2** | ✅ Gradle 骨架（去 AGP、JVM + CMP），Miuix desktop，`Main.kt` | ✅ Windows 上窗口弹出（`Lyrico 1.6.0 (d14b032)`，1166×773），Miuix 主题正常渲染；应用进程内 `taglib.dll` 实际加载成功（见 P2 复现命令） |
| **P3** | 数据层：路径模型 / Room JVM / DataStore / 扫描 / 标签读写 | ✅ **已达成**：`LibraryScanIntegrationTest` 在真实目录上跑完「扫描 → 入库 → 列表出歌 → 改标签落盘并在库里同步」。剩余：`BatchTask`/`Playback`/`SourcePlugin`/`Update` 等仓储 |
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
- **P3 复现命令（数据层）**：`./gradlew :lyrico-app:test`（现 **165 项 0 失败**：库读写/FTS/raw query/重开持久化/schema 保真 8 项 + 歌曲库 11 + 库索引 7 + 本地搜索 11 + mapper 5 + 标签读写 7 + 拼音排序键 7 + 歌词解码链 59（原 Android 测试整体搬迁：管道 31/列排序 18/编码器 10）+ 设置层 4 + 应用日志 6 + 路径模型 6 + 壳 3 + 扫描器 9 + 扫描端到端集成 9 + **文件重命名/删除 13**）。测试任务注入的系统属性：`lyrico.schema.dir` / `lyrico.android.schema.dir`（schema 比对）、`lyrico.audiotag.fixtures.dir`（音频夹具，指向 `lyrico-audiotag/src/main/cpp/taglib/tests/data`），换机器无需改测试代码。
- **测试数据层两处易踩的 Room 语义（已踩中并写进测试注释，勿凭直觉改）**：
  1. `@Upsert` 在撞唯一索引时回退为 `UPDATE ... WHERE id = ?`，所以**实体必须带上已存行的主键**才会真正更新；`id = 0` 的重复 upsert 是静默 no-op（扫描器因此先读 `existingId = dbInfo?.id ?: 0L`）。`SongLibraryRepositoryTest` 两个用例各钉一半。
  2. `artist` 标签的默认分隔符集合里 `;`/`,`/`/` 是**启用**的，而 `&`、` feat. ` 是**禁用**的；`Earth, Wind & Fire` 靠内置 no-split 名单才不被逗号劈开。`LibraryIndexRepositoryTest` 同时钉住两种行为。
- **文件操作把「改文件」与「改库」合成一件事（与 Android 有意分歧）**：Android 里重命名是「仓储改文件 + `RenameSongUseCase` 改行」，而那个 use case 只更新了 `songs` 行与艺人/专辑索引，**没管按 uri 建索引的歌词 FTS 表和 `song_custom_tag_keys`** —— 改完名的歌会从歌词搜索和自定义标签筛选里消失。桌面端把两者放进 `SongFileRepositoryImpl`：一次 `inTransaction` 里改 `songs.uri`/`filePath`/`fileName`/扩展名/排序键、删旧 uri 的 FTS 行并按新 uri 重建、把 `song_custom_tag_keys` 从旧 uri 迁到新 uri、重建索引、刷新文件夹计数；事务抛异常就把文件改回原名（库与磁盘不允许以不一致收尾）。`SongFileRepositoryTest` 同时钉住「新 uri 有 / 旧 uri 必须为空」两半。
- **`AudioFileAccess.delete`/`move` 抛异常而不返回布尔/null**：SAF 版返回 `false`/`null`，调用方只能报「失败」并丢掉原因（是锁定、只读、还是名字被占）。桌面版让异常穿到仓储，仓储据此区分 `NameConflict` / `Failed` 并记日志；存在性判断交给调用方先调 `exists()`。
- **仅改大小写的重命名在 Windows 上是合法路径而不是重名冲突**：NTFS 不区分大小写但保留大小写，`track.mp3` → `Track.mp3` 用 `Files.exists` 判断会看“目标已存在”（其实是同一个文件）。所以先比 `SongPaths.identityKey`，同文件时走「旧名 → 临时名 → 新名」两步移动，让大小写真的落到目录项上，再 `toRealPath()` 回读真实拼写写进 `songs.uri`（不能凭请求的名字写库）。`SongFileRepositoryTest` 钉住。
- **无扩展名的新名字自动保留原扩展名**：改名对话框把扩展名固定在输入框右侧、永远带着它提交，但仓储不能因此假设调用方一定带了——`Track.mp3` 改成 `Track 2019` 会得到一个扫描器（按扩展名分类）永远认不出的文件。仅当新名字里完全没有 `.` 时补上原扩展名。
- **扫描器两个 Windows 专属决定（与 Android 有意分歧，勿按 Android 改回去）**：
  1. **目录跳过名单只留真正的系统目录**（隐藏项、`$RECYCLE.BIN`、`System Volume Information`、`android`/`obb`），**不再跳 `data`/`cache`/`tmp`**——在 Windows 上它们是用户可能真的在用的普通目录名，跳了就是吞歌。
  2. **库根目录消失 ≠ 删歌**：`LibraryScanRequest.removeUnavailableFolders` 默认 `false`，默认行为是保住歌曲并上报一条 `Collecting` 失败（Windows 的根常是可移动/网络盘，拔线不是删库）；Android 的「SAF 授权失效即删」变成显式开关。
- **“缺失根”要按文件夹树匹配，不能只按 id**：扫描器报的是**根**没了，而歌曲挂在**叶子**目录上（`songs.folderId`），所以删歌前必须 `folderDao.getFolderTreeIds(missingRootIds)` 展开成子树，否则开关打开也删不掉（初版就是这么错的，`LibraryScanIntegrationTest` 钉住）。
- **改完标签后的第一次重扫会重读该文件（正常且无害）**：TagLib 写标签会改变文件**大小**与 mtime，而 `SongMetadataMapper.applyAudioTagData` 只带标签与 mtime、不带 `fileSize`，所以库里的大小是旧值 → 下次扫描判定「变了」→ 重读一次并自洽；第二次扫描 `updated = 0`。关键断言是「重扫不得抹掉用户的编辑」，`LibraryScanIntegrationTest` 同时钉住这两点。
- **数据库 schema 目录约定**：桌面分支的 schema 历史写在 `lyrico-app/schemas/`（**从 v1 起**），继承来的 Android 历史 `1..21` 在 `lyrico-app/schemas-android/`，只读参考、不要再写入 —— 否则将来桌面端的 v2 会覆盖 Android 的 `2.json`，两边历史互相污染。
- **路径模型约定**：`songs.uri` 列 = Windows 绝对路径（唯一键/权威），`filePath` 同值镜像；实体扩展属性用 `SongEntity.path: Path`，**不引入 `Uri` 类型**，`content://`/`MediaStore`/SAF 相关整块删除。Android 库文件拷到 Windows 后无需迁移即可打开（schema 相同），但 `uri` 列里若存的是 `content://`，需要一次导入期重写（P3 扫描/导入时处理）。
- **`kotlin.test` + `runBlocking` 的陷阱**：本仓库的 `kotlin.test` 落回 JUnit4，测试方法必须返回 void，而 `= runBlocking { ... }` 会把最后一个表达式的值当返回值（如 `assertFailsWith` 返回 `Throwable`），报错是 `Method ... should be void`。写法统一用 `= runBlocking<Unit> { ... }`。
- **音频测试夹具不能随便挑**：`lyrico-audiotag/src/main/cpp/taglib/tests/data` 里有很多退化/畸形样本（如 `w000.mp3` 512 字节、`mpeg-sync-flac.flac`），它们能读但写不了标签、也没有时长 —— 在上面写标签会“看似成功但读不回”。能用的真实样本：`bladeenc.mp3`、`silence-44-s.flac`、`test.ogg`、`alaw.wav`（与 `scripts/native-smoke.ps1` 用的是同一批）。
- **`BuildConfig` 已由生成任务取代**：`:lyrico-app:generateBuildInfo` 产出 `com.lonx.lyrico.BuildInfo`（`VERSION_NAME` / `VERSION_CODE` / `COMMIT` / `BUILD_TYPE` / `DEBUG`），P4 迁 UI 时那 11 处 `BuildConfig.` 直接改这个。
- **原生库在应用内的查找路径**：`NativeLibraryLoader` 依次看系统属性 `lyrico.native.dir` → 环变量 `LYRICO_NATIVE_DIR` → 相对 `native/`（jpackage 布局）→ 从当前目录向上找 `build/native/<os>-<arch>`（开发布局）。`compose.desktop.application.run` 已注入 `-Dlyrico.native.dir=<repo>/build/native/windows-x64`。
