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

1. **批量导出还没有入口**（#35）：8 种类型都有处理器了，但「勾一批歌 → 导出」的十二动作 FAB、两个 bottom sheet 与 `BatchExportViewModel` 还在 java 树。 **✅ 已收口（2026-10-11）**：十二动作 FAB 与八张 sheet 已搬进 kotlin 树并挂进曲库外壳，`BatchExportViewModel` 等四个批量视图模型已注册；见下面「P5 施工批次（批量发起界面：四个批量 viewmodel + 十二动作 FAB 与八个 sheet，2026-10-11）」。
2. **条目行点不进详情**（#36）：批量详情页的行点击目标是 `EditMetadataScreen`，它的目的地还没注册。
3. **错误文本未本地化**（见 §2.9）。
4. **导出目录不自动创建**（见 §2.4，这是有意的选择，但用户会看到一条英文失败）。
5. **测试卫生问题**（见 §5.8）：泄漏的 viewmodel 作用域**已修**（§5.8 坑 8）。但同一批全量跑里还露出**第二个无根的 flake**：`AlbumLibraryViewModelTest > the grid column count defaults to two and follows the saved setting` 一次 10 s 超时（`awaitUntil` 报 `condition not met within 10000ms; last value was 2`，即 DataStore 那次写入在 `viewModelScope`（Main = `Dispatchers.Default`）上一直没被观察到）；单跑该类 3 次全绿，同一棵树的下一次全量跑也全绿。P6 打包前应连着「全量跑偶发挂一次」这一类问题一起收（怀疑是同一个 JVM 里累计的阻塞线程把 `Dispatchers.Default` 饿住了，但还**没有证据**，不要凭猜去改）。

#### 7. 前沿（本批收口）

java 主树 **93 文件 / 0 待删重复 / 47 可搬 / 46 被挡**（本批 95 → 93；`git rm` java 版处理器时同时解锁 3 个被挡文件）。这两半的构成很不一样：

* **可搬的 47 个**大多是「原样搬」的小件：21 个 `ui/components/...`（liquid 材质 6 个、Fab 菜单 2 个、`PagerDotsIndicator` / `FieldOrderState` / `SearchSectionHeader`、几个 bottom sheet 与 `PainterUtils`）、`ExternalAudioEditHost`、`data/model/*`（`ArtistSeparator` / `RenamePreview` / `LocalSearchField` / `AppLanguage` / `CacheCategory`）、`domain/poster/*`、`utils/ConflictResolver`、`screens/OpenSourceLicenceScreen`、4 个批量配置 viewmodel。另一半是**该删而不是该搬**的 Android 遗留物（`utils/SafDocuments` / `SafSiblingFileWriter` / `UriUtils` / `CoverSourceType`、`platform/player/{PlayerIntentFactory,InstalledAppChecker}`、`data/exception/RequiresUserPermissionException`、`screens/QuickJsTestScreen`、`App.kt` / `MainActivity.kt`）——它们现在「可搬」只是因为依赖都搬完了，处理方式是逐个判定「桌面版还需要它吗」，不需要的删掉而不是硬搬。
* **被挡的 46 个就是剩下的界面侧**：15 个 screen（`EditMetadataScreen` 17 个依赖、`BatchEditScreen`、`BatchRenameScreen`、`SettingsScreen`、`AlbumDetailScreen`、`ArtistDetailScreen`、`LocalSearchScreen`、`FolderManagerScreen`、`AboutScreen`…）、13 个 `ui/components`、13 个 viewmodel（`EditMetadataViewModel`、`BatchEditViewModel`、`BatchRenameViewModel`、`SettingsViewModel`、`FolderManagerViewModel`…）、`di/AppModule.kt`（13 个依赖），以及 `utils/{RenameEngine,CacheManager,ArtistPosterFileWriter}`。这批留下的最大缺口（#35 的批量发起侧：`SongSelectionSupport` 16 个依赖 + 8 个 sheet）就在这份名单最前面。

### P5 修（专辑 ReplayGain「替补运行」那条用例的偶发假报警：判定改成单状态流采样，2026-10-11）

专辑 ReplayGain 那批（ReplayGain 边车 + 响度测量那一节）留下一条**偶发失败**：`AlbumReplayGainViewModelTest > a run that replaces an aborted one is not marked finished by the run it replaced`。它在 #35 切片 B 施工期间的 5 次全量跑里挂了 1 次（第 2 次，934 项里 1 失败，日志 `build/tmp-probe/sliceB-fullsuite2.log`），单跑该类 3 次全绿、同批另外 4 次全量跑也全绿。这条用例守的是那批修掉的一个真 bug——**被取代的那次测量不能把取代它的那次测量的状态抹掉**（后果是扫描中途面板变回可关闭，用户能再点一次发起重复测量）——所以它既不能删，也不能放宽到没有判定力；要修的是**判定方法**，不是被测行为。

成因分析要如实说清证据边界：那次失败的 XML 结果已经被后一次全量跑覆盖，**没有留下可复查的机制证据**，所以下面两条都只当"可能"，不做"就是它"的断言。

1. **跨流位置比较本身就是竞态（最可能）**：原判定把状态流与消息流都记进同一条 `synchronizedList`，再断言"成功消息的位置 < 第一个停止状态的位置"。这两条流各由一个协程收集，**谁先把日志写进去取决于线程调度**；收尾时成功消息与停止状态只隔几十微秒，负载重时状态先落就假报。这一条不需要任何窄窗口，正好能解释"5 次里坏 1 次"的量级，也能解释"单跑必绿"（单跑时机器空）。曾经怀疑的另一种形态（被取消那次的 `false` 被投递到替补开始之后）需要的窗口是"读状态与写日志之间"的纳秒级抢占，量级上对不上，不作为解释。
2. **JMM 可见性（真存在，但没复现）**：`AlbumActionsViewModel` 的 `replayGainRun` / `replayGainStartedAt` 原是普通 `private var`，而它们在协程 `finally`（IO 线程）里读、在调用方线程写；`replayGainJob?.cancel()` 与 token 写入之间没有 happens-before 边，被取消的那个 job 理论上可能读到过期 token，然后在替补运行**中途**写 `isCalculating=false`——正是失败消息描述的那件事。x86 的 TSO 下几乎不会发生，但它是那条防护协议本身的漏洞，顺手堵上。

#### 1. 改了什么（1 个测试类的判定 + 2 个字段的可见性）

| 文件 | 改动 |
| --- | --- |
| `viewmodel/AlbumReplayGainViewModelTest.kt` | 这条用例的判定重写（+38 −29）：删掉共用日志、`restart` 标记和 `LoggedState` / `LoggedMessage` 两个只为它存在的内部类 |
| `viewmodel/AlbumActionsViewModel.kt` | `replayGainRun` / `replayGainStartedAt` 加 `@Volatile`（+8 −0，含一段 KDoc 说明它们为什么必须发布） |

新判定不再拿"两条流谁先写日志"当证据，只用**单条状态流**（StateFlow 会丢中间值，但不会换序）：取消之后先等状态真的停下来，再发起替补并**在调用方线程同步读一次** `isCalculating == true`（VM 是先写状态、后起 job，所以这一步是确定界碑，把"取消自己写的停止"和"替补开始前任何其它停止"都排除在外），然后起一个采样协程 `yield()` 轮询到状态停下：停下那一刻若 `albumReplayGainWrittenCount < albumReplayGainSongCount`，就是**中途停**（正常收尾的停止里计数是满的）。这样判定与两个收集器之间的调度先后彻底无关。

#### 2. 取证

* **断言仍然抓得到那个真 bug**：把 VM 里的 token 判定临时改成 `if (true)`（跑完已还原），这条用例失败，且失败消息现在是**可读的完整状态**：`it stopped with AlbumActionsUiState(albumName=null, isCalculatingAlbumReplayGain=false, showAlbumReplayGainProgressDialog=true, albumReplayGainProgress=0.0, albumReplayGainSongCount=1, albumReplayGainWrittenCount=0, albumReplayGainTotalTimeMillis=3)`——`writtenCount=0` 配 `totalTimeMillis=3` 正是"中途停"的指纹。旧断言只打印 `LoggedState@6048e26a` 这类身份哈希，出问题也读不出来。
* **修后没有再挂**：单跑该类 3 次全绿；全量真跑 5 次全绿（107 类 / 934 项 / 0 失败 / 0 错误 / 2 门控跳过；日志 `build/tmp-probe/rgfix-*.log`），修前同样的全量跑 5 次里坏 1 次。
* `@Volatile` 那两处**没有**独立证据说明它就是本次失败的原因（机制 2 没能复现）；它是一条跨线程读写的防线，改动零行为变化，如实记在这里。

#### 3. 剩下的局限（如实记）

1. 采样是 `yield()` 轮询（微秒级），但"中途停"若**短于一次轮询间隔**就会被漏看——这与旧判定受 StateFlow 合并丢值影响是同一类暴露，不是新引入的。
2. 若替补运行真的**失败**（写标签失败），它的收尾状态里 `writtenCount < songCount`，采样会把这次收尾当成"中途停"；这条路径上用例会先在"成功消息"那条断言处失败（它在采样断言之前），失败消息里带着完整状态，不会误导。
3. 1/5 的失败率意味着 5 次全绿**不能证明**它不会再出现；能说清的是：上面两条已知机制都被绕开了，而且下次再出现时失败消息里有完整状态可读。

### P5 施工批次（批量发起界面：四个批量 viewmodel + 十二动作 FAB 与八个 sheet，2026-10-11）

#35 做的是「勾一批歌 → 发起批量任务」这条入口：十二个动作的展开式 FAB、它背后八张 sheet（匹配 / 响度 / 歌词处理 / 导出各有配置与进度，其中两片共用页）、四个批量视图模型，以及把这些挂进曲库外壳的那点接线。这批分两笔提交，分界不是按界面切的，是按**谁能在没有界面的情况下被独立取证**切的：视图模型那一笔靠真实基础设施（真 Koin 图 / Room / 曲库扫描 / TagLib / ffmpeg 边车 / 插件运行时 / 任务引擎）就能把「界面按下之后每一步」钉住，界面那一笔再补上「点哪一个格子会发生什么」。切片 A 已提交（`fa14952`），当时没配 PLAN 段落，在这里补记；切片 B 就是本段所属的这次提交。

#### 1. 切片 A：四个批量视图模型搬进 kotlin 树并注册（26 项测试）

| 文件 | 改动 |
| --- | --- |
| `viewmodel/BatchExportViewModel.kt` | 搬入并改一处（+20 −4）：`destinationTreeUri: Uri?` → `destinationDirectory: String?` |
| `viewmodel/BatchLyricsFormatViewModel.kt` / `BatchMatchViewModel.kt` / `BatchReplayGainViewModel.kt` | 纯 `git mv`（0/0），一个字没动 |
| `di/DesktopAppModule.kt` | +40 −0：四个 `viewModel { }` 注册，`viewModel {` 出现次数 18 → 22 |
| `di/DesktopAppModuleTest.kt` | +5 −1：把新注册纳入「图能起来」的断言 |
| `viewmodel/BatchExportViewModelTest.kt` 等四个新测试类 | 358 行 7 项 / 379 行 7 项 / 623 行 6 项 / 338 行 6 项，合计 1698 行 26 项 |

`BatchExportViewModel` 那处改动是整个 #35 里唯一「接口形状」改动：Android 走 SAF 拿到一棵可持久授权的树 `Uri`，桌面端用户在系统选择器里选的就是一个真实目录、没有任何权限可要，于是视图模型收 `String` 路径（`DirectoryPicker` 返回的 `File.absolutePath`），与曲库「添加文件夹」那条流共用一个形状。导出这条链在测试里被三层钉住：**写进任务行的 JSON**（`destinationDirectory` 与「并发保持缺省」只在这一层可见）、**盘上的 `.lrc` 字节**（任务的 `SUCCEEDED` 只说明处理器自己满意，不代表文件对了）、**`uiState` 的进度 / 计数 / 时长**（进度对话框读的就是它）。四个类头各自写明「有意不覆盖」的部分，不约而同都是界面渲染，而且都写着「跟 #35 的第二笔提交走」——就是这一笔。

另一个只有这批能覆盖的点是 `init` 里的**恢复**：上一个进程留下的 RUNNING 任务要重新挂上观察，否则用户关掉窗口再打开，进度就永远停在那里。四个测试类都从真实 `ViewModelStore` 取实例、`tearDown` 里 `clear()`，理由写在 `BatchExportViewModelTest` 类头：`viewModelScope` 只在 `ViewModel.clear()` 时才取消，而 `koin.get()` 直接拿到的实例谁也不会去清——上一阶段那个「泄漏的收集器打到已关闭的 Room 连接池」的偶发失败就是这么来的，这批不新增一份。

#### 2. 切片 B：搬了什么（12 个 `git mv` + 1 个改写 + 外壳接线 + 证据）

`git mv` 过来的界面文件（括号里是随行的改动量，`0/0` 表示一个字没动）：

* `ui/components/fab/ExpandableFabMenu.kt`、`ExpandableFabMenuStyle.kt`、`FabMenuItem.kt`（0/0 / 0/0 / 0/0）——菜单本体与样式原样可用。
* `ui/components/base/ExportDestinationBottomSheet.kt`（+12 −7）、`ui/components/batch/BatchExportBottomSheet.kt`（+25 −15）、`BatchLyricsFormatBottomSheet.kt`（+23 −14）、`BatchLyricsFormatConfigBottomSheet.kt`（+7 −5）、`BatchMatchBottomSheet.kt`（+23 −14）、`BatchMatchConfigBottomSheet.kt`（+21 −14）、`BatchRGBottomSheet.kt`（+23 −14）、`BatchRGConfigBottomSheet.kt`（+9 −6）、`ui/components/lyrics/LyricsProcessingSheet.kt`（+26 −14）——八张 sheet 加一片歌词处理页，改动都是 Android API 换成桌面等价的那些点。
* `ui/components/bar/SongSelectionSupport.kt` → `ui/components/bar/SongBatchSelectionActions.kt`（+93 −137，文件从 442 行变成 398 行）。**这是整批唯一被改写的文件**，Android 里它同时装着选择条与十二动作 FAB 两半；选择条那半在这里之前就单独搬走了，剩下的 FAB 这半改写成新 hub，改名是必须的（同名同路径会被 `port-frontier.py` 报成待删的陈旧副本）。四条改写点见下一节。

其余改动：

* `ui/components/bar/SongSelectionTopAppBar.kt`（+5 −7）：只改一段过期的 KDoc。它原来写着「FAB 那半留在 Android 树、文件名取得不一样是故意的」——现在两半都搬完了，那段话从说明变成了误导。
* `ui/navigation/Destinations.kt`（+28 −0）：新增 `BatchRenameDestination` 与 `BatchEditDestination`。**只声明、没注册**：对应的两个 screen 属于后面的批次。两个路由都不带参数，这不是漏了——它们操作的是选择集，而点下去之前 `SongSelectionViewModel.setSelectionUris()` 已经把选择集发布进 `SharedSelectionManager` 了，所以后面的批次只要补 `composable(...)` 与 screen，别的都不用动。
* `screens/LibraryHomeScreen.kt`（+58 −10）：把 FAB 挂进外壳。
* `ui/components/bar/SongBatchSelectionActionsTest.kt`（新增 577 行 7 项）、`screens/LibraryHomeScreenTest.kt`（+88 −3，+1 项，共 6 项）。
* `scripts/capture-window.ps1`（+19 −7）：加 `-Hwnd` 参数，取证工具见第 5 节。
* `docs/port-evidence/c35-*`：6 张整窗截图 + 1 张裁切 + OCR / 像素分析 / 窗口清单 / 亮度表。

#### 3. 桌面化改写点

hub 的 KDoc 把四条写在类头上，其中只有第一条是行为改动：

1. **导出行的「选文件夹」不再要 SAF 的树 `Uri`**：Android 起 `ActivityResultContracts.OpenDocumentTree`、对返回的树拿一个可持久化读写授权，再把 `Uri` 交给视图模型；Windows 应用本来就对用户选中的目录有权限，于是桌面接缝是 `DirectoryPicker`，视图模型拿 `directory.absolutePath`。选择器同时是参数（`directoryPicker`，缺省 null 时内部用 `rememberDirectoryPicker()` 开真 Swing 选择器），理由与曲库那边一样：导出这条链因此能被测试端到端驱动，不用人点原生对话框。
2. **`onBatchShare` 不收 `Context`**：Android 起 `Intent.ACTION_SEND` 选择器；桌面 `SongSelectionViewModel.share` 的行为是在资源管理器里定位到选中的歌（前面批次定过的桌面语义），`Context` 在这里根本不是个东西。
3. **`navigator` 是移植版的 `Navigator`，十二行里有两行指向已声明但未注册的路由**：「批量重命名」「批量编辑」的调用点是 Android 原样，导航器记一条日志而不是崩。
4. **字符串走 `Res.string`**，带计数的那个走 `formattedStringResource`（CMP 的 `stringResource(res, args)` 不跑 `String.format`，`StringFormattingGuardTest` 钉着这条）。

外壳接线（`LibraryHomeScreen.kt`）另有四条：

1. `show = isSelectionMode && selectedTab == LibraryTab.Songs`：只有停在「歌曲」页且处于选择模式时才出现。
2. 四个视图模型在 composable 里用 `koinViewModel()` 就地解析（与 Android 一样不往上提），sheet 与 FAB 行因此共用同一个实例；它们与批量任务列表页拿到的是同一批实例，因为 Koin 的 `viewModel` 在这个图上绑的是外壳所在的导航返回栈条目。
3. `bottomBarPadding` 用 `SideEffect` 从 Scaffold 的 `paddingValues` 抄出来再传给 FAB 的 `modifier`：窄窗口里标签栏在页面下方，FAB 必须抬起来让开它（宽窗口该值为 0）。
4. `selectedSongUris` 从 `SongSelectionViewModel` 收，`onSetSelectionUris` / `onBatchDelete` / `onBatchShare` 也接在它上面——外壳只做接线，选择集本身仍在那个已经有测试的视图模型里。

菜单展开高度上限（320 dp）与行高（56 dp）都是从 Android 原样搬来的（`fab/` 那三个文件 0/0），所以桌面上第 7 到 12 行同样要点开菜单再滚动才能看到——测试里用 `performScrollTo()` 滚，真窗口取证里也是滚下去点的，没有为了好测去改这个值。

#### 4. 无头取证（新增 8 项；全量 107 类 / 934 项 / 0 失败 / 0 错误 / 2 门控跳过）

`SongBatchSelectionActionsTest`（577 行 7 项）钉的是「点哪一个格子会发生什么」：

1. `the export row takes the directory from the picker into a real batch task`：点开导出 sheet → 选「指定文件夹」→ 人造 picker 返回一个临时目录 → 真任务真的跑起来 → 断言盘上有导出的 `.lrc`、且任务行里的目录就是那个临时目录。人造 picker 是这一步能自动化验证的原因（见第 3 节第 1 条）。
2. `the delete row asks first, and confirming hands the selection to the delete callback`：删除行先弹确认，确认之后才把选择集交给回调。
3. `the rename and edit rows publish the selection first and navigate only when it worked`：重命名 / 编辑两行先发布选择集（真返回）再导航；发布失败就不导航——也就是那两行「点了没反应」的路径，测试钉的是「选择集确实已经发布」这一半。
4. `the organize row opens its submenu and the operation it offers reaches the config sheet`：整理歌词是二级菜单，选中一项之后到达配置 sheet。这里只断言 SORT 这一项，菜单里其余操作的文案与语义在别处已经验过，重复断言只会让这条用例变脆。
5. `the menu carries all twelve rows the shell offers`：十二行都在（包括 320 dp 上限下要滚动才可见的那几行）。
6. `a selection that is empty leaves the FAB inert`：选择集为空时 FAB 什么都不做。
7. `tapping the scrim closes the menu without an action`：点遮罩关菜单且不触发任何动作——**模态是被断言的，不是被绕开的**。

`LibraryHomeScreenTest` 新增 1 项（共 6 项）：`the batch FAB is the shell's, and only shows while songs are selected on the songs tab`——把 FAB 属于外壳、以及它出现的两个条件（选择模式 + 歌曲页）钉在外壳这一层。

写这批用例时实测出来的三条写法，后面还会用到：Miuix 的弹层在无头测试里**可以**被测（先证明再依赖，没有为了可测去改组件）；不存在 `fetchSemanticsNodeOrNull()` 这种东西，判「有没有」用 `onAllNodesWithText(X).fetchSemanticsNodes().isNotEmpty() / isEmpty()`；`performTouchInput { click(Offset) }` 需要显式 `import androidx.compose.ui.test.click`，点遮罩用 (8f, 8f)（窗口左上角，任何一层都挡不到那儿）。

全量账：切片 A 的 26 项 + 切片 B 的 8 项 = 34 项，934 − 34 = 900，正好是上一批（ReplayGain 边车）收口时的 900。

#### 5. 真窗口取证（docs/port-evidence/c35-*）

一次会话走完整条链，进程 pid 135388、结束时杀掉；曲库数据目录在 `.gitignore` 里，没有进仓库。六张整窗截图按顺序：

| 截图 | 内容 | 怎么证明它是真的 |
| --- | --- | --- |
| `c35-songs-page.png` | 曲库「歌曲」页，24 首扫描出来的歌 | 亮度基线（补丁区均值 243.81），前一张基准 |
| `c35-selection-fab.png` | 长按选中一首（客户端 (200, 97) 松手）→ 选择条出现 → 点 FAB（客户端 (1119, 688)） | 补丁区 243.90，说明此时还没有遮罩 |
| `c35-batch-menu.png` | 十二行菜单展开 | 与上一张比 89.52% 的像素变了；补丁区掉到 195.52，遮罩真的画了 |
| `c35-export-destination.png`（+ 裁切 `-crop.png`） | 导出 sheet 压在菜单之上 | 补丁区 169.89（更深一层遮罩）；裁切图 OCR 读出「导出位置 / 指定文件夹 / 选择导出文件的保存文件夹 / 确认」 |
| `c35-folder-picker.png`（+ `-windows.txt`） | 点「指定文件夹」后弹出的系统选器 | `EnumWindows` 清单里多出一个 `SunAwtDialog`，标题正是「导出歌词」，可见性 True——即 `JFileChooser` 的目录模式，SAF 的桌面替代真的起来了 |
| `c35-export-cancelled.png` | 在选器里取消、再用 Esc 关掉 sheet 与菜单 | 补丁区回到 243.90；库里 `batch_tasks` 仍是最初那 5 行播种数据——**取消就是没发起任务**，不是靠像素猜的 |

亮度表写在 `c35-scrim-luminance.txt` 里（方法、图名、两个区域的均值、怎么读都写着）：同一块补丁区在同一会话里 243.90 → 195.52 → 169.89 → 243.90，遮罩跟着模态出现、跟着模态消失。这张表存在的理由是单看截图分不出「画了遮罩」和「sheet 本来就不透明」。

