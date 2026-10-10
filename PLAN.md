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

### P4 的 UI 轨道（基础设施批次，2026-10-09）

viewmodel 层只剩被 P5 叶子挡住的部分后，按 advisor 结论**转向 UI 轨道**——这不是放弃用户选的顺序，而是把用户选的顺序走完：剩下的 viewmodel 全是 P5 规模的活（WorkManager 替代、quickjs 运行时、SAF 删除），继续清它们等于提前开工 P5。

这一批不接任何业务界面，只把「桌面应用能起来」的四块地基铺好，并**每块都用真跑证明**：

| 地基 | 桌面化要动什么 | 证据 |
| --- | --- | --- |
| `platform/AppDirectories.kt`（新增） | 用户裁决「便携优先」：`<exe>/data`；`Program Files` 下不可写则回落 `%LOCALAPPDATA%\Lyrico`；`-Dlyrico.data.dir` 覆盖一切且**显式路径写不进去就抛异常**（不静默换地方）。Android 的 `Context.filesDir`/`cacheDir` 全部由它取代 | `AppDirectoriesTest` 10 项：可写安装目录→便携、不可写安装目录（父路径是普通文件，真实不可创建）→回落、显式覆盖、显式覆盖不可写→抛、`prepare()` 后**真的用 `openLyricoDatabase` 建库并插一行**（Room 是懒连接，只有查询才能证明路径可用） |
| `di/DesktopAppModule.kt`（新增，不搬 `di/AppModule.kt`） | Koin 本来就是 Android 端的 DI（`org.koin.androidx.compose.koinViewModel` → `org.koin.compose.viewmodel.koinViewModel` 是**同名换包**），所以 screens 的调用点一行都不用改。新模块只登记**已搬入 kotlin 树**的 22 个绑定 + 8 个 viewmodel，`Context` 换成注入的 `AppDirectories`，`BuildConfig` 换成 `BuildInfo` | `DesktopAppModuleTest` 5 项：真启动 Koin + 真数据目录，逐个解析 22 个绑定、断言单例同一性、**通过图里的数据库写真行并落盘到 `databaseFile`**、两个 DataStore 各自成文件 |
| `ui/theme/Theme.kt`（搬入） | **零改动**。原以为要改的 `androidx.compose.material3.ripple` + `LocalIndication` 其实只是「桌面 classpath 上从来没有 material3」：加上 `implementation(compose.material3)` 后逐字编译通过。22 个 screen/component 用了 material3 的少量 API（`TextButton`×12、`ButtonColors`×8、`Text`×3 等），Miuix 桌面构件不会传递带进来 | 编译通过 + P2 的 `PortStatusShellTest` 窗口渲染测试 |
| `data/network/NetworkLoggingInterceptor.kt`（搬入） | 只有 `android.util.Log` → `PlatformLog`。不搬就是**静默降级**：HTTP 失败不再进应用日志，用户查问题时看不到网络错 | `compileKotlin` + 已有的网络层测试共用 |

顺带记录两条「看起来像桌面缺能力、其实是 classpath 缺声明」的经验（与 `lifecycle` 那条同类，值得一起记住）：**`compose.material3` 在 CMP 桌面工程里不会因 Miuix 或 Compose 传递而来，必须显式声明**；`androidx.lifecycle` 的 `ViewModel` 同理。

`port-frontier.py` 的判定同时被这次搬动复核：`ScaffoldPadding.kt`（挡住 31 个文件）与 `blur/BarBlur.kt`（挡住 14 个）**逐字编译通过**，说明这两个「大闸门」是**搬运而不是重设计**；搬完后全树 **198 个文件：79 可搬 / 119 被挡**。

本批全量 **423 项 0 失败 0 跳过（43 个测试类）**，其中新增 20 项（`AppDirectoriesTest` 10 / `DesktopAppModuleTest` 5 / `UpdateManagerTest` 5）。

**下一步（screens 批次）的三个已定/待定项：**

- **导航（✅ 已定，证据见 §6 风险 3）**：**Compose Destinations 在桌面端不可用**（发布物是 `aar`，无 `jvm` 变体），因此手写一层**只覆盖用到的 API 面**的薄适配：`NavDirection`（每个 screen 一个 `data class`，带 `route` 模板）+ `Navigator`（`navigate(direction)` / `popBackStack()` / `navigateUp()`）+ `ResultRecipient`/`ResultBackNavigator`（4 个 screen 用的「回传结果」，走 `SavedStateHandle`），底层用 `org.jetbrains.androidx.navigation:navigation-compose:2.9.2`（**有 `desktopApiElements` jvm 变体**）而不是自己造回退栈——`NavBackStackEntry` 天然就是 `ViewModelStoreOwner`，`koinViewModel()` 每个路由各存一份的状态语义与 Android 一致。26 个 screen 的 `@Destination` 注解删除、`DestinationsNavigator` 参数换成 `Navigator`；**`navigator.navigate(XxxDestination(...))` 这 41 处调用形状不变**，因为 `XxxDestination` 由我们提供同名 `data class`。

- **布局裁决（✅ 已定）**：nav rail vs 底栏改按**宽度**判定（`maxWidth >= 840.dp` 用左侧 rail，窄窗口回落底栏），替换 Android 的 `maxHeight < 520.dp`——那是「横屏手机」规则，在 1180×780 的桌面窗口上会选中底栏（等于手机上屏）。
- **首个 screen**：`AppLogScreen`（前沿里**零阻塞**，且是完整功能页：日志列表/筛选/导出/保留策略），先做它可以把「Koin + 主题 + 真实数据库 + 文件保存对话框 + 剪贴板」这条链一次跑通；它需要的 `ScaffoldPadding`/`BarBlur` 已就位。随后是 `ArtistSplitSettingsScreen`（同样零阻塞）与曲库首页三栏（需先搬 `SongListViewModel`，`UpdateManager` 已就位）。

### P4 的 UI 轨道（应用外壳 + 导航适配层 + 首个真页面，2026-10-09）

上一批铺完地基后，这一批把「应用真正起来」的三件事接上：**入口外壳**（`Main.kt` 启动序列 + `LyricoDesktopApp`）、**导航适配层**（替代不可用的 Compose Destinations）、**首个真实业务页面**（`AppLogScreen`）。

#### 1. 导航适配层（手写，替代 Compose Destinations）

`ui/navigation/` 四个文件，只覆盖 26 个 screen 实际用到的 API 面：

| 文件 | 内容 |
| --- | --- |
| `NavDirection.kt` | `interface NavDirection { val route: String }` + `interface Navigator { navigate/popBackStack/navigateUp }` |
| `Navigator.kt` | `NavControllerNavigator`（`internal`）+ `@Composable rememberNavigator(controller)` |
| `Destinations.kt` | 各路由的 `NavDirection` 实现（`AppLogsDestination.ROUTE = "app_logs"`）；`data object`（调用点写光名字）与 `class`（调用点写 `X()`）两种形态**按调用点原样保留**，这样 41 处 `navigator.navigate(XxxDestination(...))` 一行都不用改 |
| `LyricoNavHost.kt` | `NavHost` + Android 原有的四段左右滑转场（`AnimatedContentTransitionScope<NavBackStackEntry>` API 桌面端逐字可用）。**临时起始路由是 `app_logs`**，曲库首页那批换成 `library_home` |

底层是 `org.jetbrains.androidx.navigation:navigation-compose:2.9.2`（`desktopApiElements-published` 变体）。**没有自造回退栈**：`NavBackStackEntry` 本身就是 `ViewModelStoreOwner`，所以 `koinViewModel()` 每个路由各存一份实例、退栈即清理，与 Android 的语义一致——这条是 26 个 screen 的共同前提，`NavigatorTest` 用真 `NavHost` 把它钉死（见下）。

**lifecycle 家族对齐**：同时声明 `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.6` 并**删掉 Google 的 `androidx.lifecycle:lifecycle-viewmodel-compose`**。理由不是偏好而是 classpath 事实：JetBrains 的 `-desktop` 构件是**薄层**（`lifecycle-runtime-compose-desktop:2.9.6` 声明依赖 `androidx.lifecycle:lifecycle-runtime-compose:2.9.4`），两套坐标属于同一家族、解析出同一个实现；若让 Google 的 2.11.0 与 Navigation Compose 的传递版本并存，就会出现同一批 `androidx.lifecycle` 包的两份实现，谁赢取决于 classpath 顺序。stock CMP 1.12 工程正是对齐到 JetBrains 家族。

#### 2. 文件保存接缝（`platform/FileSavePicker.kt`）

`fun interface FileSavePicker { suspend fun pick(defaultFileName: String): File? }` + `rememberFileSavePicker(title)` 的真实实现（AWT `FileDialog(null as Frame?, title, FileDialog.SAVE)`）。**真实实现就是 Windows 原生对话框，不是假货**；做成接缝的原因是它是整条导出链里唯一测试驱动不了的一环，而其余部分（选哪些 id、写到哪个文件、写什么字节、之后弹什么提示）全都要留在被测代码里。

`AppLogScreen` 据此从 Android 的「SAF `launcher.launch()` + `pendingExportIds` 暂存」改成「`scope.launch { picker.pick(...); viewModel.exportLogs(target, ids) }`」——**`pendingExportIds` 这个可变状态被删掉**：Android 需要它是因为 SAF 回调是异步的，桌面端协程自己 await 选文件，id 留在局部变量里就不可能过期。

#### 3. `AppLogScreen` 的桌面化改动（逐条）

`@Destination` / `DestinationsNavigator` → `Navigator`；`koinViewModel` 换包（调用形状不变）；SAF 保存 → 上面的接缝；剪贴板 → `LocalClipboard.setClipEntry(ClipEntry(StringSelection(...)))`（`ClipEntry` 类整体带 `@ExperimentalComposeUiApi`，opt-in 要加在**函数**上而不是单个调用点上）；`event.message.resolve()`（挂起）替代 `asString(context)`，放在 `LaunchedEffect` 里；`BuildConfig` → `BuildInfo`。保真处：私有的 `Set<Long>.toggle(id)` 在 Android 端就是死代码，照原样留着。

#### 4. 测试两层（共 7 项新增）

`NavigatorTest`（2 项）用**真 `NavHost`** 而不是 `LyricoNavHost`，因为一条测试不该因为「某个 screen 恰好还没搬」而红：

- `navigate` 入栈、`popBackStack` 逐层退栈、`navigateUp` 后退（有转场动画，所以断言写「目标页存在」而不是「旧页不存在」）；
- **起始路由上 `popBackStack()` 返回 `false` 且不抛异常**——`LibraryHomeScreen` 正是读这个 `false` 决定「再返回就是退出应用」；
- **每个 back stack entry 各有一份 viewmodel**：首访 1 个、入栈新路由 2 个、同路由再入栈 3 个、连退两层后**下面那层的实例没被重建**（仍渲染 `a-serial-0`）、再入栈又是新实例（4 个），并且所有实例的序号互不相同。这组断言如果换成「拿同一个单例」，26 个 screen 会在访问之间互相串状态。

`AppLogScreenTest`（5 项）用**真 Koin + 真 Room（磁盘上的库）+ 真资源**：

- **走 `LyricoNavHost()` 渲一帧**：真路由 + 真 viewmodel + Room 流式出来的行——这一项证明的是「Koin → NavHost → screen → viewmodel → 数据库」整条桌面启动链，而不是单个 composable 能画出来。
- 导出：注入的 picker 记录建议文件名并返回一个真文件，断言**文件真被写出来**且内容含那一行与诊断头（应用名/版本）；
- 取消保存：picker 返回 `null`，断言**没有留下任何文件**、列表里的行仍在；
- 删除：点删除图标 → 等 Miuix 确认窗出现 → 点确认 → 断言行不但从列表消失、**也从数据库里没了**（前半句只能证明「列表过滤掉了」，两句一起才说明删除真的落库）；
- 返回：断言调了一次 `popBackStack`。

两条 API 形状坑（都是编译/运行期直接报错，值得记下）：`runComposeUiTest`（v2）的 `waitUntil` 签名是 `(conditionDescription: String?, timeoutMillis: Long, condition)`——把超时写成第一个参数会得到 `actual type is 'Int', but 'String?' was expected`；`AppLogRepository.getLatest()` 是**挂起返回 `List`**，不是 `Flow`。第三条是竞态教训：`navigate()` 之后立刻读「新建了几个 viewmodel」会读到旧值（新 entry 在下一帧才 compose），断言必须**先等目标页的语义节点出现**再计数——第一版就是这么红的。

#### 5. 真实窗口取证（两张截图 + 可机读分析）

`./gradlew :lyrico-app:run` 真跑，`scripts/capture-window.ps1 -TitleLike "Lyrico 1.6"` 抓窗口自身矩形：

| 取证 | 文件 |
| --- | --- |
| 空状态 | `docs/port-evidence/p4-applog-empty.png`（+ `.analysis.txt`） |
| 库里真有行时的列表 | `docs/port-evidence/p4-applog-rows.png`（+ `.analysis.txt`） |

肉眼之外的客观校验交给新脚本 `scripts/analyze-window-capture.py`（`--compare` 可两图对照）：像素色数、最多色占比（**一整块单色 = 截到了「首帧还没画」的窗口**）、文字暗像素数、主题主色/暖色像素数，以及 96×28 的 ASCII 密度图。实测两图都是 1166×773、最多色占比 0.65/0.79（不是空板），文字像素 6420 → **13106**、主色蓝 160 → **2110**、暖色 329 → **1269**（等级 chip 的琥珀/红），两态差异 **13618 px（窗口的 1.51%）**——即「同一个窗口在库里有行/没行时画出的东西确实不同」，而不是同一张空图。

**这一批踩到的取证坑**：截图必须等**首帧画完**。第一次抓图时窗口的 HWND 已经存在（`IsWindowVisible` 为真、矩形正确）但还没绘制，`CopyFromScreen` 于是把窗口后面那个终端的内容拍了进来（大片墨绿色），标题却完全匹配。现在 `-SettleMs` 用 5000–6000 ms，且分析脚本会明确判 `SUSPECT`（最多色占比 > 0.9 或文字像素 < 200）。另一条仍有效的经验：`-TitleLike` 别写宽——终端窗口标题里也含 `Lyrico-Desktop`。

关于「鼠标可操作」的**如实边界**：截图证明的是「窗口画出了数据库里的真实行」（颜色/文字像素随库内容变化），点击行为（导出、删除确认、返回）由无头测试派发**真实 Compose 指针事件**证明；两者合起来覆盖了 P4 门禁，但我没有做「脚本模拟点击真实窗口并回读界面」这一步。

#### 6. 本批结果

- 全量 **427 项 0 失败 0 跳过（44 个测试类）**（上一批 423/43）；新增 7 项（`NavigatorTest` 2 / `AppLogScreenTest` 5）。
- `lyrico-app/.gitignore` 增 `/data/`：开发运行会把便携数据目录解析到工程目录（`AppDirectories.defaultInstallDir()` 用 `user.dir`），它是运行产物、不进仓库。
- 前沿重测：`python scripts/port-frontier.py` → java 树 **194 个文件 / 0 陈旧副本 / 77 可搬 / 117 被挡**。
- 复现命令：`./gradlew :lyrico-app:test --rerun-tasks`；单类 `./gradlew :lyrico-app:test --tests "com.lonx.lyrico.screens.AppLogScreenTest"`；窗口 `./gradlew :lyrico-app:run` + `scripts/capture-window.ps1 -SettleMs 6000` + `python scripts/analyze-window-capture.py <png> --write`。

下一批（曲库首页 wave）：`useNavigationRail` 由 `maxHeight < 520.dp` 改为 `maxWidth >= 840.dp`，起始路由换成 `library_home`，并补上 `LibraryHomeDestination` 与 `SongListViewModel` 一侧的页面依赖。

### P4 的 UI 轨道（封面取图链路，2026-10-09）

曲库首页的 109 文件闭包里，第一块能独立验证的是**封面图**：`SongListItem` 的 `AsyncImage(model = CoverRequest(...))` 依赖它，而它自己又依赖 Coil 的桌面接线。这一批把整条链路搬完并证明它真的解码出图。

#### 1. 搬了什么（`git mv` + 小改）

| 文件 | 桌面化改动 |
| --- | --- |
| `ui/components/CoverRequest.kt` | `android.net.Uri` → `String`（字段仍叫 `uri`：schema 的标识列就是 `songs.uri`，41 处调用点不用改名） |
| `ui/components/cover/CoverImage.kt` | 去掉 `androidx.core.net.toUri`，`(uri ?: "").toUri()` → `uri ?: ""`；其余逐字 |
| `ui/components/cover/ArtistPosterSource.kt` | 逐字 |
| `utils/coil/ArtistPosterMatcher.kt` | 逐字（纯函数，海报文件名归属规则） |
| `utils/coil/AudioCoverKeyer.kt` | 缓存键里 `data.uri.path ?: toString()` → `data.uri` |
| `utils/coil/AudioCoverFetcher.kt` | `ContentResolver.openFileDescriptor` → `AudioTagReader.readPicture(File(path).toPath())`；`Factory` 不再需要 `contentResolver` |
| `utils/coil/ExternalArtistPosterReader.kt` | `SafDocuments.children` → `File.listFiles()`；`BitmapFactory` 的合法性校验 → Skia `Image.makeFromEncoded`（与 Coil 同一个解码器）；`.mp4` 海报不再抽视频帧（桌面进程内没有视频解码器），改为读它的内嵌封面，读不到就跳过并在本文件注释里写明 |
| `utils/coil/DesktopImageLoader.kt`（新） | Android 在 `App : SingletonImageLoader.Factory` 里建 loader，桌面没有 Application 钩子，改为启动时显式装一次；缓存目录用 `AppDirectories.coverCacheDir` |

Coil **3.6.2** 是 multiplatform 的（`coil-compose-jvm` 真实存在），所以取图代码本身不用改写，只换文件访问那一层。`PosterFileFetcher.kt` 仍留在 java 树：它的 model `ArtistPosterFile` 定义在还没搬的 `ArtistPosterFoldersViewModel` 里，属于海报文件夹页面那一批。

`buildImageLoader(context, cacheDir)` 与 `installImageLoader(cacheDir)` 拆开，是为了让测试能驱动**应用真正安装的那套组件**（keyer + fetcher + 50MB 磁盘缓存），又不必跟进程级单例抢缓存目录。

#### 2. 取证（7 项，共 46 类 434 项全绿）

`CoverPipelineTest`（6 项）全部走真文件、真 TagLib、真解码，断言**解码后的像素尺寸**而不是「发出过请求」——每张生成的封面尺寸互不相同且非方图：

- 内嵌 `FrontCover` 经 TagLib 写入 flac 后，取图链路解出 **37×23**；
- `bladeenc.mp3` 无内嵌图 → 必须是 `ErrorResult`（而不是一张空图）；
- 无内嵌图时用海报文件夹里的 `Some Artist.png` → **41×17**；
- 海报文件夹里「名字完全匹配」的那个文件是坏的 → 仍取到次优的 `Some Artist_b.png`（证明 `validArtwork` 的跳过逻辑没丢，坏文件不会顶掉好文件）；
- 同一个 loader 在封面被改写、mtime 前进 5s 后解出 **61×19** 而不是缓存的 37×23（证明 keyer 用 mtime 失效缓存）；
- `installImageLoader` 装出来的**单例** loader 能解出内嵌封面（证明生产接线本身）。

`CoverImageTest`（1 项，无头 `runComposeUiTest`）钉「没有图源时立刻显示占位图标」这条规则：不发起请求、不留空白方块。

#### 3. 后果

`CoverImage` 目前还没有调用点（`SongListItem` 还没搬），这是刻意的：先让依赖可编译、可验证，再动列表项。下一批把 `SongListViewModel` / `SongSelectionViewModel` / `SongListItem` / `SongsPage` 接上。

### P4 的 UI 轨道（歌曲列表项与列表状态，2026-10-09）

封面链路之后，这个文件闭包里第二块能独立验证的是**歌曲列表本身**：一行 `SongListItem` 加驱动它的 `SongListViewModel`。页面外壳（`SongsPage` 的顶栏、选择模式、底部导航）留到下一批，所以**这批不出窗口截图**：没有页面就没有可截的界面，而「无头渲染 + 真库状态」正好覆盖了这批真正写的代码。

#### 1. 搬了什么（`git mv` + 小改）

| 文件 | 桌面化改动 |
| --- | --- |
| `ui/components/song/SongListItem.kt` | 去掉 `@SuppressLint("DefaultLocale")`（Android lint 注解在桌面不存在）与两处 `LocalView.performHapticFeedback`（桌面 Compose 没有 `LocalView`，也没有振动马达）；`R.drawable.ic_album_24dp` → `Res.drawable.ic_album_24dp`；`song.getUri` → `song.uri`；`painterResource` 换成 `org.jetbrains.compose.resources` 的那个 |
| `ui/components/song/{SongListSectionHeader,SongListEmptyState,SongListItemActions}.kt`、`ui/components/song/LibraryScanProgressText.kt`、`ui/components/library/LibraryEmptyState.kt` | 只有文案资源的系统性转换（`@StringRes Int` → `StringResource` + `stringResource` 导入，`scripts/migrate-strings-res.py`），共 9 个文案 |
| `ui/components/Painters.kt`（新） | `rememberTintedPainter`/`TintedPainter`/`RoundedRectanglePainter` 从 Android 的 `PainterUtils.kt` 里分出来。**故意不叫 `PainterUtils.kt`**：同名文件会同时存在于两棵树，正是 `port-frontier.py` 报「陈旧副本」的形状；Android 专有的 `getBitmap`/`saveBitmap`/`getSystemWallpaperColor` 留在 java 树 |
| `viewmodel/SongListViewModel.kt` | `SongInfo` 去掉 `Parcelable`/`@Parcelize`（它从不是导航参数 —— 26 个目的地的参数全是 `String`/`String?`/`Long`，`SongInfo` 只由 `EditMetadataViewModel` 建出来放在 UI 状态里）；`android.util.Log` → `PlatformLog`；`addSafFolderAndRefresh(path, treeUri)` → `addFolderAndRefresh(path)` |
| `composeResources/drawable/ic_album_24dp.svg`、`ic_arrow_up_24dp.svg`（新） | Android 的 `<vector>` XML 在桌面 `painterResource` 下**根本不解码**（CMP 只认光栅与 SVG）。新脚本 `scripts/migrate-vector-drawables.py` 把 `viewportWidth/Height` → `viewBox`、`pathData` 逐字搬、`#AARRGGBB` → `#RRGGBB` + 对应 `*-opacity`、`evenOdd` → `fill-rule`，遇到 transform/渐变/颜色引用直接拒绝而不是猜。只转这批用得到的两个，剩下 4 个（星标/关闭/信息）留给用到它们的批次 |

`addFolderAndRefresh` 是这批唯一的行为变更点：Android 必须把 SAF 授权（`treeUri`）持久化在文件夹行旁边，桌面路径按名字就能读，所以文件夹表的 `treeUri` 留 null，picker 只交一个目录。

#### 2. 取证（11 项：无头渲染 5 + 状态层 6；全量 48 类 445 项全绿）

`SongListItemTest`（5 项，无头 `runComposeUiTest`）：

1. 一行里同时出现标题、艺术家、`· 专辑`、`MP3` 徽标、`3:30` 时长与 `320kbps` 码率。时长断言取代了被删掉的 `@SuppressLint("DefaultLocale")`：那句注解的意思是「某些区域设置会格式化出非 ASCII 数字」，删注解等于做了一处行为声明，而这个断言才是那处声明。
2. 封面槽确实按这首歌发起请求（`AsyncImage` 的 contentDescription 是标题）—— 这条防的是「整库封面静默消失」。
3. **转换出来的 SVG 占位图标真的解码**：断言 `painterResource(Res.drawable.ic_album_24dp).intrinsicSize == Size(24f, 24f)`。`painterResource` 不抛异常**不等于**图真的画出来了（空 painter 也有类型），尺寸才是「SVG 解码器跑过」的证据。
4. 没有艺术家时回落到「未知艺术家」文案（文案本身在组合里读出来，避免区域设置相关的断言）。
5. 点击打开歌曲、长按**只**进入选择模式（长按不再顺手把歌打开）。

`SongListViewModelTest`（6 项）跑真 Room + 真 DataStore + 真 FTS 索引：

1. 排序跟着持久化的设置走：先断言标题升序，`onSortChange(DESC)` 后**行真的换序**，同时 `settings.sortInfo` 已落盘。排序键用真 `SortKeyUpdater` 算 —— 直接插入的行都是默认 `"#"`，那样断言会因为错误的原因通过。
2. `hasFolders` 是数据库观察而不是构造期快照：空库为 false，插入一个文件夹后翻 true。
3. 手动刷新把请求交给扫描器。
4. 加文件夹只交路径（`addedPaths == listOf(...)`）：这是 SAF 接线真的没了的唯一证据。
5. 更新检查按设置开关：先量一次「开着」时的延迟，再关掉设置调一次，等 20 倍延迟 + 500ms 断言**没**发请求 —— 先测开着的一侧，是为了让「关着」的断言不是单纯睡一觉。
6. `clearSearch()` 复位关键字类型并保持列表仍是整个库。

