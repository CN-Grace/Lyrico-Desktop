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
| 自定义标签键索引 / 插件表 / GitHub 两个网络仓储（`CustomTagKeyRepository`、`SourcePluginRepository`、`GhContributorRepositoryImpl`、`UpdateRepositoryImpl` + `data/dto` 4 个类） | ✅ | 四个仓储全部 `git mv` 进 `src/main/kotlin`（仅两处 `android.util.Log` → `PlatformLog`、一处 `BuildConfig` → `BuildInfo`）。`CustomTagKeyRepositoryTest`（12 项）跑真实库：键的规范化（trim/大写/超长 64 字符拒绝/换行拒绝/空键拒绝/同列表内重复只存一次）是**库里的形状**而非仅往返、按任意大小写查回、替换字段后旧键消失、跨歌曲计数、`removeSongs(emptyList())` 不改动。`SourcePluginRepositoryImplTest`（15 项）跑真实库：**三种源类型（metadata/lyrics/cover）的开关与排序各自独立**（开 metadata 不动 lyrics/cover，改 metadata 顺序不动 lyrics 顺序）、重名 id 覆写、按 `customName ?: name` 稳定排序、`customName` trim/空白→`null`+`displayName` 回落、manifest 契约就地更新、卸载只删一个。两个网络仓储用 **JDK 自带 `com.sun.net.httpserver.HttpServer` + OkHttp 改写 host 的拦截器**测真实 HTTP 往返（零新依赖，生产代码不知情）：`GhContributorRepositoryImplTest`（7 项）覆盖 Bot 过滤 + 按贡献降序 + 请求路径含 `?per_page=600` + 404/坏 JSON/服务器不存在→`Result.failure`；`UpdateRepositoryImplTest`（12 项）覆盖新版本带说明与链接、同版本/旧版本→`NoUpdateAvailable`、**`v1.10.0` > `1.6.0`（数字逐段比较而非字符串序）**、`-beta`/`+42` 后缀忽略、读超时→`TimeoutError`、服务器不存在→`NetworkError`。**故意偏离 Android**：Android 版把 4xx/5xx 与 JSON 解析失败都扔进 `NetworkError`，导致它自己声明的 `UpdateCheckResult.ApiError`/`ParsingError` 两个状态**永远不可达**（`UpdateManager` 里那两句用户可见文案是死代码）；桌面版把 non-2xx 映射为 `ApiError(code, message)`、`SerializationException` 映射为 `ParsingError`，测试钉住这四个分支 |
| 批量任务记账（`BatchTaskRepository`/`Impl`） | ✅ | 两个文件无一行 Android 依赖（六个依赖全部已在 kotlin 树），`git mv` 后零修改编译通过。`BatchTaskRepositoryImplTest`（23 项）跑真实库：建任务同时写任务行 + 每首歌一条 item（`itemId = "$taskId-$index"`，计数器全零、`configJson` 透传），空选曲也写任务行，同歌两次建任务不共享 item；任务状态机（`markRunning` 记 `startedAt`、`markSucceeded`/`markCancelled` 清 `errorMessage`、`markFailed` 记原因、`finishedAt` 只写一次）；item 级成功/跳过各带 `resultJson`、失败带原因且**清掉上一次的 result**、`progress` 跨状态保留、重命名后可回写 `filePath`/`fileName`；**进度计数从 item 行算出来**（succeeded+failed+skipped，不统计还在排队/运行中的）且能清空 `currentFile`、对不存在的 taskId 为 no-op；`getPendingItems` 只给 QUEUED+RUNNING；**崩溃恢复**：上次进程死掉留下的 RUNNING/QUEUED 行在下次启动时置 FAILED（`"Task interrupted by system"`，桌面无 WorkManager 可接续），已完成的任务不被污染；`workId` 列在桌面当作自己队列的 job id 存（保留列以维持 schema 与 Android 库一致）；`getRunningTaskByType` 只找排队/运行中最新的一个（同毫秒建的两次任务无定义顺序，测试显式错开）；`deleteTask`/`deleteTasks` 级联删 item，`clearFinishedTasks` 只删终态、运行中的连 item 一起留 |
| 播放 / 交给系统默认程序（`PlaybackRepository`，去 `Context`/`Uri`/`Intent`/`Toast`） | ✅ | 用户裁决取方案 B：只用系统默认关联程序（等价于在资源管理器里双击），Android 的另三条路径**删除而非模拟**（按包名指定播放器、`ACTION_CHOOSER` 选择器、两者依赖的应用枚举），两条 `Toast` 也删除 —— 仓储不负责画界面，改为返回 `PlaybackResult` 由 UI 决定提示（P4）。`DesktopOpener` 可注入接缝（真实实现是 `java.awt.Desktop` OPEN），`PlaybackRepositoryTest`（9 项）**不会真的启动播放器**：断言「交给 shell 的是什么」，文件检查是真的（临时目录里的真音频）。覆盖：交给 shell 的是该文件的绝对路径（含 `..` 的写法会先归一化，因为 `Desktop.open` 拒收相对路径）、文件不存在/是目录时**绝不调用 shell**、环境不支持时返回 `Unsupported` 且先于文件检查（这句提示对任何路径都成立）、shell 抛 `IOException`/`SecurityException` 都变成 `Failed(path, throwable)` 而不冒泡、真实 `AwtDesktopOpener.isSupported()` 在无桌面会话时不抛（headless 回落）|