窗口标题在截图里是 `Lyrico 1.6.0 (fa14952)`：会话当时 `fa14952` 还是 HEAD，切片 B 的改动还没提交（构建信息里的短哈希来自 git，不来自工作区状态）。`-Hwnd` 参数就是为了这一步加的：`capture-window.ps1` 原来只能按进程找主窗口，取不到**另一个**窗口（选择器是 `SunAwtDialog`，不是主框架）；现在可以先列出全部顶层窗口的 hwnd，再按 hwnd 精确截图。另一个坑也一并说清：控制台是 GBK，中文窗口标题经 PowerShell 参数传进去会被搞坏，所以脚本从不接收标题，只接收 hwnd（`param()` 必须是脚本第一条语句，这也是那次踩出来的）。

#### 6. 这批实测出来的坑（都会再踩）

1. **无头里没有 `fetchSemanticsNodeOrNull()`**：判节点有无要数节点数（见第 4 节）。
2. **`performTouchInput { click(Offset) }` 要显式 import**，否则编译错误出现在调用点、看起来像 API 不存在。
3. **Miuix 的模态遮罩可测，但要真的点它**：点 (8f, 8f) 关菜单，然后断言「没有动作发生」，而不是换个方式绕开遮罩。
4. **「选择集为空」不等于「没有专辑」**：空选择时 FAB 该是什么都不做，这条要单独写，别混进「库里没歌」的场景里。
5. **320 dp 上限意味着后几行不可见**：断言第 7–12 行必须先 `performScrollTo()`；同理真窗口取证里得先滚。
6. **sheet 标题与触发它的行常常同名**：断言只能用「存在 / 不存在」这一档（`onAllNodesWithText`），别去分辨「标题那一处还是行那一处」。
7. **控制台是 GBK**：中文标题进 PowerShell 参数会被 mangle，重定向输出也会丢字符——脚本改用 hwnd 定位窗口，输出用 `| Out-File -Encoding utf8`；用 `read` 工具（而不是控制台）核对 UTF-8 文本。
8. **取消的证明要去数据库看行数**，不要只看界面回到了干净状态。
9. **取证结束后应用窗口还开着会让 Gradle 报 `BUILD FAILED`**：这是预期现象（构建产物被占用），先关窗口再跑构建。

#### 7. 用户可见缺口（如实记录）

1. **没有 `BackHandler`**：Android 的返回键在这两处被用到（关菜单、退出选择模式），桌面端没有等价物接到这两个位置——菜单只能点遮罩关，选择模式只能点选择条上的关闭按钮或切标签页退出。这是零散的键盘 / 鼠标可达性损失，不是功能缺失。
2. **`ExpandableFabMenu.kt` 里 FAB 的 `contentDescription` 是 Android 硬编码的英文字面量 `"Actions"`**（原样搬来的，0/0），所以读屏用户会听到英文。不是这次移植引入的，但确实是个可改的点。
3. **菜单 320 dp 上限**（Android 自带的值）：十二行要滚动才能看全。
4. **「分享」= 在资源管理器中定位**（桌面语义裁决的结果），不是缺功能。
5. **「批量重命名」「批量编辑」两行点了没反应**（路由已声明未注册），留给后面的批次；导航器有日志。

#### 8. 前沿（本批收口）

`src/main/java` 树 89 → 76 个文件；前沿（依赖已满足、现在就能搬）48 → 40；被挡 41 → 36；陈旧副本 0。

算术要拆开看，因为「搬走的 13 个路径」里有 4 个本来是被挡的：**9 个本来就在前沿上**（八张 sheet 里除 `BatchLyricsFormatConfigBottomSheet` 之外的七张、`fab/ExpandableFabMenuStyle.kt`、`lyrics/LyricsProcessingSheet.kt`）；**3 个本来被挡、在这一批里被同批搬走的邻居解锁**——`fab/ExpandableFabMenu.kt` 与 `fab/FabMenuItem.kt` 都只被 `fab/ExpandableFabMenuStyle` 挡着，`batch/BatchLyricsFormatConfigBottomSheet.kt` 只被 `lyrics/LyricsProcessingSheet` 挡着；**第 4 个是被删并改写成 hub 的 `ui/components/bar/SongSelectionSupport.kt`**（它自己当时被 12 个依赖挡着，就是刚搬的这九个文件加三个 fab 文件）。于是 48 − 9 + 1 = 40、41 − 4 − 1 = 36：前者那条「+1」是 `screens/AlbumDetailScreen.kt`，它当时只有一个依赖，正是 `SongSelectionSupport.kt`，这个依赖随 hub 改写消失，它从被挡升到前沿，还留在 java 树里等下一批。

下一批（#36 及之后）的界面侧可以从这 40 个里挑：其中 4 个是 screen；被挡的 36 个里还剩 13 个 screen，加上 `di/AppModule.kt` 与 `MainActivity.kt`（Android 专属，不会搬）。批量重命名 / 批量编辑两个 screen 就在这 36 个里等它们的依赖。

### P5 施工批次（编辑元数据页：5 个叶子组件 + 2461 行整页 + 结果回传的接收半，2026-10-11）

`EditMetadataScreen` 是移植里第一个**跟应用外部对话**的页面：安卓版用 SAF 选封面 / 海报 / 歌词文件，用 `IntentSender` 跟用户要一次写授权，用 `Intent` chooser 把歌交给播放器，还要把「外部编辑请求」的结果回传给宿主它的 `Activity`。这批分两笔提交，分界跟 #35 一样是「谁能在没有界面的情况下被独立取证」：切片 1（`20c92b6`）搬 5 个叶子组件并补齐它们的平台能力，切片 2（本段所属的提交）搬那个 2461 行的页面本体、把结果回传的**接收半**写出来、注册路由并出窗口证据。切片 1 当时没配 PLAN 段落，在这里补记。

#### 1. 切片 1：搬了什么（5 个文件 + 3 个新文件；java 主树 69 → 64）

| 文件 | 改动 |
| --- | --- |
| `ui/components/PagerDotsIndicator.kt` | 纯 `git mv`（0/0） |
| `ExternalAudioEditHost.kt` | 纯 `git mv`（0/0）；这一笔只搬接口，接缝在切片 2 才接上 |
| `ui/components/base/LyricsOffsetField.kt` | +20 −11 |
| `ui/components/poster/ArtistPosterMenu.kt` | +28 −15 |
| `ui/components/crop/ImageCropper.kt` | +35 −23 |
| `ui/components/crop/ImageBitmapOps.kt` | 新增 89 行 |
| `ui/components/cover/CoverSourceImages.kt` | 新增 162 行 |
| `utils/LocalImageSize.kt` | 新增 62 行 |
| `platform/FileOpenPicker.kt` | +25 −8 |
| 9 个新测试类 | 1333 行 73 项 |

#### 2. 切片 1 的改写点（4 条）

1. **`ImageCropper` 的像素层整体换成 Compose**：安卓那边直接吃 `android.graphics.Bitmap`，桌面端没有，于是像素操作全走 `ImageBitmap`，落在新文件 `ImageBitmapOps.kt` 里。**单独成文件不是洁癖**：`androidx.compose.foundation.Canvas` 与 `androidx.compose.ui.graphics.Canvas` 同名，同文件里同时用到就编不过。`downscaleBitmap` 从 `private` 放成 `internal` 并加了 `maxPixels` 参数，测试才能拿 8 像素的小图验证缩放边界；`scaleTo` 用 `FilterQuality.Low` 对齐安卓的 `filter = true`，`crop` 是复制语义。
2. **5 处 `stringResource(res, args)` 改成 `formattedStringResource`**：CMP 的 `stringResource` 重载不做 `String.format`，裸 `%d` / `%s` 会原样渲染。这 5 处是 `StringFormattingGuardTest` 抓到的 —— 那两个文件原本躺在 java 树里，而守卫只扫 kotlin 树，随这次迁移才第一次被覆盖。这正是「迁移」与「守卫」放进同一笔提交的理由。
3. **`CoverSourceImages.kt`（新）**：`decodeCoverBitmap`（同步解码封面来源）与 `readCoverSourceSize`（量尺寸）。封面来源可能是本地文件、外置海报文件或网络 URL，`CoverRequest` 走 `readExternalArtistPoster`，量的就是**要显示的那个文件**；`CoverSourceImage` 只直绘 `ImageBitmap`，其余交给 Coil（`CoverDisplayModelTest` 用真 loader 证明 `ImageBitmap` 与 `file://` 都是 `ErrorResult`，即那个分支存在的理由）。
4. **`LocalImageSize.kt` + `FileOpenPicker.extensions`（新）**：`ImageIO` 只读文件头取宽高，任何失败静默返回 `null`（与 `readRemoteImageSize` 的「超时 + 日志」契约**故意不统一**，理由写在文件头：量本地图的尺寸失败不值得打扰用户）；`FileOpenPicker` 增加 `extensions` 参数，匹配逻辑抽成 `matchesExtensions` 以便无头测试 —— 对话框本身是原生窗口，驱动不了。

切片 1 的账：122 类 1078 项 0 失败 2 跳过（原 113 类 1005 项，新增 9 类 73 项：`ImageBitmapOps` 4 + `ImageCropper` 16 + `LocalImageSize` 6 + `FileOpenPicker` 4 + `CoverDisplayModel` 4 + `CoverSourceImages` 12 + `PagerDotsIndicator` 4 + `LyricsOffsetField` 14 + `ArtistPosterMenu` 9 = 73）。

#### 3. 切片 2：搬了什么（`git mv` 1 个 2461 行 + 5 个改写（其中 1 个只改 KDoc）+ 2 个新文件 + 2 个 drawable + 3 个测试文件；java 主树 64 → 63）

| 文件 | 改动 |
| --- | --- |
| `screens/EditMetadataScreen.kt` | `git mv`，2461 → 2485 行（+340 −316） |
| `ExternalAudioEditHost.kt` | +26 −1：把接缝从 `Activity` 换成 `CompositionLocal` |
| `platform/FileOpenPicker.kt` | +4 −2：`"*"` 通配 |
| `ui/navigation/Destinations.kt` | +12 −0：声明 `EditFieldSettingsDestination`（**只声明、没注册**） |
| `ui/navigation/LyricoNavHost.kt` | +31 −0：注册 `edit_metadata`，并接上三个结果接收半 |
| `ui/navigation/ResultBackNavigator.kt` | +3 −3：只改 KDoc（发送半早就写好，等的是接收半） |
| `ui/navigation/ResultRecipient.kt` | 新增 81 行 |
| `composeResources/drawable/ic_filled_star_24dp.svg` / `ic_outline_star_24dp.svg` | 新增 237 B / 258 B |
| `test/screens/EditMetadataScreenTest.kt` | 新增 520 行 5 项 |
| `test/ui/navigation/ResultRecipientTest.kt` | 新增 177 行 3 项 |
| `test/support/HeadlessMainDispatcher.kt` | 新增 41 行 |

那两个 svg 不是新图：安卓资源里是 `res/drawable/ic_*_star_24dp.xml` 的 vector，路径 / 颜色 / 线宽逐字对应（填充 `#FFD700`、描边 `#999999`），只是 CMP 的 compose resources 只认 svg/png，所以补了一份等价的 svg —— 评级那一栏的星星就是它俩。

#### 4. 切片 2 的桌面化改写点（逐条）

每条都写在代码注释里，行号是这次提交后的 `EditMetadataScreen.kt`。

1. **外部编辑的宿主从 `Activity` 变成 `CompositionLocal`**（334、348 行）：安卓是 `context as? ExternalAudioEditHost` → `finishExternalAudioEdit(requestId, saved)`。桌面没有 `Activity`，调用方也不在进程外 —— 就是拥有整张导航图的窗口。于是接缝是 `LocalExternalAudioEditHost`（`staticCompositionLocalOf`，缺省 `null`）。**没有宿主时记日志而不是静默吞掉**：请求 id 与「存没存」对调用方是有意义的（下面有一条用例专钉这句日志）。
2. **`activity.finish()` 变成日志**（358 行）：没有外部请求时，返回就是普通 `popBackStack()`；`popBackStack` 返回 false 说明编辑页不在返回栈里 —— 而编辑页**永远不是窗口的根目的地**，所以「结束」在桌面上没有意义，只记一条警告。
3. **两个 `BackHandler` 整段删掉**（711 行）：外部编辑时按返回键 = 关编辑页、展开的浮动菜单按返回键 = 收起菜单。桌面没有系统返回键 / 手势（见第 9 节第 1 条）。
4. **`WindowInsets.isImeVisible`（安卓专属扩展）删掉**（443 行）：没有软键盘，也就没有「键盘弹出时收起浮动工具栏」，浮动工具栏始终可见。
5. **SAF / `ActivityResultLauncher` → 原生对话框**（517–525 行）：选封面、选海报、导入歌词、导出封面、导出艺人图全改成 `rememberFileOpenPicker` / `rememberFileSavePicker`，拿到的是真 `File`。
6. **`permissionIntentSender` 那整段 `LaunchedEffect` 删掉**（613 行）：往 SAF 文档里写之前要先跟用户要一次授权；桌面写的是真实文件，没有这层授权。
7. **`Toast` → 状态 + snackbar**（702 行）：`PlayerPickerBottomSheet`（`Intent` chooser）按已裁决的播放策略换成 `PlaybackRepository`，结果落在 `uiState.playMessage`，界面用 snackbar 显示 —— 跟 `sameAlbumCoverMessage` 同一条策略。
8. **`ClipData` → `ClipEntry(StringSelection(...))`**（1482 行）：桌面没有 `ClipData`，直接包纯文本。
9. **导出艺人图先拿保存路径再交给视图模型**（1660 行）：安卓是 `exportArtistImage(context, target)`，内部再弹 `CreateDocument`。
10. **封面来源的解码与量尺寸不再需要 `Context`**（318、548、1996 行）：`file://` 包装也不需要，歌曲路径就是绝对路径本身。
11. **图片过滤改成文件名扩展名列表**（267 行）：`LocalImageExtensions = jpg/jpeg/png/webp/bmp/gif`（含 `ImageIO` 解得开的格式，否则选进来也读不出像素）。安卓把「什么算图片」交给系统（`PickVisualMedia`），桌面端只能自己列 —— 这是行为偏差，记在第 9 节第 6 条。
12. **导入歌词不过滤**（270 行）：`listOf("*")` 等价于安卓 `OpenDocument` 传通配 MIME（`matchesExtensions` 支持 `"*"` 这一条也是切片 2 加的）。

#### 5. 结果回传的两半（`ResultRecipient.kt`）

`ResultBackNavigator.kt`（**发送半**，前一批就写好了）当时在 KDoc 里写着「接收半等 `EditMetadataScreen` 那批」。这批把它补上：

* 键由**发送方**的基础路由决定（`resultKey(route)`）：一个屏幕会从几个子屏幕收结果（编辑页同时收「封面搜索 / 歌词搜索 / 歌词结果」三路），键必须分得开。NavHost 里把三个发送方的名字写**一次**，两半因此不会各说各话。
* `rememberResultRecipient(handle, route)` 从 `SavedStateHandle` 取 `getStateFlow<T?>(key, null)`：**观察而不是轮询** —— 结果可能在这屏组合还没搭完时就落进来了。
* `onResult { }` 在 `LaunchedEffect(value)` 里跑回调，跑完 `consume()`：同一个结果不会因为重组、或「返回再来一次」被重放；连续两次相同结果仍然各触发一次，因为中间 handle 回到了 `null`（键因此变了）。
* 类型参数里的「目的地类型」没了：它原来只用来生成键，现在键是发送路由的函数，所以每个发送方一个 `ResultRecipient<T>` 就够。类名与调用形状（`onCoverSearchResult.onResult { ... }`）刻意保持 Android 原样，搬过来的屏幕主体读起来跟安卓一致。

#### 6. 无头取证（新增 2 类 8 项；全量 124 类 / 1086 项 / 0 失败 / 0 错误 / 2 门控跳过）

`EditMetadataScreenTest`（520 行 5 项）跑的是**真基础设施**：真 Koin 图（`desktopAppModule`）、真 Room、真 `LibraryScanManager` 扫描、真 TagLib 写夹具（`placeTaggedFixture` 把 `bladeenc.mp3` 写成 `Editor Song / Editor Artist / Editor Album`），挂的是**真 `LyricoNavHost`**（`LibraryHost()`）或「真 `NavHost` 条目里的编辑页」（`EditorInTestHost`）。只有两处 OS 接缝是假的，理由都写在类头上：`PlaybackRepository`（否则真去拉系统播放器）与 `PlatformLog` sink / `ExternalAudioEditHost` 记录器。

1. `tapping a song in the library opens the editor on that file's tags`：扫描真的出一行 → 点它 → 等编辑页读出标签 → 点左上角返回 → 等「库里的行回来了**且**编辑页的表单走了」。这条钉的是 `edit_metadata` 的注册与编码路径参数（单测够不着的那半），以及「没有外部请求时返回就是普通 pop」。等待条件是**两个半边合起来才有意义**：歌曲页没有任何可编辑字段，而「正在离开的那一页」是行标题出现的另一个地方。
2. `play hands the song to the shell, and a missing player reaches the user`：FAB → 播放 → 假 shell 收到的正是被打开那首的路径，且 `no_player_found` 到达界面。
3. `the field-settings action logs the destination that has no screen yet`：点「字段设置」→ 导航器记一条含 `edit_field_settings` 与 `not ported yet` 的日志，并断言编辑页**仍是当前页**（降级可生存，不是崩）。
4. `saving an external edit request tells the window that launched it`：带 `externalEditRequestId` 时点保存，先等到保存成功的提示，再断言 `(11L, true)` 这一对交给了宿主 —— 请求 id 就是「同时有两笔编辑在飞」时能分开的那一位。
5. `an external edit with no host logs the miss instead of dropping the result`：没有宿主时保存**照样写文件、照样提示用户**，只有回传那条落进日志，且日志里点名了请求 id。

`ResultRecipientTest`（177 行 3 项）钉上一节那三条断言：同一个结果只处理一次、重组不重放；结果按**发送路由**分命名空间；发送半与接收半在一张**真导航图**里对得上（`NavHost` + `ResultBackNavigator` + `rememberResultRecipient` 各取真实的那个）。

账：122 类 1078 项 → **124 类 1086 项**（+2 类 +8 项 = 5 + 3）。

#### 7. 真窗口取证（docs/port-evidence/c36-*）

一次会话：重跑 seeder（24 首，与 C6c / C6e 的复现说明一致）→ `./gradlew :lyrico-app:run`（窗口 `hwnd=11209968`，标题 `Lyrico 1.6.0 (20c92b6)`，1166×773、客户端区 1164×741；数据目录 `lyrico-app/data` 便携模式；结束时杀掉窗口）→ 截图 → 点行 → 截图 → 点保存 → 截图。

| 产物 | 内容 | 怎么证明它是真的 |
| --- | --- | --- |
| `c36-library.png`（+ `.ocr.txt` / `.analysis.txt`） | 会话起点：曲库「歌曲」页 24 首 | 像素分析 503 色 / 最常见色占比 0.831 / `text_pixels` 15396 / 28 行全有细节；OCR 47 行，含「歌曲（24）」与 `September`、`山丘` 等行文本 |
| `c36-editor.png`（+ 同上） | 点第一行（客户端 (200, 97)）之后落的编辑页 | `text_pixels` 13345；密度图第 5–10 行（≈120–280 px）被封面块占满，300–1000 列除页边符号外全空 —— 版式那句「与安卓一致」见本节末 |
| `c36-editor-crop.png` + `c36-editor-crop.ocr.txt` | 上图的字段区裁切，(0,320)-(520,773) 3× LANCZOS（按 C6d 的裁切约定，只是命名用了 `-crop` 而不是 `.crop`） | OCR 读出**评级 / 花心 / 周华健 / 专辑艺术家 / 朋友 / 日期**（标题值那一行被认成 `亻 心`，就是「花心」）。反推坐标 = `(x/3, 320 + y/3)`。标题字段值「花心」就是磁盘链里被写的那一首 —— 两处证据互相锁定 |
| `c36-save-1.png`（+ 同上） | 点右上角保存（客户端 (1128, 25)）之后 | 落回曲库页 —— 保存成功即 `closeEditor(saved = true)`（`EditMetadataScreen.kt:637`），所以这张图证明的是「保存这条链跑完并按设计弹回」，不是「按钮没反应」 |
| `c36-save-log.txt` | 同一会话的 app 运行日志（103 行，含开头三行说明） | `AudioTagWriter: Write tag:` 36 项（`TITLE` / `RATING` / `RATE` / `LYRICS` / `UNSYNCED LYRICS` / `ALBUM` / `ARTIST` …），紧接着 `AudioTagReader: Read tag:` 读出 `TITLE = 花心 / ARTIST = 周华健 / ALBUM = 朋友 / LYRICS = [00:00.00]花的心藏在蕊中…` —— 真窗口点保存 → TagLib 真写 → 曲库刷新真重读。**本批最强的一条** |

磁盘链（数据目录在 `.gitignore` 里，没有进仓库）：`2 花心.wav` 的 size 58100 **不变**、sha256 `3dbacca7…` **不变**、mtime `09:26:01 → 09:28:35`（就是保存那一刻）；`1 朋友.mp3` 的 mtime / sha256 **完全未动**。两件事因此成立：保存写的是**被打开的那一首**，而且写回的是同一批字节（幂等重写，标签本来没被改动过）。

两条如实记：

* **「保存成功」的 snackbar 没被截到。** `LaunchedEffect(uiState.saveSuccess)` 先 `showSnackbar` 再 `closeEditor`，编辑页一弹回，snackbar 随它一起走，所以截图里已经是曲库页。无头那条用例钉的是 snackbar 文案与宿主回传，窗口里能钉的是 mtime 与日志；这里不拿「回到曲库」冒充「看到了成功提示」。
* **整窗 OCR 读不出完的表单。** 编辑页的字段标签小且浅，整窗只读出 8 行（`text_pixels` 与库页相当，但可识别的**行**少得多：库页 47 行），所以配了 3× 裁切图 —— 跟 C6d「底部面板的小字要裁切放大后才 OCR」同一个教训，两种产物都进仓库。

版式与安卓一致：`HEAD` 里的 java 版本同样是 `Scaffold` + `LazyColumn`（java 877 行）且封面是**第一个 item**（java 1004 行 `CoverSection(`），移植版在同一个位置（kotlin 957 / 1084 行）。窗口右侧大面积是页面底色，不是被裁掉的控件。

#### 8. 这批实测出来的坑（都会再踩）