**未覆盖并写明**：`SongListViewModel` 的 `searchQuery`/`searchType`/`isSearching` 在 Android 上也是**没有写入方**的 —— `SongsPage` 只调 `clearSearch()`，搜索入口是跳转到 `LocalSearchScreen`，查询归它自己的 viewmodel。`clearSearch` 断言了，但「搜索行为正确」这种话不能写：没有任何调用点能走到那条分支。

#### 3. 两个连带修复（都是既有测试/工具暴露出来的）

- **`SingletonImageLoader` 是进程级的，且第一次 `get` 之后 `setSafe` 会被忽略**。`SongListItemTest` 一渲染 `AsyncImage` 就把单例初始化掉了（测试类顺序不由我们选），于是 C1 那条「装出来的单例 loader 能解出封面」开始红。修法是在它前面补一次 `SingletonImageLoader.reset()`：这条测试要验的是**应用装的那套接线**，不是在跟谁比谁先初始化。
- **`scripts/capture-window.ps1` 现在会拒绝保存错误的像素**。截图走 `CopyFromScreen`，也就是「屏幕上有什么抓什么」；本轮窗口是**最小化**的（从游离 shell 启动 + 无人值守桌面），第一次抓到的是一张 1.34 MB 的终端画面，而分析器仍给了 OK —— 因为颜色分布看不出「这不是 Lyrico」。脚本现在先 `SW_MINIMIZE`+`SW_RESTORE`+`SW_SHOWNORMAL` 重试 5 次，再用 `GetForegroundWindow() == hwnd` 校验前台，校验不过直接报错不落盘（这一轮就是它挡下来的）。**代价如实记录**：这条改动只在「窗口不肯到前台」的环境里被执行过，有人值守的桌面上没重跑过；它的失败模式是「明明能截却报错」，不是「悄悄存错图」。

#### 4. 前沿

`python scripts/port-frontier.py` → java 树 **180 个文件 / 0 陈旧副本 / 73 可搬 / 107 被挡**（上一批 194 / 0 / 77 / 117）。

下一批（C3）：曲库首页外壳（`useNavigationRail` → `maxWidth >= 840.dp`、起始路由 → `library_home`、`SongsPage` 本体）。

### P4 的 UI 轨道（选择模式与歌曲操作，2026-10-09）

这批把「长按进入选择模式 → 顶栏 → 单曲操作面板」这条链搬过来：列表项上一批已经能渲染，这批给它接上选择状态、顶栏、菜单/详情/删除/改名四个弹层，以及弹层背后真正干活的两条平台路径（播放、定位文件）。

#### 1. 搬了什么（`git mv` + 小改）

| 文件 | 桌面化改动 |
| --- | --- |
| `data/SharedSelectionManager.kt` | **逐字搬**。它已经是纯内存状态（`selectedUris` / `isSelectionMode` / `swipeAnchorUri`），没有任何 Android 类型；不加不减地搬，是因为它的语义已经够绕（`toggle` 不建立锚点、`deselectAll` 不清选择模式、范围选择成功后才把锚点置空），任何“顺手简化”都会改掉 UI 的手感 |
| `ui/components/song/SongMenuBottomSheet.kt`、`ui/components/base/YesNoDialog.kt` | 只有文案资源的系统性转换（`scripts/migrate-strings-res.py`） |
| `ui/components/song/SongDetailBottomSheet.kt` | `android.widget.Toast` → **`onCopy: (String) -> Unit`**（弹层不再自己写剪贴板，`ClipboardManager`+`Context` 一起删）；`Formatter.formatFileSize(context, size)` → `FileSizeFormatter.format(size)`；`BuildConfig.DEBUG` → `BuildInfo.DEBUG` |
| `ui/components/song/SongActionSheets.kt` | 删掉 `PlayerPickerBottomSheet`（桌面只有系统默认关联，见 §5 已定策略），签名末尾改成 `onPlay` / `onShare` / `onCopy` / `onDelete` / `onRename` 五个回调 |
| `ui/components/bar/AlphaBetSideBar.kt` | 去掉两处 `LocalView.performHapticFeedback`（桌面没有 `LocalView`），签名不变 |
| `viewmodel/SongSelectionViewModel.kt` | **重写**：Android 的 play/share 各自拼一个 `Intent`（`ACTION_VIEW` / `ACTION_SEND` + `Uri` + `Context`）并靠 `ActivityNotFoundException` 判断“没人接”，桌面改成注入 `PlaybackRepository` / `FileRevealRepository`，两个仓储都返回结果类型，viewmodel 只负责把结果翻成文案 |
| `utils/FileSizeFormatter.kt`（新） | AOSP `Formatter.formatFileSize` 的等价物（详情弹层的文件大小行）。**先查证再写**：`formatFileSize(context, sizeBytes)` 实际是 `FLAG_SI_UNITS`（1000 进制），步进条件是 `while (result > 900)`，格式是 `mult == 1 \|\| result >= 100` 走 `"%.0f"`、其余走 `"%.2f"` |
| `platform/FileRevealer.kt`（新） | `interface FileRevealer { fun reveal(file: File) }` + `ExplorerFileRevealer(launcher: (List<String>) -> Unit = { ProcessBuilder(it).start() })`，命令行是 `explorer.exe` + **单个逗号拼接的** `/select,<绝对路径>` 令牌 |
| `data/repository/FileRevealRepository.kt`（新） | `RevealResult` = `Revealed` / `NothingToReveal` / `FilesUnavailable` / `Failed`；定位第一个真实存在的普通文件 |
| `ui/components/bar/SongSelectionTopAppBar.kt`（新） | Android 的顶部栏和 `SongSelectionSupport.kt` 揉在一个文件里，桌面端**故意用新文件名**：同名文件会同时存在于两棵树，正是 `port-frontier.py` 报“陈旧副本”的形状 |

#### 2. 三处行为分歧（都是裁决过的，不是遗漏）

1. **「分享」在桌面上等于「在资源管理器中定位文件」**（用户裁决）。Android 是 `ACTION_SEND` 多选分享；Windows 上“分享到哪儿”没有系统级默认接收方，能落地又对等的动作是在 Explorer 里定位。批量分享取**第一个存在**的文件（已写进 `FileRevealRepository` 的 KDoc）。viewmodel 里方法名仍叫 `share`：接口名不该跟着实现方式改名。
2. **批量 FAB（`SongBatchSelectionActions`，12 个动作）不在本批**（用户裁决“暂不放 FAB，只留单曲操作”）。它依赖批量任务调度器、ReplayGain 运行时与插件运行时，整体推到 P5。**代价如实记录**：C3 的歌曲页会缺这个入口，是用户可见的功能缺口。
3. **失败文案保真**：Android 里 `ActivityNotFoundException` 同时覆盖“文件没了”和“没人能打开”，两者共用一句 `no_player_found`；桌面端 `FileUnavailable` 与 `Unsupported` 都折叠成这一句，`unknown_error` 只留给真异常（异常消息作为参数带上）。`FileRevealRepository` 的 `FilesUnavailable` 同样走 `no_player_found`。

#### 3. 取证（8 类 69 项；全量 56 类 514 项全绿）

| 测试类 | 项数 | 钉住的东西 |
| --- | --- | --- |
| `SharedSelectionManagerTest` | 15 | 选择状态的每一处反直觉语义（`toggle` 不建锚点、`deselectAll` 仍留在选择模式、范围选择后锚点置空、`replaceUris` 对空映射是 no-op） |
| `SongSelectionViewModelTest` | 14 | 真 Room + 真文件 + 真 `SongFileRepositoryImpl`，只为 `PlaybackRepository` / `FileRevealRepository` 注入假实现：播放转发、批量删除（选中的没了、没选中的还在）、重命名后**旧 uri 必须查不到**、失败的 shell 调用带原因上报 |
| `SongActionSheetsTest` | 10 | 每一行菜单点击**落到哪个回调**（四个回调参数类型相同，接错线不会被类型系统发现）；删除确认真的删；改名对话框预填名字、确认时把扩展名接回去、空白名与原名都不提交 |
| `FileSizeFormatterTest` | 8 | `Locale.US` 下的逐字节期望值（含 `950` → `0.95 kB`、`95_000` → `95.00 kB` 两处反直觉值）、AOSP 自己用例的 `12_582_912` → `12.58 MB`、爬到 PB、`1e18` 停在 `1000 PB`、`Locale.GERMANY` 用逗号、负数保留 `-` |
| `FileRevealRepositoryTest` | 7 | 真临时文件 + 记录型 revealer：存在 / 空 / 已删 / 目录 / 首个存在者优先 / shell 抛错 / 含 `..` 的路径先规范化 |
| `SongSelectionTopAppBarTest` | 6 | 全选/取消全选文案随状态翻转、计数文案、`< 360.dp` 时藏起全选按钮、空选择 |
| `SongDetailBottomSheetTest` | 6 | 元数据行都在、**`onCopy` 收到的是裸值**（不是 `label: value`，否则粘出去不能用）、空白值不占一行（同一渲染里另有一行做对照）、没有回调就不画复制图标 |
| `ExplorerFileRevealerTest` | 3 | 命令行**逐字**等于 `listOf("explorer.exe", "/select,${file.absolutePath}")`、相对路径先绝对化、launcher 抛错就向外抛 |

#### 4. 三个实测出来的坑（写下来是因为都会再踩）

1. **`MutableSharedFlow` 在没有 replay、没有缓冲时，挂起的 `emit` 不会交给“后来才订阅”的收集者**。原来的测试是“先发消息、再订阅”，三条消息类断言全红；探针实测（`PROBE-A subscribers=0 (emit already suspended)` → `late subscriber got=null`）确认这不是竞态而是真实语义。改成**先订阅再动作**（就是真实屏幕 `LaunchedEffect { events.collect }` 的形状），并用 `onSubscription { ready.complete(Unit) }` + `ready.await()` 握手——实测该回调触发时 `subscriptionCount` 已经是 1，也就是**注册之后**才回调，所以握手是真的。这样“成功不发消息”这种否定断言才有意义：收集器确实活着。
2. **重命名是先动文件、后改库**，所以“等文件出现”会赢在事务提交之前，读到旧行而误判失败。改成等**库**（`getSongByUri(新路径)?.fileName`）。用一次性探针单独跑仓储拿到 `Success`，才确认代码没问题、是测试在错误的地方等。
3. **AOSP `roundBytes` 的步进是 `while (result > 900)`**：`950_000` 字节不是 `950 kB` 而是 `0.95 MB`（900 kB 是最后一个 kB 值，再大就进 MB）。第一版测试把期望值写成 `950 kB`，是测试错、代码对；现在 `900_000` / `901_000` / `950_000` / `999_000`（→ `1.00 MB`）四个值一起钉住这条边界。

#### 5. 无头渲染 Miuix 弹层是**先证明再依赖**的

这批的测试全部依赖「Miuix 的 `WindowBottomSheet`（`SongMenuBottomSheet` 基于它）与 `YesNoDialog` 能在 `runComposeUiTest` 无头环境里渲染，并且能被 `onNodeWithText` 查到」。这件事用一次性探针测试证实后删掉探针，才把断言写进正式测试 —— 否则“测试通过”可能只是弹层根本没渲染（弹层不渲染时找不到节点会抛错，但写错断言的人更容易以为是自己找错了）。

#### 6. 这批同样不出窗口截图

理由与上一批相同：还没有页面。真正的歌曲列表窗口证据要等 C3 把 `SongsPage` 接上。

#### 7. 前沿

`python scripts/port-frontier.py` → java 树 **173 个文件 / 0 陈旧副本 / 70 可搬 / 103 被挡**（上一批 180 / 0 / 73 / 107）。

“下一批（C3）”见下一节：`SongsPage` 用的 `my.nanihadesuka.compose.InternalLazyColumnScrollbar`（`libraryScrollbarOverlay`）是 Android 专有的第三方控件，先解决它才谈得上搬页面。

### P4 的 UI 轨道（第一个真页面：独立歌曲页 + 首次真窗口取证，2026-10-10）

#### 0. 范围重划：先搬「独立歌曲页」，外壳后置

原 C3 = 曲库首页外壳 + `SongsPage`。搬之前查清 `LibraryHomeScreen.kt` 的 rail 三个 tab 是**硬引用**（`SongsPage` / `AlbumsPage` / `ArtistsPage`），后两者还没搬，外壳搬过来编译不过。所以拆开：先做**能独立运行、用户可见**的歌曲页（自带顶栏/排序/选择/选目录/扫描进度/空态），外壳等专辑、艺人搬完再补，**起始路由那时从 `SongsDestination()` 换回 `library_home`**。

连带一处结构改动：`SECTIONS_ASC/DESC` 与 `TopBarState` 原本写在 `LibraryHomeScreen.kt` 里，抽成 `screens/LibrarySections.kt`，歌曲页才能用这三个符号而不 import 一个编译不过的外壳。

#### 1. 搬了什么

| 文件 | 桌面化改动 |
| --- | --- |
| `screens/library/SongsPage.kt` | `git mv` 到 kotlin 树（同名文件不能同时活在两棵树）+ 小改：`LocalClipboardManager` → `LocalClipboard` + `ClipEntry(StringSelection(...))`（`ClipEntry` 在 CMP 里是 `@ExperimentalComposeUiApi`）；`onShare` 接到上一批的「在资源管理器中定位」 |
| `ui/components/library/LibraryLayoutUtils.kt` | `git mv`，**逐字搬**（22.dp 轨道宽度等布局常量是视觉参数，不改） |
| `screens/LibrarySections.kt`（新） | 见上：从外壳里抽出的 tab/排序符号 |
| `ui/components/library/LibraryScrollbar.kt`（新） | 替代第三方滚动条，见下 |
| `platform/DirectoryPicker.kt`（新） | SAF 目录授权的桌面等价物，见下 |
| `ui/navigation/{Destinations,NavDirection,Navigator,LyricoNavHost}.kt` | 新增 4 个路由声明、route 参数编码器、未注册路由容忍、`startDestination` 变成参数（起始路由可注入，测试才能直接起在 `app_logs`） |
| `utils/logging/PlatformLog.kt` | 加 `resetSink()`（测试要摘掉 sink 且不污染后续用例） |
| `di/DesktopAppModule.kt` | 注册 `SongListViewModel`（桌面共 9 个 viewmodel） |

#### 2. 四处适配决策（都不是偷工，是桌面确实没有对应物）

1. **滚动条**：第三方控件换 Compose Desktop 自带 `VerticalScrollbar` + `ScrollbarStyle`；**保留 Android 的 22.dp 轨道宽度**（`LibraryLayoutUtils` 里的常量），滑块 6.dp / 最小 32.dp，`hoverDurationMillis = 0` 就是 Android `alwaysShowScrollbar = true` 的等价物（0 表示不淡出）。**如实记录丢掉的**：第三方控件能做的「拖动滑块时按滚动位置做范围选择」没有替代品，桌面拖拽只滚动。
2. **选目录**：SAF 换 Swing `JFileChooser`，但**不直接 new 一个**：`DirectoryPicker` 是 `fun interface` + `rememberDirectoryPicker()` 默认实现，测试注入假实现才能断言「点了一次、选的哪个目录」。用 `SwingUtilities.invokeLater` 而不是 `invokeAndWait`——后者在 Compose 的事件分发线程上会死锁。
3. **未搬路由容忍**：`SettingsDestination` / `LocalSearchDestination` / `EditMetadataDestination` **声明在、注册不在**。`NavControllerNavigator.navigate` 只捕获 `IllegalArgumentException`（`NavController` 对未注册路由抛的就是它），记一条 `No destination for route '...'; the screen is not ported yet (...)` 后 no-op。这样歌曲页可以保留 Android 的调用形状，点到未搬页面只是「没反应 + 日志里有据可查」，而不是崩掉或者悄悄什么都不做。
4. **`NavBackStackEntry.arguments` 在桌面是 `SavedState` 而不是 Android 的 `Bundle`**：`entry.arguments?.getString(KEY)` **不编译**，改为 `entry.arguments?.read { getStringOrNull(KEY) }`（`SavedState.getMap()` 在字节码里有，在 Kotlin 元数据里是 internal）。

#### 3. 搭路由时测试抓到的一个真 bug

`EditMetadataDestination.route` 原本写成 `"$PATTERN/$value"`：`PATTERN` 本身已经是 `edit_metadata/{songFileUri}`，再拼一个值进去，生成的 route 永远匹配不上注册用的 pattern。这个 bug 是**「编码 → 生成 route → 反查参数」往返测试**抓到的，不是读代码看出来的。教训：**模板与实例是两个东西**，实例是 `route = "$BASE/${encodeNavRouteArgument(value)}"`。

CJK 路径的编码器是**自己重写的**（`encodeNavRouteArgument`）：Navigation 自带的 `NavUriUtils` 在 Kotlin 元数据里是 `internal`，桌面调不到。字符集照抄 `!'()*-.0-9A-Z_a-z~`，中文走 UTF-8 百分号编码（`中文` → `%E4%B8%AD%E6%96%87`）。

#### 4. 真窗口取证：这次是「跑起来、截窗口、OCR」

无头渲染能证明节点存在，证明不了**画出来的字**。所以这批做了真窗口证据：`./gradlew :lyrico-app:run` 起窗口，`scripts/capture-window.ps1` 截图（1166×773），新脚本 `scripts/ocr-window-capture.ps1`（Windows.Media.Ocr，`zh-Hans-CN`）把像素变成可 grep 的文本。

截之前要先把开发库填上内容，否则截到的是空态：`DevLibrarySeederTest`（**门控**，见 §7）用真 TagLib 写标签、真扫描器扫目录、真 Room 存行，做出 4 首歌（周华健《朋友》《花心》、李宗盛《山丘》、Earth, Wind & Fire《September》，都是 CJK 标签）。

**第一次 OCR 就抓到一个真 bug**（`docs/port-evidence/c3-songs-page.ocr.txt`）：标题行是 `y=48 x=528 :: 歌 曲 (%d)`——用户看到的是占位符本身。这张图刻意留着当「bug 原始形态」的证据。

#### 5. 由这张 OCR 揪出的 CMP 字符串格式化差异（本批最重要的发现）

根因不是我们的代码写错，而是**库的同名 API 语义不同**。`org.jetbrains.compose.resources.stringResource(resource, vararg formatArgs)` 看着和 Android 一样，实际不走 `String.format`：反编译 `components-resources-desktop-1.12.0` 得到路径 `StringResourcesKt.loadString` → `getStringItem` → `StringResourcesUtilsKt.replaceWithArgs(text, args)`，**唯一**的格式化逻辑是正则 `%(\d+)\$[ds]`，也就是只认**位置参数** `%1$s` / `%1$d`：普通 `%d` / `%s` 原样留下，flags/宽度/精度（`%.2f`、`%1$.1f`）完全不认识。生成的 `song_list_title` 资源里也没有 `FormatStringItem`。

清点爆炸半径（每个 locale）：**94 条用位置参数，20 条用普通 `%d`/`%s`/`%.2f`**，全仓没有 `<plurals>`。

**决定：不改那 20 条字符串，改调用方。** 把普通占位符改写成 `%1$d` 也能让库猜对，但那样字符串就不再与 Android 逐字一致，而且 `%.2f` / 宽度 / 精度**在库的模型里根本表达不出来**。所以新增 `utils/FormattedStringResource.kt`：

- `formattedStringResource(res, vararg args)`：composition 里用，`stringResource(res).format(*args)`；
- `formattedString(res, vararg args)`：composition 外用（`UiMessage.resolve()` 这种协程里解析文案的地方），`getString(res).format(*args)`。

并加一个**源码级守卫测试** `StringFormattingGuardTest`：扫描 `src/main/kotlin`，把 `stringResource(...)` / `getString(...)` 里**带参数**、且第一个参数是「`Res.string.` 字面量或同文件声明的 `StringResource` 变量」的调用判为违规并报 `file:line`。是第一版守卫误报了 `SourceRuntimeConfig.getString(key, default)`（本 app 自己的方法）之后才收紧的判定条件；`formattedStringResource(` 靠「前面不能是标识符字符」排除。

守卫随后**又抓出 4 处真违规**（都是本批或前几批已经搬过来的代码，且都在用户可见路径上）：

| 位置 | 内容 | 后果 |
| --- | --- | --- |
| `ui/components/bar/SongSelectionTopAppBar.kt` | `selection_mode_selected_count`（`已选择 %d 项`） | 就是 OCR 看到的那类：选择模式顶栏显示 `已选择 %d 项` |
| `ui/components/song/SongActionSheets.kt` | `dialog_delete_file_content`（`删除“%s”？\n此操作不可撤销`） | 删除确认框把 `%s` 直接显示出来 |
| `utils/UiMessage.kt` | `stringResource(res, *args)` 与 `getString(res, *args)` 两个分支 | 所有带参数的提示文案（扫描失败原因等）都不替换 |
| `ui/components/song/LibraryScanProgressText.kt` | `scan_progress_reading`（`%1$d / %2$d`） | 位置参数本来能替换，**为了统一也改走 helper**（不是 bug，但一个规则比两个规则可靠） |

另外把 4 处**测试侧的 `text(res, *args)` 辅助函数**也换掉了：它们和产品代码用的是同一个坏调用，所以「测试绿 + UI 显示 `%d`」能同时成立——这类测试是在跟 bug 互相印证。

**修复由第二次真窗口截图闭环**（`docs/port-evidence/c3b-songs-page-formatted.ocr.txt`）：同一位置变成 `y=48 x=537 :: 歌 曲 〔 4 ）`，即 `歌曲（4）`，占位符消失、计数正确。

#### 6. 顺手把「库怎么读资源」的另一半也量了（并修掉）

上面那 20 条普通占位符之外，还把「转义与空白」量了一遍（一次性探针，量完删掉）：

- **`\n` 会被转成真换行**（`删除“%s”？\n此操作不可撤销` 读回来是两行，与 Android 一致）；
- **多行 XML body 的缩进不会被去掉**。而 aapt2 会去掉它。判定 aapt2 的规则比「去掉所有空白」更窄的证据就在本仓仓库里：`<string name="batch_task_type_label">Task Type: </string>` 的尾空格是**故意的**——Android 那边是 `stringResource(R.string.batch_task_type_label) + typeLabel`，如果 aapt2 连行内尾空格都去掉，Android 上会显示 `Task Type:Scan`。所以被丢掉的是「**带换行的那段空白**」。
- 按这条规则重写了 4 个 locale 各 2 条（共 8 条）多行 body（`batch_match_stat_format` / `batch_match_duration_format`），并新增测试 `ComposeStringResourcesTest` 钉住两件事：body 不得被换行包裹（`isPaddedByLineBreak` + 样例断言），以及上面的 `\n` 转义事实。
- 这个测试第一版写的是「body 里不许有换行」，**立刻在 `batch_edit_info_content` 上失败**——那条的换行是内容（每条提示一行），不是排版。所以规则收窄成「首尾带换行的空白」，行内尾空格（`Task Type: `）与内容里的换行都放行。

#### 7. 取证（新增 5 类 21 项；全量 61 类 534 项，533 执行 + 1 门控跳过，0 失败 0 错误）

| 测试类 | 项数 | 钉住的东西 |
| --- | --- | --- |
| `screens/library/SongsPageTest` | 7 | 全链路：真 `LyricoNavHost` + 假目录选择器 + 真扫描器 + 真 Room + 真 Flow + 真 `LazyColumn`。断言包含「选择器被点了 1 次」「库里那行的 `uri` 等于夹具的 `toRealPath()`」「标题按真实计数渲染（`歌曲（n）`，注释里写明这是 OCR 抓到的那个 bug）」；扫描用 `waitUntil` 轮询数据库，**不能用 `runBlocking` 包住点击**（composition 里启的协程只在测试线程泵帧时才推进） |
| `ui/navigation/NavigatorTest` | 5（原 2） | 新增「route 编码往返」「未注册路由只记日志不抛」「`startDestination` 注入」；同时删掉了一段重复断言 |
| `utils/FormattedStringResourceTest` | 3 | 用真资源分别验证「库自己的 vararg 格式化**不**替换普通占位符」（防回归的绊线：哪天 CMP 修好了，这条会红）+ helper 的 `%d`、两个占位符的字符串 |
| `utils/StringFormattingGuardTest` | 2 | 守卫本体 + 守卫自己的样例（含 `SourceRuntimeConfig.getString` 不得误报） |
| `utils/ComposeStringResourcesTest` | 4 | 见 §6 |
| `probe/DevLibrarySeederTest` | 1（门控跳过） | 见下 |

`DevLibrarySeederTest` 是**工具而不是断言**，所以默认跳过：它要改写 app 自己的数据目录（`lyrico-app/data`，已 gitignore），只有显式 `-Plyrico.seedDevLibrary=1` 才跑（用项目属性而不是环境变量，是因为把环境变量交给测试 JVM 的是 Gradle 守护进程，不是调用它的那个 shell）。它的意义是让真窗口取证**可复现**：换台机器，跑两条命令就能再截出同样内容的图。

#### 8. 这批的用户可见缺口（如实记录）

1. **没有外壳**：起始路由是 `SongsDestination()`，没有底部/侧边 rail，专辑、艺人两个 tab 不在；`library_home` 依赖它们。
2. **没有设置、本地搜索、编辑元数据三个页面**：路由声明了但没注册，点过去只写日志（见 §2.3）。
3. **没有批量 FAB**：上一批已裁决推到 P5。
4. **滚动条拖动不做范围选择**（见 §2.1）。
5. `stringResource` 的 `%1$.1f`（耗时显示）尚未在真窗口里验证过——批次匹配界面还没搬，等 P5 一起验。