## 4. 阶段与门禁

| 阶段 | 内容 | 完成门禁（必须实测） |
| --- | --- | --- |
| **P0** | 审计 + 本方案 | ✅ 本文 |
| **P1** | ✅ 三个原生库编 Windows x64 DLL，改掉 `android`/`log` 链接与 GCC 专用旗标，写最小 JVM JNI 冒烟程序 | ✅ `System.load` 成功；7 种格式（FLAC/MP3/M4A/OGG/OPUS/APE/WAV）实测**读出并写回**标签、封面，含 CJK 路径；91 项检查 0 失败 |
| **P2** | ✅ Gradle 骨架（去 AGP、JVM + CMP），Miuix desktop，`Main.kt` | ✅ Windows 上窗口弹出（`Lyrico 1.6.0 (d14b032)`，1166×773），Miuix 主题正常渲染；应用进程内 `taglib.dll` 实际加载成功（见 P2 复现命令） |
| **P3** | 数据层：路径模型 / Room JVM / DataStore / 扫描 / 标签读写 | ✅ **已达成**：`LibraryScanIntegrationTest` 在真实目录上跑完「扫描 → 入库 → 列表出歌 → 改标签落盘并在库里同步」。仓储层**全部**迁入 `src/main/kotlin`（最后一个 `PlaybackRepository` 按用户裁决的方案 B 重写为「只用系统默认关联程序」） |
| **P4** | UI 层 + 三栏桌面布局 + 导航 + 文件对话框/右键菜单/拖放/快捷键 | 全流程鼠标可操作，覆盖主要页面 |
| **P5** | 批量任务（进程内队列替代 WorkManager）、ReplayGain、导出、插件 quickjs 运行时、更新检查、5 语言资源迁移 | 每项功能端到端跑通 |
| **P6** | jpackage 打包 MSI/portable、图标、文件关联、许可声明 | 干净 Windows 机器上安装后可用 |

### P4 施工顺序（用户裁决：先迁 viewmodel + 状态层，再接 UI）

`scripts/port-frontier.py` 给出的是**证据**而不是猜测：它逐个文件判断「`com.lonx.lyrico` 依赖是否已由 kotlin 树满足」，因为把依赖未满足的文件搬过去只会得到一个与真问题无关的编译错误。当前 java 树 **200 个文件：76 个已在边界上可搬，124 个仍被 java 树文件挡住**（另有 0 个陈旧副本）。用法：

```
python scripts/port-frontier.py                     # 全树
python scripts/port-frontier.py --prefix viewmodel/ # 只看某层
python scripts/port-frontier.py --list-blocks       # 列出每个文件被谁挡住
```

它顺手修掉了两个**会骗人的**判断口径，值得记下：`fun String?.foo()` 这种带接收者的声明曾被当成「声明了一个叫 `String` 的类型」，于是整个包里任何出现 `String` 一词的文件都被判为被挡；同类问题让 `fun Modifier.blurSource()`、`fun EditableField.title()` 变成幽灵依赖。所以现在只把 `class/interface/object/typealias` 当作「类型引用」判据，函数/属性名不算 —— 一个同名的 `title` 属性通常只是属性，而未解析的**类型**一定是真的编译错误。另外同包引用（不写 import 那种）必须单独扫，否则「可搬」的判断不可信。

**viewmodel 层现状（22 个文件：0 可搬 / 22 被挡）**：所有**不依赖待桌面化叶子**的 viewmodel 都已搬完，剩下 22 个每个都被具体的叶子挡住，不再有「顺手就能搬」的条目。挡住它们的叶子分四类，也解释了为什么 viewmodel 层不可能靠自己收尾：

1. **`worker/BatchTaskScheduler.kt` 与各 `worker/processor/*`**（挡住 7 个：`BatchTaskDetailViewModel`、`BatchTaskListViewModel`、`BatchReplayGainViewModel`、`BatchExportViewModel`、`BatchEditViewModel`、`BatchMatchViewModel`、`BatchRenameViewModel`）——这是 WorkManager 的桌面替代，属于 P5 的施工项。
2. **SAF/URI 家族**（`utils/UriUtils.kt`、`utils/SafDocuments.kt`、`utils/SafSiblingFileWriter.kt`，挡住 `FolderManagerViewModel`、`ArtistPosterFoldersViewModel` 等）——Windows 没有 document tree，必然删而不是搬。
3. **插件层**（`plugin/source/SearchSourceProvider.kt`、`domain/SearchSourceConfigApplier.kt`、`utils/PluginFieldPostProcessor.kt`，挡住 `SearchViewModel`/`LyricsSearchViewModel`/`CoverSearchViewModel`/`SearchSourceConfigViewModel`/`PluginViewModel`）——需要 quickjs 运行时接上，P5。
4. **单点叶子**：`utils/UpdateManager.kt`（挡住 `SongListViewModel`/`AboutViewModel`，见 §5 分叉）、`utils/ReplayGainScanner.kt`（`AlbumActionsViewModel`，P5）、`utils/CacheManager.kt` + `data/model/cache/CacheCategory.kt`（`SettingsViewModel`）、`data/SharedSelectionManager.kt` + `data/model/entity/getUri`（`SongSelectionViewModel`）。