1. **无头里导航需要主线程调度器**（这条花的时间最久）：lifecycle 2.11 的桌面实现在写返回栈条目的生命周期时会校验「在主线程上」，而无头测试里点击跑在测试 worker 线程、`closeScene` 跑在 Swing EDT —— 两者都不是对方的「主线程」，于是导航一写就挂。修法是 `Dispatchers.setMain(Dispatchers.Unconfined)`（`HeadlessMainDispatcher`，setUp 装 / tearDown 还原），`Unconfined` 让校验在任何线程都过。**这是测试夹具与线程模型的错配，不是移植 bug**：生产里点击与组合都在同一个 UI 线程上，一个字没改。
2. **零尺寸节点会「假通过」位置 + 尺寸匹配器**：没被放置 / 已脱离的节点边界是全 0，`top < 32 && left < 32 && w < 64 && h < 64` 这类条件对它**恒真**。症状是 `Failed to inject mouse input ... found 3 nodes`。修法是匹配器一律要求 `bounds.width > 0f && bounds.height > 0f`，再叠一个 `config.contains(SemanticsActions.OnClick)`。
3. **库里的行文本把艺术家和专辑并在一起**（`[MP3, Row Song, Editor Artist, · Editor Album, 0:03, 64kbps]`）：所以「现在是编辑页还是库页」不能用标签值判，要用 `hasSetTextAction()`（库页没有任何可编辑字段，这一条是用 `printToString` 倒出的文本树确认的）。
4. **`action_add_folder` 只是空状态按钮的文案**（`screens/library/SongsPage.kt:198` / `ui/components/song/SongListEmptyState.kt:24`）：有行的时候断言它「不存在」是**恒真**的，等于没断言 —— 已经从等待条件里删掉。
5. **`SemanticsConfiguration.getOrNull(...)` 在这个 Compose 版本里不存在**：判断有没有某个语义动作要写 `node.config.contains(SemanticsActions.OnClick)`。
6. **失败信息里打印的节点边界可能是「之后某个状态」的**：它会与匹配器的判断互相矛盾，所以排查这类问题要在匹配器里临时 `println` 出真正被比较的值，别信失败信息里的那组数。
7. **`data object` 目的地要按对象本身导航**（`navigator.navigate(EditFieldSettingsDestination)`）：写成 `Foo()` 会被报成 `Unresolved reference`。
8. **中文注释里不能出现 `*/`**：KDoc 里写通配符 `"*/*"` 之类的串会提前结束块注释。
9. **`StringFormattingGuardTest` 会拦下整棵 kotlin 主树里的多参 `stringResource`**：崩的是全量构建，报的是别处的行号（这次是 `label_artist_image_conflict_title`，当时的 1788 行、现在的 1800 行），改成 `formattedStringResource` 即可；迁移一批文件之前先按守卫的规则扫一遍更省事。
10. **取证结束后应用窗口还开着会让 Gradle 报 `BUILD FAILED`**（与 #35 同）。

#### 9. 用户可见缺口（如实记录）

1. **两个 `BackHandler` 没了**：外部编辑时「返回 = 关编辑页」和「展开的浮动菜单按返回 = 收起」在桌面上都没有等价物。菜单还能点别处关，编辑页还有左上角那个返回箭头，所以是可达性上的损失，不是功能缺失。
2. **`activity.finish()` 那条路只剩日志**：编辑页永远不是窗口的根目的地，所以「结束」在桌面上没有意义（真走到那一步也只记一条警告）。
3. **外部编辑请求还没有宿主**：`LyricoDesktopApp` 已经把文件关联参数接进来了，但还没有 `provide` 一个 `ExternalAudioEditHost`，所以「双击关联文件打开编辑页」这条流目前会走「没有宿主」那条日志分支 —— 跟 P6 的文件关联一起做。
4. **`EditFieldSettingsScreen` 还没搬**：编辑页的「字段设置」入口可达但目的地未注册，导航器记日志（无头用例 3 钉着）。这是本批唯一一个「入口在、屏幕不在」的地方。
5. **`PlayerPickerBottomSheet` 不搬**：播放策略裁决为「交给系统默认关联程序」（`PlaybackRepository`），那个 java 文件因此不会被移植，将随清理批次删除。代价是用户不能像安卓那样在应用内选播放器。
6. **图片选择按文件名过滤**：只认 `jpg/jpeg/png/webp/bmp/gif`，选不到列表以外的格式（例如 `heic`）。安卓把这判断交给系统，桌面端只能自己列。
7. **没有 Toast**：播放失败 / 无可用播放器只剩 snackbar 一条可见通道（与 `sameAlbumCoverMessage` 同策略）。
8. **保存成功的提示在真窗口里几乎看不到**：保存成功立刻弹回上一页（安卓原样行为），桌面上弹回更快，所以提示更短命（见第 7 节）。

#### 10. 前沿（本批收口）

`src/main/java` 树 64 → **63** 个文件；前沿（依赖已满足、现在就能搬）33 → **33**；被挡 31 → **30**；陈旧副本 **0**。

算术只有一处要解释：这批搬走的 `EditMetadataScreen.kt` 本来**不在前沿上** —— 它被 `ui/components/player/PlayerPickerBottomSheet.kt` 挡着（就是第 9 节第 5 条那个不搬的 chooser），所以它属于「被挡」那一列；而 java 树里没有任何文件 import 它，于是它一走也没有邻居被解锁（前沿因此不动）。`PlayerPickerBottomSheet.kt` 现在少了一个依赖者，但它自己标着 `[ANDROID]`（依赖 `Intent`），等清理批次删掉。

下一批（#37）继续搬 screen：前沿那 33 个里已经有 `AlbumDetailScreen.kt`、`ui/components/album/AlbumListItem.kt`、`OpenSourceLicenceScreen.kt`、`ArtistSplitSettingsScreen.kt` 这几种成品，被挡的 30 个里是设置 / 艺人详情 / 本地搜索 / 文件夹管理那一串（`AlbumDetailScreen` 从 #35 起就在前沿上：它当时唯一的依赖 `SongSelectionSupport.kt` 被改写成 hub，所以它是「已就绪、只等改写」，不是被挡；它在下一节 #37-A 里搬走了）。

### P5 施工批次（#37-A：专辑详情页 + 第一个「数字参数」的已注册路由，2026-10-11）

#37 是「继续搬 screen」，按切片拆成一屏一片（切片名与顺序以 `todo` 里的 #46–#50 为准）：A 专辑详情（本节）、B 艺人详情 + 艺人海报簇、C 设置簇（settings + edit_field_settings + 文件夹管理）、D 本地搜索、E 批量重命名 + 批量编辑、F 关于页 + 更新对话框 + 开源许可页。本节只记 A 片；其余五片每一片落盘时各自成节。

#### 1. 搬了什么（`git mv` 1 个文件；java 主树 63 → 62）

`screens/AlbumDetailScreen.kt`（约 490 行）搬进 kotlin 树并完成桌面化改写。没有新文件：它依赖的 `AlbumDetailViewModel` / `AlbumActionsViewModel` / `SongSelectionViewModel`、四个组件（`SongListItem`、`SongActionSheets`、`SongBatchSelectionActions`、`AlbumReplayGainProgressBottomSheet`）与 `CoverImage` 全部在之前的批次里就位。

搬之前先按「消费者先行」确认了它的邻居：java 树里只有 `AlbumsPage` 的卡片导航到它；`ui/components/album/AlbumListItem.kt` 名字像它的一部分，但两个消费者是 `ArtistDetailScreen` / `LocalSearchScreen`（都还在 java 树里），所以那一个留给 C/D 片，不在这批动。

`LyricoNavHost.kt` 侧三处：

* 注册 `composable(route = AlbumDetailDestination.PATTERN, arguments = listOf(navArgument(ARG_ALBUM_ID) { type = NavType.LongType }))`；
* 新增私有 `SavedState?.readLong(key, default = 0L)`（默认值 0 不是合法专辑 id，所以参数丢了只会渲染一张空页，不会渲染成别人的专辑）；
* `Destinations.kt` / `AlbumsPage.kt` 里「这条路由声明了但没注册、导航器会记一条日志」的 KDoc 同步改成事实（«某路由已注册» 这类陈旧注释是每批都要顺手清的，已列进清理批次的检查项）。

#### 2. 桌面化改写点（逐条）

1. **`Toast` → `SnackbarHost`**：专辑动作的回报走 `UiMessage.resolve()` + snackbar，与 `AlbumsPage` 收同一条 `AlbumActionsViewModel.events`。
2. **`LocalContext` 丢掉**：它只用来 `Toast` 和 `shareAlbum(context, songs)`，而分享在桌面是「在资源管理器里定位」，不需要 `Context`。
3. **`BackHandler` 丢掉**（`LibraryHomeScreen` 同款）：FAB 菜单靠点 `ExpandableFabMenu` 铺的遮罩关，退出选择模式靠选择栏的关闭按钮。
4. **`@Destination` / 生成的 `EditMetadataDestination` / `DestinationsNavigator` → 手写 `Navigator`**：点一行导航到 `ui.navigation.EditMetadataDestination`，与库页是同一个手写条目。
5. **`koinActivityViewModel` → `koinViewModel()`**（对 `NavBackStackEntry`）：即安卓那个 activity 级作用域的等价物。
6. **两处调用点差异**：`SongActionSheets` 现在收 `onShare` / `onCopy`（安卓从 `Context` 推出 `ACTION_SEND` 与 `ClipData`）；`batchShare` → `share`（两条分享路在桌面是同一条）。
7. **歌曲数走 `formattedStringResource(Res.string.song_count, n)`**：`StringFormattingGuardTest` 会拦多参 `stringResource`；迁这批之前按它的规则预扫过整批，报出来的两处（`song_count` 与 `dialog_delete_album_content`）就在这个文件里，改写时一并修掉。

#### 3. 无头取证（新增 1 类 5 项；全量 125 类 / 1091 项 / 0 失败 / 0 错误 / 2 门控跳过）

`AlbumDetailScreenTest` 全栈是真的：临时目录由应用自己的扫描器扫，tag 由真 tag 仓库写、真 tag 仓库读回，专辑与曲目从真 Room 库经 `LibraryIndexRepository` 出来，页面渲染的就是那条 observable 查询的结果。

1. **表头 + 本专辑自己的顺序**：两个夹具的文件名与 track number **故意相反**（`b.mp3` 是第 1 首、`a.flac` 是第 2 首），断言按 `boundsInRoot.top` 排下来第 1 首在上；行首两个 track number 都在，且 `—` 不出现（两首都写了 track number）。另外钉 `2 首` 这条**代入后的**文案：整窗 OCR 曾经抓到过「%d 首」这种占位符漏出去的画面，这条断言就是防它。
2. **点一行 → `EditMetadataDestination(songFileUri = song.uri)`**，按值断言：路由名对、参数错也会过，所以只比路由名等于没测。
3. **长按进选择模式**：选中计数 1、`action_back` 1 → 0、FAB（a11y 标签 `Actions`）出现；点关闭后三者全部还原。
4. **表头菜单删整专辑**：三个菜单行都在 → 确认对话框带上曲数与专辑名 → **真文件从磁盘消失**、索引里的行消失、`album_delete_success` snackbar、`popCount == 1`。
5. **走真图**：`LyricoNavHost()` → 专辑页签 → 点卡片 → 详情页 → 点返回退回 grid，证明注册与 `LongType` 参数往返都没被假件遮住。

所以这批的替换只有「记录导航的假 Navigator」，而且只用在断言回调的那四项里；注册、参数解码、`koinViewModel(parameters)` 对返回栈条目解析都由第 5 项的真图覆盖。

#### 4. 真窗口取证（docs/port-evidence/c37a-*：4 张整窗 + 2 张裁切，各配 `.ocr.txt` / `.analysis.txt`）

复现：`./gradlew :lyrico-app:test --tests "*DevLibrarySeederTest*" -Plyrico.seedDevLibrary=1`（本机 `lyrico-app/data` 里已有 24 首 / 4 专辑）→ `./gradlew :lyrico-app:run -Plyrico.start.route=album_detail/22`（22 = `朋友`，2 首）→ `capture-window.ps1 -TitleLike "Lyrico 1.6"` → 点表头右侧 `⋮`（客户区 `(1128,25)`）→ 再截一张 → 点空白关掉菜单后截行区；第二组换 `-Plyrico.start.route=album_detail/24`（`山丘`，1 首，文件的 `trackerNumber` = 2）。

| 图 | 拍到了什么 |
|---|---|
| `c37a-album-detail.png`（+ocr/analysis） | 顶栏标题 `朋友`、表头 `朋友` / `未知专辑艺术家` / `2 首`、两行歌曲（行文本 `周华健 。 朋友`） |
| `c37a-album-rows-crop.png`（裁 `(56,190)-(1160,340)` 后 3× LANCZOS） | 行里的小字读得出来：`亻心`（就是 `花心`，C36 编辑页那次同款误读）、`周华健 · 朋友`、右侧 `0:03` / `128kbps`；反推坐标 = `(x/3, 190 + y/3)` |
| `c37a-album-menu.png`（+ocr/analysis） | 点 `⋮` 后菜单的三行：`计算专辑回放增益` / `分享专辑` / `删除专辑` —— 与安卓 overflow 的三行一一对应 |
| `c37a-album-track.png`（+ocr/analysis） | `album_detail/24`：顶栏 `山丘`、表头 `未知专辑艺术家` / `1 首`、行 `山丘` + **行首 `2`（PNG x=28,y=215）** —— 那个 `2` 是 FLAC 标签里的 `trackerNumber`，而同一位置在 `朋友` 那张里什么都不显示（两首都无 track number，走的是 `—` 那条分支） |
| `c37a-album-track-crop.png`（裁 `(56,90)-(1160,240)` 后 3×） | 表头小字放大后读得出：`山丘` / `未知专辑艺术家` / `1 首` |
| `c37a-album-rows.png`（+ocr/analysis） | 关掉菜单后的整窗，行区与 `c37a-album-detail.png` 一致（证明菜单只是覆盖层，没有改页面状态） |

两处证据互相锁定：无头用例 1 钉的是「夹具写了 `albumArtist` 时**不许**出现 `未知专辑艺术家`」，而真窗口里种子库的这两首歌没有 `albumArtist` 标签，表头就真的显示了 `未知专辑艺术家` —— 回退分支两边都有人看着。另外表头的封面槽位不是背景色（`(14,100)-(90,176)` 均值 `250.2`、R 通道标准差 `24.4`；同一张图下方的空白区是 `247.0` 且标准差 `0`），也就是说 80dp 的封面（这里是占位图）确实画了东西。

#### 5. 这批实测出来的坑（都会再踩）

1. **一个 `runComposeUiTest` 只能 `setContent` 一次**：所以「先扫库拿到专辑 id、再用这个 id 组合页面」不能写成「先组合、等扫描完再重组合」。专辑 id 是**参数**，必须在 compose 之前算出来 —— 这也决定了扫描不能用页面的 `waitUntil` 等。
2. **本地 host 的 `startDestination` 不能是「带必填参数的模式」**：`NavHost(startDestination = AlbumDetailDestination.PATTERN)` 配 `navArgument(...)` 会在图创建时直接抛 `Cannot navigate to startDestination ... Missing required arguments [[albumId]]`。夹具里的 host **不声明**这个参数（id 直接当形参传给页面），参数解码由走真图的那一项负责；`BatchTaskDetailScreenTest` 早就是这么写的，这次是把它背后的原因写清楚。
3. **`LibraryScanManager` 没有 suspend 的扫描 API**（`scanAll` / `scanFolders` / `addFolderAndScan` 都是发后不管，跑在它自己的 `Dispatchers.Default` 上），所以等待只能轮询页面读的那条 observable：`addFolderAndScan` 之后按 20ms 步进查 `observeAlbums().first()`，30 秒上限。测试里所有等待都等了具体的东西（专辑出现在索引里、行出现在树上），没有一个 `Thread.sleep` 是「睡够就该好了」。
4. **图标坐标从像素里量，不靠猜**：`⋮` 没有文字，OCR 读不到。`build/tmp-probe/find-icon.py` 把顶栏那一条带里的暗像素按列分组，得到 `x=1127..1130, y=46..65`，于是点击坐标 = 框架坐标 − `(1,31)` = 客户区 `(1128,25)`。第一次点就中，说明这个量法够用。

#### 6. 用户可见缺口（如实记录）

1. **删除确认对话框与结果 snackbar 只有无头证据**：真窗口那条链点开菜单就停了 —— 再点「删除专辑」就要对种子库动真格（确认后文件真没了，`/tmp` 之外的数据目录要重建才能再取证），而这条路的真假已经由无头用例 4 用**真文件**钉住（磁盘消失 + 索引消失 + snackbar + pop）。这一处是本批唯一的「证据分两半」：真窗口证明入口在、无头证明动作对，没有一张图同时证明两者。
2. **`BackHandler` 没了**（与 `LibraryHomeScreen` 同款缺口）：FAB 菜单在桌面只能点遮罩关，选择模式只能点关闭按钮退。
3. **封面没有内嵌图时是占位图**，与库页卡片一致（不是新缺口，但这一页 80dp 的封面槽位比卡片显眼）。
4. **行首 track number 为空时显示 `—`**：两个证据分支（有值 / `—`）各拍到一张，`—` 这条在安卓上也一样，不算缺口，列在这里是为了说明那张 `朋友` 的图里行首空白是设计而不是漏画。

#### 7. 前沿（本批收口）

java 主树 63 → **62**；前沿（依赖已满足、现在就能搬）33 → **32**；被挡 **30**（不动）；陈旧副本 **0**。

算术：`AlbumDetailScreen.kt` 本来在前沿上（它唯一的依赖 `SongSelectionSupport.kt` 在 #35 被改写成 hub），所以它一走只让前沿 −1，被挡那 30 个一个都没变；`62 = 32 + 30`。

下一片 #37-B 是艺人详情 + 艺人海报簇（`todo` #46）：先把这一簇的叶子与视图模型按 `port-frontier.py` 给出的链条搬进来（`ArtistDetailViewModel` 现在还被挡着），再搬 `ArtistDetailScreen`，并注册 `artist_detail/{artistId}`（与 `album_detail` 同形，参数也是数字）—— 艺人海报那几个页面自己没有入口，照 C5b 的先例用 dev 起始路由取证。

### P5 施工批次（#37-B：艺人详情页 + 艺人海报簇的 12 个文件，2026-10-11）

B 片是 #37 里最大的一片：它不是一个屏幕，而是一条以 `ArtistDetailViewModel` 为根的完整依赖链（艺人详情 → 海报动作面板 → 海报内嵌/落盘 → 海报文件夹的读、改、预览）。切法照 `todo` #51–#55 分五步（叶子 → 视图模型 → 详情页 → 海报文件夹簇 → 测试/取证），本节把五步合起来记。

#### 1. 搬了什么（`git mv` 12 个文件；java 主树 62 → 50）

搬之前用 `port-frontier.py --list-blocks` 在 HEAD 上把这条链的成员逐个列出来，得到的名单是「**4 个本来就在前沿 + 8 个被挡**」。12 个文件按角色分：

| 角色 | 文件（新位置 `src/main/kotlin/…`） | 搬来时 |
|---|---|---|
| 叶子 | `domain/poster/ArtistPosterEmbedding.kt` | 前沿 |
| 叶子 | `domain/poster/ArtistPosterFileNaming.kt` | 前沿 |
| 叶子 | `ui/components/album/AlbumListItem.kt` | 前沿 |
| 叶子 | `ui/components/poster/ArtistPosterActionsSheet.kt` | 前沿 |
| 视图模型 | `viewmodel/ArtistDetailViewModel.kt` | 被挡 |
| 视图模型 | `viewmodel/ArtistPosterFoldersViewModel.kt` | 被挡 |
| 界面 | `screens/ArtistDetailScreen.kt` | 被挡 |
| 界面 | `screens/ArtistPosterFoldersScreen.kt` | 被挡 |
| 界面 | `screens/ArtistPosterFolderContents.kt` | 被挡（本批改动最大：265 行） |
| 组件 | `ui/components/poster/ArtistPosterProgressSheet.kt` | 被挡 |
| 工具 | `utils/ArtistPosterFileWriter.kt` | 被挡 |
| 工具 | `utils/coil/PosterFileFetcher.kt` | 被挡 |

`ArtistPosterFolderContents.kt` 是唯一「位置搬了、内容也基本重写」的文件：Android 版是「按 SAF 目录列孩子再出网格」，桌面版是网格 + 全屏预览（`Dialog(usePlatformDefaultWidth = false)`），共用部分只剩「把路径交给 Coil 取图」。改写幅度过半的有 3 个 —— `ArtistPosterFolderContents.kt`（相似度 36%）、`ArtistPosterFoldersScreen.kt`（45%）、`ArtistPosterFileWriter.kt`（36%）—— 低于 git 默认 50% 的改名门槛，所以 `git diff` 会把它们显示成「删除 + 新增」而不是改名（内容没问题，`git diff -M20%` 能还原成改名；其余 9 个按 55%–100% 显示为改名）。

接线的四处改动（不是新文件）：

* `di/DesktopAppModule.kt`：注册两个 `viewModel { }`；
* `utils/coil/DesktopImageLoader.kt`：把 `PosterFileFetcher` 挂进桌面 Coil 的 fetcher 链 —— 海报文件夹里的文件是**路径**而不是 `Uri`，不挂这一项就取不到图；
* `ui/navigation/Destinations.kt` + `LyricoNavHost.kt`：`artist_detail/{artistId}`（`NavType.LongType`，与 `album_detail` 同形）与 `artist_poster_folders` 两条路由；
* `screens/library/ArtistsPage.kt`：艺人行点进 `artist_detail/{id}`（`Destinations.kt` 里那条「声明了但没注册」的 KDoc 同步改成事实）。

**没有搬的两个文件**：`utils/SafDocuments.kt`、`utils/UriUtils.kt`。它们只为 SAF 存在（列举目录 + 把 tree uri 换成人能读的路径），桌面端把依赖整体删掉（`File.listFiles()` / `File.absolutePath` 顶替），所以它们不是「还被挡着」而是**待作废** —— 现在仍留在 java 树的前沿名单里标着 `[ANDROID]`，留给清理批次处理。

**新增测试**：`viewmodel/ArtistPosterFoldersViewModelTest`（7 项）、`utils/ArtistPosterFileWriterTest`（8 项）、`screens/ArtistPosterFoldersScreenTest`（6 项）。`probe/DevLibrarySeederTest` 扩了一组**纯色**海报（4 张 PNG + `poster-notes.txt` + 嵌套 `其他/` 目录）：颜色不是装饰，第 4 节所有像素断言都靠它们定位。

#### 2. 桌面化改写点（逐条）