#### 9. 工具改动（都在 `scripts/`，都提交）

- `ocr-window-capture.ps1`（新）：Windows.Media.Ocr + `zh-Hans-CN`，输出 UTF-8 无 BOM（PowerShell 5.1 管道会糊 CJK，所以必须先落盘再读）。
- `capture-window.ps1`：激活窗口改成**确定性**的——`AttachThreadInput` 到当前前台线程 + `BringWindowToTop`，并且把它改成「先 settle，再激活 → 校验前台 → 立刻 `CopyFromScreen`」的紧凑循环（最多 10 次、间隔 200ms），中间不睡（睡就会被别的窗口抢前台，本机就有一个截图工具会抢）。
- `analyze-window-capture.py`：`SUSPECT` 判定从「颜色数少」改成「**平**且**没墨**」的合取（`most_common_share > 0.9 && text_pixels < 200`，或单看 `> 0.97`，或单看 `text_pixels < 200`），浅色主题且有字的页面不再被误判；三张历史截图复检仍为 `OK`。

#### 10. 前沿

`python scripts/port-frontier.py` → java 树 **171 个文件 / 0 陈旧副本 / 69 可搬 / 102 被挡**（上一批 173 / 0 / 70 / 103）。

下一批：`AlbumsPage` + `ArtistsPage`（含它们的 viewmodel 已就绪），然后 `LibraryHomeScreen` 三 tab 外壳 + 起始路由改回 `library_home`。

### P4 的 UI 轨道（曲库三 tab 的前两个：专辑页与艺人页，2026-10-10）

#### 1. 搬了什么

| 新文件（kotlin 树） | 来源（java 树，随本批删除） | 改动 |
| --- | --- | --- |
| `screens/library/AlbumsPage.kt` | 同名 | 见 §2 |
| `screens/library/ArtistsPage.kt` | 同名 | 见 §2 |
| `viewmodel/AlbumActionsViewModel.kt` | 同名 | ReplayGain 行/进度弹层砍掉（§2.3） |
| `ui/components/library/AlbumGridItem.kt` | 同名 | 去掉 `LocalContext`/`Toast` |
| `ui/components/library/AlbumActionBottomSheet.kt` | 同名 | 去掉 ReplayGain 入口 |
| `ui/components/artist/ArtistListItem.kt` | 同名 | `Uri` → 路径字符串，见 §2.4 |

另外：`LibraryScrollbar` 增加 `LazyGridState` 重载（专辑网格用），`Destinations` 声明
`AlbumDetailDestination` / `ArtistDetailDestination`（**声明但不注册**，与 Settings/LocalSearch 同一策略），
`DesktopAppModule` 注册 `AlbumActionsViewModel`（共 12 个 viewmodel）。

#### 2. 四处适配决策

1. **滚动条**：Android 用的 `InternalLazyVerticalGridScrollbar`（第三方库）桌面没有，换成 `LibraryScrollbar`
   的 `LazyGridState` 重载 —— 即 Compose Desktop 自带 `VerticalScrollbar` + `rememberScrollbarAdapter(state)`。
   踩到的坑写在 `LibraryScrollbar.kt` 里：`v2.LazyGridScrollbarAdapter` 是 Kotlin `internal`，不能直接构造；
   而 `rememberScrollbarAdapter` 的 v1/v2 两个重载**在 Kotlin 源码里同名**（v2 只是 `@JvmName` 混淆成
   `rememberScrollbarAdapter2`），靠参数类型（`LazyGridState`）选中 v2。
2. **A-Z 侧栏对网格的吸附**：网格里一个字母的起点必须是「包含该字母的第一行」，所以存的索引要按
   `index % columns` 回退到行首，不能像 `SongsPage` 那样直接用条目索引。
3. **ReplayGain 整块不搬，且不留空壳**：Android 的专辑操作弹层里那一行依赖 `ReplayGainProcessor`/
   worker/`MediaMetadataRetriever`，桌面这批还没有等价链路，所以弹层只有「分享（在资源管理器中定位）」
   与「删除」两项，`AlbumActionsViewModel` 也只有这两个入口。写进 P5 缺口。
4. **封面候选 `Uri` → 文件路径字符串**：`ArtistListItem` 的 `CoverCandidate` 原本要 `Uri`，桌面直接用
   绝对路径（与 `CoverRequest` 同一套约定），去掉了 `LocalContext`。

#### 3. 两个真失败（都不是「重跑就好」）

1. **单夹具的测试全在 30s 超时，三夹具的能过**。不是并发或时序问题：`bladeenc.mp3`（TagLib 自带样例）
   **没有 album / artist 标签**，而扫描器不会给「无专辑标签的歌」建专辑行 —— 于是那些测试其实在测空网格。
   修法是让测试自己写标签：新增 `screens/library/TaggedAudioFixtures.kt`，用真的 `AudioTagRepository`
   （`Overwrite` 模式）把 Title/Artist/Album/AlbumArtist 写进临时库里的副本，并断言 `AudioTagWriteResult.Success`。
   这条坑值得记：**TagLib 夹具的「标签」是数据，不是测试的输入约定**。
2. **Windows 上的 DataStore 写失败**：全量跑时 `saveAlbumGridColumns` 抛
   `IOException: Unable to rename ...\settings.preferences_pb.tmp to ...\settings.preferences_pb`。
   原因是测试用 `waitUntil { runBlocking { settings.albumSortInfo.first() } }` 轮询设置，而**每次都 `first()`
   等于每次重新订阅，每次订阅都会让 DataStore 去读一遍文件**；帧循环 ~16ms 一次，读正好和点击触发的写挨在
   一起，Windows 下「删旧文件再改名」就被打开着的句柄挡住。改为 `SettingsFlowWatcher`（只订阅一次，
   判据读内存标志位），既没这个竞态，也更接近界面自己的行为。

顺带记两条 Miuix / 页面行为，都是这批的断言逼出来的：

- 专辑卡片的一行摘要不是「`2 songs`」而是页面自己的 `buildAlbumSummary`（`歌曲数` + 空格 + `年份`，年份非空才拼），
  所以断言要按页面的规则拼出期望值，再比对**整条摘要**（这样既钉住格式化，也不会因为年份就红）。
- `OverlayIconDropdownMenu` 选完一项**不自动收起**（它用 `summary` 表示选中），再点一次排序图标反而把它收起 ——
  所以「列数」入口就在同一个已展开的菜单里，测试不该再点一次。

#### 4. 取证（新增 3 类 14 项；全量 64 类 548 项，547 执行 + 1 门控跳过，0 失败 0 错误）

| 测试类 | 项数 | 钉住的东西 |
| --- | --- | --- |
| `screens/library/AlbumsPageTest` | 6 | 真 Koin + 真扫描 + 真 Room + 真网格：卡片数与「每张卡片的摘要」（含占位符不得上屏）；长按 → 弹层 → 确认 → **文件真的被删、行真的消失、标题与 snackbar 按真计数重渲**；排序菜单字段齐全且选择落进设置流；点卡片请求 `album_detail` 路由（带参数）；空态 + 刷新按钮 |
| `screens/library/ArtistsPageTest` | 4 | 同上，外加艺人行两行摘要 `%1$d albums · %2$d songs` 必须被替换（`%1$d` 原样上屏的断言单独一条）；空态；点行进 `artist_detail`；排序菜单 |
| `viewmodel/AlbumActionsViewModelTest` | 4 | 真文件 + 真 Room 的删除（文件、索引、`album_delete_success(deleted, total)` 的参数列表）；分享把专辑每一首都交给 `FileRevealRepository`；文件已不在时折叠成 `no_player_found`；空专辑不碰 Explorer。分享用记录的假实现，因为真的会弹出资源管理器窗口 |

#### 5. 这批的用户可见缺口

1. 专辑/艺人的 **ReplayGain** 两项动作没有（§2.3）。
2. 专辑详情页、艺人详情页只有路由，没有界面 —— 本轮仍点到日志。
3. 还是没有外壳：专辑、艺人两个 tab 只能靠 `AlbumsPageTest`/`ArtistsPageTest` 各自挂一个 `NavHost` 访问，
   真正的三 tab 外壳是下一批。

#### 6. 前沿

`python scripts/port-frontier.py` → java 树 **165 个文件 / 0 陈旧副本 / 66 可搬 / 99 被挡**。
本批的 6 个文件原来都在 java 树里，随本批删除（与之前几批 `git mv` 的净效果一致）。

### P4 的 UI 轨道（曲库三 tab 外壳 + 第二次真窗口取证，2026-10-10）

#### 0. 这批把「先搬独立歌曲页」收回来

上一批把 `SongsPage` 当独立页面先搬（起始路由是 `songs`），外壳后置。这批补上外壳：歌曲 / 艺人 / 专辑三个 tab 的
pager + 自适应导航（宽窗口左侧 rail，窄窗口底部 bar），起始路由改回 `library_home`，`SongsDestination` 连同它的注册一并删除。

#### 1. 搬了什么

| 新文件（kotlin 树） | 来源（java 树，随本批删除） | 改动 |
| --- | --- | --- |
| `screens/LibraryHomeScreen.kt` | 同名 | 见 §2 |
| `screens/library/LibraryTab.kt` | 同名 | 去掉 Android 注解 |
| `ui/components/library/LibraryNavigationBar.kt` | `LibraryBottomNavigationBar.kt` | **改名**，一个文件同时放 bar 与 rail |

连带改动（都是 kotlin 树里的既有文件）：`Destinations.kt`（`SongsDestination` → `LibraryHomeDestination`，
起始路由）、`LyricoNavHost.kt`（注册新起始路由、删 `songs`）、`LibraryLayoutUtils.kt`（删掉三个「悬浮条 / 液态玻璃」
helper）、`LibrarySections.kt`（把页内容与分节滚动位置从外壳文件里分出来）、`SongsPage.kt`（KDoc 改成「外壳的第一个 tab」）。

#### 2. 四处适配决策

1. **rail / bar 的判据由「高度」换成「宽度」**：Android 看 `maxHeight < 520.dp` 决定底部 bar（手机竖屏 vs 横屏），
   桌面窗口高度不是主要变量、宽度才是（用户会把窗口拉宽），改成 `maxWidth >= LibraryHomeRailMinWidth (= 840.dp)`
   → 左侧 rail，否则底部 bar。数值不是拍的：主窗口默认 1180×780，rail 要在这个尺寸下成立，同时窄窗口下要还能用 bar。
2. **`LibraryBlurBottomBar` 整块删除**：它依赖 Miuix 的液态玻璃模糊条（`miuix-blur-desktop` 存在，但那条组件的
   交互前提是「内容从条下面穿过」），桌面这批把导航做成了布局的一部分，模糊条没有立足点。删除，写进缺口。
3. **外壳只持有 `SongSelectionViewModel`**：切 tab 要调 `exitSelectionMode()`（与 Android 一致：切走即退出选择模式）。
   各页自己的 viewmodel 仍由各页 `koinViewModel()` 解析，外壳不替它们建实例。
4. **文件必须改名**：java 树里叫 `LibraryBottomNavigationBar.kt`，而桌面这个文件里 bar 与 rail 是一对搭档，
   名字得换；`git rm` 旧文件 + 新文件，净效果与 `git mv` 相同（`LibraryHomeScreen` 的 `Scaffold` 还要把起始内边距
   关掉：`LocalScaffoldIncludesStartPadding provides false`，否则内容被 rail 推两次）。

#### 3. 先量「测试根」，再写几何断言（一次性 probe，已删）

rail/bar 的判据是宽度，而无头测试的根尺寸由测试框架决定，所以先量清楚再写断言：

- `runComposeUiTest` 的根**包住固定尺寸的子节点**，所以 `Modifier.requiredSize(w, h)` 就得到 w×h（用 `size` 会被根的
  约束改写）；`fillMaxSize` 得到 **1024×768**；density = **1.0**，即 dp == px。
- 因此 rail/bar 两个用例的宽度**从 `LibraryHomeRailMinWidth` 派生**（宽 = `+160.dp`，窄 = `−320.dp`），常量将来改了也
  不会让断言漏到另一边；裸 `LyricoNavHost()` 是 1024dp 宽的窗口 → **rail 分支**，所以第 1 个用例天然在 rail 下跑。
- 顺带量到：bar 与 rail **渲染同一组 tab 文案**（Miuix `NavigationBar` 默认 `IconAndText`，`NavigationRail` 也带标签），
  所以「rail 还是 bar」只能用几何判定（标签中心点相对页面内容节点的位置），不能用「有没有标签」。量出的原始数字
  （rail 标签 80×96、bar 标签整宽 64 高）写进了 `LibraryHomeScreenTest` 的类注释，probe 本身删掉。

#### 4. 无头取证（新增 1 类 5 项；全量 65 类 553 项，552 执行 + 1 门控跳过，0 失败 0 错误）

`screens/LibraryHomeScreenTest`（真 Koin + 真扫描 + 真 Room + 真 `LyricoNavHost`）：

| 用例 | 钉住的东西 |
| --- | --- |
| 起始路由就是三 tab 外壳 | 三个 tab 文案齐全 + 歌曲空态 + 「添加文件夹」；另两个 tab 的文案**不存在**（未合成的页不上屏） |
| 点 tab 切页并切回来 | 每次切换都断言「新页在、旧页不在」 |
| 选择模式切走再切回 | 长按进选择模式（计数条 = 1）→ 点艺人 tab → 点回歌曲 tab：**默认顶栏回来了**（选择计数条与排序图标的状态都断言） |
| 宽窗口 → rail | 标签中心点在页面内容左侧 |
| 窄窗口 → bar | 标签中心点在页面内容下方，且仍能切到专辑 tab |

#### 5. 真窗口取证：这次连「点」也验了

前两批的真窗口取证只证明「渲染出来了」。这批要证明三 tab 外壳**能响应**，于是加了 `scripts/click-window.ps1`。
写这个脚本踩到三件事，都写进了脚本注释（下一批还会用）：

1. **截图坐标 ≠ 点击坐标**：`capture-window.ps1` 截的是 DWM **框架**矩形（含 31px 系统标题栏、左右各 1px 边框），
   而 Compose 的命中测试吃的是**客户区**坐标。第一轮点击全错位，表现是「点了没反应」，看起来像 app 的 bug。
   脚本改成用 `ClientToScreen` 取客户区原点，并把两个原点都打印出来（本机：标题栏 31px、左边框 1px）。
2. **Windows 会吞掉「用于激活」的那一次点击**：窗口不在前台时，第一次点击被系统拿去激活，app 收不到。
   脚本先判断窗口是否已在前台，不在就**先花一次点击做激活**，再发用户要的那一次。
3. 点击前必须把窗口带到前台（`AttachThreadInput`，与截图脚本同一套），否则点会落到别的窗口上；而**用来激活的那一次点击
   要打在系统标题栏上**——它是一次真点击，打在内容上就等于替用户点了那个控件（实测把激活点击落在歌曲行上，应用被导航到了
   未移植的 `edit_metadata` 路由，随后真正的长按被忽略，看上去像长按不生效）。有 `-Button longpress -HoldMs 800`。

点击映射（都经 OCR 复核，从「歌曲」起）：客户区 (40, 50) → `歌曲 (4)`、(40, 131) → `艺术家 (3)`、
(40, 209) → `专辑 (3)`。三张留档：`docs/port-evidence/c4c-library-shell.png`、`c4c-artists-tab.png`、`c4c-albums-tab.png`
（各配 `.ocr.txt` 与 `.analysis.txt`）。窗口 1166×773（客户区 1164×741），rail 宽 80px，内容从 x≈154 起。

#### 6. 真窗口这轮看到的东西

- **rail 分支没有底部导航**：整幅 1166×773 截图在 y 650–770 的墨迹图是空白（只有一个孤立像素），确认 rail 分支不叠 bar。
- **三个 tab 都是真数据**：歌曲 tab 从 C3 的四首（`周华健·朋友`×2、`September`、`山丘`）不变；
  艺人 tab 三行、行摘要是 `1 专辑 · 2 首` 这种真计数（`周华健 1 专辑 · 2 首` 与库里 2 首一致）；
  专辑 tab 两张可见卡片、`朋友 / 2 首` 与 `September`。
- **专辑页是 2 列、卡片被拉得很大**（本批最值得记的一条）：卡片底色白 `255,255,255`、页底 `247,247,247`，
  卡片横向 94–619 与 628–1152（各 526px，间距 9px），封面 1:1（≈510px）居中画 26dp 的占位图标（`#666666`，
  因为开发库夹具没有内嵌封面），标题/计数在封面下方（y≈623/643），第三张专辑在窗口下沿之外（第二行从 y≈673 开始；
  网格本体是 `LazyVerticalGrid`，但**本批没有做滚动取证**，脚本只能点不能拖）。
  **这不是回归**：`albumGridColumns` 是 Android 也有的用户设置（默认 2，`coerceIn(2, 4)`），Android 平板横屏同样会这样；
  但因为桌面窗口动辄 1100+ dp，一张 526dp 的封面在桌面上是不可用的默认值 → 记成 P5 的桌面 UX 缺口（自适应列数），
  本批**不**擅自改行为。
- **顺手关掉 C3 留下的一个悬案**：「窗口排序 vs 数据库查询排序是否一致」。开发库的 `titleSortKey` 升序是
  `1_SEPTEMBER` → `1_SHANQIU` → `1_PENGYOU`，窗口顺序（September → 山丘 → 朋友×2）与之一致，两者在这份数据上**重合**，
  所以看不到分歧。这只证明「当前数据集不分歧」，能分辨分歧的数据集（如 `10` vs `2` 前缀、拼音与 CJK 混排）不在开发库里，
  留到 P5 或换数据集时再验。
- **选择模式也在真窗口里验了**（长按 → 切走 → 切回）：`click-window.ps1` 加了 `-Button longpress`（按下 → 等
  `-HoldMs`，默认 800ms → 抬起），在首行歌曲上长按，顶栏变成 `已选择 1 项` + `全选` + `关闭`（居中的 `歌曲 (4)` 消失）；
  此时点 rail 的「艺术家」，页面换成 `艺术家 (3)` 且选择条**随页面一起消失**；再点回「歌曲」，顶栏是 **默认顶栏**
  （居中 `歌曲 (4)`，没有 `已选择` / `全选` / `关闭`）——即选择模式**没有**跨 tab 存活，与无头测试一致。
  三张留档：`c4c-selection-mode.png`、`c4c-selection-tab-switch-artists.png`、`c4c-selection-cleared-after-tab-switch.png`。
  这一步也发现了那个“激活点击”的真实危害：第一次把激活点击落在内容上，它把应用导航到了另一个页面（日志里能看到
  未移植路由 `edit_metadata/...` 的警告），随后的长按被忽略——所以脚本现在把激活点击打在**系统标题栏**上（属于窗口、
  不属于客户区，点了只激活不触发任何界面）。

#### 7. 这批的用户可见缺口（如实记录）

1. 专辑详情页、艺人详情页**仍只有路由没有界面**（点卡片/行只写日志）——与上一批相同。
2. `floatingBottomBarEnabled` / `floatingBarEffect` 两个设置仍然存得住、读得出，但桌面**没有任何渲染效果**（悬浮条已裁，§2.2）。
3. **Esc / 返回键退出选择模式丢掉**：Android 的 `BackHandler` 是 Android 专属，桌面这批没做等价物（选择模式只能靠
   顶栏的关闭按钮或切 tab 退出）。写进 P5 的 UX 缺口，不假装它有。
4. 设置页、本地搜索页仍未注册（路由声明了，点过去只写日志）。
5. 批量 FAB、ReplayGain 两项动作仍缺（上一批的裁决）。

#### 8. 前沿

`python scripts/port-frontier.py` → java 树 **162 个文件 / 0 陈旧副本 / 65 可搬 / 97 被挡**（上一批 165 / 0 / 66 / 99）。
本批的 3 个文件原来都在 java 树里，随本批删除。

### P5 施工批次（插件/QuickJS：运行时 + 源层，2026-10-10）

P5 的顺序由用户裁决：**插件/QuickJS 先做，批量引擎第二**；ReplayGain 的 PCM 解码按 §5 的方案 A（捆绑 ffmpeg
sidecar）留给后面的批次。本批（记作 **C5a**）只做「运行时 + i18n + 宿主 API + 源层 + 安装器 + DI」，取证全部是
**无头真基建测试**（真 DLL、真 zip、真文件系统、真 HTTP、真 Room），因此**不出窗口截图**；5 个搜索 viewmodel 与
搜索页留到 C5b，那批才需要真窗口取证。

#### 1. 搬了什么（`git mv` 22 个文件：19 主 + 3 测试；java 主树 162 → 143）

| 层 | 文件 |
| --- | --- |
| runtime | `QuickJsNative`、`QuickJsRuntime`、`QuickJsHostApi`、`HostApiRegistry`、`HostXmlApi`、`PluginJsRuntime` |
| i18n | `PluginLocales`、`PluginStrings` |
| source | `PluginJsonParser`、`PluginScriptModels`、`ScriptSearchSource`、`ScriptSearchSourceFactory`、`PluginSearchSourceManager`、`SearchSourceProvider`、`SourcePluginInstaller` |
| 附加叶子 | `data/model/plugin/PluginMetadataField`、`domain/SearchSourceConfigApplier`、`utils/PluginFieldPostProcessor`、`utils/SourceConfigDependencyEvaluator` |
| 测试 | `plugin/i18n/PluginStringsTest`、`plugin/source/PluginJsonParserTest`、`utils/PluginFieldPostProcessorTest` |

搬完 `./gradlew :lyrico-app:compileKotlin` 一次通过；`ui/components/plugin/PluginIcon.kt` 是插件家族里唯一留下的
文件，它是 UI，归 C5b。

#### 2. 桌面化改写点（逐条）

1. **QuickJS 加载**：`System.loadLibrary` → `NativeLibraryLoader.load(QuickJsNative.QUICKJS_NG)`（`"quickjs-ng"`），
   三个 `@Keep` 注解**直接删掉**（桌面没有 R8 收缩，为一个注解引依赖不值）。
2. **Base64**：`android.util.Base64` → `java.util.Base64`。映射表在三处调用点各写一次：
   `NO_WRAP` → `getEncoder()`；`URL_SAFE or NO_WRAP or NO_PADDING` → `getUrlEncoder().withoutPadding()`；
   decode 的 `DEFAULT` → `getMimeDecoder()`。三个调用点用 `build/tmp-probe/port-base64.py` 机械核对过，不靠眼睛。
3. **`HostXmlApi` 从 kxml2/XmlPull 重写为 JDK DOM**：桌面构建里**没有** xmlpull/kxml2 依赖（先例是已搬的
   `TtmlDocumentFormat`，用的就是 `DocumentBuilderFactory` + `org.w3c.dom` + `org.xml.sax.InputSource`）。4 个操作
   （`findElements`/`getRootAttributes`/`attrsMatch`/`replaceChildrenByAttr`）的输出形状逐字保留，三处偏差已在
   KDoc 与本批测试里钉住：不再输出 XML 声明；空元素写成 `<tag />`；**属性顺序由 Xerces 决定（按限定名字典序）**。
4. **`PluginLocales` 重写**：Android 版依赖 `Context` + `LocaleList`，桌面版同 FQN 改为 `object`，内部是
   `MutableStateFlow(systemPreferences())`，`systemPreferences()` 取 `Locale.getDefault().toLanguageTag()`；
   `initialize()` / `update(List<String>)` / `update(Locale)` 三个入口保持同名。它是**进程全局**的，测试必须还原。
5. **`AppDirectories` 增两个目录**：`pluginInstallRoot = <data>/plugins/sources`、`pluginCacheDir = <cache>/plugin_cache`。
6. **`QuickJsHostApi`**：`HostAppInfo` 的伴生常量补上 `DEFAULT_PACKAGE_NAME = "com.lonx.lyrico"`（Android 版取自
   `BuildConfig`）。
7. **日志**：`android.util.Log` → `PlatformLog`（一处一行等价替换，不做整文件重排）。
8. **DI**：`di/DesktopAppModule.kt` 新增 `// ---- plugins` 段，源层与安装器的构造依赖（`Json`、`AppLogRepository`、
   目录）全部接上，`SourcePluginInstaller` 与 `PluginSearchSourceManager` 都是单例（后者 `AutoCloseable`）。

#### 3. 取证（新增 9 类 131 项；另有 3 类 25 项从 java 树搬入）