因此批量顺序是：**先搬纯状态文件 → 再处理 `UiMessage` 等叶子 → viewmodel 会自己一排排解除阻塞**。

| 叶子 | 挡住的文件数 | 桌面化要动什么 |
| --- | --- | --- |
| `utils/UiMessage.kt` | 12 | ✅ **已搬**（commit `938b56e`）：`@StringRes Int` + `Context.getString` → Compose resources 的 `StringResource` + 挂起 `getString`；`asString(context)` 这个非 Composable 入口没有 `Context` 可给，改成挂起函数 `resolve()`（Kotlin 不允许 `suspend` 重载同名函数，所以两个入口不同名）。嵌套类 `StringResource` → `Localized`，避免与 `org.jetbrains.compose.resources.StringResource` 同名套同名 |
| `ui/components/ScaffoldPadding.kt` | 31 | `WindowInsets` 是 Android 概念，桌面没有；三栏布局的边距得重新给一遍 |
| `ui/components/blur/BarBlur.kt` | 14 | `RenderEffect`/`Modifier.blur` 在桌面走 Skia，API 面不同 |

因此批量顺序是：**先搬纯状态文件 → 再处理 `UiMessage` 等叶子 → viewmodel 会自己一排排解除阻塞**。

**已搬（第一批）**：`viewmodel/StringUtils.kt`、`viewmodel/SearchPagination.kt` —— 两个都是零依赖的真逻辑，且**两边测试树都没有测试**，搬过来时补了 23 项（`StringUtilsTest` 11 / `SearchPaginationTest` 12）：`isEqualIgnoringBlank` 把 null 与空白视为同一个值（这正是「标签字段到底改没改」需要的语义），但要钉住它**不**trim —— `" a"` 与 `"a"` 不相等；`mergeSearchPage` 按 `(pluginId, id)` 去重（`"a"+"bc"` 与 `"ab"+"c"` 不能撞车，键里用了 NUL 分隔）、保序、`hasMore` 的规则是「第一页允许为空也继续说还有」——这条反直觉但照原样钉住，改它就是行为变更。`viewmodel/SortState.kt` 与 `viewmodel/SearchSourceUiModel.kt` 的处置：前者此前已搬；后者带 `@param:StringRes val labelRes: Int?`，归入下面那个系统性转换批次。

**已搬（状态层批次）**：`viewmodel/StringUtils.kt`、`viewmodel/SearchPagination.kt`、`utils/UiMessage.kt`，以及五个 `domain/song/usecase/*`（`SynchronizeLibraryUseCase`、`ReadAudioTagsUseCase` 原样搬；`SaveAudioTagsUseCase` 去掉 `IntentSender` 与权限分支；`DeleteSongsUseCase`/`RenameSongUseCase` **重写为薄包装**）与 `utils/LibraryScanManager.kt`。用例之所以能变薄，是因为桌面 `SongFileRepository` 已经把「行 + 自定义标签键 + 文件夹计数 + 索引刷新」整笔事务收在接口里，用例再长一遍逻辑就是**做两遍**；因此两组结果类型用 `typealias` 指向仓储的结果类型，而不是复制一套 sealed 层级。

本批测试 **38 项**（`LibraryScanManagerTest` 18 / `AudioTagUseCasesTest` 12 / `DeleteSongsUseCaseTest` 5 / `RenameSongUseCaseTest` 3），全量 **316 项 0 失败 0 跳过**。两条值得记下的经验：队列语义**只有在闸门挡住时才是可观测的**（没有 `CompletableDeferred` 闸门，扫描会在一次调度内跑完，所有合并测试都会在测一个空队列）；`mergePendingRequest` 只合并**等待中**的请求，**正在跑**的全库扫描不会吸收新请求——照原样钉住（新加的文件夹可能刚好被那次扫描漏掉，多跑一次比漏扫便宜）。

**已搬（浏览/搜索 viewmodel 批次）**：`viewmodel/SearchSourceUiModel.kt`、`AlbumDetailViewModel.kt`、`CharacterMappingViewModel.kt`、`LocalSearchViewModel.kt`、`ArtistLibraryViewModel.kt`、`AlbumLibraryViewModel.kt` 六个文件。前五个是原样搬（`SearchSourceUiModel` 的 `@param:StringRes val labelRes: Int?` 按既定配方改成 `StringResource?`；该字段两边树都没有调用点赋值，所以是类型转换而不是删除字段，让以后要用的 screen 一开始就编译在桌面资源类型上）。