1. **`Application` / `Context` 整体退场**：选图给的是 `File`（`rememberFileOpenPicker`），直接 `readBytes()`；图片 MIME 由 `ImageMimeTypeDetector.detect(bytes)` 按**字节魔数**判定（桌面端没有 provider 可问，而且扩展名会骗人 —— 扩展名骗人时落盘的后缀会跟着骗人）；海报文件夹是普通目录路径。
2. **SAF 授权那一整套消失**（这批最大的一处删减）：`ArtistPosterEmbedUiState.permissionIntentSender` 字段删掉、`consumeArtistPosterPermissionRequest()` 删掉、`SaveAudioTagsResult.PermissionRequired` 分支删掉（桌面端只有成功/失败两支）、`takePersistableUriPermission` 不再需要。`hasContent` 的判据从「进行中 + 待授权 + 有结果」减到「进行中 + 有结果」，界面上那个「授予权限」按钮随之不存在。
3. **文案解析搬到界面**：`resolvedMessage(@StringRes)`（VM 里 `application.getString`）→ `UiMessage.Localized(Res.string.…)`。VM 因此不再需要 `Application`，也顺带没有「界面换了语言、消息还停在旧语言」的问题。
4. **`Uri` → `path` 的改名**：`ArtistPosterFile.uri` 改叫 `path`（交给 Coil 的是文件而不是流，名字不改就是谎），`ArtistPosterFolder` 去掉 `uri` 只留 `path`，`name` 用 `File(path).name`。
5. **`readPosters` 的返回值语义逐字对齐**：`SafDocuments.children` 换 `File.listFiles()`。两边返回 `null` 的条件是同一件事（目录没了 / 不再是目录 ↔ 持久化授权失效），所以界面的「文件夹不可用」分支仍由同一个条件到达 —— 这条无头与真窗口各钉了一次（`a folder that is gone is reported unavailable instead of empty` + `c37b-poster-folders-empty.png`）。
6. **`Toast` → `SnackbarHost` + `events` 流**，`LocalContext` 丢掉；`BackHandler` 丢掉（同前几批）。
7. **`@Destination` / `DestinationsNavigator` → 手写 `Navigator`**；`koinActivityViewModel` → `koinViewModel()`。
8. **一处行为增加（不是等价改写）**：`ArtistDetailScreen` 多了 `AlbumReplayGainProgressBottomSheet` 与它的事件 snackbar —— 见第 6 节缺口 2。

#### 3. 无头取证（新增 3 类 21 项；全量 128 类 / 1112 项 / 0 失败 / 0 错误 / 2 门控跳过）

`ArtistPosterFoldersViewModelTest`（7 项，真 settings 仓库 + 真磁盘）：

1. `the folder is listed with only its poster files, sorted by name`：目录里塞了非海报文件与子目录，只留海报且按名字序；
2. `an empty folder is a folder with nothing in it, not an unavailable one`：空目录 = `posters = emptyList()`，**不是** `null`（这两个状态在界面上的文案完全不同）；
3. `a folder that is gone is reported unavailable instead of empty`：删掉目录后是 `null`；
4. `matched posters are counted against the library's artists, case-folded`：`周华健.png` 与 `周华健_live.png` 都算匹配上库里那位艺术家，大小写不敏感；
5. `choosing a folder writes the setting, and removing it clears the setting but not the files`：`addFolder` 写设置、`removeFolder` 只注销注册表 —— **磁盘上的文件一个不少**；
6. `refreshing bumps the revision so a rewritten poster is read again`：同一路径重写海报后 `refreshArtistPosters()` 让修订号 +1（否则取图链路会拿缓存）；
7. `a settings write failure is reported as an error instead of a silent no-op`：设置写失败走错误文案，不是静默成功。

`ArtistPosterFileWriterTest`（8 项，真文件系统）钉的是「按艺术家名落盘」这条规则的边界：扩展名跟着 MIME 走、非法文件名按应用的改名规则清洗、未知 MIME 回退 `jpg`、换格式替换后**目录里只剩一个**、带后缀的旧海报（`_live`）也能被替换、旧文件删不掉时新文件不落盘且旧文件保住、没有文件夹/没有字节/没有可用艺术家名时什么都不写、目录不在时是「保存失败」而不是异常。

`ArtistPosterFoldersScreenTest`（6 项，真 Koin + `ScreenInTestHost` + 记录用 `Navigator` + `HeadlessMainDispatcher`）：有文件夹时显示路径 / `4 张海报` / 且只显示海报；没文件夹时显示自解释文案 + 选择入口 + **没有**溢出菜单；返回图标离开页面；文件夹被删后刷新说「不可用」而不是「空」；存在但空说「没有找到海报」；从溢出菜单移除后注销注册且磁盘文件仍在。

这批替换掉的只有「记录导航的假 `Navigator`」与 seeder 造的真实文件，`koinViewModel()` 对返回栈条目的解析、路由注册、Coil 取图都是真的。

#### 4. 真窗口取证（`docs/port-evidence/c37b-*`：12 张图，各配 `.ocr.txt` / `.analysis.txt`）

复现：seeder 跑一遍（`-Plyrico.seedDevLibrary=1`，本次在 `lyrico-app/build/demo-posters` 里种了 4 张纯色 PNG + `poster-notes.txt` + 嵌套目录）→ `./gradlew :lyrico-app:run -Plyrico.start.route=artist_detail/22`（22 = 周华健，1 张专辑 / 2 首）→ `capture-window.ps1`；海报文件夹页自己没有入口，照 C5b 的先例走 dev 起始路由 `-Plyrico.start.route=artist_poster_folders`。

| 图 | 拍到了什么 |
|---|---|
| `c37b-artist-detail.png` | 详情页整窗：表头海报（种子色 `(220,30,60)` **精确 3870 px**）、白色描边环、徽标 `(52,130,255)`（38 px）；两个页签；`accent_warm_pixels: 4230` |
| `c37b-artist-header-crop.png` | 表头裁切（`(0,85)-(420,200)` 后 3× LANCZOS）：`周华健` / `1 张专辑 · 2 首`（`album_song_count`） |
| `c37b-artist-rows-crop.png` | 歌曲行裁切：`花心`（OCR 读成 `亻心`，C36 / C37-A 同款误读）、`0:03`、`128kbps` |
| `c37b-artist-albums.png` | 点 `专辑` 页签（客户区 `(853,173)`）：专辑卡片 `朋友` / `2 首`；表头海报仍是 3870 px（跨页签不丢） |
| `c37b-artist-poster-sheet.png` | 海报动作面板（已登记文件夹的那一支），`accent_warm_pixels: 4200` |
| `c37b-artist-poster-sheet-no-folder.png` | 同一面板在**移除文件夹之后**：`label_artist_poster_add_to_folder_no_folder = 需先选择艺术家海报文件夹`；`accent_warm_pixels` 掉到 156 |
| `c37b-poster-folders.png` | 海报文件夹页整窗：路径行、**`4 张海报`**（目录里 6 个条目 → 4 张海报）、4 个平铺格子 |
| `c37b-poster-folders-crop.png` | 格子名字裁切：`Earth, Wind & Fire.png` / `周华健.png` / `周华健_live.png` + `李宗盛.png`（后两个 OCR 挤在一行） |
| `c37b-poster-help.png` | `Info` 图标（客户区 `(1087,24)`）打开的命名规则对话框：标题 `命名规则` + markdown 正文（3 条规则、`支持 JPG、JPEG、PNG、WebP 和 MP4`、结尾一段）—— 标题与正文里那个 `**命名规则**` 小标题同字，所以对话框上「命名规则」出现两次，不是重影 |
| `c37b-poster-menu.png` | `⋮`（客户区 `(1127,24)`）菜单三行：`刷新文件夹` / `更换文件夹` / `移除文件夹` |
| `c37b-poster-remove-dialog.png` | 移除确认框：`是否移除此文件夹？` + 路径 + `停止使用此文件夹中的海报，不会删除文件。`；确认键是**实心**主色按钮（`(52,130,255)`，8091 px，框架坐标 `x=593..768, y=434..482`） |
| `c37b-poster-folders-empty.png` | 确认之后：回到空态（`artist_poster_folders_summary` + `选择文件夹` 行 + `artist_poster_folders_empty`），没有溢出菜单 |

**这批证据里最硬的两条**：

1. **表头那张海报到底从哪来（受控对照）**。海报颜色是 seeder 的种子色，于是同一条像素可以做三次测量：① 详情页 3870 px `(220,30,60)`；② 打开面板后精确色 **0** px，而 3870 px 变成 `(154,21,42)` = 同一颜色 × 0.7（模态遮罩，逐通道精确）；③ **移除文件夹后再打开同一个面板，两种颜色都是 0 px**（占位图是 172/178 的灰）。三个状态里遮罩恒定、只有海报在或不在，所以「表头海报来自那个文件夹里的文件」不是推断，而是同一批像素的加减。确认框那张图给了同一结论的第三个采样：页面背后第一个格子仍是 9777 px，颜色从 `(30,90,220)` 变成 `(21,63,154)`（也是 × 0.7，**像素数一个不差**）。
2. **「移除」到底做了什么**。确认之后：磁盘上 `build/demo-posters` 的 6 个条目（4 张海报 + `poster-notes.txt` + 嵌套目录，含嵌套目录里的那张）**一个都没少** —— 对话框里那句「不会删除文件」是真的；同时 `lyrico-app/data/settings.preferences_pb` 里 `artist_poster_folders` 变成 JSON `[]`、`artist_poster_revision` = 1 —— 注销真的落盘了。而且这个「移除」是在**重启进程之后**再看的：第二组截图是新起的进程，它读到的是空设置，所以「移除」不只是界面状态。

页面几何也顺手量了（`c37b-poster-folders.png`）：4 个格子各 105×105（每个精确色 9777 px，其余被圆角与名字条吃掉），左边界 `x=7 / 124 / 241 / 358`（步距 117），色块与种子文件一一对应 —— **顺序就是名字序**：`Earth, Wind & Fire.png` < `周华健.png` < `周华健_live.png` < `李宗盛.png`（`.` 0x2E < `_` 0x5F，`周` U+5468 < `李` U+674E），所以那次 `sortedBy { it.name.lowercase() }` 的效果是看得见的。

#### 5. 这批实测出来的坑（都会再踩）

1. **`onNode` / `runOnUiThread` 是 `ComposeUiTest` 的成员，不是扩展函数**：import 它们会编译不过（`unresolved reference`），只能通过 `this` 调。
2. **`koinViewModel()` 在 `NavBackStackEntry` 上会碰主线程检查**：lifecycle 2.11 的桌面版要求 VM 创建在主线程，无头测试里必须换 `HeadlessMainDispatcher`，否则「点行 → `navigate` → 目标页 `koinViewModel()`」这条链会在断言之前就抛。
3. **列表页 spinner 竞态**：点完刷新立刻断言「有 4 行」会偶发失败 —— 一次性流还没发射，页面正处于 `isLoading`。写法改成 `waitUntil { … }` 等**具体那几行**出现（不要等 `isLoading == false`，那只是把时序换个位置）。
4. **跨用例泄漏的 `viewModelScope` 会算到别人头上**：全量跑时 `screens.LibraryHomeScreenTest` 收过一次 `UncaughtExceptionsBeforeTest`，栈底是 `BatchLyricsFormatViewModel` 的 init 查询 + `SQLiteException: Connection pool is closed`（`Dispatchers.Main.immediate`）。这类漏出来的协程在 Compose 测试把 AWT 事件队列占满时能排队几十秒，**落在哪个类上纯看时序**，所以「失败的那个类」几乎肯定不是肇事的类。1112 项里只挂这一次（空载重跑全绿，把疑似那两个类单独重跑也全绿），定位与处置写进了 #42，本批不动它。
5. **`ViewModelStore.put` / `clear` 是公开 API，`ViewModel.clear()` 不是**：`javap` 过 `lifecycle-viewmodel-desktop-2.11.0.jar`（`clear$lifecycle_viewmodel()` 是 internal），所以「测试里主动收 VM 作用域」只能走 `ViewModelStore` —— `BatchLyricsFormatViewModelTest` 早就是这么写的。
6. **前沿算术要在 HEAD 上算，不能靠 import 扫**：同一包内的引用 import 扫不到。做法是 `git worktree add` 一份 HEAD 的副本，在那里跑 `port-frontier.py`，再 diff 两边的前沿/被挡名单（见第 7 节）。
7. **`git mv` 之后必须重新 `git add` 新路径**：不改内容时索引里是纯改名，一改内容就变成「已暂存的原内容 + 未暂存的新内容」（`git status` 显示 `RM`），直接提交会把旧内容提交上去。

#### 6. 用户可见缺口（如实记录）

1. **MP4 海报的预览只剩「内嵌封面」这一半**：Android 版对 `.mp4` 走 `VideoView` + 角标，桌面版没有视频解码，全屏预览只显示 Coil 取到的那一帧/封面。文件夹里放 MP4 仍能被识别、能当海报用，但点开不是播放器。**PLAN 里原本承诺的「MP4 也能预览」在这版没做到**，要补就得在桌面端引入视频解码/播放组件（成本不小，单独排期）。
2. **艺人详情页多了一个安卓没有的面板**：`AlbumReplayGainProgressBottomSheet`（C6d 那套）加进了这一页，因为桌面端把「专辑动作」集中在详情页；Android 那边它只在专辑详情页。这是本批唯一的功能增加，不是移植偏差，但用户会看见。
3. **`artist_poster_folder_unavailable` 那句仍是「文件夹不可用，请重新授权」**：桌面端没有「授权」这个动作可重新做（重新选文件夹就是全部补救手段），文案是 Android 遗留。这批按「存量字符串逐字保持」的约定没动它 —— 要改就整批过一遍 i18n，留给清理批次。

#### 7. 前沿（本批收口）

java 主树 62 → **50**；前沿 32 → **28**；被挡 30 → **22**；陈旧副本 **0**（两端都是）。

算术：12 个文件里 **4 个本来在前沿**（`ArtistPosterEmbedding` / `ArtistPosterFileNaming` / `AlbumListItem` / `ArtistPosterActionsSheet`）、**8 个被挡**（其余 8 个，逐个用 `--list-blocks` 在 HEAD 上确认过它们的依赖链全在这一批里），所以 `62 − 12 = 50`、`32 − 4 = 28`、`30 − 8 = 22`。**没有一个「新解锁」的前沿文件**：这条链上剩下的依赖要么是本批成员，要么是 `[ANDROID]` 的 `SafDocuments` / `UriUtils`（它们的消费者被本批删掉，所以它们不是被解锁而是被作废）。

下一片 #37-C 是设置簇（`settings` + `edit_field_settings` + 文件夹管理），而 `ArtistPosterFoldersDestination` 的入口行就在设置页里 —— B 片先把目标页搬完，C 片补入口。

### P5 施工批次（#37-C-1：设置簇的 6 个叶子，2026-10-11）

补记（与 #35 的切片 A 同样处理：C-1 提交在 `e6526bd`，PLAN 段落当时留到 C 片一起写）。切法是「叶子先走」：视图模型/界面还没动，先把它们要用的、不依赖安卓框架的那几个部件搬过来。

#### 1. 搬了什么（`git mv` 6 个文件；java 主树 50 → 44）

| 文件（新位置 `src/main/kotlin/…`） | 搬来时 | 桌面化程度 |
|---|---|---|
| `data/model/ArtistSeparator.kt` | 前沿 | 逐字 |
| `data/model/cache/CacheCategory.kt` | 前沿 | 删掉 `EXTERNAL` |
| `data/model/editfield/FieldOrderState.kt` | 前沿 | 逐字 |
| `ui/components/setting/ManagedChip.kt` | 前沿 | 逐字（含 `ChipGrid`） |
| `ui/components/setting/FolderManagementItem.kt` | 前沿 | 近改写（SAF 那一半删掉） |
| `utils/CacheManager.kt` | 前沿 | **整篇重写**（相似度 7%） |

`CacheManager` 的两处结构性改动：从「`object` + 到处传 `Context`」改成「可注入的类 + `AppDirectories`」，以及把「按文件名里有没有 `http`/`network` 猜类目」换成「按真实目录划分类目」。图片那半抽成一个 `ImageCache` 接缝（见 C-2 的 `CoilImageCache`），因为**测量**和**清理**要问的东西不一样：字节数只有 Coil 自己记的准，而清理必须走 `DiskCache.clear()`（Windows 上进程打开着的文件删不掉，Coil 的日志文件就是这样一个文件）。`CacheCategory.EXTERNAL` 随之取消：桌面端没有 `Context.externalCacheDir` 那一层。

**测试**：新增 `CacheManagerTest`（7 项：分类计量 / 封面缓存目录不计入 OTHER / IMAGE 走 Coil 记账而不是目录大小 / 清理语义 / 清理后目录骨架还在 / 目录缺失报 0）；把 `FieldOrderStateTest`（4 项）从**死测试**里迁进 kotlin 测试树（用例逐字，断言换 `kotlin.test`），顺带交叉验证了搬过来的 EditFieldConfig API。

#### 2. 死测试的发现（#62）

搬 C-1 时发现 `src/test/java/**` 里 16 个安卓期测试文件（约 1300 行）**根本没被编译过**：`sourceSets { main/test { java.setSrcDirs(emptyList()) } }` 清空了 java 源集，而 KGP 是从 java 源集推导 kotlin 源集的，所以这个模块只编译 `src/test/kotlin/**`。证据：`--rerun-tasks :lyrico-app:compileTestKotlin` 之后那 16 个文件对应零个 class。

处置规则（写进 #62）：**主语已经在 kotlin 树里的，迁移 + 真跑**（`FieldOrderStateTest` 属于这类）；**主语还没搬的，不删**，记进「待搬覆盖」清单，避免把文件删成覆盖空洞。剩下 15 个的分类与体量记在 #62 里。

#### 3. 前沿（本批收口）

java 主树 50 → **44**；前沿 28 → **25**；被挡 22 → **19**；陈旧副本 **0**。算术：6 个搬走（都在前沿），另外**新解锁 2 个**（`viewmodel/SettingsViewModel`、`screens/EditFieldSettingsScreen`），它们的依赖在本批被搬完；被挡名单少 3 个 = 搬走 1 个 + 解锁 2 个。

### P5 施工批次（#37-C-2：SettingsViewModel + 语言接缝 + DI，2026-10-11）

C-2 是设置簇里「没有界面也能独立取证」的那一半：设置视图模型、语言这条偏好从存储到界面的整条链、以及它们需要的 DI 接线。界面（`SettingsScreen`）留给 C-3。

#### 1. 搬了什么（`git mv` 2 个 + 新文件 2 个 + 接线 8 处）

| 动作 | 文件 |
|---|---|
| `git mv` | `data/model/AppLanguage.kt`（内容几乎全换：`git diff -M1%` 下仍显示为 D+A，而不是改名） |
| `git mv` | `viewmodel/SettingsViewModel.kt`（R080） |
| 新文件 | `ui/AppLocale.kt`（语言接缝，182 行） |
| 新文件 | `utils/coil/CoilImageCache.kt`（`ImageCache` 的 Coil 实现，37 行） |

接线（不是新文件）：`data/repository/SettingsRepository.kt`（`appLanguage` / `saveAppLanguage` 两个成员）、`SettingsRepositoryImpl.kt`（`app_language` 键 + 默认值 + 容错解码 + 导出/导入）、`data/model/SettingBackup.kt`（`app_language` 字段）、`di/DesktopAppModule.kt`（`ImageCache` / `CacheManager` / `SettingsViewModel` 三个注册）、`LyricoDesktopApp.kt`（外壳收集主题与语言）、`plugin/i18n/PluginLocales.kt`（KDoc 与运行时更新）、`utils/FormattedStringResource.kt` + `utils/UiMessage.kt`（组合外解析走同一个语言）、`lyrico-app/build.gradle.kts`（探针的两个 `-Plyrico.*` 转发）。

`SettingsViewModel` 的改写幅度不大（R080），实质性改动只有三条：导出/导入的参数从 `(Context, Uri)` 变成 `File`；缓存改走注入的 `CacheManager`；多了一个 `appLanguage`（`SettingsUiState` 字段 + 合并进 `combine` 的那条流 + `setAppLanguage`）。

#### 2. 语言接缝（本节的核心）

安卓那边这件事是免费的：`AppCompatDelegate.setApplicationLocales` 会用 per-app 的 `Configuration` 重建 activity，一路上每个 `getString` 看到的都是选中的 locale。桌面端没有这一层，所以要自己接上资源系统读环境的那个点：`LocalComposeEnvironment`。

做法（`ui/AppLocale.kt`）：`ProvideAppLocale(language) { … }` 造一个只换 locale 的 `ComposeEnvironment`，语言/脚本/地区来自选中的 locale，**主题与密度照抄库的默认环境**（`isSystemInDarkTheme()` + `LocalDensity.current`），再用 `CompositionLocalProvider` 提供下去。`AppLanguage.SYSTEM` 直接走平台的 `Locale.current`，也就是默认环境读的同一个源。

查库的字节码确认过两件事实，接缝才敢这么写：

1. **可组合的 `stringResource` 确实读这个 local**：它调 `rememberResourceState(resource, …)`，后者取 `LocalComposeEnvironment.current.rememberEnvironment()`，并把结果**和资源一起**当 `remember` 的键。所以换环境会让已经组合过的文案重新解析 —— 切换是实时的，不需要重建界面（`AppLocaleTest` 里那项「已经组合过的字符串会被重新解析」就是钉这一点）。
2. **`composeResources` 目录下只有 `values`、`values-zh-rCN/rHK/rTW` 和 `drawable`** —— 没有任何主题/密度限定目录，所以那两个限定符今天选不出任何东西：语言切换既不会被它们影响、也不会影响它们，**不存在 overlay 陈旧的问题**（因为没有 overlay 目录）。

**代价要说清楚：这个文件用了 Compose Resources 的 internal API**（`ComposeEnvironment` / `LocalComposeEnvironment` / `ResourceEnvironment` 的构造函数都是 `internal`），文件头因此带 `@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")`。这不是没注意，全项目也只有这一处；三个替代方案都比它差：

* **只用公开 API —— 没有**。库没有提供安装环境的入口，而可组合路径又会按环境缓存结果，所以覆盖这个 local 也是唯一能拿到「实时切换」而不是「重建界面才换」的办法。
* **`Locale.setDefault(…)` + `key(language)` 重建子树**能work（默认环境读 JVM 默认 locale，重新解析靠 `key` 丢掉记住的值），但它是**进程级副作用**：UI 语言一变，进程里每个库（和每个线程）的数字/日期/排序格式都会跟着变。
* **反射**（`ResourceEnvironmentKt.getLocalComposeEnvironment()` 在字节码里是 public）能绕开编译期诊断，代价换成运行期静默失败：动态代理实现那个接口、反射构造 `ResourceEnvironment`，还得自己补 Compose 的分组记账（代理拿到的是 `Composer` 本身）。

压制可见性的代价是**有界且响亮**的：这些符号仍然过编译器检查，库一升级把它们挪了/改了，构建会带清楚的错误失败，而不是运行期悄悄出问题；`AppLocaleTest` 还从两侧把解析结果钉住。

**组合外解析**（零食条那种在协程里跑的）也一并统一了：`ProvideAppLocale` 通过 `SideEffect` 把当前语言写进进程级的 `AppLocaleState`，`resolveLocalized` 用它选出环境（用 `getString(environment, resource)`），`formattedString` 改为委托给它 —— 于是 `UiMessage.resolve()` 和 EditMetadataScreen 那十来处 `formattedString(...)` 都跟着设置里的语言走，不会出现「界面中文、提示英文」。`AppLanguage.SYSTEM` 在这条路上仍走库自己的 `getString`。

**插件语言同步**：`PluginLocales.update(locale)` 在语言变化时被外壳调用（`SYSTEM` 走 `initialize()` 重读平台 locale），否则插件自己的字符串会和周围界面说不同的话；`PluginLocales` 的 KDoc 原来那句「没有运行时语言切换」随之改成事实（系统语言仍需重启，设置里的语言是实时的）。

#### 3. 桌面化改写点（逐条）