| 测试类 | 项数 | 验什么 |
| --- | --- | --- |
| `QuickJsRuntimeTest` | 17 | **真 DLL**：`eval`、`call` 的 JSON 往返、脚本异常、宿主 API、缓存 TTL、超时、内存上限、关闭后的运行时、两个运行时并存 |
| `HostXmlApiTest` | 17 | 4 个操作 + 属性顺序规则 + 转义 + 往返 + CDATA + XXE 加固 |
| `QuickJsHostApiTest` | 31 | 走 JNI 入口 `call(name, payloadJson)`：base64/URL/xor/压缩/加密/缓存/日志/HTTP/XML/i18n/未知操作 |
| `PluginLocalesTest` | 6 | 偏好来源、`update`、`Locale.ROOT` → `"en"`；还原进程全局 |
| `SourcePluginInstallerTest` | 29 | 真 zip 的安装/更新/降级/限额/路径confine（含 zip-slip）/**逐条错误文案** |
| `PluginSearchSourceManagerTest` | 11 | 每类型过滤与排序、缓存复用与失效、`invalidate`、entry 丢失的跳过与上报、locale 改名的重发 |
| `PluginSourceEndToEndTest` | 5 | **全链路**：真 zip → 安装 → Room 行 → 读 `manifest.json` → `includeDirs` 拼接 → 真 QuickJS → `Platform.http.getText` → 真回环 HTTP → 解析成 `SongSearchResult`；含翻页、502 错误路径、未声明能力不触脚本、lyrics 候选 |
| `SearchSourceConfigApplierTest` | 6 | 真 DataStore：配置按 id 落到源上、无配置给空、多源各给各的、后加入的源也会被配上、改设置会重发、取消 Job 后不再应用 |
| `SourceConfigDependencyEvaluatorTest` | 9 | 条件表达式真值表（含空 `and`=真、空 `or`=假）+ 文档里的 manifest JSON 反序列化 |

共享夹具：`plugin/support/PluginSandbox.kt`（真 `TestLibrary` + 真安装器 + 真文件系统 + zip 构造器，
`install(manifestJson, script, extra, enabled)` 直接落一个可用的插件）；`LocalGitHubServer` 从「更新检查专用」
泛化成通用回环 HTTP 夹具（记录 method/带原始 query 的 path/headers/body，可按路径响应）；`RecordingAppLogRepository`
增加 `entries`（`log()`）记录。

**全量：77 类 / 709 项 / 708 执行 + 1 门控跳过 / 0 失败**（上一批基线 65 类 / 553 项）。

#### 4. 这批实测出来的坑（写下来是因为都会再踩）

1. **zip 夹具的参数错位**。`rawArchive("manifest.json", "source.js", manifest, script)` 这种「名字、内容、名字、内容」
   的写法把内容传进了名字槽，一次搞出 20 个假失败。修法不是「仔细点」，而是**改 API 形状**：
   `pluginArchive(manifestJson, script, extra)` 把 `"manifest.json"`/`"source.js"` 写死在函数体里，调用点**没地方**
   传错；只有目录形状/zip-slip 这类要自己控名字的场景才用 `rawArchive(name, content)`。
2. **安装器限额在实例上，不在 session 上**。`prepareImport` 用一个带小限额的安装器、再用默认安装器
   `installPrepared`，限额就静默失效——生产里安装器是单例所以碰不到，只有测试会踩。测试里必须**同一个实例**跑完两半。
3. **JNI 的 `call` 返回 `JS_JSONStringify(result)`**：JS 函数返回**字符串**会被 JSON 引号包住（`"hi world"`），
   只有返回对象/数组才是裸 JSON。夹具里的脚本函数所以写成 `() => ([...])`（带括号）。
4. **`isEnabledAnywhere = metadataEnabled || lyricsEnabled || coverEnabled`，不含主开关 `enabled`**。所以「关一个类型」
   不会让插件从 `getSources()` 消失，要三个都关；而且关≠卸载（行还在）。
5. **`http.getText` 在 4xx/5xx 上不抛**——它返回响应体。错误路径只能靠**插件脚本自己抛**（例如 `JSON.parse` 一个
   HTML 体），本批的错误用例就是这么构造的。
6. **`SupervisorJob` 上 `join()` 永不返回**。测试里写「取消后不再生效」时，我本能地 `scope.coroutineContext.job.join()`，
   而一个独立的 `SupervisorJob` 永远不会终止，于是整个测试套挂死 15 分钟。正确写法是 `job.cancelAndJoin()`
   （join 被取消的那个 Job）。
7. **`MutableStateFlow` 相同值不重发**。用 `.value = listOf(sameInstance)` 想模拟「列表重发」是**不会**触发的
   （StateFlow 做过 `equals` 去重）。改成「用户改了设置 → 设置流重发 → combine 重发」，这也更接近真实场景。
8. **`PluginConfigDependency` 的 JSON 形状是 `{"and":{"conditions":[...]}}` / `{"not":{"condition":{...}}}`**，
   不是 `{"and":[...]}`。文档（`docs/plugins/manifest.md`）就是这么写的，作者序列化器也这么实现——是我第一版测试
   猜错了形状。已经把文档里的样例直接搬进测试。
9. **别把 Gradle 输出接进管道**。`./gradlew ... | grep ...` 在构建结束后**不会**因为构建结束而返回：守护进程持有
   从客户端继承的 stdout，管道 EOF 迟迟不来，shell 会话就一直挂着（本批因此白等了两个 15 分钟超时）。改成
   `> build/tmp-probe/run.log 2>&1` 再 grep，`--tests` 过滤过的运行也不会漏掉失败信息。

#### 5. 这批的用户可见缺口（如实记录）

1. **插件管理页与搜索页仍未注册**：本批只做到「源能被安装、能被构建、真能联网搜出结果」，用户在界面上**还看不到
   任何插件**——那是 C5b（5 个搜索 viewmodel + `SearchResultsScreen`/`SearchLyricsScreen`/`SearchCoverScreen` +
   插件管理页 + 真窗口取证）。所以这批的成果只在测试里可见。
2. 本批不碰 ReplayGain 与批量任务（§5 方案 A 留给后面的批次），也不碰 `SettingsScreen`/`LocalSearchScreen`
   （仍只有路由没有界面）。
3. `HostXmlApi` 的三个偏差是**有意保留**的：XML 声明不再输出、空元素写成 `<tag />`、属性按限定名排序。插件的
   `attrsMatch` 是子集匹配、不依赖顺序，所以行为无差异，但**字节级不一致**这件事必须写在这里而不是假装没有。

#### 6. 前沿

`python scripts/port-frontier.py` → java 树 **143 个文件 / 0 陈旧副本 / 61 可搬 / 82 被挡**（上一批 162 / 0 / 65 / 97）。
本批有 19 个文件原来在 java 主树里，随本批删除（另有 3 个测试文件从 java 测试树搬入）；插件家族只剩 `ui/components/plugin/PluginIcon.kt`（C5b）。
被挡的大头仍是批量任务引擎（`worker/BatchTaskWorker.kt`、各 `worker/processor/*`）与 SAF/URI 家族。

### P5 施工批次（搜索与插件界面：5 个 viewmodel + 5 个页面 + 结果回传契约，2026-10-10）

C5a 结束时，插件「装得上、搜得出、解析得对」全部有真基建测试，但**用户在界面上看不到任何插件**。本批（记作 **C5b**）
补上那一半：5 个搜索 viewmodel + 5 个页面（搜索结果 / 搜歌词 / 搜封面 / 插件管理 / 插件配置）+ 4 个新接缝，注册 5 条路由，
并给出真窗口取证。

#### 0. 这批的页面**没有已发布入口**，这是设计，不是遗留

这 5 个页面在 Android 上的调用方是 `EditMetadataScreen`（搜歌词 / 搜封面 / 搜索结果的结果接收方）与 `SettingsScreen`
（插件管理入口），两者都还在 java 树里。所以本批的页面**只能**通过「开发起始路由」`-Dlyrico.start.route=<route>` 到达：
那个开关不是临时脚手架，而是这批唯一的入口形式，真窗口取证也全靠它（`build.gradle.kts` 把 Gradle 属性 `-Plyrico.start.route`
转发成 app 进程的 `-Dlyrico.start.route`，因为 Gradle 自己的 `-D` 落在守护进程上、不会到应用）。

#### 1. 搬了什么（`git mv` 19 个文件；java 主树 143 → 124）

| 层 | 文件 |
| --- | --- |
| 页面 | `SearchResultsScreen`、`SearchLyricsScreen`、`SearchCoverScreen`、`PluginManagerScreen`、`PluginConfigScreen` |
| viewmodel | `SearchViewModel`、`LyricsSearchViewModel`、`CoverSearchViewModel`、`SearchSourceConfigViewModel`、`PluginViewModel` |
| 叶子 | `data/model/search/LyricsSearchResult`、`ui/components/bar/SearchBar`、`ui/components/base/ActionBottomSheet`、`ui/components/base/PillButton`、`ui/components/base/YesNoBottomSheet`、`ui/components/lyrics/LyricRenderConfigBottomSheet`、`ui/components/lyrics/LyricsPreviewPane`、`ui/components/plugin/PluginIcon`、`utils/MusicMatchUtils` |

另有 4 个**没有 Android 对应物**的新文件：`ui/navigation/ResultBackNavigator.kt`（结果回传契约的发送半）、
`ui/components/MiuixMarkdown.kt`（markdown 渲染桥）、`utils/RemoteImageSize.kt`（封面尺寸探测）、
`platform/FileOpenPicker.kt`（打开文件对话框接缝）。搬完 `./gradlew :lyrico-app:compileKotlin` 一次通过。

#### 2. 桌面化改写点（逐条）

1. **结果回传契约自己实现**。`ResultBackNavigator<LyricsSearchResult>` 是 Compose Destinations 生成的类型，而它没有 JVM 产物
   （同 `NavDirection`）。替代实现：值写进**上一个返回栈条目**的 `SavedStateHandle`（键 `resultKey(route) = "<route>:result"`），
   再 pop 自己。三条实测事实写进了该文件的 KDoc：JVM 的 `SavedStateHandle` 存任意对象（`SavedStateHandleProbeTest` 钉住
   `LyricsSearchResult` 的 `Set`/`Map`/enum 往返是**同一实例**，所以不做序列化）；上一层的 handle 活得比本层长；接收方必须
   **观察 `getStateFlow`** 而不是读一次（结果可能早于接收方组合完成）。起始路由没有上一个条目 → 记警告、不抛。
   **接收半等 `EditMetadataScreen` 批次**，本批只有发送半 + 契约测试。
2. **路由参数一律 query 参数 + 自定义百分号编码**。`search_results?keyword=`、`search_lyrics?title=&artist=&album=&date=`、
   `search_cover?keyword=`；`encodeNavRouteArgument` 只放行 unreserved 字符，所以 CJK / 空格 / `&` / `/` / `+` / `%` 全被转义
   ——真图往返用 `"周华健 朋友 & 朋友/朋友+50%"` 钉住。`plugin_config/{pluginId}` 用**路径**参数（必需）：没有插件 id 这条路由
   就不该匹配。
3. **`Toast` → Miuix `SnackbarHost`**。`PluginViewModel` 的事件语义（`message` + 递增 `messageVersion`）逐字保留，页面用 Snackbar 呈现。
4. **`OpenDocument()`（SAF）→ `FileOpenPicker`**。Android 拿回 content `Uri` 再用 `ContentResolver` 读；桌面在原生对话框里给
   绝对路径，`importPlugin(archivePath)` 用 `File.inputStream()` 读。归档的合法性仍由安装器校验，所以这个接缝**不决定能不能装**。
5. **删掉 `LocalConfiguration` + `PluginLocales.update(configuration)`**（两个插件页面各一处）：Android 用它即时刷新插件文案，
   桌面没有等价物 → 语言变更在下次启动生效。写进缺口。
6. **`compactMode`（紧凑列表开关）只剩内存**：Android 存 `SharedPreferences`，桌面改成 `rememberSaveable`，**不跨启动保留**。写进缺口。
7. **剪贴板**：`ClipData.newPlainText(label, text)` → `java.awt.datatransfer.StringSelection(text)`（单参构造）+ `ClipEntry`。
8. **markdown 渲染换库并加桥**。Android 用只支持 Android 的 `compose-markdown` 的 `MarkdownText`；桌面换成 multiplatform
   renderer，但它的 `markdownColor()`/`markdownTypography()` 默认读 **Material 3** 主题，而本应用是 Miuix 主题，直接用会把
   文字画在错误的底色上。所以 `MiuixMarkdown.kt` 把 `MarkdownColors`/`MarkdownTypography` 从 `MiuixTheme` 映射过去（表格写在
   该文件 KDoc 里）。**两个已知缺口：图片渲染成空白、链接有样式但不可点。**
9. **封面尺寸探测从 `BitmapFactory` 换成 JDK `ImageIO`/`ImageReader`**：流程是「只读 header 拿宽高」而不是解码整图，
   5 秒连接/读取超时，跑在 `Dispatchers.IO`；任何异常返回 `null`（徽标不显示），不抛也不报错。
10. **拖拽排序用 `sh.calvin.reorderable` 3.1.0**（与 Android 同库、同用法：`rememberReorderableLazyListState` + `ReorderableItem`）。
11. **字符串迁移脚本扩展**：`scripts/migrate-strings-res.py` 增加 `R.drawable.*` → `Res.drawable.*` 与
    `androidx.compose.ui.res.painterResource` → `org.jetbrains.compose.resources.painterResource`（这批页面第一次用到矢量资源）。
    本批**没有**新增 `strings.xml` 条目：693 条在 P4 已一次性迁完，这批只是把已有条目接到页面上。
12. **上一批的守卫测出一个既有 bug**：`StringFormattingGuardTest`（P4 引入）第一次扫到这批页面时红了——**17 处**
    `stringResource(Res.string.X, args)` / `stringResource(<StringResource 值>, args)` 在 CMP 1.12 上**不做替换**（该库只替换位置参数
    `%1$s`/`%1$d`，`%s` + 变参是 Android 的行为）。修法是把 callee 换成 `formattedStringResource(...)`：`PluginConfigScreen` 2 处、
    `PluginManagerScreen` 14 处、`SearchResultsScreen` 1 处。定位靠脚本机械扫描 + 复核，不靠眼睛；这是「有了守卫之后，旧批次的
    漏网在前一批之后才暴露」的实例，所以修在守卫**已经存在**的这一批里。

#### 3. 无头取证（新增 6 类 23 项；全量 84 类 / 733 项 / 731 执行 + 2 门控跳过 / 0 失败 0 错误）

| 测试类 | 项数 | 验什么 |
| --- | --- | --- |
| `PluginManagerScreenTest` | 3 | 列表行来自真 Room 行 + 真 `manifest.json`；经真 picker 接缝导入真 zip（本地 `NavHost` + 记录型 picker）；开关写回启用标记 |
| `PluginConfigScreenTest` | 3 | 必填项挡住保存、填好后值真的落进设置存储；markdown 字段经桥渲染出内容；未知插件 id 报「无效的搜索源」 |
| `PluginSearchScreensTest` | 4 | 路由 keyword → QuickJS → 列表（keyword 含 `&`/`%`/空格/CJK）；空结果态；歌词候选点开拿到 LRC 行；封面走真回环 HTTP（37×19 PNG）+ 真尺寸徽标 + 服务端确实收到 `/cover.png` |
| `ResultBackNavigatorTest` | 4 | 结果投递 + pop；起始路由不抛；5 条路由参数往返（CJK/空格/`&`/`/`/`+`/`%`、空串、null）；`resultKey` 命名空间 |
| `RemoteImageSizeTest` | 5 | 真回环 HTTP 上的 PNG / JPEG / GIF 宽高；非图片体 → `null` 且落日志；404 → `null`；不可达主机 → `null` 而不是抛 |
| `probe/SavedStateHandleProbeTest` | 4 | 钉住「类型可以原样运输」这个前提（一次性 probe，已保留：它是结果契约的依据） |

表里是 6 类 23 项，而全量账是 7 类 24 项：第 7 类是 `probe/DevPluginSeederTest`（1 项，`-Plyrico.seedDevPlugin=1` 门控，即第 2 个门控跳过）。所以 77 + 7 = 84 类、709 + 24 = 733 项。

搜索页的 4 个用例**不是**直接调 viewmodel：它们用 `LyricoNavHost(startDestination = DevStartDestination(SearchResultsDestination(keyword).route))`
组合，让**被测的路由串**由出货的 destination 类自己拼并编码；插件则通过出货的 `SourcePluginInstaller` 装进应用自己的 Koin 图
（只有 OS 拥有的接缝被替换）。

#### 4. 真窗口取证（6 张图，各配 `.ocr.txt`）

先写 `probe/DevPluginSeederTest`（`-Plyrico.seedDevPlugin=1` 门控，与 `DevLibrarySeederTest` 同一套做法：真安装器 + 真 zip +
真 Room，脚本用固定列表而不是联网 API，取证不依赖别人的服务器），把演示插件装进应用自己的便携数据目录，并打印三条搜索路由。
然后 `./gradlew :lyrico-app:run -Plyrico.start.route=<route>` 逐条启动 + `scripts/capture-window.ps1` 截图（点开歌词面板那一步
用 `scripts/click-window.ps1`）：

| 图 | OCR 里能核对到的东西 |
| --- | --- |
| `c5b-plugin-manager.png` | `插件管理` 标题、三个类型 tab、插件行 `演示音源 Demo Source`、`元数据/歌词/封面` 能力摘要、`最低 API 5 · 最低宿主 API 4` |
| `c5b-plugin-config.png` | `插件类型：元数据源/歌词源/封面源`、字段组 `基础配置`、`API 地址` 与默认值 `https://example.com/api`、`API Key`；**markdown 字段渲染出标题 `接入说明` + 段落 + 两条 `·` 列表项** |
| `c5b-search-results.png` | 搜索框里是 `朋友 & 朋友`（真路由参数解码）、来源 tab `全部` / `演示音源 Demo Source`、三行 `朋友 & 朋友 · 朋友/花心/山丘`、`加载更多` |
| `c5b-search-lyrics.png` | 三个候选 `山丘 李宗盛 · 朋友/花心/山丘`（证明初始 keyword = `title + " " + artist` 真的到了脚本） |
| `c5b-search-lyrics-preview.png` | 点开候选后的歌词面板：标题 `朋友` + 三行 LRC `[00:01.00]朋友一生一起走` / `[00:05.00]那些日子不再有` / `[00:09.00]一句话 一辈子` |
| `c5b-search-cover.png` | 3 列封面网格、每张带来源标签与标题/艺人/专辑，卡片右上角有尺寸徽标；整图 OCR 读作 `S00 黑` / `5 00 黑 5`，把徽标区域放大 6 倍后读到 `600`（Windows OCR 把 `×` 认成汉字，所以徽标的**精确文本**由无头测试钉（`37×19`）；这里的 `600`/`500` 正是 seeder 写下的两张 PNG 的实际尺寸） |

第一张图里那行 `插件 API 5 · 最低宿主 API 4` 就是 §2.12 修的 17 处之一（`plugin_api_versions_with_value`，两个
`%1$d`/`%2$d`）：修之前 CMP 1.12 不会替掉这两个占位符，窗口里会原样显示 `%1$d` / `%2$d`。所以这张截图同时是那处修复的
真窗口证据，而不是“重跑了一遍看不出区别”。

#### 5. 这批实测出来的坑（都会再踩）

1. **QuickJS 交给脚本的是活对象，不是 JSON 字符串**。`ScriptSearchSource` 把请求序列化成 JSON 递给桥，而桥在调用前把它
   **反序列化成对象**；脚本里写 `JSON.parse(request)` 会得到 `SyntaxError: unexpected token: 'object'`。第一版 4 个用例里 3 个
   超时（超时信息完全不提这件事），靠一个一次性 probe 打印语义树 + `AppLogRepository.getLatest()` 才定位。正确写法：`request.keyword`、
   `request.config`、`request.song`、`request.page`。
2. **`runComposeUiTest` 里不能用 `performClick()` 驱动导航/popBackStack**：会得到
   `IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED'` 与 `Method setCurrentState must be called on the main thread`。
   改成 `runOnIdle { }`。因此 5 个页面都把 `resultNavigator` 收成**构造参数**，测试传记录型假实现；只有「真图导航」用例才用 `runOnIdle`。
3. **桌面 `stringResource` 没有 `id` 参数**：`stringResource(id = X)` 必须写成位置参数 `stringResource(X)`；再加 §2.12 的变参问题，
   规则是「主源码里不允许 `stringResource(res, 变参)`」。
4. **封面结果要先过滤 `picUrl.isNotBlank()`** 才进网格（无图不成卡片）；`c5b-search-cover.png` 里的尺寸徽标就是这个 filter 的输出。
5. **开发数据落点**：`AppDirectories.resolve()` 在开发运行下取的是模块目录旁的便携目录（`lyrico-app/data`，已 gitignore），
   所以 seeder 与应用进程天然共用同一份数据——不需要 `-Dlyrico.data.dir`。
6. **Windows 上 DataStore 改名偶发失败**：一次全量跑里 `SongsPageTest` 的排序用例失败（
   `Unable to rename ...settings.preferences_pb.tmp ... multiple instances of DataStore`），单独跑与随后的全量都通过。记为 Windows 文件锁抖动
   （`stopKoin()` 不会关闭 DataStore），与本批改动无关；再现时先重跑再查。

#### 6. 这批的用户可见缺口（如实记录）

1. 5 个页面**没有已发布入口**（调用方在 java 树），只能用开发起始路由到达，普通用户看不到它们。
2. 结果回传只有**发送半**：搜索页做出选择后值进了 `SavedStateHandle`，但还没有页面去接（等 `EditMetadataScreen`）。
3. markdown 里的**图片不渲染**、**链接不可点**（§2.8）。
4. 插件页文案**不随语言设置即时切换**（下次启动生效）；`compactMode` **不持久化**（§2.5、§2.6）。
5. 封面尺寸探测失败时**静默不显示徽标**（有测试钉住 `null`），与 Android 的静默降级一致，但用户看不到任何原因。
6. `LocalSearchScreen` 不在本批范围（用户裁决），仍只有路由没有界面。

#### 7. 前沿

`python scripts/port-frontier.py` → java 树 **124 个文件 / 0 陈旧副本 / 51 可搬 / 73 被挡**（上一批 143 / 0 / 61 / 82）。
被挡的大头仍是批量任务引擎（`BatchTaskWorker` + 各 `worker/processor/*`，含 ReplayGain）与 `EditMetadataScreen`/`SettingsScreen` 这条链。

### P5 施工批次（批量任务引擎：进程内 runner + 调度器 + 3 个本地处理器，2026-10-10）

Android 用 WorkManager + 前台通知跑批量任务；桌面既没有 WorkManager 也没有通知中心，所以这批（记作 **C6a**）把「谁来跑、跑在哪、中途取消怎么记账、进程被杀留下什么」重新做一遍，并把 3 个**不需要插件、不需要 ffmpeg** 的处理器（`EDIT_TAGS` / `RENAME_FILES` / `CONVERT_LYRICS_FORMAT`）真接到文件上。

范围由用户裁决，拆成四个子批：**C6a** = 引擎 + 3 个本地处理器（本批）；**C6b** = 3 个 match 处理器 + QuickJS 插件夹具；**C6c** = 任务列表/详情界面 + 路由 + 全局进度面；**C6d** = ReplayGain（要 ffmpeg 边车）；**C6e** = 导出处理器（被 `DocumentFile` 挡住）。本批**不出界面**，因此也不出窗口截图：它的证据是「一个从真实 Koin 图取出的任务，真的改了真文件、真数据库」。

#### 1. 搬了什么（`git mv` 13 个文件；java 主树 124 → 108）

| 层 | 文件 |
| --- | --- |
| 引擎 | `worker/BatchTaskScheduler`、`worker/processor/BatchTaskProcessor`、`worker/processor/BatchTaskProcessorFactory` |
| 处理器 | `worker/processor/EditTagsProcessor`、`worker/processor/RenameFilesProcessor`、`worker/processor/LyricsFormatProcessor` |
| 用例 | `domain/song/usecase/BatchEditSongsUseCase`、`PatchSongTagsUseCase`、`OverwriteSongTagsUseCase` |
| 工具 | `utils/FileNameSanitizer`、`utils/FormatParser`、`utils/RenameToken`、`utils/TagField` |

另有 2 个**没有 Android 对应物**的主源码文件：`worker/BatchTaskRunner.kt`（替代 `BatchTaskWorker.doWork()`）、`viewmodel/LyricsFormatConfig.kt`（从 `BatchLyricsFormatViewModel` 里抽出的配置类）。**`git rm` 掉 3 个 Android-only 文件**：`worker/BatchTaskWorker.kt`、`worker/BatchTaskNotification.kt`、`worker/BatchTaskCancelReceiver.kt` —— 它们分别绑死 WorkManager 的 `CoroutineWorker`、`NotificationCompat` 前台通知、`BroadcastReceiver`，桌面一个都不能用；替代物名字不同（`BatchTaskRunner`），沿用 C5a 的先例。搬完 `./gradlew :lyrico-app:compileKotlin` 通过（两次，第二次修掉 `TagField`/`RenameFilesProcessor` 的适配）。

#### 2. 桌面化改写点（逐条）

1. **WorkManager → 进程内队列**。`BatchTaskRunner.run(taskId)` 是 `doWork()` 的直译：装载任务与待办 item → 解析并发 → 逐条 `async(Dispatchers.IO)` + `Semaphore.withPermit` → 逐条记账 → 写汇总日志。去掉 `isStopped`（WorkManager 的取消信号）与 WakeLock，改为 `awaitAll()` 之后取一次 `!currentCoroutineContext().isActive`；`BatchTaskScheduler` 用 `ConcurrentHashMap<String, Job>` 保持 KEEP 语义（同一任务在跑时再 enqueue 直接返回），`workId` 列在桌面存**自己队列的 job id**（列保留，schema 不变）。**一处刻意偏离**：Android 先写 `workId` 再判 KEEP（`isStopped` 分支里会把已经启动的任务的 id 覆盖掉），桌面只在真的要启动时才写。
2. **前台通知 → 界面内进度（本批还没有界面）**。Android 靠通知显示进度、靠通知按钮取消，桌面没有等价物 → 进度只落数据库（task/item 行 + `AppLogRepository` 的 `BATCH` 日志），取消只有 API（`BatchTaskScheduler.cancel`）。**这是本批最大的用户可见缺口**，写进 §5；「要不要一个常驻进度指示器」留给 C6c 裁决。顺带删掉 `getTaskTitle`（9 个任务类型的中文名已由 `BatchTaskType.labelRes` 全覆盖）。
3. **孤儿任务改为启动维护清**。Android 靠 WorkManager 重排已死进程的任务；桌面在 `Main.kt` 第 5 步启动维护里调 `BatchTaskRepository.markOrphanedTasksFailed()`，把上次进程留下的 RUNNING/QUEUED 行置 FAILED（与既有 `BatchTaskRepositoryImplTest` 的崩溃恢复用例同一契约），失败落日志而不是吞掉 —— 因为静默失败正好会留下它要清的那排假「运行中」。
4. **处理器在 `markRunning` 之前解析：缺处理器 → 任务直接 FAILED**。Android 先 `markRunning` 再在 `doWork` 里查处理器，查不到就抛，任务行**永远停在 RUNNING**。桌面把查找提前，缺失时 `markFailed("No processor registered for X")` + ERROR 日志 + 立即返回，`startedAt` 保持 null、item 保持 QUEUED（测试钉住）。**故意偏离**，也顺带把「工厂只注册了 3 种类型」变成一条用户能看见的原因，而不是静默排队。
5. **`PermissionRequired` 分支删除**。`BatchEditSongsUseCase` 里 Android 的「SAF 权限丢了」结果在桌面不可达（直接读绝对路径），连同 `BatchEditResult.PermissionRequired` 一起删；`RenameFilesProcessor` 改为消费 `SongFileRepository` 的 `RenameSongFileResult`（`Success` / `NameConflict` / `Failed`），不再自己拼 SAF 的重命名结果。
6. **`TagField.description` 由 `@StringRes Int` 改 `StringResource`**（`R.drawable`/`R.string` → 桌面资源扩展属性），`BatchTaskType.labelRes` 同理；用户可见的字段名与任务类型名一个字符没动。
7. **`LyricsFormatConfig` 抽成独立文件**（仍在 `com.lonx.lyrico.viewmodel` 包）：引擎要反序列化它，不该依赖任何 viewmodel。字段与 `@SerialName("columnEdits")` 逐字保留，所以**已存在的任务行仍能解析**；java 树里那个 viewmodel 只留一行注释（它还在 java 树，尚未搬）。
8. **DI 与 Android 同形**。3 个处理器各一个 typed single，工厂用 `mapOf(类型 to get<处理器>())` 组装（不是 `single<BatchTaskProcessor>` 绑定）；`BatchTaskRunner` / `BatchTaskScheduler` 各一个 single，调度器跑在应用级 `CoroutineScope` 上。

#### 3. 无头取证（新增 3 类 26 项；全量 87 类 / 759 项 / 757 执行 + 2 门控跳过 / 0 失败 0 错误）

| 测试类 | 项数 | 验什么 |
| --- | --- | --- |
| `worker/BatchTaskRunnerTest` | 15 | 引擎自己的记账（处理器是脚本型的，即被测对象的**协作者**）：空任务 → SUCCEEDED 且 `concurrency=0`；3 条成功 → 计数/`currentFile`/处理器结果落进 item 行；跳过+失败混跑 → 任务仍 SUCCEEDED、WARNING 日志、`status=finished_with_errors`、**跳过原因落在 `resultJson` 列**、失败带栈；**没有注册处理器 → 任务 FAILED 且 `startedAt == null`、item 留 QUEUED、ERROR 日志**；未知/空 taskId 不落日志；**并发表**（`configJson == null` → 1，`{}` → 3，`0` → 1，`2` → 2，`99` → 5 夹紧，坏 JSON → 3）用「同时在场数」量出来；3 种类型的 `configJson` 摘要逐字比对（rename / edit-tags / lyrics-format，另几种类型在工厂里还没有处理器）；长配置值 200 字符截断；坏配置串 → 记 `configParseError=` 但任务仍 SUCCEEDED；**取消两条**（任务 CANCELLED、在跑的那条 FAILED、其余留 QUEUED、WARNING 日志计数） |
| `worker/BatchTaskSchedulerTest` | 5 | 队列语义：跑着时重复 enqueue 不改 `workId`、不重复启动；跑完后再 enqueue 被接受；跑中取消 → CANCELLED，再请求只捞**没启动过**的 item；取消不存在的任务 = no-op；两个任务各自排队互不干扰 |
| `worker/BatchTaskEngineEndToEndTest` | 6 | **真 Koin 图 + 真 TagLib + 真文件 + 真 Room**：编辑标签（文件读回是新标题、库里那一行同步、**没有多出第二行**）；重命名（磁盘上新名存在/旧名消失、行跟随、`resultJson` 记下原始与新路径）；LRC→TTML（**文件标签里**出现 `itunes:key="L1"` 与 `<text for="L1">翻译行</text>`、行同步）；3 个文件按默认并发 3 同时改名（每首都拿到自己标签推出的名字）；跑之前把文件删掉 → 只有那一条 FAILED、其余成功、任务仍 SUCCEEDED 且 WARNING 日志 `failure=1`；完成日志经真 `AppLogRepository` 可读（INFO / `BATCH` / `total=1` / 配置摘要含改动字段） |

端到端那 6 项**不自己构造处理器**：处理器、runner、调度器、三个用例、扫描器与仓储全部从 `desktopAppModule(directories)` 取；扫描走的是 `LibraryScanRepository.synchronize(...)`（与 `LibraryScanManager` 内部同一调用，但可 await，不必轮询数据库）；音频用 TagLib 自带的 mp3 夹具抄进临时目录再打标；根目录行按桌面语义写成 `addedBySaf = true`。路径断言一律对 `toRealPath()` 的规范路径，因为扫描入库的 `uri` 就是它。

#### 4. 这批实测出来的坑（都会再踩）

1. **取消时「在跑的那一条」落 FAILED，不是 RUNNING**。第一版测试按直觉断言 RUNNING，两条取消用例都红了（`expected:<RUNNING> but was:<FAILED>`）。随后写一次性 probe 打印真实行才定下来：任务 CANCELLED、`processed=1, success=0, skipped=0, failure=1`、那条 item `FAILED`、`errorMessage` 是 kotlinx 的取消串（实测 `StandaloneCoroutine was cancelled`），其余 item 留 QUEUED。**没有为了对齐「Android 大概是这样」而加 `catch (CancellationException) { throw e }` 去把状态改成 RUNNING** —— 那是凭猜测造行为；改为把量到的现实写进测试与本节。
2. **上一条的连带后果**：`getPendingItems` 只给 QUEUED+RUNNING，所以**取消后再点一次运行不会重试被中断的那条**，它会一直挂在 FAILED 上；真正清掉它的是下一次启动维护（§2.3）。也就是说「取消」在桌面等于「这条按失败记账，且不会被同一个任务再捞起来」。
3. **Room 的 suspend DAO 在已取消的协程里仍会执行完**：取消之后那笔 `markItemFailed`、`updateProgressFromItems`、汇总日志都真的落了库（probe 直接读出来的）。这条与前两条一起决定了「取消后的任务行长什么样」，也是为什么取消用例能断言计数。
4. **跳过原因存 `resultJson` 列而不是 `errorMessage`**：第一版断言 `errorMessage` 红了（`expected:<No lyrics> but was:<null>`），查 `markItemSkipped` 是 `updateItemStatus(itemId, SKIPPED, resultJson, null, now)` —— 与既有 `BatchTaskRepositoryImplTest` 一致。日志里则是另一条 `SKIPPED <文件名>: <原因>`。
5. **`BatchTaskScheduler` 的 `jobs.remove(taskId, job)` 在协程 `finally` 里，晚于最后一笔行写入**：测试里「看到 SUCCEEDED 就立刻再 enqueue」会偶发被 KEEP 挡掉。改法是重试到 `workId` **变化**（那才是被接受的信号），不是只看状态；日志条数也一样要显式等（`logBatch` 写在 `markSucceeded` 之后）。
6. **脚本型处理器要注册给所有 `BatchTaskType.entries`**：摘要用例会建 RENAME / CONVERT 任务，只注册一种类型会让它们掉进「没有处理器」分支，报一个与用例意图无关的失败。

#### 5. 这批的用户可见缺口（如实记录）

1. **没有界面入口**：任务列表/详情页仍在 java 树，所以本批的能力只能由测试或将来 C6c 的路由触发；普通用户碰不到。
2. **没有进度展示**：进度只在数据库与 `BATCH` 日志里（无通知、无托盘、无常驻指示器）。跑长任务时用户看不到任何东西。
3. **退出即丢**：任务跑在应用进程里，直接关窗口会让在跑的任务消失（下次启动被记为 FAILED `Task interrupted by system`）；没有「优雅停机等任务跑完」。
4. **6 种任务类型还没有处理器**（`MATCH_METADATA`/`MATCH_LYRICS`/`MATCH_COVER`/`SCAN_REPLAY_GAIN`/`EXPORT_LYRICS`/`EXPORT_COVER`）：建了会**立刻 FAILED** 并写明原因（§2.4），不会静默排队。后三个分别等 C6b/C6d/C6e。
5. **取消只有 API**：没有界面按钮，也没有「取消后把中断的那条重试」的语义（§4.2）。

#### 6. 前沿

`python scripts/port-frontier.py` → java 树 **108 个文件 / 0 陈旧副本 / 49 可搬 / 59 被挡**（上一批 124 / 0 / 51 / 73）。批量任务这条链剩下的都在被挡一侧：3 个 match 处理器（要 QuickJS 夹具）、`BatchExportProcessor`、ReplayGain 两个文件，以及 `BatchTaskListScreen`/`BatchTaskDetailScreen`/`BatchEditScreen`/`BatchRenameScreen` 与那 7 个 bottom sheet。

### P5 施工批次（3 个 match 处理器 + 真实 QuickJS 夹具端到端，2026-10-10）

C6a 的三个处理器全部**自给自足**：要写什么由用户选的字段和标签自己决定。**MATCH_METADATA / MATCH_LYRICS / MATCH_COVER 不一样 —— 它们要写的内容来自外部**：插件脚本在 QuickJS 里发 HTTP、解析回包、挑一个结果，处理器再按得分门槛决定「这条结果到底配不配写进用户的文件」。所以这批（**C6b**）的重点不是「把代码搬到能编译」，而是证明**这条链路真的通**：字节级真实的插件 zip → 真安装器 → 真 QuickJS 运行时 → 真回环 HTTP → 真 TagLib 写入 → 真数据库行。

#### 1. 搬了什么（`git mv` 5 个文件；java 主树 108 → 103）

| 层 | 文件 | 行数 |
| --- | --- | --- |
| 模型 | `data/model/ScoredSearchResult` | 10 |
| 模型 | `data/model/metadata/SearchResultApplier` | 160 |
| 处理器 | `worker/processor/MatchMetadataProcessor` | 458 |
| 处理器 | `worker/processor/MatchLyricsProcessor` | 173 |
| 处理器 | `worker/processor/MatchCoverProcessor` | 107 |

#### 2. 桌面化改写点（这批几乎没改，这本身是个结论）

1. **三个处理器里只有 `MatchMetadataProcessor` 需要动，且只动了日志**：`android.util.Log` → `PlatformLog`（1 个 import + 6 个调用点）。`MatchLyricsProcessor` 与 `MatchCoverProcessor` 是**逐字搬过来的（0 行变更）** —— 它们从来没引用过 `Log`、`Uri`、`Context`、`SAF`。这三份代码之所以能这样搬，是因为 C5a/C5b 已经把插件契约（`SearchSourceProvider` / `ScriptSearchSource` / `PluginJsonParser`）和写入契约（`PatchSongTagsUseCase` → `PictureMutationResolver` → `ImageBytesFetcher`）都搬到了桌面侧；处理器本身只是这两个契约的**调用方**。
2. **DI 与 Android 同形**：3 个处理器各一个 typed single（`MatchMetadataProcessor(get(), get(), get(), get(), get(), get())` 等），`BatchTaskProcessorFactory` 的 map 从 3 项扩到 6 项。工厂仍是**部分映射**：`SCAN_REPLAY_GAIN` / `EXPORT_LYRICS` / `EXPORT_COVER` 依然会走「没有注册处理器 → 任务立刻 FAILED」那条路（C6a §2.4 的行为不变，C6d/C6e 才补）。
3. **搬完 `worker/processor` 的 java 目录只剩 2 个文件**：`BatchExportProcessor.kt`（C6e）与 `ReplayGainProcessor.kt`（C6d）。

#### 3. 无头取证（新增 2 类 17 项；全量 89 类 / 776 项 / 774 执行 + 2 门控跳过 / 0 失败 0 错误）

| 测试类 | 项数 | 验什么 |
| --- | --- | --- |
| `data/model/metadata/SearchResultApplierTest` | 10 | 纯逻辑（不走插件）：覆写替换、补充只填空白、**纯空白算空白**、全 DISABLED 不写、空答案永远不清（即便覆写）、插件编造的标准键被忽略、`track_number "3/12"` 按字符串保留、未改动字段不进补丁、`buildPatch` 无变化时等于空 `AudioTagData`、`SongEntity.toAudioTagData()` 的字段映射（含 `trackerNumber`→`trackNumber`） |
| `worker/processor/MatchProcessorEndToEndTest` | 7 | **真 Koin 图 + 真插件 zip + 真 QuickJS + 真回环 HTTP + 真 TagLib + 真 Room**（见下） |

端到端那 7 项**不自己拼夹具对象**：插件是 `ZipOutputStream` 打出来的真 zip，经 `SourcePluginInstaller.prepareImport` / `installPrepared` 真装进 `AppDirectories.pluginInstallRoot` 并落 Room 行；脚本由 QuickJS 真跑，`Platform.http.getText` 打到 `LocalGitHubServer`；插件配置（`baseUrl`）经任务的 `sourceSettings` → `source.applyConfig` 通道下发（**不是测试从后门塞的**）；音频用 TagLib 自带的 mp3 夹具抄出来再打标；任务由真 `BatchTaskRunner` 驱动。7 项分别是：

1. **元数据匹配真的写进文件和数据库行**：插件字段落盘（专辑被填、注释补上），而**没被选为目标字段的标题/艺人原样保留**、库里同步且**没有多出第二行**、请求真的带 `q=` 出去了、插件的调用日志能用 `relatedId` 查回来。
2. **全字段 DISABLED → 跳过（"No fields need processing"），插件一次都没被问**。
3. **插件答不上来 → 跳过（"No match found"），任务仍 SUCCEEDED，文件未被改**。
4. **歌词匹配真的写进文件与行**：先按引擎生成的每条 query 搜，最后才取歌词；文件标签里出现 LRC 内容（对内容断言，不对编码格式断言）。
5. **SUPPLEMENT 模式下已有歌词不被覆盖**（"Lyrics already exist"，且**没花掉一次搜索**）。
6. **封面匹配真的把图下载进标签**：断言读回的 `pictures.single().data` 与服务器发出的 PNG 字节**逐字节相等** —— 证明写进去的是图，不是一个 URL 字符串。
7. **一个插件都没装 → 跳过（"No enabled lyrics source"）、任务 SUCCEEDED、日志是 INFO 且 `skipped=1 / failure=0`**（这是刚装完 Windows 版时的真实处境）。

#### 4. 这批实测出来的坑

1. **v4 契约对「封面结果」比对元数据结果更严**：`PluginJsonParser.parseCoverResults(enforceApi4Contract = true)`（apiVersion ≥ 4）会**丢掉**任何 title / artist / album / **date** / coverUrl 缺一项的条目。第一版夹具只给了 title/artist/album/cover，插件日志写的是 `Plugin cover search returned 0 result(s)`，处理器报 `No reliable cover match`。**这不是报错，是静默不匹配** —— 一个只返回「歌名+封面」的插件在这条链上永远匹配不到东西，且屏幕上只会说「没有可靠的封面匹配」。夹具补上 `year` 后才通；测试里留了注释。
2. **「跳过」的 item 让任务状态仍是 SUCCEEDED，所以断言任务状态等于什么都没验**：封面用例第一版就是这样「绿着红」的 —— 任务 SUCCEEDED、文件一个字没写，直到断言请求路径时才发现只请求了 `/covers`。此后**每条正向用例都断言 item 状态**（失败信息里带上 `resultJson`），任务状态只用来验「跳过/失败不算任务失败」。
3. **歌词匹配会按 `MusicMatchUtils.buildSearchQueries` 逐条搜**（实测 3 次 `/search`），不是一次；所以断言写成「最后一条是 `/lyrics`，之前的都必须是 `/search`」，而不是写死 2 条请求。
4. **写给「无插件」用例的断言必须落在**「处理器在找到歌之后才报 `No enabled lyrics source`」上：`MatchLyricsProcessor` 会先取源、再查歌、再判 plan；所以那个用例必须先真的入库一首歌，否则拿到的是 `Song not found`（另一个诚实但不同的原因）。

#### 5. 这批的用户可见缺口（如实记录）

1. **仍然没有界面入口**：任务只能由测试或将来 C6c 的路由建；批量发起界面（`BatchEditScreen` 那一串）排在其后。
2. **MATCH_* 的行为依赖用户没见过的配置**：`enabledSourceOrderIds` / `sourceSettings` / 分数门槛都落在 `configJson` 里，当前没有任何界面能编辑它们。
3. **`SCAN_REPLAY_GAIN` / `EXPORT_LYRICS` / `EXPORT_COVER` 仍无处理器**，建了会立刻 FAILED 并写明原因。

#### 6. 前沿

`python scripts/port-frontier.py` → java 树 **103 个文件 / 0 陈旧副本 / 48 可搬 / 55 被挡**（上一批 108 / 0 / 49 / 59）。批量任务链上还在被挡一侧的只剩 `BatchExportProcessor`（C6e）与 `ReplayGainProcessor` + `ReplayGainScanner` + `LibEbuR128`（C6d，ffmpeg 边车）。


### P5 施工批次（批量任务历史：列表页 + 详情页 + 两条互跳路由，2026-10-11）

C6a/C6b 让批量任务引擎真的跑起来了，但**用户界面一直缺着**：任务只能由测试建，进度只能从数据库里读。这批（**C6c**）搬的是
「用户看批量任务时看到的东西」——任务历史列表（类型/状态筛选、多选删除、清空、取消）与单任务详情（进度头卡 + 成功/失败/跳过三个 tab + 点一行去元数据编辑器）。

业务逻辑一行没改（两个 viewmodel 逐字搬入），真正新的是三件事：这是移植后**第一对互相跳转的已注册路由**（列表 → 详情 → 元数据编辑器）、
这是**第一次出现「路径参数 + 参数化 viewmodel」**的组合，也是**第一次要在无头测试里对付 Miuix 的分页器与下拉筛选**——
后两件事各自带来一个坑（见 §5），所以这批的重点不是「把代码搬到能编译」，而是证明**这两页在真窗口里长出来的东西**和**点击之后发生的事**都对。

#### 1. 搬了什么（`git mv` 4 个文件；java 主树 103 → 99）

| 层 | 文件 | 行数 |
| --- | --- | --- |
| 页面 | `screens/BatchTaskListScreen` | 439 |
| 页面 | `screens/BatchTaskDetailScreen` | 258 |
| viewmodel | `viewmodel/BatchTaskListViewModel` | 91 |
| viewmodel | `viewmodel/BatchTaskDetailViewModel` | 30 |

另有 3 个**已有文件**的改动（不搬文件，但属于这批）：`di/DesktopAppModule.kt`（两个 viewmodel 的注册，其中一个带 Koin 参数）、
`ui/navigation/Destinations.kt`（两个路由）、`ui/navigation/LyricoNavHost.kt`（两个 `composable(...)` 块 + 文档）。
测试是**新写的**（java 树里没有对应的测试文件），共 2 类 17 项。

#### 2. 桌面化改写点（逐条）

1. **资源：`R.string`（Int）→ Compose Resources 的 `Res.string`（类型化）**，每个字符串一个 import，两页的 import 块差异（列表 41 行、详情 25 行）
   基本都是这个。**带格式参数的字符串必须换 `formattedStringResource`**：列表页 3 处（`batch_task_delete_selected_message`、`batch_match_stat_format`、
   `batch_match_duration_format`）、详情页 3 处（后两个 + `batch_task_detail_progress`）。这条规则 C5a 已经踩过，这次只是同一条规则的第二次应用，
   不再当"坑"记。
2. **图标：`android.R.drawable.ic_menu_close_clear_cancel` 桌面侧不存在**。两页的"取消任务"X 换成 Miuix 的 `MiuixIcons.Close`（`imageVector =`）；
   其余图标本来就是 Miuix 的，只有这一个借了 Android 系统资源。
3. **路由：删 `@Destination<RootGraph>(route=...)`，常量搬进 `Destinations.kt`**：`BatchTaskListDestination.ROUTE = "batch_task_list"`、
   `data class BatchTaskDetailDestination(taskId)`（`BASE` / `PATTERN = "batch_task_detail/{taskId}"`，`route` 走既有的 `encodeNavRouteArgument`）。
   `taskId` 用**路径参数**而不用 query：仓储生成的 id 是 UUID 形状，空段不可能出现，所以 `NavType.StringType` + 必填段，非法路由直接不匹配。
4. **导航器：`DestinationsNavigator` → 桌面自己的 `Navigator`**（`navigate(方向对象)` / `popBackStack()`），跳转那两行逐字保留：
   `navigator.navigate(BatchTaskDetailDestination(task.taskId))` 与 `navigator.navigate(EditMetadataDestination(item.songUri))`。
5. **两个 viewmodel 逐字搬入（`git diff` 0 行变更）**，它们只依赖仓储/调度器/`viewModelScope`，全是 C6a 就搬好的桌面契约——和 C6b 一样，
   这是"契约早就搬完"的结论。新加的只有 DI 两行：`viewModel { BatchTaskListViewModel(get(), get()) }` 与
   `viewModel { (taskId: String) -> BatchTaskDetailViewModel(taskId, get(), get()) }`；详情页取法**保持 Android 原样**
   `koinViewModel(parameters = { parametersOf(taskId) })`（Koin 侧先例是 `AlbumDetailViewModel(albumId)`）。
6. **`TaskDetailTab` 的 `labelRes` 类型从 `Int` 变 `StringResource`**（枚举自带 `R.string` 是不行的），这是唯一一处类型签名改动。

#### 3. 无头取证（新增 2 类 17 项；全量 91 类 / 793 项 / 791 执行 + 2 门控跳过 / 0 失败 0 错误）

| 测试类 | 项数 | 验什么 |
| --- | --- | --- |
| `screens/BatchTaskListScreenTest` | 10 | 真内存 Room + 真 Koin + 真 `LyricoNavHost`；筛选、多选删除、清空、取消，以及"点了要求去哪" |
| `screens/BatchTaskDetailScreenTest` | 7 | 同上，加真 `HorizontalPager` 的三个 tab、取消写库、未知 id |

两个类都**不给屏幕喂状态对象**：数据由 `seedFinished` / `seedRunning` 经真仓储写进真 Room，屏幕自己通过真 Koin 取 viewmodel。
断言里带数据库副作用的都读回数据库（`taskDao().getTask(id)` 的 status 是 `CANCELLED`、被删的行 `getTask` 为 null），
不只看界面消失。17 项的覆盖面：

1. **出货导航图 + 真数据库渲染历史**（`LyricoNavHost(startDestination = BatchTaskListDestination())`）：标题、两条任务的类型行/状态行、进行中任务的 `0/1` 原始进度、
   空状态卡片**不出现**——这一项覆盖的是 `batch_task_list` 的注册本身。
2. **类型筛选收窄、选「全部」恢复**（真 `WindowDropdownPreference`，选完菜单关闭，断言按"重新打开"写）。
3. **状态筛选收窄**（同上，换维度）。
4. **进行中的任务给「取消」、已完成的给「删除」**（同一屏上的两个不同动作）。
5. **删除对话框确认后数据库里那行真的没了**，且**对话中取消不会删**。
6. **取消进行中的任务 → 数据库里状态变 `CANCELLED`**（调度器 `cancel` + `markCancelled`，在测试里是无害的空转）。
7. **清空历史保留进行中的任务**（已完成的被删、活着的还在）。
8. **点一行 → 要求详情路由**（`RecordingNavigator`，参数按 `data class` 相等比较，不比对字符串）。
9. **返回箭头 → 要求 `popBackStack`**。
10. **真导航图走一遍列表 → 详情**：点第一行之后，详情标题出现、`2/2` 进度出现、文件名出现、**列表标题消失**（上一栈项被降级为 CREATED，见 §5.1）、
    返回图标只剩 1 个；点返回后列表回来、详情消失。
11. 详情类 7 项：**头卡统计**（`4/4`、成功 2/失败 1/跳过 1、耗时行；只有活着的任务才给取消）、**每个 tab 只显示自己状态的 item**、
    **空 tab 显示占位**、**取消活任务 → 数据库 `CANCELLED` 且图标消失**、**点一行 → 要求那个文件 URI 的元数据编辑器**、
    **返回箭头 → 要求 `popBackStack`**、**数据库不认识的 id → 渲染空详情而不是崩**。

#### 4. 真窗口取证（4 张图，各配 `.ocr.txt` / `.analysis.txt`）

先给已有的 `probe/DevLibrarySeederTest`（`-Plyrico.seedDevLibrary=1`）加**真批量任务**：4 条覆盖列表行的全部词汇
（等待中 / 成功 / 部分失败 / 失败），item 状态经 `markItemSucceeded` / `markItemFailed` / `markItemSkipped` 写，计数走 `updateProgressFromItems`，
文件名/路径由 `createTask` 从真 `SongEntity` 抄——所以详情页那两行显示的是**真文件的真名字**。然后 `-Plyrico.start.route=<route>` 逐条启动 + 截图：

| 图 | OCR 里能核对到的东西 |
| --- | --- |
| `c6c-batch-task-list.png` | 标题 `任务历史`、筛选行 `任务类型`、4 行任务的 `任务类型 …` / `运行状态 …` / `成功 n 失败：n 跳过：n` / `耗时 0.0s` / 时间戳 `2026-10-11 03:51:28`（4 行是 seeder 建的 4 条真任务） |
| `c6c-batch-task-detail.png` | 标题 `任务详情`、`进度：4/4`、`歌词匹配 · 成功`、统计行、耗时行，tab 行三段文字，列表两行 `2 花心.wav` / `1 朋友.mp3` 及其完整路径（含 CJK 目录名） |
| `c6c-batch-task-detail-failed.png` | 上面那张**点中排 tab 之后**的同一页：两行变成 `1 September.ogg` 及其路径 + **错误原文 `标签写入失败：权限不足`**（这条错误是 seeder 写进 item 的 `errorMessage`）；与上一张的像素差 10341（1.15%），全落在 tab 行与列表区 |
| `c6c-batch-task-detail-row-tap.png` | 点中第一行之后的窗口：OCR 十行与 `c6c-batch-task-detail.png` **逐字相同**（`进度：4/4`、两行文件名都在原位），像素差 86921（9.64%）全部来自被点那一行的**悬停底色**（这张里 `(235,235,235)` 有 80823 个像素，对照图的 `top_colours` 里没有这个颜色）。同一进程的标准输出里有 `W/Navigator: No destination for route 'edit_metadata/H%3A%5C…%5C2%20%E8%8A%B1%E5%BF%83.wav'`——**元数据编辑器还没注册**，所以这一击的效果是"明确记录的退化"，不是崩溃也不是静默（见 §6.2） |

关于第 4 张的**重做实验**（因为第一次取到 11 条同名警告，见 §5.5）：新起一次、同一路由，日志里先是 0 条，
点一次 item 变 1 条、再点一次变 2 条、点空白区（600,600）不变——**1 次点击 = 1 次导航尝试**，与无头测试一致。

#### 5. 这批实测出来的坑（都会再踩）

1. **Skiko 无头测试里，会触发导航的点击必须包在 `runOnUiThread { }` 里**。症状是"测试在 teardown 崩溃"：报错是 `closeScene` 抛
   `IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED'`，而真正的异常被它盖住了。挖到底层是两件事叠加：
   `SkikoComposeUiTest.performClick()` 把指针事件**同步派发在 JUnit 线程上**，而 `LifecycleRegistry.setCurrentState` 有主线程检查
   （`NavBackStackEntry.setMaxLifecycle` → `enforceMainThreadIfNeeded`），于是 `navigate` **半途而废**：背栈项被压进去了但停在 `INITIALIZED`，
   过渡从未开始，teardown 时那个项既没到 CREATED 也没法 DESTROY。修法是 `runOnUiThread { onNode(matcher).performClick() }`；
   并且**不需要** `mainClock.advanceTimeBy`——第一次 `waitForIdle()` 就会把过渡走完。实测（probe，已删）：包起来之后背栈变 3 项，
   详情 RESUMED、列表降为 CREATED，返回一击干净弹出、teardown 退出码 0。**不触发导航的点击不用包**（只写状态的那些是线程安全的），
   但两类测试的工具函数统一包了，理由写在各自的 KDoc 里。
2. **`HorizontalPager` 的 `beyondViewportPageCount = 1` 会组合相邻页**，所以"可见"和"存在"在详情页不是一回事：停在第一个 tab 时树里其实有
   tab 0 和 tab 1 两页（都空时占位符有 **2** 个），而 `onNodeWithText` 取的是**第一个命中**——它可能是屏幕外那一页，于是 `assertIsDisplayed()`
   假失败。两条对策：tab 断言写成"**任一命中都可见**"（对全部命中逐个试 `assertIsDisplayed`），而"未知 id"那项**直接把 2 这个数字当证据**
   （它同时钉住了 `beyondViewportPageCount = 1` 的行为）。
3. **`markOrphanedTasksFailed()` 把 QUEUED 也算孤儿**：seeder 故意留一条"等待中"的任务，窗口一打开它已经是 `FAILED`，错误信息是硬编码英文
   `Task interrupted by system`（`c6c-batch-task-list.png` 里第 4 行 `封面匹配` 的状态就是它）。结论：**应用重启后不存在"活着的"批量任务**；
   而批量任务链目前没有用户入口，也无法在窗口开着的时候新建任务——所以真窗口里**永远截不到"取消"按钮**，这一条只能由无头测试用真数据库钉。
4. **取证脚本的杀进程过滤器必须锚定**（这条是我自己踩的）：`Get-Process | ? { $_.MainWindowTitle -like '*Lyrico*' } | Stop-Process -Force`
   会命中**任何**标题里带 "Lyrico" 的窗口——这台机器上就是工作目录名带 Lyrico 的终端（`zap-oss`，标题 `… - Lyrico-Desktop`），
   实测把终端本身杀了。正确写法是两条约束：`Get-Process -Name java,javaw | ? { $_.MainWindowTitle -match '^Lyrico ' }`（只扫 java/javaw + 标题**以** `Lyrico ` 开头）。
5. **点击脚本在不同前台状态下投递次数不一致**：窗口不在前台时脚本会先点一次 OS 标题栏来激活（否则 `SetForegroundWindow` 会被系统拒绝），
   那一次实测里日志出现 **11 条**路由缺失警告（覆盖全部 4 个 item），而"窗口已在前台"的重做实验是严格的 1 次点击 = 1 条。
   11 条那次没能复现、也没能解释，所以**结论按重做实验写**，同时记下规则：取证点击前先确认窗口已在前台，异常次数不要当结论。

#### 6. 这批的用户可见缺口（如实记录）

1. **仍然没有入口**：Android 从设置页的"任务历史"行进来，`SettingsScreen` 还在 java 树，所以这两页目前只能由开发起始路由到达。
2. **详情页点一行没有目标页**：`EditMetadataDestination` 只在 `Destinations.kt` 里声明、未注册，`NavControllerNavigator` 捕获
   `IllegalArgumentException` 并写 `W/Navigator` 日志（真窗口实测到，见 §4 第 4 张图）。用户看到的是"点了没反应"——这是**明确的退化**，
   不是崩溃，也不是静默（`NavigatorTest` 两侧都钉住了）。
3. **真窗口里看不到"取消"按钮**（原因见 §5.3）：界面行为由无头测试 + 真数据库覆盖。
4. **列表页没有"新建任务"入口**：`BatchEditScreen` / `BatchRenameScreen` 与那 7 个 bottom sheet 仍在 java 树，所以筛选、多选、删除、清空是真的，
   但"发起批量任务"还不是。

#### 7. 前沿

`python scripts/port-frontier.py` → java 树 **99 个文件 / 0 陈旧副本 / 46 可搬 / 53 被挡**（上一批 103 / 0 / 48 / 55）。
批量任务链上还在被挡一侧的只剩 `BatchExportProcessor`（C6e）与 `ReplayGainProcessor` + `ReplayGainScanner` + `LibEbuR128`（C6d，ffmpeg 边车）；
界面侧剩 `BatchEditScreen` / `BatchRenameScreen` / `EditMetadataScreen` 与设置页那一片。

### P5 施工批次（专辑 ReplayGain：ffmpeg 边车 + 响度测量 + 专辑动作面板那一行，2026-10-11）

C4c 搬 `AlbumsPage` 时**故意少画了一行**——「计算专辑回放增益」——理由就写在那一行原来的位置上：`ReplayGainScanner` 还在 java 树，
而响度测量需要一个外部解码后端（Android 用 `MediaExtractor` + `MediaCodec`，桌面上没有对应物），所以那一行连同它的进度面板一起缺席，
并在注释里说明这是"记录下来的缺口"。这批（**C6d**）把整条链补齐：ffmpeg 边车（LGPL 构建，`scripts/fetch-ffmpeg.ps1` 取到 `build/ffmpeg/windows-x64/ffmpeg.exe`）、
`FfmpegAudioDecoder`（把 ffmpeg 的 `pcm_f32le` 流变成回调）、`RiffWaveHeader`（自己读 wav 头拿到真采样率与声道数）、`LibEbuR128`（BS.1770 的 JNI 桥）、
`ReplayGainScanner`（逐曲 + 整专辑测量）、`ReplayGainProcessor`（批量任务的 `SCAN_REPLAY_GAIN`），最后是**那一行本身**、
`AlbumActionsViewModel` 里驱动它的状态机，以及它的进度面板 `AlbumReplayGainProgressBottomSheet`。

这批的难点不在"搬"，在**证明测出来的数字是对的**：回放增益是写进文件、由播放器读走的标签，写错了不报错、只是音量不对，事后无法察觉。
所以取证是两条互相独立的链：(a) 无头侧把桥测出来的 LUFS **和 ffmpeg 自己的 `ebur128` 滤镜对账**，并用算术定量纲
（满量程正弦 −3 LUFS、振幅减半 −6 LU、单声道复制成双声道 +3 LU）；(b) 真窗口侧跑一遍二十首歌的专辑，再把二十个文件**从磁盘读回来**，
逐条核 `目标 − 实测 = 标签`。两条链的证据都落在 `docs/port-evidence/`。

#### 1. 搬了什么（`git mv` 3 个文件 + 4 个新文件；java 主树 99 → 95）

| 层 | 文件 | 行数 |
| --- | --- | --- |
| 搬入 | `utils/LibEbuR128` | 97 |
| 搬入 | `utils/ReplayGainScanner` | 361 |
| 搬入 | `worker/processor/ReplayGainProcessor` | 154 |
| 新增 | `platform/FfmpegAudioDecoder` | 405 |
| 新增 | `platform/FfmpegSidecar` | 99 |
| 新增 | `platform/RiffWaveHeader` | 190 |
| 新增 | `ui/components/library/AlbumReplayGainProgressBottomSheet` | 163 |

三个 `git mv` 的文件里 `ReplayGainScanner` 与 Android 版的相似度只有 54%（解码那一半整个换掉了），另外两个是 33 / 48 行的改写。
`di/DesktopAppModule`（20 行）、`screens/library/AlbumsPage`（24 行）、`ui/components/library/AlbumActionBottomSheet`（35 行）、
`viewmodel/AlbumActionsViewModel`（282 行）是**已有文件**的改动（不搬文件，但属于这批）。非代码部分：`scripts/fetch-ffmpeg.ps1`（127 行，取边车）、
`docs/third-party/ffmpeg-lgpl.md`（77 行，许可证与构建来源）、`.gitignore` 加一行 `/.kotlin`（Kotlin 2.x 的会话目录，不该进版本库）。
测试是**新写的**（java 树里没有对应测试），共 **10 类 83 项**，另有 `screens/AlbumsPageTest` 里新增的 1 项。

#### 2. 桌面化改写点（逐条）

1. **解码后端：`MediaExtractor` + `MediaCodec` → 一个 ffmpeg 子进程。** 命令行的每个参数都是契约（`FfmpegAudioDecoder` 的 KDoc 逐条写了理由）：
   `-map 0:a:0` 是因为带封面的 FLAC 如果直接 `-f wav -`，会把附加图片当视频流 mux 进 WAV 而失败；`-c:a pcm_f32le` 是 `ebur128.dll` 能直接吃的格式；
   **不加 `-ac` / `-ar`**，因为把单声道复制成双声道会让 BS.1770 响度涨 3.01 LU、重采样会动 K 加权滤波器的能量；**不加 `-v`**，因为 `Duration:` 是 INFO 级，
   静音日志等于静默关掉进度上报；`-nostdin` 是因为子进程没有控制台可回答交互提示（stdin 也一并关掉）。
2. **PCM 只能以 direct buffer 交给 JNI，且样本必须落在 index 0。** `ebur128.cpp` 用 `GetDirectBufferAddress` 取地址，堆 buffer 拿到 `null` 之后
   `processDirectNative` 直接返回——**不报错，只是这一块被丢掉**（`sampleCount` 不涨，整首歌测成静音）；而那个地址是 buffer 的基址，不认 position/limit，
   所以不能传切片。两条各有一个测试钉住（其中之一就叫 `a heap buffer measures nothing even though the sample count advances`）。
3. **帧对齐要在 Java 侧自己保证。** 一次 64 KiB 的读取会把立体声 float PCM 的帧切开（65536 不是 6 的倍数），半个帧必须留到下一次读取里拼，
   不能当成完整帧交出去——`PcmFrameAssembler` 就是干这个的，`FfmpegAudioDecoder` 承诺交出的每个 buffer 都是整帧。
4. **`Success.mimeType` 的含义变了**：ffmpeg 报的是**输入**编解码器名（`flac` / `mp3` / `vorbis`），不是 Android 风格的 MIME（`audio/flac`）。
   目前只用于显示，但 EditMetadata 页那张"格式名"表要在搬它的时候按这些名字重写（#36）。
5. **两个错误分支在桌面没有对应物，删掉而不是假装有**：`UnsupportedCodec(mimeType)`（当时是去问平台"有没有解码器"）与
   `CodecException.isAlacIssue`（平台 ALAC 解码器的老毛病）——ffmpeg 既不提供那个问题，也没有那个缺陷。删掉之后
   `replay_gain_error_unsupported_codec` / `replay_gain_error_alac_issue` 两个字符串在资源里**暂时没人用**，留到 #36 重写错误映射时裁决。
   `UnknownMimeType` 保留但降级为防御性：含义从"平台没报 MIME"变成"ffmpeg 报了音频流但没报编解码器"，正常文件走不到。
6. **静音地板 `SILENCE_LOUDNESS_LUFS = -70.0`。** libebur128 对静音返回 `-inf`，而 `"%.2f"` 会写出 `"Infinity dB"`——没有播放器能解析。
   `-70` 正是 BS.1770 的绝对门限，也正是 ffmpeg 自己的 `ebur128` 滤镜对同一个文件打印的值（实测：桥给 `-Infinity`，滤镜给 `-70.0 LUFS`），
   于是按地板写；JNI 桥本身仍报原值，替换只发生在进入 `ReplayGainAnalysis` 的路上。
7. **`ReplayGainProcessor` 的失败信息带上原因。** Android 把任何分析失败都报成同一句 `ReplayGain analysis failed`；桌面有一个**用户能修**的失败——
   边车没装——它的消息里列着探过的目录，吞掉就等于给用户一行"失败"和一个无从下手的谜。`BatchTaskProcessorFactory` 里 `SCAN_REPLAY_GAIN`
   从"没有处理器"变成 `get<ReplayGainProcessor>()`（同一处注释从三种待补类型改成两种）。
8. **资源：`R.string` → `Res.string`，两个带格式参数的走 `formattedStringResource`。** `batch_replay_gain_total_time`（`用时 %.2f 秒`）与
   `batch_replay_gain_success` 若直接用 CMP 1.12 的 `stringResource(res, args)` 重载，`%.2f` 会原样上屏。这条规则 C5a 已经记过，
   `StringFormattingGuardTest` 守着主源码，这次是第三次应用，不再当"坑"记。
9. **进度面板的标题取 `uiState.albumName`，不取页面的 `selectedAlbum`。** Android 就是这么写的，原因也留在面板的 KDoc 里：动作面板在用户点下那一行的
   瞬间就被关掉了，等进度面板上屏时 `selectedAlbum` 已经是 null。所以 `AlbumsPage` 里这两个面板**不在同一个 `selectedAlbum` 块里**，
   面板也把 `allowDismiss` 绑到 `!isCalculatingAlbumReplayGain` 上（测量中不能划走，按钮从"中止"变"关闭"用的是同一个标志）。
10. **"运行的身份"用对象比，不用计数器。** `calculateAlbumReplayGain` 每次建一个新的 `run` 对象，`finally` 里只有 `replayGainRun === run`
    才把 `isCalculating` 置回 false——被新运行顶掉的旧运行不许把新运行的进度标成"已完成"。`cancelAlbumReplayGain()` 则**无条件**发一条
    `replay_gain_calculate_cancelled`（Android 原样），这一点在测试里必须先消费掉那条消息再断言替代运行的成功（见 §5.6）。
11. **面板源码里 Android 留了个缩进 bug**：`LinearProgressIndicator` 之后那段 `Column` 比它的父级多缩进一层（`git show 0bdc498:` 里就是这样），
    桌面版顺手拉平——纯格式、无行为变化，但这正是这个文件 diff 有 306 行的原因之一。

#### 3. 无头取证（新增 10 类 83 项 + `AlbumsPageTest` 新增 1 项；全量 101 类 / 877 项 / 875 执行 + 2 门控跳过 / 0 失败 0 错误）

| 测试类 | 项数 | 验什么 |
| --- | --- | --- |
| `platform/FfmpegAudioDecoderTest` | 13 | 真边车 + 真夹具解码：编解码器名与时长、真 PCM 数值（16 bit 满量程 → float 1.0）、帧数与声道序、进度单调、**取消会真的杀掉子进程**（按 `ProcessHandle` 的子进程判）、非 ASCII 路径、截断文件、命令行的每个承诺 |
| `platform/FfmpegSidecarTest` | 6 | 搜索顺序（启动属性 → 环境变量 → 逐级向上的开发/打包布局）与"一个都找不到时把探过的路径全列出来" |
| `platform/FfmpegStderrSummaryTest` | 8 | 从 stderr 里取的就是**输入**编解码器（不是它被要求写出的 pcm），也能取时长（含超过一小时的）、长 stderr 只留尾部、三种失败分类各归各类 |
| `platform/PcmFrameAssemblerTest` | 7 | 跨读取的帧重组：64 KiB 读进来会切开立体声 float 帧、交给 JNI 的必须是 direct buffer、任意读取尺寸都精确重组 |
| `platform/RiffWaveHeaderParserTest` | 10 | 解析 ffmpeg 写出的 wav 头：奇数字节 chunk 的填充位、extensible float 的 18 字节 `fmt`、拒绝 16 bit pcm（命令行从不要求）、超预算就放弃 |
| `utils/LibEbuR128Test` | 6 | JNI 桥的绝对值：满量程正弦 −3 LUFS、真峰值能抓到采样间峰值而采样峰值抓不到、静音在桥上是 `-inf`、多状态合并成一个节目、空节目保持地板、**堆 buffer 什么也测不到** |
| `utils/ReplayGainScannerTest` | 15 | 桥的上层：**与 ffmpeg 自己的 `ebur128` 滤镜对账**、振幅减半 −6 LU、双声道 +3 LU、整专辑当成一个节目、静音曲目仍得到有限增益、标签文本格式、取消是取消而不是失败、边车缺失带上探过的目录 |
| `worker/processor/ReplayGainProcessorEndToEndTest` | 7 | 批量处理器端到端（真库 + 真文件 + 真写标签）：写入三个标签、第二次运行按参考响度跳过、目标响度变了就重测、没有配置就跳过而不是猜、边车缺失时任务行失败并说明怎么装 |
| `viewmodel/AlbumReplayGainViewModelTest` | 6 | 专辑状态机（真库 + 真文件）：写入计数与进度、中止不留痕迹、解码器每种拒绝各报各的消息、空专辑、运行中二次点击被忽略、**被顶掉的运行不许标记完成** |
| `ui/components/library/AlbumReplayGainProgressBottomSheetTest` | 5 | 面板本身：测量中显示专辑名/百分比/已写数 + 按钮是"中止"；停止后显示"用时" + 按钮变"关闭"；未上报进度时也能渲染 |
| `screens/AlbumsPageTest`（已有类 +1） | 7 | 新增那项是**那一行的端到端**：长按专辑 → 出现「计算专辑回放增益」→ 点它 → 动作面板消失、进度面板上屏 → 真 ffmpeg 跑完 → 报"已写入 2 首"→ **再从磁盘把每首歌的专辑增益读回来**（报告本身不算证据） |

那 83 项里有两项是"对账"性质的，它们是这批的核心：`the measurement agrees with ffmpeg's own ebur128 filter` 把桥的读数与
`ffmpeg -filter_complex ebur128` 的输出直接比，`the absolute scale is anchored by arithmetic and by ffmpeg` 用 1 kHz 正弦的解析值
（满量程 −3.01 LUFS）钉住尺度。没有这两条，"测量"只是一串自洽的数字。

#### 4. 真窗口取证（5 张图，各配 `.ocr.txt` / `.analysis.txt`；其中 4 张另配 `.crop.png` / `.crop.ocr.txt`）

这批的真窗口取证要解决一个**时序问题**：`计算中` 是瞬时状态，原来那三个专辑只有四个很短的夹具文件，一次运行半秒不到，写真的脚本根本来不及截图。
解法不是造假数据，而是**在 seeder 里加一个二十首歌的专辑**：`Various Artists - 演示合集` = 同一份 3.55 s 夹具的二十份拷贝（各带不同标题），
它是真的二十首歌、真的二十次测量、真的时长加权专辑响度，只是足够长到能拍下来；库体积成本 20 × 28 KB。
实测这次运行为 **1.04 秒**（面板自己写的"用时 1.04 秒"），从点击到第一张截图约 0.6 秒（两次 PowerShell 调用各约 0.45 s 启动 + Add-Type），
所以 `计算中` 那一帧落在 **90%** 而不是更早——余量是真实的 0.4 秒左右，如实记在这里，不靠把专辑加到 40 首来"更保险"。
为了不破坏 C6c 那批截图的可复现性，批量任务的歌曲选择走 `taskSongs = songs.filterNot { it.album == DEMO_ALBUM_NAME }`——**代价是 seeder 现在种 24 首歌**，
所以 C6c 之前（4 首 / 3 专辑）的截图描述的是更早一次 seeder 的输出，这一点记在 §6。

| 图 | OCR 里能核对到的东西 |
| --- | --- |
| `c6d-albums-tab.png` | `专辑（4）`——seeder 新加的那个专辑被算进去了，网格出现第二行（`.analysis.txt` 里白色卡片占 81.5%，`grid_rows_with_detail 28/28`） |
| `c6d-album-actions-sheet.png` | 动作面板：`演示合集` + 三行 `计算专辑回放增益` / `分享专辑` / `删除专辑`（+`删除专辑中的所有文件`）。**这一行就是这批搬回来的东西**，它上屏本身就否证了 C4c 的"缺口"注释（`accent_warm` 647 像素 = 删除两行的红字） |
| `c6d-album-replay-gain-progress.png` | **运行中的瞬态**：`计算中`、`90%`、`成功：0`、`0 / 20`、按钮 `中止`、标题 `演示合集`；同一个窗口的页面被 scrim（`(178,178,178)` 占 64.3%）压住，`accent_blue` 3364 像素里有 3180 是进度条 |
| `c6d-album-replay-gain-done.png` | 结束态：`用时 1.04 秒`、`成功：20`、`20 / 20`、按钮 `关闭`、进度条 100%（`accent_blue` 3913 > 上一张的 3364，与 90% → 100% 一致），**并且同一帧里有 snackbar `已写入 20 首歌的专辑回放增益`**（`text_pixels` 28080，对照运行中的 8577——深色 snackbar 底把墨迹计数拉高了） |
| `c6d-album-replay-gain-aborted.png` | **中止路径**：点完那一行之后 0.6 秒内再点 `中止`，得到 `用时 0.64 秒`、`成功：0`、`0 / 20`、进度条 **0%**、按钮回到 `关闭`，snackbar 是 `已取消回放增益计算`——0.64 s 落在 1.04 s 的运行里，说明它真的在测量途中被掐断（`accent_blue` 405 = 只剩空的进度条轨道） |

最后一步是**从磁盘读回标签**（这条链与界面无关，是独立证据）：`docs/port-evidence/c6d-album-replay-gain-tags.txt` 分三段——
(1) 对二十个文件逐个跑 `ffmpeg -i`，每个都打出 `REPLAYGAIN_ALBUM_GAIN: -11.29 dB` / `REPLAYGAIN_ALBUM_PEAK: 0.709146` /
`REPLAYGAIN_REFERENCE_LOUDNESS: -18 LUFS`；(2) 去重后**只有一组标签**（二十份一样，因为二十份是同一份音频，也证明每个文件真的都被测了）；
(3) CLI 侧交叉核对：`ebur128` 给 `I: -6.7 LUFS`、`peak: -3.0 dBFS`、`loudnorm` 给 −6.8；算术核对 `-18 − (-6.71) = -11.29 dB` ✓、
`20·log10(0.709146) = -2.99 dBFS` ✓。文里特意写明这三个标签是**由标签反推** target 与 measured 得到的一致，而不是"应用内嵌库的自证"。

另外记一条这批新增的取证约定：**面板里的小字，全窗口 OCR 读不出来**（`计算中` / `90%` / `用时 …` 在 1166×773 的整窗 OCR 里全都丢了）。
可行的做法是裁 `(250,500,950,773)` 再 2× LANCZOS 放大后单独 OCR，产物是 `*.crop.png` / `*.crop.ocr.txt`，坐标按 `裁切原点 + 值/2` 反推回捕获坐标。
两种产物都进版本库：整窗那张证明"面板在窗口里、页面被压暗"，裁切那张读得出数值，谁也不能单独当证据。

#### 5. 这批实测出来的坑（都会再踩）

1. **新建到 kotlin 树的文件必须同时删掉 java 树里那一份**，否则 `port-frontier.py` 报 `stale duplicates`。这次差点漏掉：
   `AlbumReplayGainProgressBottomSheet.kt` 被写成新文件之后，java 树里的 Android 原版还在，是脚本先喊出来的（`git rm` 之后 java 主树 96 → 95）。
   这条不变量靠脚本兜底比靠人记可靠。
2. **ebur128 的 JNI 只认 direct buffer，而且只认 index 0。** 症状是"测出来是静音、但 `sampleCount` 不为 0"——因为 `GetDirectBufferAddress` 对堆 buffer
   返回 `null`，`processDirectNative` 拿到 `null` 就静默返回。这条写成 §2.2 的规则，并由 `LibEbuR128Test` 里那项"堆 buffer 什么也测不到"钉死。
3. **ffmpeg 的 `-v error` 会连 `Duration:` 一起吞掉**（它打在 INFO 级），于是"静音日志"与"没有进度"是一回事。解码器的命令行因此**不带任何
   `-v`/`-loglevel`**，只带 `-nostdin -hide_banner`。
4. **`ffmpeg -i` 那句 `Estimating duration from bitrate` 警告是正常的**：seeder 用的 mp3 没有 Xing/VBR 头，ffmpeg 只能估时长。
   做证据时不要把它当异常（tags 文件里就那么写着）。
5. **lavfi 的 `sine` 振幅只有 1/8**，不是满量程。想要精确振幅得用 `aevalsrc` 或**手写 WAV 头**——`AudioFixtures` 里的夹具后者居多，
   因为"满量程正弦 = −3 LUFS"这条断言对振幅的精度很敏感。
6. **`cancelAlbumReplayGain()` 无条件发 `replay_gain_calculate_cancelled`**（Android 原样）。所以"中止之后又启动一次、断言第二次成功"的测试
   必须**先把中止那条消息消费掉**，否则会拿旧消息当新运行的结论。这条在测试的 KDoc 里写了。
7. **`kotlin.test` 的 `assertTrue(actual, message)` 是 message 在后**（JUnit 系是反的）。写反了不报错，只是断言失败时打印一串布尔值，
   定位成本很高。
8. **PowerShell 脚本的参数里不要放 CJK 路径**：`powershell -File script.ps1 -Path 中文/文件` 会把参数按 GBK 解出来，脚本收到乱码路径。
   取证时一律用相对 ASCII 路径（`build/tmp-probe/...`），并且 `Get-FileHash` 在 PS 5.1 上对某些文件会报错，需要校验就换 .NET API 或 `pwsh` 7。
9. **`click-window.ps1` 不会在 `-SettleMs` 之后再截图**，所以"点一下 → 稳定后截图"必须是**两次独立调用**（一次 click、一次 capture），
   两次之间就有约 0.9 s 的机器时间——这正是这批 1.04 s 运行余量的来源（见 §4）。要卡准确时间点只能把两次调用都设成 `-SettleMs 0` 再自己算账。
10. **Windows 自带 OCR（zh-Hans-CN）会把数字和标点读成汉字**：`1.6.0` → `1 ℃ 囤`、`集` → `隼`、`(0d4b55b)` → `H45555 ）`。
    这是识别引擎的行为，不是文件编码坏了——提交进版本库的 UTF-8 文本本身是对的（用编辑器看是好的）。所以 OCR 只能当"这一屏大概是什么",
    精确数值要靠裁切放大后重读，或直接不信 OCR、去看像素分析。

#### 6. 这批的用户可见缺口（如实记录）

1. **批量回放增益还没有入口**：`SCAN_REPLAY_GAIN` 的处理器与工厂都注册好了，但发起这种任务的界面（`BatchEditScreen` 与那 7 个 bottom sheet，
   包括 `BatchReplayGainSheet`）还在 java 树，属于 #35。所以现在能"按专辑算"，不能"勾一批歌算"。
2. **新错误文本没有本地化过的映射**：`ReplayGainProcessor` 与 `ReplayGainScanner` 的失败文本现在是英文（`the file has no audio track` 等），
   而 EditMetadata 页的错误映射还在 java 树（#36）；批量任务页显示的是任务行里的文案，可读但不是 zh-rCN 资源串。
3. **边车要手动取**：`scripts/fetch-ffmpeg.ps1` 目前是开发者手动跑的一步，安装包里还没带（#7 打包阶段决定怎么随包分发 LGPL 构建）。
   没有边车时应用**不崩**：专辑那一行会在进度面板里报"探测过的目录"，批量任务行会失败并说明怎么装——这是设计，不是遗留。
4. **写进去的专辑增益目前没人用**：应用自己不做基于 `REPLAYGAIN_ALBUM_*` 的回放增益调整（Android 也不做，属于播放器/系统的活），
   所以这批的验证方式是"标签读得回来且算术对"，不是"听起来音量对了"。
5. **C6c 那批截图的复现说明变了**：seeder 现在种 24 首歌（多了二十首演示曲目），旧说明里的"4 首歌 / 3 个专辑"只适用于更早的输出；
   批量任务那一行特意用 `taskSongs` 过滤掉演示专辑，所以 C6c 截图里的**文件名**在重跑后仍然逐字相同。

#### 7. 前沿

`python scripts/port-frontier.py` → java 树 **95 个文件 / 0 陈旧副本 / 46 可搬 / 49 被挡**（上一批 99 / 0 / 48 / 55）。
批量任务链上只剩 `BatchExportProcessor`（C6e）与那 7 个 sheet；界面侧剩 `BatchEditScreen` / `BatchRenameScreen` / `EditMetadataScreen` /
`ArtistDetailScreen` 与设置页那一片。
### P5 施工批次（批量导出：处理器补齐 + 真文件落盘 + 磁盘链取证，2026-10-11）

C6c 搬批量任务链时，`BatchTaskProcessorFactory` 对 `EXPORT_LYRICS` / `EXPORT_COVER` 是**明确拒绝**的（工厂里写着「没有桌面处理器」，被拒绝的任务会在任务行上记一条失败）。原因是 Android 的 `BatchExportProcessor` 整条依赖 SAF：目的地是 `destinationTreeUri`，找文件靠 `DocumentFile.findFile`，写文件靠 `openOutputStream`。桌面上没有 SAF，所以这不是「改个路径」就能过的活。这批（**C6e**）把它补齐：`git mv` 平台无关的 `ExportDestination`，重写处理器本体（251 行；Android 版 236 行），`git rm` java 树那一份，在 Koin 图里注册，并补上 `BatchTaskRunner` 里三个缺失的 config summarizer。**至此应用能发起的 8 种任务类型在桌面上都有处理器**（工厂注释同步改成这句话，`BatchTaskRunnerTest` 的摘要断言 8 种全覆盖）。这批不引入任何外部依赖：导出就是 `java.nio` 写文件。

难点不在「搬」，在**证明文件真的写出来了、而且字节就是容器里那份**。这是这条链上第一个「产出物离开应用」的处理器——写出来的 `.lrc` / `.jpg` 是给别的播放器用的，处理器自己返回的 `updatedFilePath` 不能当证据。所以取证是三条互相独立的链：(a) 无头 17 项端到端（真 Koin 图 + 真 Room + 真扫描 + 真 TagLib 读回，断言落在磁盘上的字节）；(b) seeder 在真 Koin 图里跑一次真任务（真 `BatchTaskRunner` + 真处理器），产出落进 `lyrico-app/build/demo-exports/`；(c) **磁盘链**：shell 列目录 + sha256、字节级读回、**ffmpeg 独立读回源 mp3 / wav 的 `lyrics-LYRICS` 标签**、绕过 Room 直接 `sqlite3` 读任务行与条目行——三段都在 app 进程被杀之后做（复查进程计数 0）。真窗口那三张图证明的是**读侧**：界面显示的与磁盘、数据库里的是同一批数据。

#### 1. 搬了什么（`git mv` 1 个 + 重写 1 个 + 删 1 个；java 主树 95 → 93）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 原样搬入 | `data/model/ExportDestination.kt` | `git mv`，0 行改动 |
| 桌面重写 | `worker/processor/BatchExportProcessor.kt` | 新写 251 行（Android 版 236 行） |
| 删除 | java 树里同名的那一份 | `git rm`，−236 行 |

已有文件：`di/DesktopAppModule.kt`（+11 −4）、`worker/BatchTaskRunner.kt`（+45 −4）。
新测试类：`worker/processor/BatchExportProcessorEndToEndTest.kt`（17 项）。
已有测试：`BatchTaskRunnerTest`（+144 −1，+6 项 → 21 项）、`DevLibrarySeederTest`（+67 −5，加一次真导出任务）、`AppLogViewModelTest`（+7 −3，见 §5.8 的既有竞态）。
非代码：18 个取证文件（3 张真窗口图 + 4 张裁切放大图 + 各自的 OCR / 像素分析 + 1 份磁盘链文档）。

#### 2. 桌面化改写点（逐条）

1. **配置字段 `destinationTreeUri` → `destinationDirectory`，并且解码是有意宽松的。** `BatchExportTaskConfig` 的 `CONFIG_JSON` 用 `Json { ignoreUnknownKeys = true }`，而库里其它处理器用默认严格解码。理由：Android 时代的任务行里 `AUDIO_DIRECTORY` 配置带 `destinationTreeUri`，严格解码会让这些行连「跳过」都记不上而直接崩在解析上。宽松解码的边界被两项测试钉住：一行 Android `AUDIO_DIRECTORY` 配置**仍然导出到音频旁边**（`destinationTreeUri` 被忽略），而一行把目的地写成 `content://…` 的 `SELECTED_DIRECTORY` 配置**失败而不是写到别处**。
2. **封面是 `ByteArray`，不是 `Any?`。** Android 版对 `coverSource` 做 `when (getCoverSourceType(coverSource))`，六种来源（BYTE_ARRAY / NETWORK_URL / CONTENT_OR_FILE_URI / URI / FILE_PATH / BITMAP）分别去拿字节。桌面版上游早就把封面收敛成「已经取好的字节」（C6b 的封面轨），所以这里只剩一条路：字节直接 `Files.write` 成 `.jpg`。少掉的不是功能而是六种历史分支。
3. **SAF 的两步（`findFile` + `openOutputStream`）塌成一次 `Files.write`。** Android 需要两步是因为 SAF 的文档树要先按名字找到文档、再打开输出流；磁盘上一个 `Files.write(path, bytes, CREATE, TRUNCATE_EXISTING)` 就是全部。副作用是语义变清楚：**同名文件是被覆盖的**，不会出现 `朋友 (1).lrc` 这种备份名（`BatchExportProcessorEndToEndTest` 的 `replace-on-reexport` 与 `shared-base-name` 两项各钉一半）。
4. **「选中的目录」必须已经存在且可写，不替你创建。** Android 的目录来自选择器，选择器创建过它；桌面版 `requireSelectedDirectory` 只做存在性与可写性检查，**不 `createDirectories`**。理由：导出目录是用户明确指过的地方，一个拼错的路径应该是响亮的失败，而不是让文件散落在一个新造出来的目录里。失败消息带上探过的路径（Android 只有一句 `Destination folder unavailable`）。
5. **兄弟目录写入（`AUDIO_DIRECTORY`）用 `songUri` 的父目录，并且仍然有一把互斥锁。** `AUDIO_DIRECTORY` 的语义是「歌词与音频放一起」，所以目的地是源音频的父目录；并发导出多首时同一目录下的写入用 `audioDirectoryWriteMutex` 串行化（Android 里为 SAF 并发写也这么做）。真窗口那次跑的是 `SELECTED_DIRECTORY`，这条路径由无头测试的 `audio-dir sibling` 一项覆盖。
6. **TTML 判定与文件名沿用 Android。** 歌词正文里出现 `begin=` + `end=` + `<?xml` 就按 TTML 命名成 `.ttml`，否则 `.lrc`；`baseFileName` 取 `fileName.substringBeforeLast(".", missingDelimiterValue = fileName)`——只截最后一段扩展名（`a.b.mp3` → `a.b`），没有扩展名就原样用。这条有两个测试（`last-extension-only`、`extensionless name`），因为 Android 这里曾经出过错。
7. **「跳过」与「失败」的分界原样保留。** 没有歌词、没有封面、没有配置都不是失败，是 skip（`markItemSkipped(itemId, resultJson)`，原因进 `resultJson` 而不是 `errorMessage`）；只有「目标目录没了 / 不可写」「源文件读不出来」「写不进去」才是失败。磁盘链里那条 `No lyrics` 就落在 `resultJson` 里，详情页对跳过项也不显示红字。
8. **结果形状不变。** `BatchTaskProcessResult` 的 `updatedFilePath` / `updatedFileName` 照旧写回条目行——批量详情页显示的路径与文件名就是它，所以这两列是「导出成功」在用户侧的唯一可见物。
9. **唯一一处用户可见的下降（如实记）：错误文本还是英文原文**（`Destination folder unavailable` 之类），本地化映射表跟着错误界面一起做（#36）。

#### 3. 无头取证（新增 23 项；全量 102 类 / 900 项 / 898 执行 + 2 门控跳过 / 0 失败 0 错误）

| 测试类 | 项数 | 验的是 |
| --- | --- | --- |
| `BatchExportProcessorEndToEndTest` | 17 | 真 Koin 图 + 真库 + 真 TagLib：`.lrc` 的 UTF-8 字节全等、TTML 命名、无歌词跳过、重导出覆盖、`AUDIO_DIRECTORY` 与音频同目录且音频字节不变、同名互斥、源文件缺失失败、封面字节不变、无封面跳过、目录不存在失败且消息带路径、目的地是文件时失败、无配置跳过、**Android `AUDIO_DIRECTORY` 行照旧导出**、**Android `SELECTED_DIRECTORY` 行失败而不写别处**、只截最后一段扩展名、无扩展名保名、不支持的类型拒绝 |
| `BatchTaskRunnerTest` | +6 | 三类配置摘要（replay-gain / match / export），含「默认值不显示成 `null`」 |
| 合计 | +23 | 全量 877 → 900 |

那 2 项门控跳过是 seeder 类里需要 `-Plyrico.seedDevLibrary=1` 的两个用例，不是被跳过的漏测；另外这批的端到端测试显式关掉 `ignoreShortAudio`，否则几秒钟长的夹具会被静默过滤，测试会因为「库里没有歌」而假通过。

#### 4. seeder 真跑 + 真窗口取证（3 图 + 4 裁切 + 1 份磁盘链）

seeder 那一次跑的是一条真的 `EXPORT_LYRICS` 任务，参数与结果在窗口与数据库两侧都能对上：

| 事实 | 值 |
| --- | --- |
| taskId | `482a9c2d-0a88-4d09-ad02-554295fe529f` |
| 配置 | `{"destinationDirectory":"H:\…\lyrico-app\build\demo-exports","concurrency":1}`（`destination` 是默认值 `SELECTED_DIRECTORY`，序列化时省略） |
| 统计 | 3/3，成功 2、失败 0、跳过 1（第三首 `1 山丘.flac` 没有歌词 → `resultJson` = `No lyrics`） |
| 耗时 | 18 ms（`startedAt` / `finishedAt` 之差） |
| 产物 | `1 朋友.lrc` 93 B、`2 花心.lrc` 63 B |

真窗口三张图（截的是窗口自身矩形，标题栏里是 `Lyrico 1.6.0 (dfc8119)`）：

| 图 | OCR 读到的 |
| --- | --- |
| `c6e-batch-list.png` | 任务历史里那一行：`任务类型 导出歌词` / `运行状态：成功` / `成功：2 失败：0 跳过：1` / `耗时：0.0s` |
| `c6e-export-detail.png` | 详情页：`进度：3/3` / `导出歌词 · 成功` / 同一组统计 / 两个成功条目及其 `demo-exports` 路径 |
| `c6e-export-detail-skipped.png` | 点 `跳过` 页签后：只剩 `1 山丘.flac` 一条，路径是**源音频**（与「跳过 = 一个文件也没写」一致） |

第三张与其前一张的像素差被记进 `c6e-export-detail-skipped.analysis.txt`：`194374` 个像素不同 = 整窗的 `21.57%`，`bbox (13,212)-(1153,424)`——这次点击确实重画了那一块，而不是「点了没反应」。数字要靠裁切放大件（4 张，3× LANCZOS，**裁切原点跟着行位置走**）才读得出来：`成功：2 | 失败：0 | 跳过：1`、`2 花心.lrc` + `…\demo-exports\2 花心.lrc`、`跳过` 页签。

磁盘链（`docs/port-evidence/c6e-export-files.txt`，三段都在 app 进程被杀之后）：

* 目录里**只有两个** `.lrc`，字节数 93 / 63 能按 UTF-8 逐字符算平（既没多写 BOM / 尾换行，也没漏写）；
* ffmpeg 从**源** mp3 与 wav 里读出的 `lyrics-LYRICS` 标签，与导出文件逐行**逐字相同**（顺带证明读侧对 8 kHz 单声道 A-law WAV 也通）；
* `sqlite3` 直读：任务行 `SUCCEEDED` / `2 / 0 / 1`；条目行的成功项 `filePath` 指向 `demo-exports` 下的真文件、跳过项的 `filePath` 仍是源音频、`errorMessage` 全为 `None`。

**一个必须如实写下的边界**：这个导出任务是 **seeder 在 Koin 图里真跑出来的**，不是窗口里点出来的——发起批量任务的界面（十二动作 FAB + 两个 bottom sheet + `BatchExportViewModel`）还在 java 树，跟着 #35 走。窗口证明的是**读侧**：列表行与详情页显示的就是上面那批真数据（同一个 taskId、同一组统计、同一批文件名与路径），两边能对上的唯一解释是「进程里跑的和界面里显示的是同一批 DB 行」。

#### 5. 这批实测出来的坑（都会再踩）

1. **新建 kotlin 文件必须把 java 那一份 `git rm`。** 只写不删，`port-frontier.py` 的 stale duplicates 检查会立刻报出来（两个树里同一路径同名类），而且它挡住的不只是一个文件：删掉之后被它挡住的 3 个依赖文件同时解锁（受挡 49 → 46）。
2. **数字在整窗 OCR 里会丢，裁切放大件才有数字。** `成功：2 失败：0 跳过：1` 在整窗 OCR 里糊成 `成功 2 ] 实败 ： 跳泣 ：`；裁切 + 3× LANCZOS 之后才读得出 `成功 ： 2 | 失败 ： 0 | 跳过 ： 1`。**裁切原点必须跟着行位置走**，不能写死矩形：同一页面每行高度不同，写死就裁到空白。
3. **`File.writeText` 是「先建文件、再写字节」，所以等 `target.isFile` 会读到空文件。** 这是本批出现的第一个 flake：`AppLogViewModelTest` 的导出日志断言失败，而 JUnit 的失败消息是**空的**——那就是「文件存在但内容为空」的指纹。修法是等真正的完成信号（`exportLogs` 成功后发的那条事件，它在 `writeText` 返回之后才发），不是加大超时。见 §5.8。
4. **`click-window.ps1` 不截图，`capture-window.ps1` 不点击。** 要点完看结果必须调两次；客户端坐标 = 捕获坐标 − (1, 31)（左右各 1px 边框、上面 31px 系统标题栏）。
5. **杀掉应用窗口之后 Gradle 会报 `BUILD FAILED`（`NTSTATUS 0xFFFFFFFF`）**，这是预期的（`compose.desktop.application.run` 的进程被强杀），不要当成回归去查。
6. **seeder 触发的任务要在文里写明。** 「窗口里看到导出成功」本身不构成「界面能发起导出」的证据，两者必须分开写（见 §4 末尾与 §6）。
7. **别把 `stringResource` 的坑算到这批头上**：这批没有 UI，`StringFormattingGuardTest` 一行没动——它真正的考验在下一批（#35 的界面）。
8. **两个既有 flake 在这批的 4 次全量跑里露了头**（都不是这批的代码引起的）：
   * `AppLogViewModelTest` 的空文件竞态——**已修**（坑 3），修完全量 102 类 / 900 项 / 0 失败。
   * **泄漏的 viewModelScope 与 `database.close()` 竞争**——**已修**（`LibraryHomeScreenTest` 自己当宿主，见下）。症状是下一次全量跑里 `LibraryHomeScreenTest` 报 `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`，XML 的 `system-err` 里真正的异常是 `Exception in thread "AWT-EventQueue-0 @coroutine#11721" androidx.sqlite.SQLiteException: Error code: 21, message: Connection pool is closed`，调用栈穿过 `SongListViewModel` 的 `stateIn` 收集（`SongListViewModel.kt:109`）——也就是**前一个测试留下的 viewmodel 作用域还在查那个已经被 tearDown 关掉的 Room 池**，而 `Dispatchers.setMain` 的 uncaught 队列把它记到了下一个测试头上（所以「报错的类」和「出错的类」不是同一个）。同一个代码树连跑两次的结果是一次绿、一次挂。

     根因不是「谁忘了取消」：`koinViewModel()` 把 viewmodel 放进 `library_home` 这个 `NavBackStackEntry` 的 store，而那个 store 挂在**宿主**上——headless 帧虽然会自己造一个，但它永远不会被清。于是组合释放只终止了页面的订阅（`stateIn(WhileSubscribed)` 那一环），viewmodel 自己活着。

     修法是这个测试类自己当宿主：`LibraryHomeHost` 用 `CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner)` 包住 `LyricoNavHost()`，tearDown 里**先** `viewModelStore.clear()` 再 `stopKoin()` / `database.close()`——`NavControllerViewModel.onCleared()` 会连带清掉每个导航条目的 store，所以清这一个 store 就够。

     取证：修前单跑该类 1 挂 1 绿；修后单跑 6 次全绿（6×5 个窗口），全量 **926 项 / 0 失败 / 2 跳过**。另外两个可观测的佐证：tearDown 里断言 `viewModelStore.keys()` 非空（证明这个 store 真是 shell 用的那个，否则「清了个空 store」也会全绿），以及 5 次单跑之后 `%TEMP%/lyrico-library-home*` 的目录数不再增长（关库时已经没有在飞的 Room 调用，文件锁放开、`deleteRecursively` 成功）。**没走的两条路**：加 `Thread.sleep` 或排空 AWT 队列（连接 acquire 的唤醒是从 Room 线程投递过来的，任何「等一等」只是把窗口缩小而不是关掉）；以及「干脆不关库」（实测 5 次单跑 25/25 个临时目录全部留下来，等于把资源泄漏留在那里）。

#### 6. 这批的用户可见缺口（如实记录）

1. **批量导出还没有入口**（#35）：8 种类型都有处理器了，但「勾一批歌 → 导出」的十二动作 FAB、两个 bottom sheet 与 `BatchExportViewModel` 还在 java 树。
2. **条目行点不进详情**（#36）：批量详情页的行点击目标是 `EditMetadataScreen`，它的目的地还没注册。
3. **错误文本未本地化**（见 §2.9）。
4. **导出目录不自动创建**（见 §2.4，这是有意的选择，但用户会看到一条英文失败）。
5. **测试卫生问题**（见 §5.8）：泄漏的 viewmodel 作用域**已修**（§5.8 坑 8）。但同一批全量跑里还露出**第二个无根的 flake**：`AlbumLibraryViewModelTest > the grid column count defaults to two and follows the saved setting` 一次 10 s 超时（`awaitUntil` 报 `condition not met within 10000ms; last value was 2`，即 DataStore 那次写入在 `viewModelScope`（Main = `Dispatchers.Default`）上一直没被观察到）；单跑该类 3 次全绿，同一棵树的下一次全量跑也全绿。P6 打包前应连着「全量跑偶发挂一次」这一类问题一起收（怀疑是同一个 JVM 里累计的阻塞线程把 `Dispatchers.Default` 饿住了，但还**没有证据**，不要凭猜去改）。

#### 7. 前沿（本批收口）

java 主树 **93 文件 / 0 待删重复 / 47 可搬 / 46 被挡**（本批 95 → 93；`git rm` java 版处理器时同时解锁 3 个被挡文件）。这两半的构成很不一样：

* **可搬的 47 个**大多是「原样搬」的小件：21 个 `ui/components/...`（liquid 材质 6 个、Fab 菜单 2 个、`PagerDotsIndicator` / `FieldOrderState` / `SearchSectionHeader`、几个 bottom sheet 与 `PainterUtils`）、`ExternalAudioEditHost`、`data/model/*`（`ArtistSeparator` / `RenamePreview` / `LocalSearchField` / `AppLanguage` / `CacheCategory`）、`domain/poster/*`、`utils/ConflictResolver`、`screens/OpenSourceLicenceScreen`、4 个批量配置 viewmodel。另一半是**该删而不是该搬**的 Android 遗留物（`utils/SafDocuments` / `SafSiblingFileWriter` / `UriUtils` / `CoverSourceType`、`platform/player/{PlayerIntentFactory,InstalledAppChecker}`、`data/exception/RequiresUserPermissionException`、`screens/QuickJsTestScreen`、`App.kt` / `MainActivity.kt`）——它们现在「可搬」只是因为依赖都搬完了，处理方式是逐个判定「桌面版还需要它吗」，不需要的删掉而不是硬搬。
* **被挡的 46 个就是剩下的界面侧**：15 个 screen（`EditMetadataScreen` 17 个依赖、`BatchEditScreen`、`BatchRenameScreen`、`SettingsScreen`、`AlbumDetailScreen`、`ArtistDetailScreen`、`LocalSearchScreen`、`FolderManagerScreen`、`AboutScreen`…）、13 个 `ui/components`、13 个 viewmodel（`EditMetadataViewModel`、`BatchEditViewModel`、`BatchRenameViewModel`、`SettingsViewModel`、`FolderManagerViewModel`…）、`di/AppModule.kt`（13 个依赖），以及 `utils/{RenameEngine,CacheManager,ArtistPosterFileWriter}`。这批留下的最大缺口（#35 的批量发起侧：`SongSelectionSupport` 16 个依赖 + 8 个 sheet）就在这份名单最前面。

## 5. 待定分叉（到 P5 前必须由用户裁决）

**「更新检查」指向哪个仓库**（`utils/UpdateManager.kt`）—— ✅ **已裁决：方案 B**（2026-10-09，用户选择）：指向本 fork `CN-Grace/Lyrico-Desktop`。以下为当初的选项留档：

`UpdateManager` 唯一的阻塞是 `App.kt`，而它只取两个常量：`App.Companion.OWNER_ID = "Replica0110"`、`REPO_NAME = "Lyrico"`。照原样搬就是**行为对齐**，但桌面版会把 Android APK 当成更新包报给用户：

- 实测 `Replica0110/Lyrico` 有 3 个 release，资产是 `Lyrico-1.6.0-*.apk`（Android）；本 fork `CN-Grace/Lyrico-Desktop` 目前 **0 个 release**。
- **A. 照原样指向 Android 上游**：行为对齐，但「发现新版本」会打开一个装不上的 APK 下载页。
- **B. 指向 `CN-Grace/Lyrico-Desktop`**：语义正确，但 0 release 期间永远是「已是最新」（`NoUpdateAvailable`，无害但无用），要等 P6 打包开始发 release。
- **C. 做成可配置**（设置项或构建期常量，默认指向本 fork）：最灵活，多一个设置项。

裁决后 `UpdateManager` 随基础设施批次搬入（它是 `SongListViewModel`/`AboutViewModel` 的唯一阻塞，而这两个 viewmodel 是「曲库首页」这批 UI 的前置）。两个常量不再硬编码在 `App.kt` 里，而是由 `generateBuildInfo` 生成到 `BuildInfo.UPDATE_OWNER` / `UPDATE_REPO`：更新源是**构建期属性**而不是散在类里的字面量，将来要换仓库或做方案 C（可配置）只动一处。0 release 期间行为是「已是最新」，P6 发出第一个 MSI release 后自动生效。

**「桌面端数据目录放哪里」**（2026-10-09 新增，已裁决）—— ✅ **便携优先**：`<exe 所在目录>\data`；该目录不可写（典型：装在 `Program Files`）时回落 `%LOCALAPPDATA%\Lyrico`；`-Dlyrico.data.dir=<路径>` 覆盖两者且**显式指定的路径写不进去直接报错**。理由：一个可解压即用、可整体搬走/备份/卸载的绿色目录最符合「单机音乐库」的使用方式；回落分支保证「装到受管目录」不会变成启动即崩。选目录时会**真实写一个探针文件**（Windows 上目录的 `canWrite` 不可靠，而 `Program Files` 正是它说谎的场景），并把最终目录写进应用日志。

**「首屏 wave 的排序菜单」与「计数排序箭头语义」（§5 上一条）在接 UI 时一并裁决**：`ArtistSortBy`/`AlbumSortBy` 的比较器保持逐字一致到那一刻。

**「计数排序的升降序语义」要不要改**（`ArtistLibraryViewModel` / `AlbumLibraryViewModel`，2026-10-09 移植时发现，尚未裁决）——

`ArtistSortBy.SONG_COUNT`/`ALBUM_COUNT` 与 `AlbumSortBy.SONG_COUNT` 的比较器固定是 `compareByDescending`，`ASC` 分支**直接返回**它的结果，只有 `DESC` 才 `asReversed()`。于是：计数「升序」= 数量由多到少，计数「降序」= 由少到多；而 NAME/ALBUM_ARTIST/YEAR 三个键的升降序是正常的。

这**不是移植错误**（与 Android 逐字一致，已由 40 项 viewmodel 测试逐条钉住），但接 UI 时会直接变成一个箭头指反的排序菜单：

- **A. 照原样**：排序箭头对计数项的含义与其它项相反。零风险，但用户会看到「↑」把歌多的排前面。
- **B. 计数项改成正常语义**（比较器换成 `compareBy` 升序，`DESC` 才反转）：与其它排序键一致，是一处**有意行为变更**，三个键各要改一行 + 改对应测试。
- **C. 排序菜单里给计数项固定「多的在前」并隐藏箭头**（把「ASC = 多在前」当默认而不是方向）：不动比较器，只动 UI，属于接 UI 那批的事务。

按 §4 排期，sorting 菜单属于 P4 的 screens 批次，所以这条等接 UI 时一并裁决；在那之前保持逐字对齐（A）。

**ReplayGain 的 PCM 解码方案**（Android 端 `ReplayGainScanner.kt` 用 `MediaExtractor`+`MediaCodec`，桌面无等价物）—— ✅ **已裁决：方案 A**（2026-10-10，用户选择）：捆绑 ffmpeg sidecar。以下为当初的选项留档：

- **A. 捆绑 ffmpeg**（sidecar `ffmpeg.exe` 经 stdin/stdout 管道喂 f32le，或 JavaCPP `ffmpeg-platform` 直接调 libav*）：覆盖全部格式（含 APE/AIFF/DSF/Opus），需要额外约 50–150 MB 二进制与 LGPL/GPL 许可声明；与现有 `ebur128` JNI 对接最直接。
- **B. 自带解码源码**（dr_libs + stb_vorbis + libopus 等）：体积小、无外部许可包袱，但**覆盖不全**（APE/AAC/DSF 缺），需要为缺失格式降级。
- **C. 解析声道响度仅走 TagLib 已读标签 + 跳过无解码格式**：最省事，但功能不对齐，与「全功能对齐」目标冲突。

**方案 A 的落地约束（2026-10-10 随裁决记录）**：「测响度」这一半不需要新原生代码——`ebur128.dll` 与它的 JNI 桥（`lyrico-app/src/main/cpp/ebur128.cpp`，导出 `initNative`/`processDirectNative`/`getLoudnessNative`/`getPeakNative`/`getMultipleLoudnessNative`）已在 P1 构建完成，对应的 Kotlin 侧 `utils/LibEbuR128.kt` 是现成的可搬叶子。缺的只是「把音频解成 PCM」。按方案 A，这一半用 sidecar `ffmpeg.exe` 经管道喂 f32le。三项已知代价必须一并处理、不得静默省略：

1. **体积**：发布包多一个 50–150 MB 的二进制。本机 PATH 上**没有** ffmpeg，要自己取一个 Windows 构建放进随包分发的目录（与 `taglib.dll`/`ebur128.dll`/`quickjs-ng.dll` 同一布局）。
2. **许可**：取到的构建若是 GPL 版（含 libx264 等）会把整个应用拖进 GPL 传染，**优先取 LGPL 构建**，并把声明加进「开源许可」页（`OpenSourceLicenceScreen`，本身是 frontier 可搬项）。
3. **进程管理**：每文件起一次进程有启动开销（批量任务需考虑复用与并发上限），另有路径失效、程序拒绝参数、管道写满导致死锁三条错误路径都要有明确处理与测试。

另：找不到 ffmpeg 时降级为「跳过该文件并如实报错」，不得静默假成功——`ReplayGainScanner` 的桌面版多一个「外部进程」依赖，是**有意引入**，在 P5 的 ReplayGain 批次里验证。

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
3. ~~**compose-destinations 的 KSP 代码生成**是否支持 CMP Desktop~~ ✅ **已查清（2026-10-09）：不支持，走手写导航适配层**，且适配层已落地（见 §4「应用外壳 + 导航适配层」）。证据不是印象，而是发布物的 Gradle module metadata：`io.github.raamcosta.compose-destinations:core:2.3.0` 的 `core-2.3.0.pom` 是 `<packaging>aar</packaging>`，`core-2.3.0.module` 只有 `releaseVariantReleaseApiPublication` / `Runtime` / `Source` / `JavaDoc` 四个变体（`org.jetbrains.kotlin.platform.type` 为空），依赖 `androidx.navigation:navigation-compose:2.9.5`；同版本号的 `core-jvm` / `core-desktop` / `core-android` 三个坐标全部 HTTP 404（该 group 只发 Android）。AAR 在纯 JVM 模块下无法参与编译，KSP 就算生成代码也没有可用运行库，所以**这条路是死的**。
   替代方案的证据同样是 metadata：`org.jetbrains.androidx.navigation:navigation-compose:2.9.2` 的 `desktopApiElements-published` 变体 `platform = jvm`（另有 js/native 变体），即 **Navigation Compose 本身在桌面端可用**。配合上面的薄适配层即可，不需要自己实现回退栈与 ViewModelStore。Android 侧 `LyricoApp.kt` 的 `DestinationsNavHost` + 自定义左右滑转场在桌面端改为 `NavHost` + `slideInHorizontally/slideOutHorizontally`（转场函数本身是 CMP 公共 API，逐字保留）。**已落地验证**：`ui/navigation/{NavDirection,Navigator,Destinations,LyricoNavHost}.kt`，`NavigatorTest` 用真 `NavHost` 钉住「`popBackStack()` 在起始路由返回 `false`」与「每个 back stack entry 各有一份 `koinViewModel()` 实例、退栈后重建」——后者正是当初选 Navigation Compose 而不是自造 `when (current)` 宿主的原因，也是 26 个 screen 的共同前提。
4. **Miuix desktop 与 Android 版的行为差异**（`BackHandler`、`TopAppBar`、滚动条、窗口拖拽区）——逐屏过。
5. **`androidx.lifecycle.ViewModel` 30 处在桌面端的生命周期**——✅ **已验证**：Koin 的 `viewModel { }` 在桌面可用（`org.koin.core.module.dsl.viewModel` + `koin-compose-viewmodel`），`DesktopAppModuleTest` 真启动 Koin 并解析 9 个 viewmodel 实例。以下为当初的排查留档（`lifecycle` 一处）**部分已证伪**：`lifecycle-viewmodel-compose` 在多平台构件里存在 `-desktop` 变体，显式声明 `implementation(libs.androidx.lifecycle.viewmodel.compose)` 后 `ViewModel`/`viewModelScope` 在 `kotlin("jvm")` 模块编译并运行正常（它原本只在运行期 classpath 上，所以看起来像「桌面没有 ViewModel」）。剩下未验证的是 Koin 的 `viewModel {}` 注入（`di/AppModule.kt` 要被搬过来才会遇到）。
6. **非 ASCII 工程路径 + Gradle 参数文件编码**（已踩中并修复，勿回退）：工程位于 `H:\VibeCoding\03-应用\Lyrico-Desktop`。Gradle 用**守护进程默认字符集**（`ArgWriter` → `new PrintWriter(File)`）把 worker JVM 的 classpath 写进临时 `@argfile`，而 `java.exe` 用 **Windows ANSI 代码页（936/GBK）** 解析该文件；`gradle.properties` 里原本的 `-Dfile.encoding=UTF-8` 会把含中文的工程路径写成乱码 → worker 报 `ClassNotFoundException`（每个测试类都找不到，甚至 `GradleWorkerMain`）。修复：`org.gradle.jvmargs` 用 `-Dfile.encoding=GBK`（= 本机 ANSI 代码页）。**换机器时该值必须等于该机 ANSI 代码页**；`run`/`JavaExec` 任务同样走这条路径，所以 P2 之后不要再改回 UTF-8。

## 7. 约定

- 旧的 Android 代码以 commit 形式留在 git 历史（`origin/master`）中，不再保留在本分支源码树内。
- 平台无关代码优先「原地改造」，不做复制；只有确定要删的 Android 专属文件才删除。
- 每阶段结束必须留下可复现的验证命令（见 §4 门禁）。
- **P1 复现命令**：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-native.ps1`（产出 `build/native/windows-x64/{taglib,ebur128,quickjs-ng}.dll`）→ `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/native-smoke.ps1`（纯 Java 冒烟，无需 Gradle，失败返回非 0）。冒烟源码在 `tools/native-smoke/`，其中的 `com/lonx/audiotag/{TagLib,model/*}.java` 是**临时替身**，等 P2 的 Kotlin JVM 库建好后应改为直接依赖真实 Kotlin 类。
- **P1 复现命令（Gradle 侧，真 Kotlin 绑定）**：`./gradlew :lyrico-audiotag:test`（13 项检查 0 失败，覆盖 7 种格式的标签/封面读写、CJK 路径端到端）。跑之前确保 `build/native/windows-x64/*.dll` 已由 `scripts/build-native.ps1` 产出。
- **P2 复现命令**：`./gradlew :lyrico-app:run` 弹出窗口（标题 `Lyrico <版本> (<commit>)`）；取证用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-window.ps1 -TitleLike "Lyrico 1.6.0" -OutputPath docs/port-evidence/p2-miuix-window.png`（截的是窗口自身矩形；**别用模糊标题匹配**——终端窗口标题里也含 “Lyrico-Desktop”）。截图非空白的客观校验在 `docs/port-evidence/p2-miuix-window.analysis.txt`（561 色；白底 `255,255,255` + 卡片底 `247,247,247`；2906 个文字暗像素分布在 96 行）。
- **P2 版本锁定**：Kotlin 2.4.20 + Compose Multiplatform **1.12.0** + Miuix **0.9.4**。不是随手写的：Miuix `-desktop` 产物的 pom 显示它是用 CMP 1.12.0 / Kotlin 2.4.20 编的，Kotlin 版本又要跟仓库原有 2.4.20 对齐，三者必须同进同退。
- **P4 真窗口取证复现命令**：`./gradlew :lyrico-app:test --tests "*DevLibrarySeederTest*" -Plyrico.seedDevLibrary=1`（把 4 首真标签的歌填进 `lyrico-app/data`）→ `./gradlew :lyrico-app:run` → `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-window.ps1 -TitleLike "Lyrico 1.6" -OutputPath docs/port-evidence/<名>.png -SettleMs 4000` → `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ocr-window-capture.ps1 -Path <png> -OutFile <txt>` → `python scripts/analyze-window-capture.py <png> --write`。**别用模糊标题匹配**（终端窗口标题里也含 “Lyrico-Desktop”），别在激活后加 sleep（会被别的窗口抢前台），OCR 结果先落盘再读（PowerShell 管道会糊 CJK）。
- **要点界面时用 `scripts/click-window.ps1`**（`-X -Y` 是**客户区**坐标，`-SettleMs` 后只等待、**不自截图**；长按用 `-Button longpress -HoldMs 800`）：截图带 31px 系统标题栏、左右各 1px 边框，所以「截图里 y=240 的东西」要点 `-Y 209`；窗口不在前台时脚本会先花一次点击做激活（Windows 会把那一次吞掉；这一下现在打在**标题栏**上，因为它是真点击），否则第一次点击看着像「点了没反应」。脚本会把框架原点与客户区原点都打出来，不要手算。**要「点完稳定后」的图必须另调一次 `capture-window.ps1`**（两次 PowerShell 启动 + `Add-Type` 各约 0.45 s，所以「点击 → 第一帧」最快约 0.6 s；要卡瞬态就两边都用 `-SettleMs 0`，见 C6d 的「计算中」取证）。
- **`stringResource` 的两个坑（本仓已踩，勿回退）**：
  1. **带参数的 `stringResource` / `getString` 不走 `String.format`**：CMP 的实现是 `replaceWithArgs` + 正则 `%(\d+)\$[ds]`，只认位置参数。本仓 20 条字符串用普通 `%d`/`%s`/`%.2f`，所以主源码里禁止直接写 `stringResource(res, args)` / `getString(res, args)`，一律走 `formattedStringResource` / `formattedString`（`StringFormattingGuardTest` 会把违规的 `file:line` 报出来）。**不要**为了迁就库去改字符串：字符串必须与 Android 逐字一致，而且 `%.2f`/宽度/精度在库的模型里根本表达不出来。
  2. **多行 XML body 的缩进不会被去掉**（`\n` 转义会被正确转成换行）。aapt2 去掉的是「首尾带换行的那段空白」，行内尾空格是故意的（`Task Type: ` 后面拼值）。所以 Compose 资源里的 `<string>` body 一律写成一行；`ComposeStringResourcesTest` 会检查。
- **P3 复现命令（数据层）**：`./gradlew :lyrico-app:test`（P3 收口时 **243 项 0 失败**；P4 各批（状态层、浏览/搜索 viewmodel、剩余 viewmodel、UI 地基、UI 轨道、选择与操作面板、独立歌曲页、专辑页与艺人页、三 tab 外壳）之后，全量现为 **553 项（552 执行 + 1 门控跳过）0 失败 0 错误、65 个测试类**，按包可核对：data 203 · viewmodel 134 · utils 117 · ui 33 · screens 27 · domain 20 · platform 13 · di 5 · probe 1；其中 UI 轨道 25 项 = 封面 7 + 歌曲列表 11 + 壳 7，选择与操作面板 69 项，独立歌曲页 7 项 + 导航 5 项 + 字符串格式化 9 项，专辑页 6 项 + 艺人页 4 项 + 专辑操作 viewmodel 4 项，三 tab 外壳 5 项，见 P4 节）：库读写/FTS/raw query/重开持久化/schema 保真 8 项 + 歌曲库 11 + 库索引 7 + 本地搜索 11 + mapper 5 + 标签读写 7 + 拼音排序键 7 + 歌词解码链 59（原 Android 测试整体搬迁：管道 31/列排序 18/编码器 10）+ 设置层 4 + 应用日志 6 + 路径模型 6 + 壳 3 + 扫描器 9 + 扫描端到端集成 9 + 文件重命名/删除 13 + 自定义标签键 12 + 插件表 15 + GitHub 贡献者 7 + 更新检查 12 + 批量任务 23 + **播放转发 9** + 独立歌曲页全链路 7 + 导航 5（原 2）+ 字符串格式化（守卫 2 + helper 3 + 资源 XML 4）+ 开发库播种 1（门控跳过））。测试任务注入的系统属性：`lyrico.schema.dir` / `lyrico.android.schema.dir`（schema 比对）、`lyrico.audiotag.fixtures.dir`（音频夹具，指向 `lyrico-audiotag/src/main/cpp/taglib/tests/data`），换机器无需改测试代码。
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
- **ffmpeg 边车（C6d 起）的查找路径**：`FfmpegSidecar` 依次看系统属性 `lyrico.ffmpeg.dir` → 环境变量 `LYRICO_FFMPEG_DIR` → 从当前目录**逐级向上**找 `build/ffmpeg/<os>-<arch>`（开发布局）→ `ffmpeg/<os>-<arch>`（jpackage 布局）→ `ffmpeg`（被压平的布局）。**故意不查 `PATH`**：否则做测量的二进制版本与许可证会随机器而变。取边车用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-ffmpeg.ps1`（写到 `build/ffmpeg/windows-x64/ffmpeg.exe`，LGPL 构建，只要 `ffmpeg.exe` 不要 `ffprobe`，来源写进 `docs/third-party/ffmpeg-lgpl.md`）。找不到时的行为是「带探过目录的失败」，不是崩溃。
- **C6d 真窗口取证复现命令**：`./gradlew :lyrico-app:test --tests "*DevLibrarySeederTest*" -Plyrico.seedDevLibrary=1`（种 24 首歌 / 4 个专辑，其中 `演示合集` 是二十份夹具拷贝，专门用来把「计算中」拉长到能拍）→ `./gradlew :lyrico-app:run` → 截图链：`capture-window.ps1`（歌曲页）→ 点 rail 第二项（客户区 `(40,131)`，rail 三档映射见 C4c）→ `capture-window.ps1`（专辑 grid）→ `click-window.ps1 -X 889 -Y 714 -Button longpress -HoldMs 900`（第二行第二列那张封面 = `演示合集`）→ `capture-window.ps1`（动作面板）→ `click-window.ps1 -X 499 -Y 561 -SettleMs 0`（那一行）→ `capture-window.ps1 -SettleMs 0`（`计算中` / `完成`）；中止那条链是在两者之间再插一次 `click-window.ps1 -X 855 -Y 586 -SettleMs 0`（`中止`）。OCR 与像素分析按前述两条约定（整窗 + 裁切放大）。
- **底部面板的小字要在裁切放大后 OCR（C6d 起）**：整窗 OCR 读不出面板里的小字（C6d 的 `计算中` / `90%` / `用时 1.04 秒` 全丢）。做法是裁 `(250,500,950,773)` 再 2× LANCZOS 放大，产物 `*.crop.png` / `*.crop.ocr.txt`，坐标按 `裁切原点 + 值/2` 反推回捕获坐标。两种产物都进版本库：整窗那张证明「面板在窗口里、页面被压暗」，裁切那张读得出数值，谁也不能单独当证据。
- **行内数字只能靠「跟着行位置走」的裁切放大件（C6e 起）**：`成功：2 失败：0 跳过：1` 这种「中文标签 + 阿拉伯数字」的行，整窗 OCR 会把数字连同标点一起糊掉（C6e 实测读成 `成功 2 ] 实败 ： 跳泣 ：`）。做法是**裁左侧那一列、裁切矩形的 y 跟着行位置走**（同一页面每行高度不同，写死矩形会裁到空白），3× LANCZOS 后 OCR 才读得出 `成功 ： 2 | 失败 ： 0 | 跳过 ： 1`。另外「点击真的重画了」这件事用像素差而不是 OCR 证明：`PIL.ImageChops.difference` 出 `differing_pixels` / `bbox` / 百分比，把结论追加进 `*.analysis.txt`（C6e 的页签切换：`194374` px = `21.57%`，`bbox (13,212)-(1153,424)`）。
- **导出侧三条有意的约定（C6e 起，勿按 Android 改回去）**：① `BatchExportTaskConfig` 的 `CONFIG_JSON` 用 `ignoreUnknownKeys = true`（其它处理器是严格解码）——不这样就解不开 Android 时代带 `destinationTreeUri` 的 `AUDIO_DIRECTORY` 任务行；② 选中目录**只校验、不创建**（`requireSelectedDirectory`），拼错的路径要响亮失败；③ 写出的 `.lrc` 是「标签里的正文原样」：不带 BOM、不加尾换行、同名覆盖而不改名（`BatchExportProcessorEndToEndTest` 用 `toByteArray(UTF_8)` 全等钉住）。
- **等 `File.writeText` 写完不能等 `isFile`（C6e 起，测试里通用）**：`writeText` 是「先 `FileOutputStream` 建空文件、再写字节」，所以 `awaitUntil { target.isFile }` 可能读到 0 字节的空文件——症状是 JUnit 失败消息**为空**（`assertEquals("", text)` 里 `text` 是空串），看着像断言写错了，其实是「文件已经存在、字节还没落盘」。正确做法是等真正的完成信号（本仓的做法：等写完成后才发的那条事件），不要用加大超时或 `Thread.sleep` 糊过去。同类问题还有 UI 测试里泄漏的 viewmodel 作用域与 `database.close()` 竞争（证据与修法见 C6e 段 §5.8，已修）。
- **C6e 真窗口取证复现命令**：`./gradlew :lyrico-app:test --tests "*DevLibrarySeederTest*" -Plyrico.seedDevLibrary=1`（本批 seeder 额外跑一条真的 `EXPORT_LYRICS` 任务，产出落进 `lyrico-app/build/demo-exports/`）→ `./gradlew :lyrico-app:run` → `capture-window.ps1 -TitleLike "Lyrico 1.6"`（任务历史 `c6e-batch-list.png`）→ 点那一行（客户区 `-X 579 -Y 249`）→ `capture-window.ps1`（详情页 `c6e-export-detail.png`）→ 点 `跳过` 页签（客户区 `-X 946 -Y 192`，即捕获坐标 `(947,223)`）→ `capture-window.ps1`（`c6e-export-detail-skipped.png`）；四张裁切放大件与像素差按上面两条约定出。然后先杀掉窗口（标题匹配 `^Lyrico `）再跑磁盘链：目录列表 + sha256 → `build/ffmpeg/windows-x64/ffmpeg.exe -i <源音频>` 读 `lyrics-LYRICS` → `python -c "…sqlite3…"` 直读任务行与条目行，整理进 `docs/port-evidence/c6e-export-files.txt`。