这一批暴露了一个「看起来像桌面端没有 ViewModel」的假问题：`androidx.lifecycle` 的 `ViewModel`/`viewModelScope` **在运行期 classpath 上（经 Compose 传递进来）但不在编译期 classpath 上**，所以搬过来直接是 `Unresolved reference 'ViewModel'`。修法是显式声明 `implementation(libs.androidx.lifecycle.viewmodel.compose)`（`androidx.lifecycle:lifecycle-viewmodel-compose` 是多平台构件，Gradle 元数据会解析到 `-desktop` 变体）。**传递依赖不是编译依赖**——这条值得单独记住，否则会误判成「桌面端没有 lifecycle」。同时加了 `testImplementation(libs.kotlinx.coroutines.test)`：viewmodel 的流跑在 `viewModelScope` 上，纯 JVM 测试里没有 Android looper，必须 `Dispatchers.setMain` 才能把状态流驱动起来。

这批测试 **40 项**（`SearchSourceUiModelTest` 6 / `CharacterMappingViewModelTest` 8 / `ArtistLibraryViewModelTest` 8 / `AlbumLibraryViewModelTest` 9 / `LocalSearchViewModelTest` 6 / `AlbumDetailViewModelTest` 3，外加共用的 `LibraryBrowseFixture.kt`），全量 **356 项 0 失败 0 跳过（37 个测试类）**。测试用真实 Room + 真实 DataStore + `Dispatchers.setMain(Dispatchers.Default)`，用轮询（`awaitUntil`）等真实 IO 而不是虚拟时间——Room/DataStore 是真文件 IO，虚拟时间会把它变成空转。

三条被钉住的语义，都是「不加测试就会想当然写错」的类型：

1. **`AlbumSortBy.YEAR` 的降序分支靠 `return@combine` 提前返回**。它自己构造降序比较器，跳过其余分支共享的那次 `asReversed()`；一旦有人顺手把那个 `return@combine` 去掉，「年份降序」就会悄悄变成「升序，且无年份的排最前」。测试用「1961 / 1959 / 无年份」这种三元组把它钉住：升序 = `[1959, 1961, 无]`，降序 = `[1961, 1959, 无]`。
2. **计数类排序的 `ASC` 是「多的在前」**。`SONG_COUNT`/`ALBUM_COUNT` 的比较器固定用 `compareByDescending`，`ASC` 分支直接返回它的结果，`DESC` 才 `asReversed()`——所以「计数升序」= 数量由多到少。这不是移植错误（与 Android 逐字一致），照原样钉住；等接 UI 那批再决定箭头语义要不要改，改它就是行为变更，得在 §5 里挂号。
3. **专辑列表的 `albumArtist` 比较先 `.uppercase()`**，且 `null` 走 `orEmpty()` 排在最前。测试里特意放了小写的 `bill evans` 与 `Miles Davis`：按大小写敏感的默认比较，`M` < `b` 会把顺序倒过来，所以这条测试能区分「有大写化」与「没有」。

**已搬（剩余三个 viewmodel 批次）**：`viewmodel/ArtistSplitSettingsViewModel.kt`、`EditFieldSettingsViewModel.kt`、`AppLogViewModel.kt`。前两个原样搬（依赖早已就位）；第三个做了桌面化重写：

- `exportLogs(context, uri)` → **`exportLogs(target: File, ids)`**。Android 用 SAF 让用户挑目标（`ACTION_CREATE_DOCUMENT` + `Uri`），桌面端把「挑文件」留给 UI 层（`java.awt.FileDialog`/`JFileChooser`），viewmodel 只接收一个 `File` 并按 UTF-8 写入。**这是一处已知的 P4 UI 缺口**：文件保存对话框尚未接上，`exportLogs` 现在只有测试与将来的 UI 调用它。
- `buildDiagnosticInfo()` 里的 `BuildConfig.VERSION_NAME` → `BuildInfo`（`generateBuildInfo` 任务生成的桌面常量），`Build.VERSION.RELEASE/SDK_INT` → `System.getProperty("os.name"/"os.version"/"os.arch")`（Windows 11 上 `os.name` 仍是 `Windows 10`，所以版本号一并打印——不要以为这是 bug）。
- `buildDeviceModel()` 整段删掉：`com.hjq.device.compat` 的 `DeviceMarketName`/`DeviceOs`/`SystemPropertyCompat` 是「把厂商内部代号翻成市场名」的 Android 专用库（`ro.product.marketname` 之类的系统属性），桌面端没有对应概念，硬凑一个 `Build.MANUFACTURER` 替代只会给出更差的信息。**这是明确记录的缺口**，诊断信息里不再有「设备型号」这一行。
- `UiMessage.StringResource` → `UiMessage.Localized`（`Res.string.export_success` / `export_failed`），与 `938b56e` 的改名一致。

本批测试 **47 项**（`AppLogViewModelTest` 7 / `ArtistSplitSettingsViewModelTest` 20 / `EditFieldSettingsViewModelTest` 20），全量 **403 项 0 失败 0 跳过（40 个测试类）**。三个 viewmodel 的测试各钉住一个容易想当然的点：

