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
- **`stringResource` 的两个坑（本仓已踩，勿回退）**：
  1. **带参数的 `stringResource` / `getString` 不走 `String.format`**：CMP 的实现是 `replaceWithArgs` + 正则 `%(\d+)\$[ds]`，只认位置参数。本仓 20 条字符串用普通 `%d`/`%s`/`%.2f`，所以主源码里禁止直接写 `stringResource(res, args)` / `getString(res, args)`，一律走 `formattedStringResource` / `formattedString`（`StringFormattingGuardTest` 会把违规的 `file:line` 报出来）。**不要**为了迁就库去改字符串：字符串必须与 Android 逐字一致，而且 `%.2f`/宽度/精度在库的模型里根本表达不出来。
  2. **多行 XML body 的缩进不会被去掉**（`\n` 转义会被正确转成换行）。aapt2 去掉的是「首尾带换行的那段空白」，行内尾空格是故意的（`Task Type: ` 后面拼值）。所以 Compose 资源里的 `<string>` body 一律写成一行；`ComposeStringResourcesTest` 会检查。
- **P3 复现命令（数据层）**：`./gradlew :lyrico-app:test`（P3 收口时 **243 项 0 失败**；P4 各批（状态层、浏览/搜索 viewmodel、剩余 viewmodel、UI 地基、UI 轨道、选择与操作面板、独立歌曲页、专辑页与艺人页）之后，全量现为 **548 项（547 执行 + 1 门控跳过）0 失败 0 错误、64 个测试类**，按包可核对：data 203 · viewmodel 134 · utils 117 · ui 33 · screens 22 · domain 20 · platform 13 · di 5 · probe 1；其中 UI 轨道 25 项 = 封面 7 + 歌曲列表 11 + 壳 7，选择与操作面板 69 项，独立歌曲页 7 项 + 导航 5 项 + 字符串格式化 9 项，专辑页 6 项 + 艺人页 4 项 + 专辑操作 viewmodel 4 项，见 P4 节）：库读写/FTS/raw query/重开持久化/schema 保真 8 项 + 歌曲库 11 + 库索引 7 + 本地搜索 11 + mapper 5 + 标签读写 7 + 拼音排序键 7 + 歌词解码链 59（原 Android 测试整体搬迁：管道 31/列排序 18/编码器 10）+ 设置层 4 + 应用日志 6 + 路径模型 6 + 壳 3 + 扫描器 9 + 扫描端到端集成 9 + 文件重命名/删除 13 + 自定义标签键 12 + 插件表 15 + GitHub 贡献者 7 + 更新检查 12 + 批量任务 23 + **播放转发 9** + 独立歌曲页全链路 7 + 导航 5（原 2）+ 字符串格式化（守卫 2 + helper 3 + 资源 XML 4）+ 开发库播种 1（门控跳过））。测试任务注入的系统属性：`lyrico.schema.dir` / `lyrico.android.schema.dir`（schema 比对）、`lyrico.audiotag.fixtures.dir`（音频夹具，指向 `lyrico-audiotag/src/main/cpp/taglib/tests/data`），换机器无需改测试代码。
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