1. **语言是存储里的偏好，不是 AppCompat 状态**：新增 `app_language` 键，默认 `SYSTEM`，值是 **BCP 47 标签**（`""` / `en` / `zh-CN` / `zh-TW` / `zh-HK`）而不是枚举名 —— 理由是同一个 token 要同时给界面（`AppCompatDelegate` 的替代物）和资源加载器用，而且手改过的备份能按标签读；认不出的标签**退回 `SYSTEM` 而不是抛异常**（坏掉的偏好不该让界面起不来）。导出/导入各带一个 `app_language` 字段，导入时也做容错归一。
2. **外壳从「传默认值」变成「传设置值」**：`LyricoDesktopApp` 之前调 `LyricoTheme {}`（AUTO / null / false），也就是说**主题设置在这一版之前是死设置**（没有任何地方消费）。现在它收集 `themeConfigFlow` 与 `appLanguage`，把 `LyricoTheme(colorMode, keyColor, monetEnabled)` 和 `ProvideAppLocale` 一起挂在整个导航图之上 —— 这正是 `MainActivity` 在 `setContent` 之前做的事的桌面等价物。
3. **导出/导入是文件不是 `Uri`**：`exportSettings(target: File)` / `importSettings(source: File)`，写读失败都走 `logException` + 事件提示（不再有「`openInputStream` 返回 null 就静默什么都不做」那一支）。
4. **缓存走注入的 `CacheManager`**，`refreshCache()` / `clearCache()` 不再收 `Context`；`ImageCache` 的绑定是 `CoilImageCache`，它**每次调用时才**向 `SingletonImageLoader` 要 loader（懒解析），所以没装 loader 的进程拿到 Coil 的默认 loader 而不是崩溃。
5. **`SettingsUiState.keyColor` 的默认值保持 `KeyColors[1]`**（安卓如此），与 `ThemeConfig` 默认 `KeyColors.first()` 不一致这件事按「存量逐字」留着，记在缺口里。

#### 4. 无头取证（新增 4 类 21 项 + 扩 2 类；全量 134 类 / 1146 项 / 0 失败 / 0 错误 / 3 门控跳过）

| 测试类 | 项 | 钉住的东西 |
|---|---|---|
| `ui/AppLocaleTest` | 7 | 显式语言选哪份 catalog；**地区限定符**分得开 zh-TW / zh-HK（`cache_category_network` = 「網路快取」/「網絡快取」）；**已经组合过的字符串会被重新解析**（实时切换）；`SYSTEM` 与「不提供 provider」以及库自己的 `getString` 三者一致；组合外解析跟随语言且仍会格式化；认不出的标签退回 `SYSTEM` |
| `viewmodel/SettingsViewModelTest` | 8 | 语言写入后能被同一存储的新读者读到；启动前写入的语言就是首帧语言；备份携带 `app_language` 且导入能改回来；导出失败/文件缺失/内容不是备份三种失败都**被报告而不是抛出**；缓存三个类目的数字（IMAGE 走接缝、NETWORK 量真实目录）与清理语义；`clearSongs()` 清空 folders 表 |
| `utils/coil/CoilImageCacheTest` | 5 | 用 `buildImageLoader` 造真 loader + 真磁盘缓存：**往缓存目录里塞一个 Coil 不认识的文件**，尺寸仍报 Coil 的账（目录大小 > 账本，因此这条断言有区分力）；`clear()` 之后仍能写新条目（证明是走 Coil 清的，不是把目录删了） |
| `probe/DevPreferencesProbeTest` | 1（门控） | 把语言/主题写进开发数据目录，供真窗口取证用；不带 `-Plyrico.devLanguage/-Plyrico.devTheme` 时跳过 |

扩的两处：`SettingsRepositoryTest` +2（默认 `SYSTEM`、写读往返、**重启后仍在**、备份里的语言、备份里认不出的标签归一）；`DesktopAppModuleTest` 的可解析绑定 22 → **24**（加 `CacheManager` / `ImageCache`）。

全量口径：1112 → **1146** 项（+34），跳过 2 → **3**（多的那项就是门控探针）。注意这个口径**不含** `src/test/java` 里那 15 个死测试（见 C-1 第 2 节 / #62），#62 收口时口径会再变一次。

#### 5. 真窗口取证（`docs/port-evidence/c37c2-*`）

C-2 的界面（设置页）还没搬，所以取证走**探针写偏好 → 起窗口 → 截整窗**：同一次机器上系统主题/系统语言都没动，变的只有存储里那两个值 —— 因此两张图能唯一归因到「外壳开始消费设置了」。

| 文件 | 写入的偏好 | OCR 关键行 | 分析数字 |
|---|---|---|---|
| `c37c2-shell-en-light.png` | `app_language=en` + `theme_mode=LIGHT` | `y=51 x=576 :: Songs （ 24 ）` | 主色 `(247,247,247)` 占 81%；`VERDICT: OK` |
| `c37c2-shell-zh-dark.png` | `app_language=zh-CN` + `theme_mode=DARK` | `y=48 x=572 :: 歌 曲 （ 24 ）` | 主色 `(0,0,0)` 占 81%；与上一张 `changed_pixels=95.52%`；`VERDICT: OK` |

两张的侧栏文案同源可查：英文那张 OCR 出 `Songs （ 24 ）` / `AIbums`（Albums），中文那张出 `歌 曲 （ 24 ）`。**深色那一张是主题接线的决定性证据**：同一台机器、同一个系统主题设置，只因为存储里 `theme_mode` 是 `DARK`，主色就从 247 灰变成 0 黑（亮/暗各占 81%）—— 这在本批之前不可能发生（外壳传的是默认 `AUTO`）。

#### 6. 这批实测出来的坑（都会再踩）

1. **Compose Resources 的环境 API 是 `internal`，不是 opt-in**：`@OptIn(InternalResourceApi::class)` 能解决 `LanguageQualifier` / `ResourceItem` 这类「public 但标了 opt-in」的符号，但 `ComposeEnvironment` / `LocalComposeEnvironment` / `ResourceEnvironment` 的构造函数是 Kotlin `internal`（编译器报 `INVISIBLE_REFERENCE`，措辞是 “it is internal in file”）。要用只能 `@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")` + `@file:OptIn(InternalResourceApi::class)`，而且**必须读字节码确认链接得上**（`javap -c` 自己的输出里能看到 `getLocalComposeEnvironment()` 与 `new ResourceEnvironment`）。
2. **可组合 `stringResource` 的缓存键里有环境**：`rememberResourceState(resource, …)` 把 `(resource, environment)` 一起当键，所以换环境会重新解析；反过来说，**只改语言但不提供新环境**（例如只 `Locale.setDefault`）不会让已组合的文案重算。这条决定了接缝只能接在环境上。
3. **`getString(res)`（组合外）走的是另一条路**：它用 `getSystemResourceEnvironment()` → 一个可设置的 `KFunction` 变量，读 `Locale.getDefault()`。也就是说组合内/组合外是两条独立的路，`UiMessage.resolve()` 这类调用不会自动跟随组合里的语言 —— 必须显式统一（本批用 `resolveLocalized`）。
4. **事件流没有缓冲时，没有收集者会把 `emit` 挂住**：`SettingsViewModel._events = MutableSharedFlow()`（`replay=0`、`extraBufferCapacity=0`），测试里只收集 `uiState` 的话，导出成功那一步的 `_events.emit` 会**永远挂起**（表现为「文件写出来了但日志没有、断言超时」）。VM 测试里必须同时起一个 `events` 的收集协程。
5. **失败路径写的是 `logException`，不是 `log`**：`RecordingAppLogRepository` 把两者分别记在 `entries` 和 `exceptions` 里，断言要看对那一个（本批第一版看错，两条用例白等 10 秒）。
6. **`AppLocaleState` 是进程级的**：它是给「组合外解析」用的，所以两个语言不同的组合（只有测试会这样）共享同一个值 —— 测试必须在 `@AfterTest` 里复位成 `SYSTEM`，否则会决定同 JVM 里**后面**某个测试（`FormattedStringResourceTest` 就在同一个 JVM 里）的语言。
7. **写进缓存目录的「野文件」是量账本还是量目录的判别器**：`CoilImageCacheTest` 靠「Coil 账本 4096、目录里有额外 64 KB」把两种实现分开；只断言「大于 0」是量不出区别的。
8. **`ocr-window-capture.ps1` 收的是 Windows 相对/绝对路径，不吃 MSYS 风格**：传 `/h/...` 或 `$(pwd -W)` 会抛 `NotSupportedException`（“给定路径的格式不受支持”），在仓库根目录用 `build\tmp-probe\x.png` 这种相对路径最省事。
9. **`-Plyrico.*` 必须在 `build.gradle.kts` 里逐个转发**成 `systemProperty`：Gradle 的 `-P` 不会自动变成系统属性（daemon 负责传环境，注释里写着这条），本批为探针补了 `lyrico.devLanguage` / `lyrico.devTheme`。

#### 7. 用户可见缺口（如实记录）

1. **改语言的界面还没有**：C-2 只做了「存了要生效」，写入端（设置页那个下拉）在 C-3。所以本节的真窗口取证靠探针写偏好，而不是点界面 —— 用户此刻能感受到的是「偏好文件里的语言会被尊重」。
2. **系统语言只在启动时读一次**（`PluginLocales.initialize()`）：设置里的语言是实时生效的；改 Windows 显示语言仍需重启。这是 `PluginLocales` 从安卓 `Configuration` 换到 JVM 默认 locale 的既有结论，本批只把「设置里的语言」这一半补成实时。
3. **`SettingsUiState.keyColor` 的默认值仍是 `KeyColors[1]`**（与 `ThemeConfig` 默认 `KeyColors.first()` 不一致）：首帧之前那一瞬的图标色会偏蓝。存量行为逐字保留，要改得整批过一次。
4. **主题键色（`KeyColors`）在薄荷模式下不生效**：`LyricoTheme` 只在 `monetEnabled` 为假时把 `keyColor` 传给 `ThemeController`（安卓原样）。不是本批引入，但设置页一上就会有人试「选个红色 + 开薄荷」，所以记在这里。
5. **命令行传入的文件仍然不会自动打开**：`LyricoDesktopApp(externalFilePath = …)` 这个参数至今没有被消费（`main` 把它从 `args.firstOrNull()` 传进来，但没人拿它去开编辑页），所以双击关联文件启动只会打开曲库首页。C-2 在改这个文件时把 KDoc 里那句已经不成立的「等编辑页搬完再接上」改成了事实（编辑页早已搬完，缺的是接线本身）——缺的是接线不是屏幕，这条得单独排期。

#### 8. 前沿（本批收口）

java 主树 44 → **42**；前沿 25 → **24**；被挡 19 → **18**；陈旧副本 **0**。算术：2 个搬走（都在前沿）；**新解锁 1 个**（`screens/LyricsCleanupRulesScreen`，它只等 `SettingsViewModel`）；被挡名单少 1 个就是它。

下一片 #37-C-3 是 `SettingsScreen`（836 行）。它现在被两个 java 树文件挡着：`ui/components/PainterUtils.kt`（`[ANDROID]`，在 #38 的作废清单里）和 `viewmodel/FolderManagerViewModel.kt`（C-5 才搬）。语言那一行的改写是确定的：`AppCompatDelegate.getApplicationLocales()[0]` → `uiState.appLanguage`，`setApplicationLocales(...)` → `settingsViewModel.setAppLanguage(...)`。

### P5 施工批次（#37-C-5：文件夹管理页（视图模型 + 1312 行界面）+ 作废 UriUtils/SafDocuments，2026-10-11）

顺序上 C-5 排在 C-3 前面，不是当初 `todo` 里的顺序，而是依赖逼出来的：`SettingsScreen` 的文件夹区直接 `koinViewModel()` 取 `FolderManagerViewModel`（前沿脚本把它列为 SettingsScreen 的两个阻塞之一），而 `FolderManagerViewModel` 自己又只等 `UriUtils`。先搬 C-5，C-3 的阻塞就只剩 `PainterUtils` 一个。

#### 1. 搬了什么（`git mv` 2 个 + 路由 1 条 + 作废 2 个）

| 动作 | 文件 |
|---|---|
| `git mv` | `viewmodel/FolderManagerViewModel.kt`（123 行，R0 改写量小） |
| `git mv` | `screens/FolderManagerScreen.kt`（1312 行；26 处 `R.string` 走机械迁移，其余是手工縫合） |
| 新路由 | `Destinations.kt` 的 `FolderManagerDestination` + `LyricoNavHost` 的注册 |
| 作废 | `utils/UriUtils.kt`、`utils/SafDocuments.kt`（`git rm`） |

作废这两个文件时机成熟了：C-5 之前 `UriUtils` 的最后一个消费者是 `FolderManagerScreen`/`FolderManagerViewModel`，SafDocuments 只剩它自己引用自己（`grep` 过两端，kotlin 树里只出现在 KDoc 注释中）。它们不是「被盖住的未搬代码」而是**被替换掉的机制**：桌面端选目录走 `DirectoryPicker`，拿到的就是路径，`DirectoryPicker` 的 KDoc 早就写着「`UriUtils` 因此根本没搬」。

#### 2. 桌面化改写点（逐条）

1. **SAF 那一整套退场**：`rememberLauncherForActivityResult(OpenDocumentTree())` + `takePersistableUriPermission` + `UriUtils.getFileAbsolutePath` + `treeUri = uri.toString()` 全部换成 `rememberDirectoryPicker(title).pick()` 一次挂起调用，拿到 `File` 后 `viewModel.addFolder(path)`（VM 的 `addFolder` 也从 `(path, treeUri)` 变成只收 `path`）。
2. **`Application` / `contentResolver` 从视图模型里消失**（`Application` 只用来拿 `contentResolver`）；删除文件夹时那句「释放持久化授权失败就 log.w」没有对应的失败可记，不是被吞掉了 —— 桌面端选目录不授予任何比进程活得更久的权限。
3. **`BackHandler` 删掉**（和 `AlbumDetailScreen`/`LibraryHomeScreen` 同一条理由：桌面端没有系统返回手势）：菜单靠 `ExpandableFabMenu` 自己画的遮罩关，选择模式靠 `SongSelectionTopAppBar` 的关闭按钮退，回上一层靠面包屑。`PLAN.md` 把这处 UX 缺口记在这里，而不是用一个假手势藏起来。
4. **`SongActionSheets` 的分享/复制改为显式传入**（桌面版把这两个动作拆到调用点：分享 = `selectionViewModel.share(listOf(song))`，复制 = `LocalClipboard` + `ClipEntry(StringSelection(text))`，与其它已搬的歌曲列表一致）；`batchShare` 换成 `share`（两条分享路径在桌面端都是「在资源管理器中显示」）。
5. **加文件夹按钮补了 `contentDescription`**（安卓原文是 `null`，而同一页的排序图标就有 `cd_sort`）：图标按钮不能既读不出来也点不中，而测试要能命中它也得有个名字。
6. **可空选择器参数**：`FolderManagerScreen(navigator, directoryPicker: DirectoryPicker? = null)`，函数体内 `?: rememberDirectoryPicker(...)` —— 和 `SongsPage` 一模一样的写法，理由也一样（可组合函数的默认参数不能调用可组合函数），这是测试能驱动「用户加了文件夹」的前提。

#### 3. 顺手改掉的一个真 bug（路径分隔符）

界面里有三处照安卓的字面写法取文件夹名字：

```kotlin
folder.path.substringAfterLast("/")   // 标题、行名、面包屑各一处
```

安卓的 SAF 路径是 `/` 分隔的，而桌面端存的是 **Windows 路径（反斜杠，见 `FolderDao.FOLDER_SEPARATOR`）**，所以这三处全部会把**整条路径**当名字显示出来 —— 文件夹标题会变成 `H:\VibeCoding\...\demo-library`。测试里那项「已存在的文件夹要能被列出」就是先挂在这里，改成 `File(path).name`（新增 `folderDisplayName` 并写了 KDoc）之后三处一起好。真窗口证据里 `demo-library` / `周华健 - 朋友` 这种短标题就是修好之后的形状（全路径退到副标题行）。

同一批里同步修了四处 `stringResource(res, args)`：库自己的 vararg 格式化不会替换普通的 `%d`/`%s`（见 `formattedStringResource` 的 KDoc），`StringFormattingGuardTest` 在我把文件搬进来后立刻挂了。这批的四处都是安卓期就存在的写法，只是安卓树不在守卫的扫描范围里（只看 `src/main/kotlin`）。

#### 4. 无头取证（新增 2 类 10 项；全量 136 类 / 1156 项 / 0 失败 / 0 错误 / 3 门控跳过）

| 测试类 | 项 | 钉住的东西 |
|---|---|---|
| `viewmodel/FolderManagerViewModelTest` | 6 | 文件夹列表来自数据库且带上扫描状态；**删除文件夹会连子树一起删掉并剪索引**；**忽略是递归的**；「刷新这一个文件夹」向扫描器要的恰好是那个 id；「添加」只给路径（没有 tree uri 可给）；歌曲列表随选中文件夹与排序变化（排序断言先把 `titleSortKey` 真造出来，否则降序与升序在并列键上返回同一个顺序，测试会白过） |
| `screens/FolderManagerScreenTest` | 4 | 真实 `NavHost` + 真 Koin 图 + 假 `DirectoryPicker`：选中的目录被**真扫描**（目录遍历 → TagLib → Room → 列表行）；取消选择不写任何东西；已存在的根目录不经选择器也会被列出（这一项就是分隔符 bug 的捕获网）；点进文件夹再切到「歌曲」页能看到里面的歌 |

全量：上文 C-2 那节的 1146 → **1156** 项（+10），跳过仍 3。

关于这次全量跑：搬完这一批后，连续两次全量各自掛了一项，而且**挂的不是同一个类** —— 第一次是 `ArtistPosterFoldersScreenTest`（“夹具目录必须可删”），第二次是 `BatchTaskSchedulerTest`（一个 10s 超时），两个类单独重跑都全绿。两次都能用一个机制解释，而且肇事的正是本批新增的测试：**DI 里那个 `CoroutineScope` 是一个 single，`stopKoin()` 不会取消它**。本批的屏幕测试会在它上面起一次真扫描，测试结束时临时目录被删，而扫描还在继续走已不存在的目录 —— 剩下的活就会算到别人的超时/删不掉文件上。处置：新测试的 `tearDown` 里先 `GlobalContext.get().get<CoroutineScope>().cancel()` 再 `stopKoin()`；改完连跑两次全量都是 136/1156/0/0/3。（#42 记的是同一族现象，但那一条的根系在别的测试里，本批只处理自己新引入的那一半。）

#### 5. 真窗口取证（`docs/port-evidence/c37c5-*`）

开发启动路由 `-Plyrico.start.route=folder_manager`（C5b 加的那个逃生口，因为这一页的正式入口在还没搬的设置页里），真数据目录（已播种的 `demo-library`）：

| 文件 | 内容 | OCR 关键行 | 分析 |
|---|---|---|---|
| `c37c5-folder-manager.png` | 根级列表：`demo-library` 一行（名字 + 全路径副标题 + “4 个文件夹，24 首歌曲”） | `y=249 x=29 :: demo-llbrary`（OCR 把 i 读成 l）；`y=275` 是同一条全路径 | `VERDICT: OK`，主色占比 0.707 |
| `c37c5-folder-manager-songs.png` | 点进根目录：面包屑 `文件夹 > demo-library`、4 个子文件夹各带数量 | `y=106 x=29 :: 文 件 夹 >`、`y=108 x=102 :: demo-llbrary`、`y=249 :: Earth, Wnd & Fire - September` | `VERDICT: OK` |
| `c37c5-folder-songs.png` | 再点进 `周华健 - 朋友` 并切到「歌曲」页：三级面包屑 + 两行歌 | `y=106 :: 文 《 牛 夹 > demo-library >`、`y=108 :: 周 华 健 - 朋 友`、`y=186 x=841 :: 歌 曲 （ 2 〕` | `VERDICT: OK` |

三张合起来是一条完整链：列表 → 进目录 → 进叶子并看里面的歌；而**第一张的 `demo-library` 就是分隔符 bug 修好后的直接证据**（没修之前那里会是整条 Windows 路径）。其余数字断言在无头测试里（真库、真扫描、真 Room），截图只负责「这一页在真实窗口里长得对」。

#### 6. 这批实测出来的坑（都会再踩）

1. **`ViewModelStore.put(...)` 编得过但跑不起**：`store.put("k", viewModel)` 能通过编译（lifecycle 2.11 的元数据里有），运行期抛 `NoSuchMethodError: put(String, ViewModel)`。想「在测试里拥有 VM 的 scope」就得走 `ViewModelProvider.create(store, factory)`（工厂里返回实例）——本批的 `FolderManagerViewModelTest` 就是这么写的，并在 KDoc 里把这个坑标出来了。
2. **`awaitUntil` 的 `describe` 不是 suspend 的**：直接在里面读 DAO（suspend）会编译不过（“Suspension functions can only be called within coroutine body”）。做法是用一个收集器把 DAO 的 flow 镜像到 `AtomicReference`，再对快照轮询（顺便让断言落在“用户会看到的那条流”上）。
3. **排序断言不造 sort key 就是空断言**：`titleSortKey` 默认是 `"#"`，并列键下 `ASC` 与 `DESC` 返回同一个顺序 —— 得先用 `SortKeyUpdater().update(song)` 造出键，否则“降序”这条测不出任何东西。
4. **短音频默认被丢弃**：TagLib 的夹具都短于一分钟，而应用默认 `ignoreShortAudio = true`，不先 `saveIgnoreShortAudio(false)` 的话扫描结果为空——正是这条让本批的一项测试先超时。
5. **图标按钮没有文字，`onNodeWithText` 找不到**：要么补 `contentDescription` 再用 `onNodeWithContentDescription`，要么测试根本点不到。
6. **页签文案带计数，而同一页的统计行也含同一个词**：`onNodeWithText("歌曲", substring = true)` 会命中两个节点（页签 + “0 个文件夹，1 首歌曲”），要么用完整标签（`"歌曲 (1)"`），要么按索引取。
7. **`File(dir, name)` 与 `Path.resolve` 不能互换**：夹具里混用两者会得到 “actual type is File, but Path! was expected” 这类报错；`placeFixture` 保留 `Path`，测试里那些“给扫描器一个路径”的地方用 `File`。
8. **`SongEntity.path` 是 `java.nio.file.Path` 扩展属性**（不是字符串）：断言文件名要用 `stored.path.fileName` 或直接用 `stored.fileName`。
9. **`StringFormattingGuardTest` 是搬文件时就生效的**：安卓树不在它的扫描范围（只看 `src/main/kotlin`），所以一批安卓期的 `stringResource(res, args)` 会在文件搬进来的那一刻才被发现。搬大文件时先跑一次全量，比最后才发现便宜。
10. **`stopKoin()` 不会取消 DI 里的 `CoroutineScope`**：那是一个 `single`，测试结束后它上面的协程（真扫描、真 IO）会继续跑，还拿着马上就要被删的临时目录 —— 表现为**别的不相干的类**超时或删不掉文件。新写的屏幕测试要在 `tearDown` 里先 `cancel()` 再 `stopKoin()`（见本节第 4 条）。