1. **`ArtistSplitSettingsViewModel` 的重复校验是「跨两类集合」的**：新增分隔符既要跟其它自定义项比，也要跟**当前可见**的内置项比，而「可见」由 `hiddenBuiltinSeparatorIds` 与 `builtinSeparatorOverrides` 两个集合共同决定。测试同时钉住两个方向：`/` 默认开启所以被拒，一旦 `removeBuiltinSeparator("slash")` 就能加；` feat. ` 默认关闭所以能加，一旦 `setBuiltinSeparatorEnabled("feat_dot", true)` 就被拒（输入写成 `feat.` 而内置值是 ` feat. `，所以这条同时证明比较是**两边 trim 后**做的）。`ArtistSplitValidationError.DUPLICATE_BUILTIN` 是**死枚举常量**（没有任何分支会返回它），照原样留下并在测试中体现为「与内置冲突时返回的是 `DUPLICATE`」。
2. **`ArtistSplitSettingsViewModel.updateConfig` 是「每次调用一个新协程的读-改-写」**，两次修改同时在飞会丢一次（真实用户点击是串行的，所以 Android 上没暴露）。测试里第一次遇到这个坑：连续 `addCustomSeparator` 两次后只看到一项。现在每个修改都等落地再发下一个——这不是为了测试好写，而是记下这个**并发写设置的真实弱点**。
3. **`EditFieldSettingsViewModel` 的「启用」是三态且带组件门**：`isEffectivelyEnabled` = 字段自身显隐 AND 组件开关（只有 ReplayGain 有组件），所以测试专门关掉 `component:ReplayGain` 并断言 `enabledCount` 少掉整块、而每项自身的 `enabled` **不变**——只改单字段的测试看不出组件开关有没有被漏掉。另外 `showFieldSongs` 的失败分支只有一条可达路径（组件分支对无 `target` 的字段 `requireNotNull`，例如 `lyrics_offset`），测试直接调用该路径，免得这个标志哪天变成死代码后「整库为空」被当成正常结果。同时钉住一个**如实记录的既有语义**：宽容读标签会吞掉「文件打不开」并返回「没有封面」，于是磁盘上已不存在的歌会被列进「没有封面」而不是单独一类——这是照原样保留的行为，不是期望行为。

**三处刻意的行为修正（`SaveAudioTagsUseCase` 的 FTS 刷新在上一批，后两处属本批）：**

1. `SaveAudioTagsUseCase` 在 Android 上更新 `songs.lyrics` 却**不刷新歌词 FTS**（`LibraryIndexRepository.reindexSongInTransaction` 名字虽然笼统，实际只重建艺术家/专辑索引），后果是改完歌词后**新歌词搜不到、旧歌词仍能搜到**。桌面其余写路径（`SongLibraryRepositoryImpl.updateSong`、`SongFileRepositoryImpl.renameSong`）都显式调 `LyricFtsIndexer.replaceSong`，所以这一条属于漏网，已在事务里补上并被 `AudioTagUseCasesTest` 钉住。
2. `UpdateRepositoryImpl` 把 `ApiError`/`ParsingError` 折叠成 `NetworkError` 的那处（commit `4972444`）：Android 把两种完全不同的失败报成同一种，用户看不出是「网断了」还是「服务器返回了看不懂的 JSON」，桌面端分开保留。
3. `AppLogViewModel.exportLogs` 在 Android 上把 `CancellationException` 一并吞进 `catch (e: Exception)`，于是「页面关掉了」会被导出成一条「导出失败」的日志与 toast，还会往一个已经没人看的状态里写。桌面端在通用 catch **之前**重新抛出 `CancellationException`（这也是本仓所有协作取消点的统一做法），测试双向钉住：`exportText` 抛出 `CancellationException` 时**既不写日志也不弹 toast**（把上面那个 catch 删掉，这条测试立刻失败——已用变异验证），而「导出到目录」这种必失败目标仍会正常报错。

**`@StringRes Int` 是一个系统性转换，值得单独一批**：`AppLanguage`、`CacheCategory`、`LocalSearchField`、`LogRetentionOption`、`AppLogLevel`、`AppLogType`、`SearchSourceUiModel` 都挂着一个 `labelRes: Int`，调用点统一是 `stringResource(x.labelRes)`。好消息是 Compose resources 的 `stringResource(res: StringResource, vararg args)` 与 Android 的 `stringResource(resId, *args)` **调用形状完全一样**，所以转换只需改「字段类型」与「初始化处」（`R.string.foo` → `Res.string.foo`）以及 import，调用点不用动。

**一个需要用户留意的改名**：`UiMessage.StringResource` 这个嵌套类，在桌面会**包着一个** `org.jetbrains.compose.resources.StringResource`——同名套同名。✅ 已改名 `Localized`（commit `938b56e`）。

## 5. 待定分叉（到 P5 前必须由用户裁决）

**「更新检查」指向哪个仓库**（`utils/UpdateManager.kt`，2026 年新发现的分叉）—— 到目前还没有裁决：

`UpdateManager` 唯一的阻塞是 `App.kt`，而它只取两个常量：`App.Companion.OWNER_ID = "Replica0110"`、`REPO_NAME = "Lyrico"`。照原样搬就是**行为对齐**，但桌面版会把 Android APK 当成更新包报给用户：

- 实测 `Replica0110/Lyrico` 有 3 个 release，资产是 `Lyrico-1.6.0-*.apk`（Android）；本 fork `CN-Grace/Lyrico-Desktop` 目前 **0 个 release**。
- **A. 照原样指向 Android 上游**：行为对齐，但「发现新版本」会打开一个装不上的 APK 下载页。
- **B. 指向 `CN-Grace/Lyrico-Desktop`**：语义正确，但 0 release 期间永远是「已是最新」（`NoUpdateAvailable`，无害但无用），要等 P6 打包开始发 release。
- **C. 做成可配置**（设置项或构建期常量，默认指向本 fork）：最灵活，多一个设置项。

按 §4 的排期，「更新检查」本就属于 **P5**，所以 `UpdateManager` **不在 P4 搬**（它是本批唯一剩下的状态层文件，但事实上不属于状态层）；等到 P5 做更新检查时再一并裁决指向——到那时 P6 打包已经开始发 release，选项 C/B 的成本都会变。

**「计数排序的升降序语义」要不要改**（`ArtistLibraryViewModel` / `AlbumLibraryViewModel`，2026-10-09 移植时发现，尚未裁决）——

`ArtistSortBy.SONG_COUNT`/`ALBUM_COUNT` 与 `AlbumSortBy.SONG_COUNT` 的比较器固定是 `compareByDescending`，`ASC` 分支**直接返回**它的结果，只有 `DESC` 才 `asReversed()`。于是：计数「升序」= 数量由多到少，计数「降序」= 由少到多；而 NAME/ALBUM_ARTIST/YEAR 三个键的升降序是正常的。

这**不是移植错误**（与 Android 逐字一致，已由 40 项 viewmodel 测试逐条钉住），但接 UI 时会直接变成一个箭头指反的排序菜单：

- **A. 照原样**：排序箭头对计数项的含义与其它项相反。零风险，但用户会看到「↑」把歌多的排前面。
- **B. 计数项改成正常语义**（比较器换成 `compareBy` 升序，`DESC` 才反转）：与其它排序键一致，是一处**有意行为变更**，三个键各要改一行 + 改对应测试。
- **C. 排序菜单里给计数项固定「多的在前」并隐藏箭头**（把「ASC = 多在前」当默认而不是方向）：不动比较器，只动 UI，属于接 UI 那批的事务。

按 §4 排期，sorting 菜单属于 P4 的 screens 批次，所以这条等接 UI 时一并裁决；在那之前保持逐字对齐（A）。

**ReplayGain 的 PCM 解码方案**（Android 端 `ReplayGainScanner.kt` 用 `MediaExtractor`+`MediaCodec`，桌面无等价物）：

- **A. 捆绑 ffmpeg**（sidecar `ffmpeg.exe` 经 stdin/stdout 管道喂 f32le，或 JavaCPP `ffmpeg-platform` 直接调 libav*）：覆盖全部格式（含 APE/AIFF/DSF/Opus），需要额外约 50–150 MB 二进制与 LGPL/GPL 许可声明；与现有 `ebur128` JNI 对接最直接。
- **B. 自带解码源码**（dr_libs + stb_vorbis + libopus 等）：体积小、无外部许可包袱，但**覆盖不全**（APE/AAC/DSF 缺），需要为缺失格式降级。
- **C. 解析声道响度仅走 TagLib 已读标签 + 跳过无解码格式**：最省事，但功能不对齐，与「全功能对齐」目标冲突。

**「播放 / 用其它程序打开」在 Windows 上如何落地**（`PlaybackRepository`）—— ✅ **已裁决：方案 B**（2026-10-09，用户选择）：只走系统默认关联程序，删除按包名指定与选择器两条路径，`java.awt.Desktop` 经 `DesktopOpener` 接缝注入。以下为当初的选项留档：

Android 版是四件事四个 `Intent`：`play()`（`ACTION_VIEW` + `audio/*`）、`openWithPackage()`（指定包名）、`openSystemChooser()`（`ACTION_CHOOSER`）、`openDefaultApp()`，失败时 `Toast` 提示，全部依赖 `Context`/`Uri` —— 桌面一个都没有。这不是技术缺口，而是产品定位问题，需要用户裁决：

- **A. 只保留内置播放器**：删掉整个 `PlaybackRepository` 与「用其它程序打开」菜单项。最省事，但用户不能把歌丢给 foobar2000/Occulante 这类专用工具，「在文件夹中显示」也一并没了。
- **B. 只走系统默认关联程序**（`java.awt.Desktop.getDesktop().open(File)`）：实现最小，行为对齐 Explorer 双击，但**不能指定程序**，且 AWT Desktop 在部分精简/无 shell 环境下会 `UnsupportedOperationException`（需回落）。
- **C. 内置播放器 + 可配置外部播放器**（在设置里存一个 `exe` 路径，用 `ProcessBuilder(listOf(exe, path))` 启动；未配置时回落到 B）：功能对齐 Android 的「用其它程序打开」，能用 foobar2000；代价是要多一个设置项 + 一条进程启动的错误处理路径（路径失效、程序拒绝参数）。
- **D. C + 仿 Android 的「选择器」**：额外枚举已安装程序（读 `HKEY_CLASSES_ROOT\Applications` / `Applications\*.exe\shell\open\command`）做成选择列表。功能最齐，但注册表枚举要处理 32/64 位视图与引号解析，投入产出比最低。