#### 7. 用户可见缺口（如实记录）

1. **没有系统返回手势**：安卓的 `BackHandler` 在桌面端没有对应物，所以“回上一层”只能点面包屑或子目录列表。与 `AlbumDetailScreen` / `LibraryHomeScreen` / `EditMetadataScreen` 是同一处缺口（那几处已记录）。
2. **文件夹行的“在资源管理器中显示”与“复制”分工**：安卓的分享/复制在桌面端统一成 reveal + 剪贴板（组件 KDoc 里写明），与其它歌曲列表一致，但和安卓的分享面板不是一一对应。
3. **这一页现在只能从开发路由（`-Dlyrico.start.route=folder_manager`）到达**：正式入口是设置页里那一行，而设置页要到 C-3 —— 与本批之前 `artist_poster_folders` 的状态相同。

#### 8. 前沿（本批收口）

java 主树 42 → **38**（搬走 2 + 作废 2）；前沿 **24 → 22**（两个搬走的不在前沿，两个作废的在）；被挡 18 → **16**；陈旧副本 **0**。

算术：搬走的 2 个（`FolderManagerScreen` / `FolderManagerViewModel`）本在**被挡**名单，它们一走被挡就 18 → 16；作废的 2 个（`UriUtils` / `SafDocuments`）本在**前沿**，所以前沿 24 → 22。`SettingsScreen` 的阻塞列表从两项剩到一项（只剩 `PainterUtils.kt`），下一个切片 C-3 就是它。

### P5 施工批次（#37-C-3：设置页（836 行）+ `settings` 路由 + 语言行接上 C-2 的接缝，2026-10-11）

#### 1. 搬了什么（`git mv` 1 个 836 行 + 路由 + 三处调用点）

| 动作 | 位置 |
|---|---|
| `git mv` | `screens/SettingsScreen.kt`（83 处 `R.string` 走机械迁移，其余手改） |
| 路由注册 | `LyricoNavHost` 里 `SettingsDestination.ROUTE` -> `SettingsScreen(navigator)` |
| 声明 | `Destinations.kt` 新增 `LyricsCleanupRulesDestination` / `ArtistSplitSettingsDestination` / `AboutDestination`（屏幕分别属于 C4 / F，按文件自带的「声明了但未注册」惯例先声明） |
| 调用点 | `SongsPage` / `ArtistsPage` / `AlbumsPage` 的齿轮图标：`navigate(SettingsDestination())` → `navigate(SettingsDestination)`（对象不能用 `()` 调，见坑 5） |

#### 2. 桌面化改写点（逐条）

1. **语言行接上了 C-2 的接缝**：`LocalConfiguration` + `AppCompatDelegate.getApplicationLocales()/setApplicationLocales(LocaleListCompat)` 全部换成 `settingsUiState.appLanguage` + `settingsViewModel.setAppLanguage(...)`。写完之后不需要重建界面：外壳订阅同一条流，`ProvideAppLocale` 换掉资源环境，整页就连同零食条一起换语言（真窗口证据第 2 张）。
2. **导出/导入从 SAF 换成两个选择器接缝**：`rememberLauncherForActivityResult(CreateDocument(OpenDocument))` → `rememberFileSavePicker` / `rememberFileOpenPicker`，两个入口都是挂起调用，选完直接投给 ViewModel（VM 的收参是 `File`，C-2 改的）。默认文件名里的时间戳改成点击那一刻取（安卓原本就在 `onClick` 里取）。
3. **缓存文案：`Formatter` → `FileSizeFormatter`，并且不再用 `remember` 包**。安卓用 `context.getString(...)` + `android.text.format.Formatter`；桌面端用 `formattedStringResource` + `FileSizeFormatter`（同一个 AOSP 算法，单位阈值由 `FileSizeFormatterTest` 钉住）。原来的 `remember(...)` 去掉了：它的 lambda 不是可组合的，而拼这段文案要调 `stringResource`/`formattedStringResource`，几行字符串的重算比手工管网缓存依赖便宜。
4. **`Toast` → Miuix `Snackbar`**：事件流收下来的 `UiMessage` 在展示这一刻 `resolve()`（所以语言切换后提示也跟着换），Scaffold 补上 `snackbarHost`。调试用的「已清空数据库 / 清空失败」两条从 `Toast` 改成同一个 host。
5. **唯一的行为偏差：主题键色的「默认」项**。安卓用 `getSystemWallpaperColor(context)`（Android S 的 `dynamicColorScheme` 从壁纸取色，低版本回落到 Miuix primary）；桌面端没有壁纸 API，也不做 Material You，所以直接回落 `MiuixTheme.colorScheme.primary`。这是本批唯一“做不到”而非“换种写法”的地方，记在缺口里。
6. **`BuildConfig.DEBUG` → `BuildInfo.DEBUG`**（2 处，调试区块）；**QuickJS 测试页那一行删掉**（它的屏幕属于 #38 的作废清单，调试闭卷子对桌面端没有对应物）；`koinViewModel` 换成多平台的 `org.koin.compose.viewmodel.koinViewModel`；`@Destination<RootGraph>` + `DestinationsNavigator` 换成路由注册 + 手写的 `Navigator`。
7. **两个选择器参数是可空的**（`fileSavePicker` / `fileOpenPicker`），与 `SongsPage` 同一条理由：可组合函数的默认参数不能调可组合函数，所以真选择器在函数体里按需构造。

#### 3. 无头取证（新增 1 类 7 项；全量 137 类 / 1163 项 / 0 失败 / 0 错误 / 3 门控跳过）

`screens/SettingsScreenTest`（真 `NavHost` + 真 Koin 图 + 真设置存储 + 假选择器）：

| 项 | 钉住的东西 |
|---|---|
| 选语言写进设置 | 驱动真的下拉框，然后读**存储**（不是 UiState）——读存储才意味着外壳也会看到 |
| 启动前写入的语言就是行的选中项 | 行读的是存储而不是本地 `remember` |
| 导出写出选择器返回的文件 | 真文件落盘、内容是备份、建议文件名是 `.json` |
| 导入应用选择器返回的文件 | 用应用自己导出的备份，把语言从 zh-CN 改回 en |
| 缓存行用格式化器报告大小 | 行的副标题走 `formattedStringResource` + `FileSizeFormatter`（空缓存读作 `0 B`） |
| 文件夹管理行导航到已注册路由 | 用记录型 navigator 断言 `FolderManagerDestination` |
| 页面渲染标题 | 最小烟雾 |

全量：C-5 那节的 1156 → **1163**（+7）。注意 `StringFormattingGuardTest` 又抳住两处 `stringResource(res, args)`（扫面区里的文件夹统计），已改 `formattedStringResource`。

#### 4. 真窗口取证（`docs/port-evidence/c37c3-*`，开发路由 `-Plyrico.start.route=settings`）

| 文件 | 内容 | OCR 关键行 | 分析 |
|---|---|---|---|
| `c37c3-settings-zh.png` | 进页：中文界面，语言行「跟随系统」，主题行「浅色模式」，文件夹管理行「已发现 1 个文件夹」 | （中文行，引擎 zh-Hans-CN） | `VERDICT: OK` |
| `c37c3-lang-dropdown.png` | 点开语言下拉：五个选项（跟随系统✓ / English / 简体中文 / 繁體中文（台灣）/ 繁體中文（香港）） | `y=234 x=958 :: English` | `VERDICT: OK` |
| `c37c3-settings-en.png` | **点 English 之后**：整页换成英文（标题 Settings、App language = English、Scan Settings、Artist poster folder…） | `y=141 :: App language`、`y=507 :: Scan Settings`、`y=573 :: 1 folders faund`、`y=624 :: Artist poste folder` | `VERDICT: OK` |
| `c37c3-poster-row.png` | 点「Artist poster folder」行：进入已搬的海报文件夹页，**仍是英文** | `y=50 :: Artist poster folder`、`y=176 :: Choose folder for reading and adding artist posters` | `VERDICT: OK` |

这三步（选语言 → 整页换文 → 跳页后仍是新语言）就是 C-2+C-3 合起来要证的整条链；同一台机器、同一次进程，没重启、没重建窗口。顺便由证据看到一处存量 i18n 毛病：`folder_found` 在英文里读作 **"1 folders found"**（复数没做）。这不是本批引入（字符串逐字保持），但把观察记在这里。

#### 5. 这批实测出来的坑（都会再踩）

1. **`performScrollTo()` 对 `LazyColumn` 没用**：未组合的行根本不是节点。`performScrollToNode` 又要求滚动容器唯一，而这一页里 `hasScrollAction()` 同时匹配列表和顶栏的 nested-scroll，所以测试改用「在 root 上 swipeUp 直到匹配出现」的有界循环。
2. **`ComposeTimeoutException` 不是 `Exception`**：写诊断代码时 `catch (e: Exception)` 接不住它（得 `catch (e: Throwable)`），否则你只会看到原始超时信息、看不到自己加的上下文。
3. **同一个 `Text` 既是行标题又是对话框标题时，节点数会变**：`exists()` 用「非空」就相对安全，`onNodeWithText(...)`（要求恰好一个）则会在对话框打开后报“找到两个”。
4. **`data object` 不能用 `()` 调**：把 `navigate(X()` 写成对 `data object` 的调用会得到“unresolved reference”。端口里的惯例是：**屏幕已搬的路由用 `data object` + `navigate(X)`**（如 `EditMetadataScreen` 对 `EditFieldSettingsDestination`），**仅声明的用 `class` + `navigate(X())`**；两边混写时编译器会告诉你哪一个错了，但要知道该往哪边改。
5. **`AppLanguage.entries` 的语言标签在测试里也得用 `stringResource` 取**：语言名是 `translatable="false"` 的，所以四个语言在任何 locale 下都一样 —— 这也让漏试能拿语言标签当稳的匹配串。
6. **`remember` 不能包可组合调用**：拼接缓存文案要调 `stringResource`/`formattedStringResource`，原来那个 `remember(...)` 只能去掉；换成“每次组合重算几行文本”后，依赖（语言、大小）自然就正确了。

#### 6. 用户可见缺口（如实记录）

1. **主题键色的「默认」不再跟随壁纸**（唯一的行为偏差，见改写点 5）：安卓 S+ 会用壁纸主色，桌面端固定用当前主题的 primary。真要补，得引入一套桌面端的取色机制，单独排期。
2. **缓存确认框的内容没有无头断言**：这一项是真没做到 — 点击那一行后 `WindowDialog` 在测试里没打开（行可见、点击也到了，就是没有对话框），而同一页另一行 `ArrowPreference` 的导航是好的、海报页的对话框在自己的测试里也能断言。原因在本次切片内没查到，所以按「记而不是装」的原则记在这里；**清理本身的语义由 `SettingsViewModelTest` 覆盖**（三类目计量 + 清理相走后接缝）。
3. **英文文案里的复数**（`1 folders found`）：存量字符串逐字保持的后果，要改就整批过一遍 i18n（与 B 片记的 `artist_poster_folder_unavailable` 同一类）。
4. **调试区只剩一条「清空数据库」**：QuickJS 测试页那行删了（它的屏幕在 #38 的作废清单里）；剩下的两项 `BuildInfo.DEBUG` 行为与安卓一致。

#### 7. 前沿（本批收口）

java 主树 38 → **37**；前沿 22 → **22**；被挡 16 → **15**；陈旧副本 **0**。

算术：`SettingsScreen` 本在**被挡**名单（阻塞它的是 `PainterUtils`），一搬走被挡就 16 → 15；它在被挡名单里而不在前沿，所以前沿不变。值得注意的是 `PainterUtils` 始终没被用上：`RoundedRectanglePainter` 在 kotlin 树里已经有一份（`ui/components/Painters.kt`），所以 C-3 靠“不再引用它”就解除了阻塞，它自己留给 #38。

下一片 C-4 是三个设置子页（`EditFieldSettingsScreen` / `LyricsCleanupRulesScreen` / `ArtistSplitSettingsScreen`），三个都已在 C-3 里声明了路由，且都在前沿（它们的视图模型早就搬完了）。

### P5 施工批次（#37-C-4：三个设置子页（718 + 272 + 547 行），2026-10-11）

#### 1. 搬了什么（`git mv` 3 个 + 路由注册 3 条）

| 文件 | 行数 | 机械迁移的字符串数 | 手工改写 |
|---|---|---|---|
| `screens/EditFieldSettingsScreen.kt` | 718 | 31 | 路由形式；`LocalConfiguration` 的 sheet 高度；两处 `stringResource(res, args)` |
| `screens/LyricsCleanupRulesScreen.kt` | 272 | 14 | 路由形式；一处 `stringResource(res, args)` |
| `screens/ArtistSplitSettingsScreen.kt` | 547 | 29 | 路由形式；`Toast` → Snackbar；一处 `stringResource(res, args)` |

路由：`LyricoNavHost` 里把 C-3 声明的三个 `Destination` 接上真实屏幕（之前它们只是「声明了但未注册」）。

#### 2. 桌面化改写点（三条都是共性的，不是这三页各自的细节）

1. **`@Destination<RootGraph>` + `DestinationsNavigator` → 路由注册 + 手写 `Navigator`**：三个文件都是同一个改法。
2. **`Toast` → Miuix `Snackbar`**（仅 `ArtistSplitSettingsScreen`）：安卓用它报两类消息——输入校验失败与「索引重建完成」；现在都走 `snackbarHostState.showSnackbar(...)`，Scaffold 补 `snackbarHost`。文案仍然是可组合的 `stringResource`（在 `LaunchedEffect` 之外算好），而状态清理的时机不变（提示发出后再 `clearError()`）。
3. **`LocalConfiguration.screenHeightDp` → 固定上限**（仅 `EditFieldSettingsScreen` 的歌曲 sheet）：安卓按屏幕高 55%（封顶 420dp）算列表高；桌面端没有那个配置对象，且窗口可缩放，所以直接取那个上限。这是**换写法而不是做不到**（sheet 在两种屏幕上都只是「别把窗口占满」）。

#### 3. 无头取证（新增 1 类 6 项；全量 138 类 / 1169 项 / 0 失败 / 0 错误 / 3 门控跳过）

`screens/SettingsSubPagesTest`（三个页各自挂在真实路由 + 真 Koin 图；数据全部先经真存储写入）：

| 页 | 项 | 钉住的东西 |
|---|---|---|
| 编辑字段 | 2 | 标题渲染；先写入的自定义标签会出现在列表里 |
| 歌词清理 | 2 | 标题渲染；存储里的规则会出现在列表里 |
| 艺术家分隔 | 2 | 标题渲染；**空名字提交后校验消息以零食条形式出现**，且被拒的输入没有写进配置 |

交互深度比 `SettingsScreenTest` 浅是故意的：这三页是长表单，每个控件的语义已由它们各自的视图模型测试钉住，屏幕这一层新增的是**组合、字符串、与消息通路**（而消息通路正好是本批改的东西）。

全量：C-3 那节的 1163 → **1169**（+6）。这次又抳住四处安卓期的 `stringResource(res, args)`（删除确认框、启用计数、自定义标签删除提示），已改 `formattedStringResource`。

#### 4. 真窗口取证（`docs/port-evidence/c37c4-*`，三页各自用 `-Plyrico.start.route=<route>` 起一次）