我的建议：**P4 先用 C 的最小形态**（内置播放器 + 设置里一个可选的「外部播放器」路径，未配置时 `Desktop.open` 回落），D 留到有人真的需要时再做。另外 Android 的 `Toast` 提示在桌面换成右下角通知条（Miuix `Snackbar` 之类），属 UI 层事务，不阻塞这里。

## 6. 主要风险

1. ~~**Room KMP 在纯 `kotlin("jvm")` 模块下的 KSP 配置**~~ ✅ 已解决：`ksp(libs.androidx.room.compiler)` + 官方 `androidx.room` Gradle 插件 + `room { schemaDirectory(...) }`，`kotlin("jvm")` 模块下工作正常（11 实体/7 DAO 全部生成）。另注：`kotlinx.serialization` 的 `@Serializable` 还需要 `alias(libs.plugins.kotlin.serialization)`（只有运行库依赖不够，报错是 `Unresolved reference 'serializer'`）。
2. ~~**原生库 CMake 适配**~~ ✅ 已解决：TagLib 3.x 在 CMake 4.4.2 下可配置；`-flto`/`--pack-dyn-relocs`/`android`+`log` 链接已替换；ebur128 缺 `<sys/queue.h>`（自带 `compat/sys/queue.h`）与 `M_PI`（加 `_USE_MATH_DEFINES`）两处已补。复用 `/MT` 静态 CRT，DLL 无第三方运行时依赖。
3. **compose-destinations 的 KSP 代码生成**是否支持 CMP Desktop（不支持则退化为手写 `when` 导航，screens 共 24 个，可控）。
4. **Miuix desktop 与 Android 版的行为差异**（`BackHandler`、`TopAppBar`、滚动条、窗口拖拽区）——逐屏过。
5. **`androidx.lifecycle.ViewModel` 30 处在桌面端的生命周期**——CMP 自带 `lifecycle-viewmodel-compose`，但要确认 Koin 的 `viewModel {}` 在桌面可用。**部分已证伪**：`lifecycle-viewmodel-compose` 在多平台构件里存在 `-desktop` 变体，显式声明 `implementation(libs.androidx.lifecycle.viewmodel.compose)` 后 `ViewModel`/`viewModelScope` 在 `kotlin("jvm")` 模块编译并运行正常（它原本只在运行期 classpath 上，所以看起来像「桌面没有 ViewModel」）。剩下未验证的是 Koin 的 `viewModel {}` 注入（`di/AppModule.kt` 要被搬过来才会遇到）。
6. **非 ASCII 工程路径 + Gradle 参数文件编码**（已踩中并修复，勿回退）：工程位于 `H:\VibeCoding\03-应用\Lyrico-Desktop`。Gradle 用**守护进程默认字符集**（`ArgWriter` → `new PrintWriter(File)`）把 worker JVM 的 classpath 写进临时 `@argfile`，而 `java.exe` 用 **Windows ANSI 代码页（936/GBK）** 解析该文件；`gradle.properties` 里原本的 `-Dfile.encoding=UTF-8` 会把含中文的工程路径写成乱码 → worker 报 `ClassNotFoundException`（每个测试类都找不到，甚至 `GradleWorkerMain`）。修复：`org.gradle.jvmargs` 用 `-Dfile.encoding=GBK`（= 本机 ANSI 代码页）。**换机器时该值必须等于该机 ANSI 代码页**；`run`/`JavaExec` 任务同样走这条路径，所以 P2 之后不要再改回 UTF-8。

## 7. 约定

- 旧的 Android 代码以 commit 形式留在 git 历史（`origin/master`）中，不再保留在本分支源码树内。
- 平台无关代码优先「原地改造」，不做复制；只有确定要删的 Android 专属文件才删除。
- 每阶段结束必须留下可复现的验证命令（见 §4 门禁）。
- **P1 复现命令**：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-native.ps1`（产出 `build/native/windows-x64/{taglib,ebur128,quickjs-ng}.dll`）→ `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/native-smoke.ps1`（纯 Java 冒烟，无需 Gradle，失败返回非 0）。冒烟源码在 `tools/native-smoke/`，其中的 `com/lonx/audiotag/{TagLib,model/*}.java` 是**临时替身**，等 P2 的 Kotlin JVM 库建好后应改为直接依赖真实 Kotlin 类。
- **P1 复现命令（Gradle 侧，真 Kotlin 绑定）**：`./gradlew :lyrico-audiotag:test`（13 项检查 0 失败，覆盖 7 种格式的标签/封面读写、CJK 路径端到端）。跑之前确保 `build/native/windows-x64/*.dll` 已由 `scripts/build-native.ps1` 产出。
- **P2 复现命令**：`./gradlew :lyrico-app:run` 弹出窗口（标题 `Lyrico <版本> (<commit>)`）；取证用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-window.ps1 -TitleLike "Lyrico 1.6.0" -OutputPath docs/port-evidence/p2-miuix-window.png`（截的是窗口自身矩形；**别用模糊标题匹配**——终端窗口标题里也含 “Lyrico-Desktop”）。截图非空白的客观校验在 `docs/port-evidence/p2-miuix-window.analysis.txt`（561 色；白底 `255,255,255` + 卡片底 `247,247,247`；2906 个文字暗像素分布在 96 行）。
- **P2 版本锁定**：Kotlin 2.4.20 + Compose Multiplatform **1.12.0** + Miuix **0.9.4**。不是随手写的：Miuix `-desktop` 产物的 pom 显示它是用 CMP 1.12.0 / Kotlin 2.4.20 编的，Kotlin 版本又要跟仓库原有 2.4.20 对齐，三者必须同进同退。
- **P3 复现命令（数据层）**：`./gradlew :lyrico-app:test`（P3 收口时 **243 项 0 失败**；加上 P4 状态层 38 项、浏览/搜索 viewmodel 40 项与剩余 viewmodel 47 项后，全量现为 **403 项 0 失败 0 跳过、40 个测试类**，见 P4 节）：库读写/FTS/raw query/重开持久化/schema 保真 8 项 + 歌曲库 11 + 库索引 7 + 本地搜索 11 + mapper 5 + 标签读写 7 + 拼音排序键 7 + 歌词解码链 59（原 Android 测试整体搬迁：管道 31/列排序 18/编码器 10）+ 设置层 4 + 应用日志 6 + 路径模型 6 + 壳 3 + 扫描器 9 + 扫描端到端集成 9 + 文件重命名/删除 13 + 自定义标签键 12 + 插件表 15 + GitHub 贡献者 7 + 更新检查 12 + 批量任务 23 + **播放转发 9**）。测试任务注入的系统属性：`lyrico.schema.dir` / `lyrico.android.schema.dir`（schema 比对）、`lyrico.audiotag.fixtures.dir`（音频夹具，指向 `lyrico-audiotag/src/main/cpp/taglib/tests/data`），换机器无需改测试代码。
- **测试数据层两处易踩的 Room 语义（已踩中并写进测试注释，勿凭直觉改）**：
  1. `@Upsert` 在撞唯一索引时回退为 `UPDATE ... WHERE id = ?`，所以**实体必须带上已存行的主键**才会真正更新；`id = 0` 的重复 upsert 是静默 no-op（扫描器因此先读 `existingId = dbInfo?.id ?: 0L`）。`SongLibraryRepositoryTest` 两个用例各钉一半。
  2. `artist` 标签的默认分隔符集合里 `;`/`,`/`/` 是**启用**的，而 `&`、` feat. ` 是**禁用**的；`Earth, Wind & Fire` 靠内置 no-split 名单才不被逗号劈开。`LibraryIndexRepositoryTest` 同时钉住两种行为。
- **文件操作把「改文件」与「改库」合成一件事（与 Android 有意分歧）**：Android 里重命名是「仓储改文件 + `RenameSongUseCase` 改行」，而那个 use case 只更新了 `songs` 行与艺人/专辑索引，**没管按 uri 建索引的歌词 FTS 表和 `song_custom_tag_keys`** —— 改完名的歌会从歌词搜索和自定义标签筛选里消失。桌面端把两者放进 `SongFileRepositoryImpl`：一次 `inTransaction` 里改 `songs.uri`/`filePath`/`fileName`/扩展名/排序键、删旧 uri 的 FTS 行并按新 uri 重建、把 `song_custom_tag_keys` 从旧 uri 迁到新 uri、重建索引、刷新文件夹计数；事务抛异常就把文件改回原名（库与磁盘不允许以不一致收尾）。`SongFileRepositoryTest` 同时钉住「新 uri 有 / 旧 uri 必须为空」两半。
- **`AudioFileAccess.delete`/`move` 抛异常而不返回布尔/null**：SAF 版返回 `false`/`null`，调用方只能报「失败」并丢掉原因（是锁定、只读、还是名字被占）。桌面版让异常穿到仓储，仓储据此区分 `NameConflict` / `Failed` 并记日志；存在性判断交给调用方先调 `exists()`。
- **仅改大小写的重命名在 Windows 上是合法路径而不是重名冲突**：NTFS 不区分大小写但保留大小写，`track.mp3` → `Track.mp3` 用 `Files.exists` 判断会看“目标已存在”（其实是同一个文件）。所以先比 `SongPaths.identityKey`，同文件时走「旧名 → 临时名 → 新名」两步移动，让大小写真的落到目录项上，再 `toRealPath()` 回读真实拼写写进 `songs.uri`（不能凭请求的名字写库）。`SongFileRepositoryTest` 钉住，包括「临时名已被上一次中断的改名占用时不覆写、也不失败」。已知残留：若在两步之间进程被杀，文件会留在临时名下（名字含 `.lyrico-rename-tmp`，可人工改回）；库里那行仍指向旧名，下次扫描会发现文件丢失。建库时不做恢复逻辑，因为代价（每次启动扫目录）大于收益。
- **同名（完全一模一样）的重命名不特殊处理**：仓储容忍它（走完两步移动 + 一次事务），Android 的 UI 层已经用 `fullNewName != song.fileName` 拦住了。不在这里做短路，是因为“返回成功但库里没变”会让 `Success.song` 里的 `dbUpdateTime` 说谎。
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