| 文件 | 内容 | OCR 关键行 | 分析 |
|---|---|---|---|
| `c37c4-edit_field_settings.png` | 标题 Edit Fields；右上「22 / 22 enabled」（走 `formattedStringResource`）；12+ 行字段开关（Cover / Rating / Title / Artists / Album Artist / Album / Date / Language / Genre / Track Number / Disc Number / Lyricist…） | `y=100 x=1048 :: 22 / 22 enabled` | `VERDICT: OK` |
| `c37c4-lyrics_cleanup_rules.png` | 标题 Non-Lyric Content Rules；默认规则芯片（[by: / [kana: / [trans: / [roma: / 作词: …）；说明文字；Add rule / Reset to default | `y=50 :: Non-Lyric Content Rules`、`y=331 :: Add rule` | `VERDICT: OK` |
| `c37c4-artist_split_settings.png` | 标题 Artist split rules；Enable artist splitting（开）/ Rebuild artist index（The current artist index is up to date）；分隔符号芯片（/ \| ; ： ， 、 已启用，& feat. ft. featuring 未启用）；Add separator / Reset to default；Add no-split artist | `y=50 :: Artist split rules`、`y=96 :: Artist split`、`y=213 :: Rebuild artist index`、`y=386 :: Add separator` | `VERDICT: OK` |

三张都是英文界面：上一切片（C-3）把 `app_language` 改成了 `en`，这一批没有任何地方再去撞它，所以跨进程重启仍然英文 —— 顺带证明了那条偏好真的落盘了。另外由证据看到一处存量小瑕疵：歌词清理页的默认芯片里有两对「看起来一样」的（`作词:` 与 `作词：`、`OP:` 与 `OP：`），实际是全角/半角冒号两条不同规则；默认列表来自 `LyricsProcessingOptions.DefaultTagLineKeywords`（安卓同源），不是本批引入，但这字体下用户很容易以为是重复项。

#### 5. 这批实测出来的坑（都会再踩）

1. **`StringFormattingGuardTest` 是搬文件的立即反馈**：本批四个文件搬进来时又共抳出 4 处 `stringResource(res, args)`。结论：**搬完一个文件先跑全量（或至少跑这个守卫）**，比最后一起发现便宜得多（C-3/C-5 都踩过同一条）。
2. **`stringResource(res, args)` 的搜法不能靠肉眼看 `%1$s`**：本批的四处都是多行参数写法（`stringResource(\n Res.string.x,\n arg\n)`），行内 grep `stringResource\(R` 找不到；要么直接跑守卫，要么用「`stringResource(` 后跟逗号且跨行」的模式搜。
3. **页面挂真实路由才能测 `koinViewModel()`**：三个测试都用「`NavHost` + `startDestination = 该页路由`」的写法（与已搬的屏幕测试一致），否则视图模型拿不到这个 entry 的生命周期。
4. **确定性入口比点击链便宜**：三个页的窗口证据直接用 `-Plyrico.start.route=<route>` 起，比从设置页一路点进去少一堆坐标猜谜（C5b 加那个逃生口就是为这种场景）。

#### 6. 用户可见缺口（如实记录）

1. **设置子页的入口都在设置页里**，而设置页已经注册（C-3），所以这一批之后四页都可从界面到达；但**旧条目仍有指向未注册路由的行**（如插件管理 → 已注册；批量任务/关于/本地搜索 → 待 C6/F/D），点下去只会在日志里看到一条「未注册」警告，不崩。
2. **`EditFieldSettingsScreen` 的歌曲 sheet 高度不再随窗口大小变**：固定 420dp 上限（改写点 3）。在极小的窗口里 sheet 可能偏高，在很大的窗口里则不会占满 —— 不影响功能，但不如安卓那样「总占屏高一半多」。
3. **歌词清理默认芯片里的全角/半角冒号对子**（上文第 4 节）：存量数据，要改就动默认列表（影响行为，留给清理批次）。

#### 7. 前沿（本批收口）

java 主树 37 → **34**（三个都在前沿）；前沿 22 → **19**；被挡 15 → **15**；陈旧副本 **0**。

算术：三个搬走的都在前沿（它们的视图模型/依赖早已搬完），所以 `37 - 3 = 34` 与 `22 - 3 = 19`；被挡名单不受影响（`SettingsScreen` 上批已走）。剩下的 19 个前沿文件里已有 5 个是 [`ANDROID`] 待作废（`PainterUtils` / `QuickJsTestScreen` / `PlayerIntentFactory` / `InstalledAppChecker` / `LyricoApp`），13 个属于 D（本地搜索）与 E（批量重命名/编辑）。

下一片 C-6：本簇收口（全量 + 真窗口证据汇总 + 本批已写好的 PLAN 段落回查）。C-6 原本还要拍「语言切换前后两张图」，**这个已经在 C-3 完成了**（`c37c3-settings-en.png` 就是切换后），所以 C-6 只剩汇总与校验。

### P5 施工批次（#37-C-6：设置簇收口，2026-10-11）

本簇切了五刀，每一刀都单独提交并单独带证据（前四节就是各自的记录，下面只做汇总与回查）。

#### 1. 五刀的提交与体量

| 切片 | 提交 | 搬了什么 | 无头 | 真窗口 |
|---|---|---|---|---|
| C-1 叶子 | `e6526bd` | `git mv` 6 个（含 `CacheManager` 整篇重写） | 新增 1 类 7 项 + 迁移死测试 4 项 | — |
| C-2 视图模型 + 语言接缝 | `be63cba` | `git mv` 2 + 新文件 2 + 接线 8 处 | 新增 4 类 21 项 + 扩 2 类 | `c37c2-*`（2 张） |
| C-5 文件夹管理 | `62d7240` | `git mv` 2（含 1312 行页）+ 作废 2 | 新增 2 类 10 项 | `c37c5-*`（3 张） |
| C-3 设置页 | `2ae6c7a` | `git mv` 1（836 行）+ 路由 | 新增 1 类 7 项 | `c37c3-*`（4 张） |
| C-4 三个子页 | `20a4983` | `git mv` 3（1537 行）+ 路由 3 条 | 新增 1 类 6 项 | `c37c4-*`（3 张） |

顺序上 C-5 在 C-3 之前（依赖逼出来的，见 C-5 节开头）；其余与 `todo` 一致。共 `git mv` 14 个文件、`git rm` 2 个、新增 4 个主源文件（`ui/AppLocale.kt`、`utils/coil/CoilImageCache.kt` + 路由/接线如各节所述）。

#### 2. 收口时的全量口径

**138 类 / 1169 项 / 0 失败 / 0 错误 / 3 门控跳过**（空载全量；本簇起点是 37-B 的 128 类 / 1112 项 / 2 跳过）。本簇共新增 10 类、57 项；跳过从 2 到 3 是多出来的那一个探针。

口径的两个注意点：

* 这个数**不包含** `src/test/java` 里那 15 个从未编译过的安卓期测试（C-1 第 2 节 / #62）；#62 收口时口径会再变一次。
* 全量跑里偶发过三次不同类的失败（`ArtistPosterFoldersScreenTest` / `BatchTaskSchedulerTest` / `LibraryHomeScreenTest` 族），根因是「DI 的 `CoroutineScope` 不随 `stopKoin()` 取消」 —— C-5 把本批新引入的那一半修了（新屏幕测试在 `tearDown` 里先 `cancel()`）；#42 记的旧的那一半仍在。

#### 3. 原始 recon 清单的回查（一件一件对）

设置簇开工时的 recon 列了七个「必须处理」项，逐条对账：

| recon 里的项 | 结果 |
|---|---|
| `SettingsScreen` 836 行搬迁 | ✅ C-3 |
| `Formatter.formatFileSize` 三处 → `FileSizeFormatter` | ✅ C-3（还额外修了 4 处 `stringResource(res, args)`） |
| `AppCompatDelegate` + `LocalConfiguration` 的语言行 | ✅ C-2 做接缝、C-3 接上（真窗口证了实时切换） |
| `BuildConfig.DEBUG` 两处 → `BuildInfo.DEBUG` | ✅ C-3 |
| 删掉 QuickJS 测试行 | ✅ C-3 |
| 壁纸取色的偏差 | ✅ C-3（回落 `MiuixTheme.colorScheme.primary`，记入缺口） |
| 海报文件夹入口行 + settings 路由 + 海报页证据 | ✅ C-3（行与路由）+ `c37c3-poster-row.png`（证明点得进） |

recon 之外、实际撞上并处理了的额外三件：**`FolderManagerViewModel` 的 SAF 退场**（C-5）、**文件夹名分隔符 bug**（C-5，三处）、**`UriUtils`/`SafDocuments` 的作废**（C-5，`git rm`）。

#### 4. 本簇的缺口（汇总，各自展开在对应节）

1. **主题键色的「默认」不跟壁纸取色**（C-3）：唯一的行为偏差，因为桌面端没有壁纸/Material You。
2. **主题键色在薄荷模式下不生效**（C-2）：`LyricoTheme` 只在 `monetEnabled` 为假时把 `keyColor` 交给 `ThemeController`（安卓原样）。
3. **缓存确认框内容无无头断言**（C-3）：点那一行后 `WindowDialog` 在测试里没打开，原因未查到；清理语义由 `SettingsViewModelTest` 覆盖。
4. **桌面端没有系统返回手势**（C-5）：从文件夹回上一层只能点面包屑/子目录列表（其它已搬屏幕同一缺口）。
5. **命令行传入的文件不会自动打开**（C-2）：`externalFilePath` 至今无人消费，双击关联文件只会打开曲库首页。
6. **英文文案里的复数**（C-3）：`1 folders found` 这类；字符串逐字保持的后果。
7. **歌词清理默认芯片的全角/半角冒号对子**（C-4）：存量默认列表。
8. **`EditFieldSettingsScreen` 的歌曲 sheet 高度固定 420dp**（C-4）：不再随窗口大小变。

#### 5. 前沿（本簇收口）

java 主树 50 → **34**（本簇推平 16 个：14 个搬走 + 2 个作废）；前沿 28 → **19**；被挡 22 → **15**；陈旧副本从始至终 **0**。

剩下的 19 个前沿里，**5 个是 [`ANDROID`] 待作废**（`PainterUtils` / `QuickJsTestScreen` / `PlayerIntentFactory` / `InstalledAppChecker` / `LyricoApp`），其余属于 D（本地搜索）、E（批量重命名/编辑）与尾部杂项；被挡的 15 个集中在 `App.kt` / `di/AppModule` / `MainActivity` / `AboutScreen` / `FolderManager` 的尾巴与批量编辑。

下一片是 #37-D（本地搜索页），它的 recon 已经在本会话早期做过（`LocalSearchField` / `SearchSectionHeader` / `LocalSearchScreen` / `LocalSearchViewModel` + 依赖）。

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

### P5 施工批次（#37-D：本地搜索页（767 行 + 2 个叶子），2026-10-11）

#### 1. 搬了什么（`git mv` 3 个 + 路由注册 1 条）

| 文件 | 行数 | 说明 |
|---|---|---|
| `data/model/search/LocalSearchField.kt` | 34 | `labelRes` 从 `@StringRes Int` 变成 `StringResource`（唯一的语义类型改动） |
| `ui/components/search/SearchSectionHeader.kt` | 19 | 逐字 |
| `screens/LocalSearchScreen.kt` | 767 | 13 处字符串机械迁移 + 手工缝合（路由/BackHandler/分享/字符串格式化） |

路由：`LyricoNavHost` 注册 `LocalSearchDestination`（`local_search`）—— 曲库页的搜索图标早就指向这个声明，这一次才真正落地成屏幕。

#### 2. 桌面化改写点（逐条）

1. **`LocalSearchField.labelRes`：`@StringRes Int` → `StringResource`**。这是本批唯一需要改类型的地方：安卓用资源 id，桌面端用 `StringResource`。改完之后屏幕里那个私有 `LocalSearchTab.labelRes` 也跟着从 `Int` 变 `StringResource`，于是 `stringResource(tab.labelRes)` 三处编译错误一次消掉 —— **类型系统自己把接缝找了出来**，比 grep 可靠。
2. **`BackHandler` 删掉**（桌面端没有系统返回手势）：菜单靠遮罩关、选择模式靠顶栏关闭按钮退，与 `FolderManagerScreen` / `AlbumDetailScreen` / `LibraryHomeScreen` 同一处缺口。
3. **`SongActionSheets` 的分享/复制显式传入 + `batchShare` → `share`**（与 C-5 同款改动；`LocalClipboard` + `ClipEntry(StringSelection(...))`）。
4. **三处 `stringResource(res, args)` → `formattedStringResource`**（歌/专辑/艺人三个分区标题的计数）：库的 vararg 格式化不替换普通 `%d`，由 `StringFormattingGuardTest` 抓住。
5. **`@Destination<RootGraph>` + `DestinationsNavigator` → 路由注册 + 手写 `Navigator`**；`koinViewModel` 换多平台入口。

#### 3. 无头取证（新增 1 类 2 项；全量 139 类 / 1171 项 / 0 失败 / 0 错误 / 3 门控跳过）

`screens/LocalSearchScreenTest`（真路由 + 真 Koin 图 + 图**自己的**数据库）：

| 项 | 钉住的东西 |
|---|---|
| 输入查询后只列匹配的歌 | 往搜索框真打字（`hasSetTextAction()`），匹配的歌出现、**不匹配的不出现**（后者是防「其实没过滤」的对照断言） |
| 没输入任何东西时页面也能组合 | 真库 + 真图下的最小烟雾 |

写这个测试时踩了一个自己造的坑并修掉：一开始用 `TestLibrary` 播种，而它打开的是**另一个**数据库文件（图开的是 `AppDirectories` 下那个），屏幕会很正确地什么都读不到。改成经 `GlobalContext` 拿图里的 `LyricoDatabase` 播种后一致。

#### 4. 真窗口取证（`docs/port-evidence/c37d-local-search.*`，`-Plyrico.start.route=local_search`）

| 文件 | 内容 | OCR 关键行 | 分析 |
|---|---|---|---|
| `c37d-local-search.png` | Local Search 页：搜索框（占位「Search song metadata」）+ 十一个字段页签（All / Title / Artists / Album / Album Artist / Date / Language / Genre / Lyricist / Composer / Copyright / Comment） | `y=50 :: Local Search`、`y=156 :: Title Artsts Album`、`y=156 :: Album Artist`、`y=157 :: Comment` | `VERDICT: OK` |

**如实记录一处证据缺口**：这一页的「输入查询 → 出结果」没能在真窗口里拍到。试过两条路都不行 —— `click-window.ps1` 点击后另起一个 PowerShell 发 `SendKeys`（焦点已丢），以及「激活 → 真点击 → 同会话 SendKeys」（窗口在第二块显示器上、坐标是负的，点击本身到位了但字符仍然没进输入框）。所以窗口证据只证「页面在真窗口里长得对、十一个 `StringResource` 页签都渲染出来」，而**查询行为由上面那个无头测试钉住**（它往同一个输入框真打字并断言匹配/不匹配）。要补真实输入链得给取证脚本加一个「按客户区坐标点击 + 打字」的参数，列出为后续工具改进项。

#### 5. 这批实测出来的坑（都会再踩）

1. **`@StringRes Int` → `StringResource` 的改动，编译器会替你找齐所有调用点**（而 grep 找不全）：改完声明后三处 `stringResource(tab.labelRes)` 报错，正好是全部接缝。
2. **测试播种必须播进「图自己的那个数据库」**：`TestLibrary` 是另一个文件；症状是页面很正确地什么都不显示，让人误以为是屏幕 bug。同理，`SongLibraryRepositoryImpl` 要拿 `GlobalContext.get().get()` 得到的那一个 `LyricoDatabase`。
3. **`onAllNodes` / `onNode` 是 `ComposeUiTest` 的成员，不是扩展函数**（C5b 已记过一次）：import 它们会编译失败，直接 `this` 上调即可。
4. **窗口可能在第二块显示器上（坐标是负的）**：`capture-window.ps1` 会打印负的原点，而「屏幕坐标」要先减去那个原点才是屏幕点；`click-window.ps1` 自己算这个偏移所以没事，自己写 PowerShell 就会打偏。
5. **`SendKeys` 打不进 Skiko 文本框**（本机的实测结论，至少对「另开会话」的方式）：窗口必须先真聚焦，且点击与打字要在同一次焦点保持期内完成；即使同会话试过也没成功，所以本页的输入链改由无头测试覆盖。

#### 6. 用户可见缺口（如实记录）

1. **本页的真窗口证据只有渲染，没有查询结果**（上一节）：行为由无头测试覆盖，工具链的输入支持列为后续改进项。
2. **桌面端没有系统返回手势**（同 C-5 / C-3 的缺口）。
3. **`local_search` 现在有了正式入口**（曲库页搜索图标），不再是只能靠开发路由到达的页面。

#### 7. 前沿（本批收口）

java 主树 34 → **31**（三个都在前沿）；前沿 19 → **17**；被挡 15 → **14**；陈旧副本 **0**。

算术：三个搬走的文件里**两个本在前沿**（`LocalSearchField` / `SearchSectionHeader`），**一个本在被挡名单**（`LocalSearchScreen`，它等的正是那两个叶子）；所以前沿 `19 - 2 = 17`、被挡 `15 - 1 = 14`、`34 - 3 = 31` 三者自洽。剩下的 16 个里 5 个是 `[ANDROID]` 待作废，其余属于 E（批量重命名/编辑）与尾部杂项。

### P5 施工批次（#37-E-1：批量重命名链（5 个文件）+ RenameEngine 的真文件测试，2026-10-11）

E 片按「引擎」与「批量编辑」切开：E-1 是重命名这条链（`ConflictResolver` → `RenamePreview` → `RenameEngine` → `BatchRenameViewModel` → `BatchRenameScreen`），E-2 是批量编辑与字符映射页（约 2500 行）。

#### 1. 搬了什么（`git mv` 5 个 + 路由 1 条 + DI 1 条 + 声明 1 条）

| 文件 | 行数 | 说明 |
|---|---|---|
| `utils/ConflictResolver.kt` | 34 | 逐字（`File`/`Set<String>`，本来就没有安卓 API） |
| `data/model/RenamePreview.kt` | 7 | 逐字 |
| `utils/RenameEngine.kt` | 78 | 逐字（格式解析/清洗/冲突解决，纯逻辑） |
| `viewmodel/BatchRenameViewModel.kt` | 414 | 1 处字串机械迁移 + `UiMessage.StringResource(...)` → `UiMessage.Localized(Res.string.…)` |
| `screens/BatchRenameScreen.kt` | 560 | 22 处字符串机械迁移 + 手工缝合（见下） |

接线：`LyricoNavHost` 注册 `BatchRenameDestination`（`batch_rename`）；`DesktopAppModule` 注册 `BatchRenameViewModel`（**之前没注册过** —— 安卓的 `di/AppModule.kt` 里有，桌面图里没有，测试一挂就暴露了）；`Destinations.kt` 新增 `CharacterMappingDestination`（E-2 的屏幕，按「声明了但未注册」惯例先声明）。

#### 2. 桌面化改写点（比前几批少，因为这条链本身很纯）

1. **`@Destination<RootGraph>` + `DestinationsNavigator` → 路由注册 + 手写 `Navigator`**；`koinViewModel` 换多平台入口。
2. **`UiMessage.text(context)` 那类调用改成 `asString()`**（桌面端没有 `Context`，`asString` 是可组合的；调用点本来就在组合里）。
3. **私有 `BatchRenameTab.labelRes: Int` → `StringResource`**（与 D 片同一条教训：类型一改，三处调用点自己报出来）。
4. **`stringResource(id = Res.string.x)` 的具名参数去掉**（安卓的 `stringResource` 有 `id` 参数，Compose Resources 的没有）。
5. **9 处 `stringResource(res, args)` → `formattedStringResource`**（计数/耗时那些；守卫抓住）。
6. **`characterMappingConfig` 那条 `CharacterMappingDestination` 的调用**：目标屏幕属于 E-2，先声明路由，呼叫点写成对象式 `CharacterMappingDestination()`（未注册路由的惯例）。

#### 3. 无头取证（新增 2 类 7 项；全量 141 类 / 1178 项 / 0 失败 / 0 错误 / 3 门控跳过）

| 测试类 | 项 | 钉住的东西 |
|---|---|---|
| `utils/RenameEngineTest` | 5 | 真临时文件 + 真 `AudioTagData`：新名从标签来且保后缀；**两首同名会加 `(1)` 且第二首被标 `conflict`**（预览页靠这个标记上色）；清洗后名字为空时回落到原名；字符映射规则作用在新名上；预设格式互不相同且用 `@` 占位语法 |
| `screens/BatchRenameScreenTest` | 2 | 真路由 + 真图：空选择下页面能组合出标题；**设置里存的格式就是字段里显示的格式**（顺带证明读写走同一条偏好） |

**为什么没有 `BatchRenameViewModelTest`**：这个 VM 的「真干活」那一半是 `executeRename()`，它把活交给批量任务引擎（`BatchTaskRepository` + `BatchTaskScheduler`）；那条链已经由 `BatchTaskSchedulerTest` / 各处理器测试端到端覆盖，而它真正自有的计算就是 `RenameEngine`（上面已用真文件钉住）。这是取舍，不是漏掉。

写这批测试时又踩到两件事，一并记下：**测试用的 `TestLibrary` 和图不是同一个数据库**（D 片已踩过一次，这次没重犯）；以及下面第 5 节的坑 1。

#### 4. 真窗口取证（`docs/port-evidence/c37e1-batch-rename.*`，`-Plyrico.start.route=batch_rename`）

| 文件 | 内容 | OCR 关键行 | 分析 |
|---|---|---|---|
| `c37e1-batch-rename.png` | Batch Rename 页：Config / Preview (0) 两个页签、Rename Format 字段里是 `@1 - @2`（设置里的默认值）、提示「Enter the naming format, for example @1 - @2」、Placeholder help 开关、置灰的 Rename 按钮 | `y=50 :: Batch Rename`、`y=101 :: Preview (O)`、`y=149 :: Rename Format`、`y=320 :: Placeholder help`、`y=388 :: Rename` | `VERDICT: OK` |

空选择下 Rename 置灰、Preview 计数为 0，正是这一页在没有选歌时的正确形状；有选择的预览/执行链由 `RenameEngineTest` 与批量任务测试覆盖。

#### 5. 这批实测出来的坑（都会再踩）

1. **不要用正则批量重写 `stringResource(\n Res.string.x,`**：这次为了让 9 处多行调用改走 `formattedStringResource`，我写了一个跨行正则，结果**把单参数的多行调用也改掉了**，加上顺手加的一个 `if/else` 花括号，整个文件语法坏掉，报出来的却是一堆「`BatchRenameConfigTab` 未解析」这种下游错误（声明本身是好的，是解析器在中途崩了）。正确做法：**一处一处改**，或者先只定位行号再逐条替换；跨行正则在这个代码风格里不值得。
2. **`viewModel { }` 没注册时，第一个挂载该页的测试就会告诉你**（`NoDefinitionFoundException`）：安卓的 `di/AppModule.kt` 里有一份定义，很容易以为桌面图里也有 —— 搬页面时顺手确认一次。
3. **`stringResource(id = ...)` 是安卓形态**：Compose Resources 的 `stringResource` 没有 `id` 参数，具名调用会直接编译不过（比想象中容易漏，机械脚本不动具名参数）。
4. **守卫（`StringFormattingGuardTest`）会把「多行参数写法」全找出来**：本片它在两个不同的函数里各抓一次，如果只看行内 grep 会漏。

#### 6. 用户可见缺口（如实记录）

1. **正式入口在曲库页的批量菜单里**（已注册的 `batch_rename`）：现在点得进去，页面本身也是完整的 Config/Preview 两页；但**没有选歌时按钮置灰**，这与安卓一致。
2. 桌面端没有系统返回手势（同前几片）。
3. **`character_mapping` 路由已声明但未注册**（E-2 的屏幕），点下去只在日志里警告。

#### 7. 前沿（本批收口）

java 主树 31 → **26**（5 个：2 个本在前沿、3 个本在被挡）；前沿 17 → **15**；被挡 14 → **11**；陈旧副本 **0**。

### P5 施工批次（#37-E-2：批量编辑页（1427 行）+ 字符映射页（157 行）+ 936 行视图模型，2026-10-11）

#### 1. 搬了什么（`git mv` 3 个 + 路由 2 条 + DI 1 条）

| 文件 | 行数 | 说明 |
|---|---|---|
| `viewmodel/BatchEditViewModel.kt` | 938 | 4 处字符串机械迁移；`coverUri` 从 `Any?`/`Uri` 变 `File?`；`Log.e` → `PlatformLog.e`；`labelResId` 从 `Int` 变 `StringResource`；两个纯函数改 `internal`（见第 3 节） |
| `screens/BatchEditScreen.kt` | 1427 | 40 处字符串机械迁移；SAF/ActivityResult 选封面 → `rememberFileOpenPicker` 接缝；`BackHandler` 删除；`Uri`→`File`；`CoverRequest(path)`；5 处 `stringResource(res, args)` → `formattedStringResource` |
| `screens/CharacterMappingScreen.kt` | 157 | 7 处字符串机械迁移；路由形式；1 处 `formattedStringResource` |

接线：`LyricoNavHost` 注册 `batch_edit` 与 `character_mapping`；`DesktopAppModule` 注册 `BatchEditViewModel`（又是安卓 `AppModule` 里有、桌面图里没有的一个）。

`data/model/entity.getUri` 这个「阻塞项」最后是虚惊：桌面端的 `SongEntity.uri` 本身就是路径字符串，把那处 `song.getUri.toString()` 写成 `song.uri` 即可，**没有为它新增任何文件**——前沿脚本的依赖判断基于符号所在树，而这是安卓期的扩展函数，已在别处被同样处理过。

#### 2. 桌面化改写点（逐条）

1. **`Uri` 全线退场**：`updateCover(uri: Uri)` → `updateCover(cover: File)`；状态里的 `coverUri: Any?` → `coverUri: File?`；`applyCoverSource` 的 String 分支直接 `File(cover)`（桌面端那个字符串**就是**路径），ByteArray 分支写临时文件后传 `File`；`CoverRequest(option.previewUri)`（它的 `uri` 参数本来就是 `String`）。
2. **选封面：`PickVisualMedia` → `rememberFileOpenPicker`**（可空参数供测试注入，理由同 `SongsPage`）；两个入口都是挂起调用，选完投给 ViewModel。
3. **`stringResource(id = ...)` / `painterResource(id = ...)` 的具名参数去掉**（Compose Resources 的这两个函数没有 `id`）——本批各两处。
4. **`Log.e` → `PlatformLog.e`**（桌面端的日志接缝，其它已搬的 VM 同款）。
5. **`labelResId: Int` → `StringResource`**（字段枚举与预览项各一处）。
6. **`BackHandler` 删除**（同前几片）。

#### 3. 无头取证（新增 1 类 6 项；全量 142 类 / 1184 项 / 0 失败 / 0 错误 / 3 门控跳过）

`viewmodel/BatchEditPreviewTest` 驱动的是这个 VM 里**决定「到底会写什么」**的两个纯函数（`buildPreviewChanges` 与 `toTaskConfig`，为了让测试能直接调用而从 `private` 改成 `internal`，这是本批唯一的可见性改动）：

| 项 | 钉住的东西 |
|---|---|
| 编辑过的字段出现在预览里，带自己的标签资源 | 新旧值与标签都指向对的那个字段 |
| 没动过的字段不产生预览行 | 未动 = `KEEP_VALUE` 哨兵，不是空串 |
| 被配置隐藏的字段即使状态里有值也不进预览 | 显隐由「编辑字段」配置决定 |
| 清空一个字段**会**作为「改成空」被预览 | 「清空」与「没动」是两回事 |
| 没动过的字段进任务配置是 `keep` | 反向保证：不会把用户没碰的标签抹掉 |
| 清空的字段进任务配置是空串 | 清空是一次真写入，不是 keep |

写这批测试时的收获正好是个反例教材：我第一版把「没动过」写成空串，三条断言当场挂了 —— **代码是对的，我的假设错了**（哨兵 `KEEP_VALUE` 才是「没动」）。真窗口证据里每一行都显示 `<keep>`，与测试互相印证。

没有为这两个页面写屏幕测试：它们的组合路径由真窗口证据覆盖（下面两张图），而它们真正自有的逻辑就是上面那两个函数。

#### 4. 真窗口取证（`docs/port-evidence/c37e2-*`，两张各用 `-Plyrico.start.route` 起一次）

| 文件 | 内容 | OCR 关键行 | 分析 |
|---|---|---|---|
| `c37e2-batch_edit.png` | Edit Tags：Config / Preview (0)、「0 songs selected」、封面上传占位、评分星、以及每行都是 `<keep>` 的字段（Title / Artists / Album Artist / Album …） | `y=101 :: Preview (O)`、`y=149 :: 0 songs selected`、`y=494/585/676 :: <keep>` | `VERDICT: OK` |
| `c37e2-character_mapping.png` | Configure Character Mapping：提示行 + 每个字符一行，右侧是替换方案下拉（Full-width backslash ( ＼ ) 等） | `y=100 :: Tap character tO change its replacement`、`y=157 :: Character \`、`y=231 :: Character /` | `VERDICT: OK` |

字符映射页那几行的标题正是 `formattedStringResource(Res.string.character_to_replace, character)` 的结果（字符被替进标题），所以这张图同时证明了那处格式化改写。

#### 5. 这批实测出来的坑（都会再踩）

1. **`stringResource(res, args)` 的批量改名不能靠正则**：E-1 的教训（跨行正则改坏了文件）这次换成一个**括号/引号感知的小脚本**（`build/tmp-probe/format-calls.py`）：找到 `stringResource(` 后跳到配对的 `)`，只在**顶层有逗号**时替换，于是「单参数但跨行」的调用被正确放过。同一脚本把 `BatchEditScreen` 的 5 处与 `CharacterMappingScreen` 的 1 处各一次改对。
2. **删 `BackHandler` 不能靠数括号**：它是 `BackHandler { … }`（尾随 lambda），从 `(` 开始配对括号只会删掉头部，留下一个孤立的 `if` 块 —— 报出来的是「顶层声明」语法错误。要连 lambda 体一起删。
3. **`stringResource(id = …)` / `painterResource(id = …)` 是安卓形态**（本批各两处），Compose Resources 的这两个函数都没有 `id` 参数。
4. **`internal` 是把纯函数暴露给测试的最轻手段**：不必引入接口或构造参数，也不要把它们挪出类（`toTaskConfig` 是成员扩展，测试里用 `with(viewModel()) { … }` 调用即可）。
5. **UI 状态里「没动」用哨兵而不是空串**：这个 VM 用 `EditTagsTaskConfig.KEEP_VALUE`（`<keep>`），把「清空」与「没动」分开；写测试前先读实现，否则会像我第一版那样把语义读反（三条断言白挂）。

#### 6. 用户可见缺口（如实记录）

1. 这两页的**正式入口都在曲库页的批量菜单里**（已注册的路由），字符映射页从重命名页/批量编辑页的行进入；现在都点得进去。
2. 桌面端没有系统返回手势（同前几片）。
3. **没有为这两页写屏幕级无头测试**（理由见第 3 节末）；窗口证据覆盖它们的组合与文案。

#### 7. 前沿（本批收口）

java 主树 26 → **23**（3 个：`BatchEditViewModel` 与两个屏幕，都不在前沿）；前沿 15 → **15**；被挡 11 → **8**；陈旧副本 **0**。

算术：三个搬走的文件都在被挡名单（两个等 `BatchEditViewModel`、一个等 `getUri` 那个符号），所以被挡 `11 - 3 = 8`，前沿不变、`26 - 3 = 23`。剩下的 15 个前沿里 5 个 `[ANDROID]` 待作废（`PainterUtils` / `QuickJsTestScreen` / `PlayerIntentFactory` / `InstalledAppChecker` / `LyricoApp`），其余是 F（关于页/更新对话框/许可页）、`App.kt`+`di/AppModule`+`MainActivity` 这条安卓外壳尾巴与几个尾部杂项。

### P5 施工批次（#37-F：关于页 + 更新对话框 + 开源许可页，2026-10-11）

#### 1. 搬了什么（`git mv` 4 个 + 路由 2 条 + DI 1 条 + 新文件 1 个 + 接缝扩展 1 处）

| 文件 | 行数 | 说明 |
|---|---|---|
| `screens/AboutScreen.kt` | 280 | `App.Companion` 三个常量 → `BuildInfo.UPDATE_OWNER/UPDATE_REPO` + 页内常量；`BuildConfig` → `BuildInfo`；`openBrowser(context, url)` → `openBrowser(url)`；路由形式；2 处 `stringResource(res, args)` → `formattedStringResource`；**新增：托管更新对话框** |
| `viewmodel/AboutViewModel.kt` | 86 | 去掉 `Context`/`Intent`（浏览器交给 `DesktopOpener`）；贡献者查询改用 `BuildInfo.UPDATE_OWNER/UPDATE_REPO`；新增 `releaseInfo` 流与 `dismissUpdate()` |
| `ui/components/update/UpdateDialog.kt` | 55 | `MarkdownText`（安卓专用库）→ 端口已有的 `MiuixMarkdownText`；1 处 `formattedStringResource` |
| `screens/OpenSourceLicenceScreen.kt` | 130 | 数据源从 `produceLibraries(R.raw.aboutlibraries)` 换成手维护的 `AppLicences.all`；滚动条从 `my.nanihadesuka`（安卓库）换成端口的 `LibraryScrollbar` 覆盖层 |
| 新文件 | `data/model/AppLicence.kt` | 许可条目模型 + 手维护清单（12 项） |

接缝扩展：`DesktopOpener` 增加 `browse(url: String)`（`java.awt.Desktop` 的 BROWSE 动作与 OPEN 是两个可分别不受支持的动作）。DI：`AboutViewModel` 与 `DesktopOpener` 各一条；路由：`about` 与 `opensource_licence`（安卓拼写就是小写 s，照抄）。

#### 2. 桌面化改写点（逐条）

1. **`ACTION_VIEW` intent → `DesktopOpener.browse`**：`openBrowser(context, url)` 变成 `openBrowser(url)`，调用点少一个参数；VM 不再碰 `Context`，而这个接缝是**可注入**的，所以测试能断言「把哪个 URL 交给了系统」。
2. **`App.Companion` → `BuildInfo`**：安卓那两个常量在 `App.kt`（整个安卓外壳都在作废清单上），桌面端用的是生成出来的 `BuildInfo.UPDATE_OWNER/UPDATE_REPO`（fork 决策写在那份生成文件的注释里）。`TELEGRAM_GROUP_LINK` 没有对应物，作为页内常量保留原值。
3. **`MarkdownText` → `MiuixMarkdownText`**（`com.github.jeziellago:compose-markdown` 是安卓专用；端口已经有基于 multiplatform-markdown-renderer 的实现，见 `libs.versions.toml` 里那段注释）。
4. **许可页的数据源从头换了**：安卓由 `aboutlibraries` 插件在编译期从依赖图生成；桌面构建没有这个插件。新清单是手维护的 12 项（UI 工具包 → 应用自身管道 → 媒体/引擎），`AppLicence` 的 KDoc 把代价写清楚了：**加到 `libs.versions.toml` 而没加到这里的东西不会出现在这一页**；`AppLicencesTest` 机器化地钉住能钉的部分（id 唯一、每项有 name/version/licence/URL）。
5. **版本号全部对着权威文件核过一遍**：初稿我凭印象写了几个（Koin 4.1.1、Room 2.8.2、OkHttp 5.1.0、miuix 0.6.0、ffmpeg 7.1/LGPL-2.1…），随后逐个改成 `gradle/libs.versions.toml`、`lyrico-audiotag/src/main/cpp/taglib/taglib/toolkit/taglib.h`（TagLib 2.3.0）与 `docs/third-party/ffmpeg-lgpl.md`（`N-127271-gba987fe24d`，**LGPL-3.0** 而不是 v2.1）里的值；quickjs-ng 在仓库里没有固定版本，就写 `bundled` 而不是编一个。
6. **滚动条换成端口自己的 `LibraryScrollbar`**：它是覆盖层（`libraryScrollbarOverlay` 让开顶栏/底栏），所以它是列表的兄弟节点；`LazyColumnScrollbar` 那个包装式写法随之删掉。

#### 3. 无头取证（新增 2 类 6 项；全量 144 类 / 1190 项 / 0 失败 / 0 错误 / 3 门控跳过）

| 测试类 | 项 | 钉住的东西 |
|---|---|---|
| `data/model/AppLicencesTest` | 4 | 每项都有 id/名字/版本/至少一个许可证；id 唯一（列表用它当 key）；每项都有 https 的 URL；编译期真正依赖的那几个组件在清单里 |
| `viewmodel/AboutViewModelTest` | 2 | `openBrowser` 把 URL 交给注入的 `DesktopOpener`；**贡献者查询问的是 fork（`CN-Grace/Lyrico-Desktop`）而不是安卓上游** |

顺带修了两处因接缝扩展而挂掉的既有测试假体（`PlaybackRepositoryTest` 与 `EditMetadataViewModelTest` 里的匿名 `DesktopOpener`）——这是接口加成员时的正常连锁，两处各补一行 `browse`。

全量：E-2 那节的 1184 → **1190**（+6）。

#### 4. 真窗口取证（`docs/port-evidence/c37f-*`，两页各用 `-Plyrico.start.route` 起一次）

| 文件 | 内容 | OCR 关键行 | 分析 |
|---|---|---|---|
| `c37f-about.png` | About：App Version `1.6.0(20)`（`BuildInfo`）、GitHub、Telegram（`https://t.me/lyrico_app`）、Open source licenses 行、Auto-check for updates 开关、Check for updates，以及**真实拉到的贡献者**（Replica0110 565 commits / CN-Grace 53 commits） | `y=50 :: About`、`y=213 :: GitHub`、`y=287 :: Telegram`、`y=366 :: source licenses`、`y=557 :: Contributors`、`y=709 :: 53 commits` | `VERDICT: OK` |
| `c37f-opensource_licence.png` | Open source licenses：手维护清单的前几项（kotlinx.coroutines 1.10.2 / kotlinx.serialization 1.11.0 / AndroidX Room 2.8.5 / DataStore 1.2.1 / OkHttp 5.5.0 / Coil 3.6.2 / TagLib…），右侧能看到端口的滚动条 | `y=104 :: kotlinx.coroutlnes`、`y=314 :: AndroidX Room`、`y=524 :: OkHttp`、`y=629 :: Coil` | `VERDICT: OK` |

关于页那张图的**左上角有一条第三方输入法候选窗残留**（我早前试「点击+打字」时留下的系统 IME 候选窗，恰好叠在窗口框架上）；它不是应用的一部分，许可页那张（后拍的）是干净的。这里如实记下来，免得以后有人以为界面多了个控件。

#### 5. 这批实测出来的坑（都会再踩）

1. **给接口加一个成员会让所有测试假体挂掉**：`DesktopOpener` 加 `browse` 之后，两个既有匿名实现当场编译不过；加成员前先 `grep "object : DesktopOpener"`，比等编译器告诉你便宜。
2. **手维护的清单里不要凭印象写版本号**：本批初稿六个版本号是错的（我把 ffmpeg 写成 LGPL-2.1，而仓库自己的 `docs/third-party/ffmpeg-lgpl.md` 明写着 `--enable-version3` 即 LGPL v3）。正确做法是先找到权威文件（`libs.versions.toml` / 上游头文件 / `docs/third-party/`），找不到就写「bundled」之类的实话，不要编。
3. **`my.nanihadesuka` 与 `MarkdownText` 都是安卓专用库**，端口各有替换（`LibraryScrollbar` 覆盖层、`MiuixMarkdownText`）；它们各自的使用形状不一样（一个包装列表、一个是兄弟覆盖层），别照着旧调用形状改。
4. **`stringResource(res, args)` 的机械检查还是要靠守卫**：本批 `AboutScreen` 又有 2 处，`build/tmp-probe/format-calls.py` 一次改对。

#### 6. 用户可见缺口（如实记录）

1. **启动时的自动检查更新没有接线**：安卓的 Activity 外壳在启动时触发检查，桌面端只有关于页的「Check for updates」按钮会触发（`SongListViewModel.checkForUpdate()` 已存在但无人调用）。设置了「自动检查更新」也只在打开关于页时才生效 —— 要接就得一并决定对话框挂在哪里（见下一条），归到后续批次。
2. **更新对话框现在只挂在关于页上**：安卓挂在 Activity 外壳上，所以它在任何页面之上都能弹出；桌面端把它放在了触发检查的那一页。若把启动检查接上，这个宿主位置需要重新决定。
3. **许可清单是手维护的**（第 2 节第 4 条）：比安卓那份「全量依赖」短，且会滞后于 `libs.versions.toml`。
4. 桌面端没有系统返回手势（同前几片）。

#### 7. 前沿（本批收口）

java 主树 23 → **19**；前沿 15 → **13**；被挡 8 → **6**；陈旧副本 **0**（三个数来自 `port-frontier.py` 的实测输出）。

算术：四个搬走的文件里**两个本在前沿**（`UpdateDialog` 与 `OpenSourceLicenceScreen`），**两个本在被挡名单**（`AboutScreen` / `AboutViewModel`，它们等的就是 `App.kt` 里的两个常量 —— 本批把那两个常量改成读 `BuildInfo`，所以没有新增任何文件，阻塞是「换掉依赖」而不是「搬进来」解除的）。于是前沿 `15 - 2 = 13`、被挡 `8 - 2 = 6`、`23 - 4 = 19`，三者自洽。剩下的 13 个前沿里 5 个是 `[ANDROID]` 待作废，其余是安卓外壳尾巴（`App.kt` / `di/AppModule.kt` / `MainActivity.kt` / `LyricoApp.kt`）、播放器选择与 `FriendlyPlayer` 簇、`ReferenceFloatingBar` + 6 个 liquid 组件、以及 `DeleteRawPropertiesMigration`。

### P5 施工批次（#38：安卓遗留源收口 —— `src/main/java` 归零，2026-10-11）

这一批没有「搬」任何东西：它把 java 主树最后 19 个文件按「机制已被替换 / 本来就不属于桌面」逐条作废，于是
**`lyrico-app/src/main/java` 下的 Kotlin 文件数 = 0**，`port-frontier.py` 的三行输出变成 `0 files / 0 frontier / 0 blocked`，陈旧副本始终 0。

#### 1. 作废清单（每个文件一行理由，理由都能在 kotlin 树里指到出处）

| 文件 | 为什么可以作废（出处） |
|---|---|
| `App.kt` | 安卓 `Application`：启动序列搬进了 `Main.kt`（数据目录 → 原生库 → DI → 维护 → Coil → 开窗）；它携带的两个常量变成生成的 `BuildInfo.UPDATE_OWNER/UPDATE_REPO`（F 片） |
| `di/AppModule.kt` | 安卓 DI 图：桌面端是 `di/DesktopAppModule.kt`（每个已搬的 VM/仓库都在那里注册过） |
| `MainActivity.kt` | 安卓 Activity 外壳（含 `UpdateDialog` 的宿主与 `onNewIntent`）：窗口 + 外壳在 `Main.kt` / `LyricoDesktopApp.kt`；更新对话框的宿主改到关于页（F 片，缺口已记） |
| `LyricoApp.kt` | 安卓 Compose 根（`NavHost` + 外部文件入口）：`LyricoDesktopApp` + `ui/navigation/LyricoNavHost.kt` |
| `screens/QuickJsTestScreen.kt` | 调试用插件游乐场：C-3 已把设置页里那一行删掉，屏幕没有入口（也就没有被搬的理由） |
| `ui/components/PainterUtils.kt` | 可移植的部分（`RoundedRectanglePainter` / `rememberTintedPainter`）已在 `ui/components/Painters.kt`；`Context` 相关的 `getBitmap`/`saveBitmap`/`getSystemWallpaperColor` 没有桌面等价物，壁纸取色的偏差在 C-3 记过 |
| `platform/player/PlayerIntentFactory.kt`<br>`platform/player/InstalledAppChecker.kt`<br>`data/player/FriendlyPlayer.kt`<br>`data/player/FriendlyPlayerRegistry.kt`<br>`ui/components/player/PlayerPickerBottomSheet.kt` | 一整套「让用户挑一个外部播放器 App」的机制。`PlaybackRepository` 的 KDoc 写明：桌面端 `Desktop.open` 永远用系统默认程序，定向到包名与选择器都没有等价物（Windows 的「打开方式」在资源管理器里，不在应用里） |
| `ui/components/library/ReferenceFloatingBar.kt`<br>`ui/components/library/liquid/*`（6 个） | 液态玻璃悬浮栏。`LibraryNavigationBar` 的 KDoc 记着这处删减：端口保留毛玻璃（`BarBlur`）与纯色，液态那一套的视觉效果没有搬，`LIQUID_GLASS` 这个枚举值仍然存在（旧偏好要能读出来）但不再有对应实现 |
| `data/DeleteRawPropertiesMigration.kt` | 安卓 Room 的 `AutoMigrationSpec`。`LyricoDatabase` 的 KDoc 写着：「Android specifics that are gone: no `Context` … and no `AutoMigration`/`Migration` list」—— 桌面库的 schema 从版本 1 重新开始 |

#### 2. 与 `build.gradle.kts` 的一处耦合（留给 #62）

`lyrico-app/build.gradle.kts` 里那两行 `java.setSrcDirs(emptyList<String>())`（main 与 test）**现在还不能删**：KGP 从 java 源集推导 kotlin 源集，而 `src/test/java` 下还有 15 个从未编译过的安卓期测试。删掉这两行会把它们拉进编译，构建当场失败。等 #62 把那 15 个文件分类处置完（迁移 / 记录 / 删除），这两行才可以一起删。

#### 3. 取证

- **无头**：全量 **144 类 / 1190 项 / 0 失败 / 0 错误 / 3 门控跳过**（与 F 片同一口径；删的是未编译文件，所以数字不变 —— 这本身就是「端口不依赖它们」的证据）。
- **前沿**：`port-frontier.py` → java 主树 **0**、前沿 **0**、被挡 **0**、陈旧副本 **0**。
- **真窗口**：`build/tmp-probe/c38-boot.png`（未入库，见下）—— 删完之后应用照常启动并画出曲库页：`VERDICT: OK`、1334 种颜色、正文像素 15607、运行日志无异常。这条是「删掉的不是端口需要的东西」的运行时证据。
- 说明：这张图**没有**进 `docs/port-evidence/`，因为它的内容与已入库的库页证据重复（同一个启动态）；它的作用是本次变更的运行时回归检查，故留在 `build/tmp-probe/` 并在本节记下它的分析输出。

#### 4. 这一步之后还剩什么

| 项 | 状态 |
|---|---|
| 所有屏幕 / 视图模型 / 仓库 / 工具 | ✅ 已搬完（java 主树 0） |
| #62 死测试 | 15 个 `src/test/java` 文件待分类；分类完可删第 2 节那两行 `setSrcDirs` |
| #42 测试卫生 | 全量偶发挂一次（不同类）的根因之一已修（C-5），旧的一半仍在 |
| #7 打包 | jpackage/MSI + portable zip、ffmpeg 许可随包（`docs/third-party/ffmpeg-lgpl.md` 的 P6 open item） |
| #6 收口 | P6 端到端验收 |

### P5 施工批次（#62 死测试分类 + #42 测试卫生收口，2026-10-11）

#### 1. #62：`src/test/java` 里 15 个从未编译过的测试

C-1 发现这批文件时（见 #37-C-1 第 2 节）定下的规则是：**主语已在本树 → 迁移 + 真跑；否则记录，不删成覆盖空洞**。这一批把 15 个全部处置完：

| 原文件 | 处置 | 结果 |
|---|---|---|
| `EditFieldBlockTest`（74 行） | 迁移 | ✅ 通过 |
| `EditFieldComponentOrderTest`（60） | 迁移 | ✅ 通过 |
| `EditFieldConfigOrderTest`（72） | 迁移 | ✅ 通过 |
| `EditFieldConfigTest`（130） | 迁移 | ✅ 通过（1 处断言参数顺序） |
| `EditFieldMigrationTest`（125） | 迁移 | ✅ 通过 |
| `SongFieldBlankTest`（71） | 迁移 | ✅ 通过 |
| `ArtistPosterFolderSettingTest`（33） | 迁移 | ✅ 通过（2 处断言参数顺序） |
| `AudioTagMutationFactoryPicturesTest`（122） | 迁移 | ✅ 通过（3 处断言参数顺序） |
| `ArtistPosterEmbeddingTest`（198） | 迁移 | ✅ 通过（9 处） |
| `ArtistPosterFileNamingTest`（90） | 迁移 | ✅ 通过（17 处） |
| `ArtistPosterMatcherTest`（103） | 迁移 | ✅ 通过 |
| `CoverRequestCacheKeyTest`（28） | 迁移 | ✅ 通过 |
| `BatchExportTaskConfigTest`（32） | 迁移 + **改写两处断言**（见下） | ✅ 通过 |
| `MatchMetadataProcessorTest`（91） | 迁移 | ✅ 通过（5 处） |
| `ExampleUnitTest`（16） | 删除 | Android 模板的 `2+2` 样板 |

迁移是**机械**的：这 14 个文件**没有 import 任何安卓 API**（只有 `androidx.datastore` 这类跨平台的），所以脚本只做三件事 —— `org.junit.Test/Before/After/Ignore` → `kotlin.test.Test/BeforeTest/AfterTest/Ignore`、`org.junit.Assert.*` → `kotlin.test.*`、`assertArrayEquals` → `assertContentEquals`。测试用例本身逐字保持。

第二处机械差异是**断言参数顺序**：JUnit4 是 `assertTrue("为什么", 条件)`，`kotlin.test` 是 `assertTrue(条件, "为什么")`。写了个括号/引号感知的转换脚本（`build/tmp-probe/swap-assert-args.py`），只在**第一个参数是字符串字面量**时把消息挪到最后，共处理 37 处。

唯一需要**判断**的是 `BatchExportTaskConfigTest`：它断言 `destinationTreeUri` 能往返 —— 那是安卓 SAF 的字段，端口把它换成了 `destinationDirectory` 路径。按 C6e 定下的约定（`CONFIG_JSON` 用 `ignoreUnknownKeys`，好让安卓时代的任务行还能解开），两处断言改成「安卓时代的 JSON **能解开**、`concurrency` 保留、被忽略的字段不会变成目录」，并让测试用与生产同一份宽松 `Json`（初版用严格默认值，当场就被自己的测试教育了一次）。

**结果**：`src/test/java` 与 `src/main/java` 都归零，`lyrico-app/src/androidTest` 里两个安卓仪器化测试也一并处置（`ExampleInstrumentedTest` 删除；`PluginI18nRuntimeTest` 删除 —— 它测的是安卓 `Configuration`/`LocaleList` 那套语言机制，桌面端的等价物已由 `PluginLocalesTest` / `PluginStringsTest` / `QuickJsRuntimeTest` 覆盖）。

**并且**：`lyrico-app/build.gradle.kts` 里那两行为了绕开 java 源集而写的 `java.setSrcDirs(emptyList<String>())` **现在删掉了** —— 它们是 #38 第 2 节记下的耦合，两个 java 树都空了之后就不需要了；删掉后全量重跑仍是 158 类 / 1279 项 / 0 失败。

#### 2. #42：全量套件的偶发失败 —— 查清并修掉

症状（C 簇期间记录）：全量跑**偶尔**挂一项，且每次挂的类都不一样（`ArtistPosterFoldersScreenTest`、`BatchTaskSchedulerTest`、`LibraryScanManagerTest`、`SongSelectionViewModelTest` 都轮到过），单独重跑一律通过；把新测试的 DI 作用域取消（C-5）只治了一半。

这一批做了两个实验、一个结论：

1. **假设「跨用例的进程内状态泄漏」→ 用 `forkEvery = 1`（每个测试类一个新 JVM）验证。** 结果：**不能修**（4 分 05 秒跑完，`BatchTaskSchedulerTest` 照样挂），而且把套件从 1 分 50 秒拖到 4 分多。于是这条假设被排除，`forkEvery` 改动撤掉（不留一个既无效又慢的配置）。
2. **直接读失败信息**：两处都不是「慢」，而是**在更弱的信号上就下了断言**：
   * `BatchTaskSchedulerTest` 等的是任务**状态**变成 `SUCCEEDED`，然后立刻断言日志**条数** —— 而日志是在状态之后写的；同一测试里第二段早就用了「轮询等日志」的写法，第一段漏了。
   * `SongSelectionViewModelTest` 等的是「歌都没了」，然后立刻断言「选择模式已退出」—— 而选择模式的更新在其后。
   两处都改成**等被测的那个条件本身**（这正是本仓在别处写明的约定：不要等 `isLoading == false`，要等那几行出现）。
3. 顺带把 `BatchTaskSchedulerTest` 里 5 处 `withTimeout(10_000)` 提到 20 秒并写明理由：这些等待背后是**真的任务引擎在开真文件、写真行**，10 秒是空闲机器上的预算。

**证据**：连跑 3 次全量，全部 **158 类 / 1279 项 / 0 失败 / 0 错误 / 3 门控跳过**（每次 `--rerun-tasks`，耗时各约 1 分 48 秒）；再加删掉 `setSrcDirs` 之后的一次全量重跑，同样全绿。

#### 3. 口径变化

全量从 C 簇结束时的 144 类 / 1190 项变成 **158 类 / 1279 项**：+14 类 / +89 项，全部来自这一批迁进来的死测试（它们此前从未在桌面端跑过，其中 `ArtistPosterFileNamingTest`、`ArtistPosterEmbeddingTest`、`MatchMetadataProcessorTest` 是 90–198 行的大文件）。**从此「全量」的口径第一次覆盖了那些测试原本想覆盖的逻辑**。
