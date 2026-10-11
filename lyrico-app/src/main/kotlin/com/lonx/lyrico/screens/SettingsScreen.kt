package com.lonx.lyrico.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.about_title
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.app_language
import com.lonx.lyrico.resources.app_log_summary
import com.lonx.lyrico.resources.app_log_title
import com.lonx.lyrico.resources.artist_poster_folder_not_selected
import com.lonx.lyrico.resources.artist_poster_folder_selected
import com.lonx.lyrico.resources.artist_poster_folders
import com.lonx.lyrico.resources.artist_separator
import com.lonx.lyrico.resources.artist_separator_hint
import com.lonx.lyrico.resources.artist_split_settings_summary
import com.lonx.lyrico.resources.artist_split_settings_title
import com.lonx.lyrico.resources.bar_blur
import com.lonx.lyrico.resources.bar_blur_summary
import com.lonx.lyrico.resources.batch_task_list_title
import com.lonx.lyrico.resources.cache_item
import com.lonx.lyrico.resources.cache_size_label
import com.lonx.lyrico.resources.cache_total
import com.lonx.lyrico.resources.calculating_cache
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.clear_cache
import com.lonx.lyrico.resources.clear_cache_confirm
import com.lonx.lyrico.resources.clear_songs
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.conversion_mode
import com.lonx.lyrico.resources.conversion_mode_hint
import com.lonx.lyrico.resources.edit_field_settings_summary
import com.lonx.lyrico.resources.edit_field_settings_title
import com.lonx.lyrico.resources.export_config
import com.lonx.lyrico.resources.export_config_hint
import com.lonx.lyrico.resources.floating_bar_effect
import com.lonx.lyrico.resources.floating_bottom_bar
import com.lonx.lyrico.resources.floating_bottom_bar_summary
import com.lonx.lyrico.resources.folder_found
import com.lonx.lyrico.resources.folder_ignored
import com.lonx.lyrico.resources.folder_manage_hint
import com.lonx.lyrico.resources.folder_manager
import com.lonx.lyrico.resources.ignore_short_audio
import com.lonx.lyrico.resources.import_config
import com.lonx.lyrico.resources.import_config_hint
import com.lonx.lyrico.resources.key_color
import com.lonx.lyrico.resources.lyric_index_summary
import com.lonx.lyrico.resources.lyric_index_title
import com.lonx.lyrico.resources.lyric_mode
import com.lonx.lyrico.resources.monet
import com.lonx.lyrico.resources.non_lyrics_cleanup_rules_summary
import com.lonx.lyrico.resources.non_lyrics_cleanup_rules_title
import com.lonx.lyrico.resources.only_translation_if_available
import com.lonx.lyrico.resources.only_translation_if_available_hint
import com.lonx.lyrico.resources.plugin_manager_title
import com.lonx.lyrico.resources.quickjs_test_description_summary
import com.lonx.lyrico.resources.quickjs_test_title
import com.lonx.lyrico.resources.remove_empty_lines
import com.lonx.lyrico.resources.remove_empty_lines_hint
import com.lonx.lyrico.resources.replay_gain_peak_mode_sample
import com.lonx.lyrico.resources.replay_gain_peak_mode_true
import com.lonx.lyrico.resources.replay_gain_target_custom_hint
import com.lonx.lyrico.resources.replay_gain_target_invalid
import com.lonx.lyrico.resources.replay_gain_target_preset_custom
import com.lonx.lyrico.resources.replay_gain_target_preset_default
import com.lonx.lyrico.resources.replay_gain_target_preset_ebu
import com.lonx.lyrico.resources.replay_gain_target_preset_streaming
import com.lonx.lyrico.resources.roma
import com.lonx.lyrico.resources.roma_hint
import com.lonx.lyrico.resources.search_limit
import com.lonx.lyrico.resources.search_limit_tip
import com.lonx.lyrico.resources.search_source_tab_style
import com.lonx.lyrico.resources.section_appearance
import com.lonx.lyrico.resources.section_backup
import com.lonx.lyrico.resources.section_lyrics
import com.lonx.lyrico.resources.section_other
import com.lonx.lyrico.resources.section_scan
import com.lonx.lyrico.resources.section_search
import com.lonx.lyrico.resources.section_text_processing
import com.lonx.lyrico.resources.settings_replay_gain_peak_mode
import com.lonx.lyrico.resources.settings_replay_gain_target_loudness
import com.lonx.lyrico.resources.settings_replay_gain_target_loudness_summary
import com.lonx.lyrico.resources.settings_title
import com.lonx.lyrico.resources.show_all_search_result_fields
import com.lonx.lyrico.resources.show_all_search_result_fields_summary
import com.lonx.lyrico.resources.theme_mode
import com.lonx.lyrico.resources.translation
import com.lonx.lyrico.resources.translation_hint
import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.data.model.AppLanguage
import com.lonx.lyrico.data.model.ArtistSeparator
import com.lonx.lyrico.data.model.ConversionMode
import com.lonx.lyrico.data.model.FloatingBarEffect
import com.lonx.lyrico.data.model.ReplayGainPeakMode
import com.lonx.lyrico.data.model.SearchSourceTabStyle
import com.lonx.lyrico.data.model.ThemeMode
import com.lonx.lyrico.data.model.lyrics.LyricFormat
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.ui.components.RoundedRectanglePainter
import com.lonx.lyrico.ui.components.base.YesNoBottomSheet
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.theme.KeyColors
import com.lonx.lyrico.viewmodel.FolderManagerViewModel
import com.lonx.lyrico.viewmodel.SettingsEvent
import com.lonx.lyrico.viewmodel.SettingsViewModel
import com.lonx.lyrico.platform.FileOpenPicker
import com.lonx.lyrico.platform.FileSavePicker
import com.lonx.lyrico.platform.rememberFileOpenPicker
import com.lonx.lyrico.platform.rememberFileSavePicker
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.AboutDestination
import com.lonx.lyrico.ui.navigation.AppLogsDestination
import com.lonx.lyrico.ui.navigation.ArtistPosterFoldersDestination
import com.lonx.lyrico.ui.navigation.ArtistSplitSettingsDestination
import com.lonx.lyrico.ui.navigation.BatchTaskListDestination
import com.lonx.lyrico.ui.navigation.EditFieldSettingsDestination
import com.lonx.lyrico.ui.navigation.FolderManagerDestination
import com.lonx.lyrico.ui.navigation.LyricsCleanupRulesDestination
import com.lonx.lyrico.ui.navigation.PluginManagerDestination
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.lonx.lyrico.utils.FileSizeFormatter
import com.lonx.lyrico.utils.formattedStringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun SettingsScreen(
    navigator: Navigator,
    // Nullable for the reason `SongsPage` records: a composable default argument cannot call a
    // composable, so the real pickers are built in the body and tests inject fakes here.
    fileSavePicker: FileSavePicker? = null,
    fileOpenPicker: FileOpenPicker? = null,
) {
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val folderViewModel: FolderManagerViewModel = koinViewModel()
    val folderUiState by folderViewModel.uiState.collectAsStateWithLifecycle()
    val settingsRepository: SettingsRepository = koinInject()
    val artistPosterFolder by settingsRepository.artistPosterFolder.collectAsStateWithLifecycle(initialValue = null)

    val lyricFormat = settingsUiState.lyricFormat
    val artistSeparator = settingsUiState.separator
    val romaEnabled = settingsUiState.romaEnabled
    val themeMode = settingsUiState.themeMode
    val monetEnable = settingsUiState.monetEnable
    val floatingBottomBarEnabled = settingsUiState.floatingBottomBarEnabled
    val barBlurEnabled = settingsUiState.barBlurEnabled
    val floatingBarEffect = settingsUiState.floatingBarEffect
    val currentKeyColor = settingsUiState.keyColor
    val translationEnabled = settingsUiState.translationEnabled
    val onlyTranslationIfAvailable = settingsUiState.onlyTranslationIfAvailable
    val removeEmptyLines = settingsUiState.removeEmptyLines
    val ignoreShortAudio = settingsUiState.ignoreShortAudio
    val folders = folderUiState.folders
    val totalFolders = folders.filter { it.addedBySaf }.size
    val conversionMode = settingsUiState.conversionMode
    val searchSourceTabStyle = settingsUiState.searchSourceTabStyle
    val showAllSearchResultFields = settingsUiState.showAllSearchResultFields

    val ignoredFolders = folders.count { it.isIgnored && it.addedBySaf }
    val searchPageSize = settingsUiState.searchPageSize
    val scope = rememberCoroutineScope()

    val minSearchSize = 1
    val maxSearchSize = 20
    val tempSearchPageSize = remember(searchPageSize) {
        mutableIntStateOf(searchPageSize)
    }
    val showClearCacheDialog = remember { mutableStateOf(false) }
    val showRgTargetDialog = remember { mutableStateOf(false) }
    val monetVisibilityState = remember(settingsUiState.isInitialized) {
        MutableTransitionState(monetEnable)
    }

    LaunchedEffect(monetEnable) {
        monetVisibilityState.targetState = monetEnable
    }

    val themeModeItems = ThemeMode.entries.map { stringResource(it.labelRes) }
    val selectedThemeModeIndex =
        ThemeMode.entries.indexOf(themeMode).coerceAtLeast(0)

    val lyricFormatItems = LyricFormat.entries.map { stringResource(it.labelRes) }
    val selectedLyricFormatIndex = LyricFormat.entries.indexOf(lyricFormat).coerceAtLeast(0)

    val conversionModeItems = ConversionMode.entries.map { stringResource(it.labelRes) }
    val selectedConversionModeIndex =
        ConversionMode.entries.indexOf(conversionMode).coerceAtLeast(0)
    val searchSourceTabStyleItems = SearchSourceTabStyle.entries.map { stringResource(it.labelRes) }
    val replayGainPeakModeItems = listOf(
        stringResource(Res.string.replay_gain_peak_mode_sample),
        stringResource(Res.string.replay_gain_peak_mode_true)
    )
    val selectedReplayGainPeakModeIndex = ReplayGainPeakMode.entries
        .indexOf(settingsUiState.replayGainPeakMode)
        .coerceAtLeast(0)
    val selectedSearchSourceTabStyleIndex =
        SearchSourceTabStyle.entries.indexOf(searchSourceTabStyle).coerceAtLeast(0)

    val floatingBarEffectItems = FloatingBarEffect.entries.map { stringResource(it.labelRes) }

    val artistSeparators = remember {
        listOf(
            ArtistSeparator.ENUMERATION_COMMA,
            ArtistSeparator.SLASH,
            ArtistSeparator.COMMA,
            ArtistSeparator.SEMICOLON
        )
    }
    val artistSeparatorItems = artistSeparators.map { it.toText() }
    val selectedArtistSeparatorIndex = artistSeparators.indexOf(artistSeparator).coerceAtLeast(0)

    LaunchedEffect(Unit) {
        settingsViewModel.refreshCache()
    }

    // 桌面端没有回调式的文件选择器：两个入口都是挂起调用，选完直接投给 ViewModel（与 `AppLogScreen`
    // 导出日志、`ArtistPosterFoldersScreen` 选文件夹同一条路）。
    val savePicker = fileSavePicker
        ?: rememberFileSavePicker(title = stringResource(Res.string.export_config))
    val openPicker = fileOpenPicker
        ?: rememberFileOpenPicker(title = stringResource(Res.string.import_config))
    fun exportSettings() {
        scope.launch {
            val defaultName = "lyrico_settings_backup_${System.currentTimeMillis()}.json"
            val target = savePicker.pick(defaultName) ?: return@launch
            settingsViewModel.exportSettings(target)
        }
    }

    fun importSettings() {
        scope.launch {
            val source = openPicker.pick() ?: return@launch
            settingsViewModel.importSettings(source)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        settingsViewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowToast -> {
                    // Android 的 Toast 换成 Miuix 的 Snackbar；文案在展示这一刻按当前语言解析。
                    event.message.resolve()?.let { snackbarHostState.showSnackbar(it) }
                }
            }
        }
    }

    val calculatingText = stringResource(Res.string.calculating_cache)
    val confirmText = stringResource(Res.string.clear_cache_confirm)

    // 这里原本有一个 `remember`：现在没有了，因为拼这段文案要调可组合的字符串解析（`stringResource` /
    // `formattedStringResource`），而 `remember` 的 lambda 不是可组合的。几行字符串的重算远比为了缓存而
    // 把语言/大小的依赖手工管网要便宜。
    val cacheContent = if (settingsUiState.categorizedCacheSize.isEmpty()) {
        calculatingText
    } else {
        val details = settingsUiState.categorizedCacheSize
            .map { (category, size) ->
                formattedStringResource(
                    Res.string.cache_item,
                    stringResource(category.labelRes),
                    FileSizeFormatter.format(size)
                )
            }
            .joinToString(separator = "\n")

        buildString {
            append(confirmText)
            append("\n\n")
            append(details)
            append("\n\n")
            append(
                formattedStringResource(
                    Res.string.cache_total,
                    FileSizeFormatter.format(settingsUiState.totalCacheSize)
                )
            )
        }
    }
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop(enabled = barBlurEnabled)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                SmallTopAppBar(
                    title = stringResource(Res.string.settings_title),
                    color = Color.Transparent,
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        IconButton(
                            onClick = { navigator.popBackStack() }
                        ) {
                            Icon(
                                MiuixIcons.Back,
                                contentDescription = stringResource(Res.string.action_back)
                            )
                        }
                    },
                    scrollBehavior = topAppBarScrollBehavior,
                )
            }
        }
    ) { paddingValues ->
        if (!settingsUiState.isInitialized) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(size = 32.dp)
            }
            return@Scaffold
        }

        WindowDialog(
            title = stringResource(Res.string.clear_cache),
            show = showClearCacheDialog.value,
            onDismissRequest = { showClearCacheDialog.value = false }
        ) {
            Column {
                Text(
                    text = cacheContent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    color = MiuixTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(Res.string.cancel),
                        onClick = {
                            showClearCacheDialog.value = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(Res.string.confirm),
                        onClick = {
                            settingsViewModel.clearCache()
                            showClearCacheDialog.value = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
        ReplayGainTargetLoudnessSheet(
            show = showRgTargetDialog.value,
            currentLoudness = settingsUiState.replayGainTargetLoudness,
            onDismiss = { showRgTargetDialog.value = false },
            onSave = { settingsViewModel.setReplayGainTargetLoudness(it) }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blurSource(topBarBackdrop),
        ) {
        LazyColumn(
            modifier = Modifier
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .fillMaxHeight(),
            contentPadding = scaffoldContentPadding(
                paddingValues = paddingValues,
                bottomExtra = 12.dp
            ),
            overscrollEffect = null,
        ) {
            item(key = "appearance"){
                SmallTitle(text = stringResource(Res.string.section_appearance))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    // 语言是设置里的一条偏好（`app_language`），不是 AppCompat 的每应用语言：外壳
                    // （`LyricoDesktopApp`）订阅同一条流，选中之后整个界面与零食条立刻换语言，不需要
                    // 安卓那种「换语言 = 重建 activity」。
                    WindowDropdownPreference(
                        title = stringResource(Res.string.app_language),
                        items = AppLanguage.entries.map { stringResource(it.labelRes) },
                        selectedIndex = AppLanguage.entries
                            .indexOf(settingsUiState.appLanguage)
                            .coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            settingsViewModel.setAppLanguage(AppLanguage.entries[index])
                        }
                    )
                    WindowDropdownPreference(
                        title = stringResource(Res.string.theme_mode),
                        items = themeModeItems,
                        selectedIndex = selectedThemeModeIndex,
                        onSelectedIndexChange = { index ->
                            settingsViewModel.setThemeMode(ThemeMode.entries[index])
                        }
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.bar_blur),
                        summary = stringResource(Res.string.bar_blur_summary),
                        checked = barBlurEnabled,
                        onCheckedChange = settingsViewModel::setBarBlurEnabled,
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.floating_bottom_bar),
                        summary = stringResource(Res.string.floating_bottom_bar_summary),
                        checked = floatingBottomBarEnabled,
                        onCheckedChange = { settingsViewModel.setFloatingBottomBarEnabled(it) }
                    )
                    AnimatedVisibility(visible = floatingBottomBarEnabled) {
                        WindowDropdownPreference(
                            title = stringResource(Res.string.floating_bar_effect),
                            items = floatingBarEffectItems,
                            selectedIndex = floatingBarEffect.ordinal,
                            onSelectedIndexChange = { index ->
                                settingsViewModel.setFloatingBarEffect(
                                    FloatingBarEffect.entries[index]
                                )
                            },
                        )
                    }
                    SwitchPreference(
                        title = stringResource(Res.string.monet),
                        checked = monetEnable,
                        onCheckedChange = {
                            settingsViewModel.setMonetEnable(!monetEnable)
                        }
                    )
                    AnimatedVisibility(visibleState = monetVisibilityState) {
                        val currentSelectedIndex = KeyColors.indexOf(currentKeyColor).let {
                            if (it == -1) 0 else it
                        }
                        val options = KeyColors.map { keyColor ->
                            DropdownItem(
                                title = stringResource(keyColor.nameResId),
                                icon = {
                                    val tintColor =
                                        // 桌面端没有壁纸取色 / Material You（`getSystemWallpaperColor` 靠 Android S 的
                                      // dynamicColorScheme），所以「默认」这一项回落到当前主题的 primary ——
                                      // 本批唯一的行为偏差，记在 PLAN 的缺口里。
                                      keyColor.color ?: MiuixTheme.colorScheme.primary

                                    Icon(
                                        painter = RoundedRectanglePainter(),
                                        contentDescription = stringResource(keyColor.nameResId),
                                        modifier = Modifier.padding(end = 12.dp),
                                        tint = tintColor
                                    )
                                }
                            )
                        }

                        WindowSpinnerPreference(
                            items = options,
                            selectedIndex = currentSelectedIndex,
                            title = stringResource(Res.string.key_color),
                            onSelectedIndexChange = {
                                val selectedKeyColor = KeyColors[it]
                                settingsViewModel.setKeyColor(selectedKeyColor)
                            }
                        )
                    }
                }
            }

            item(key = "scan"){
                SmallTitle(text = stringResource(Res.string.section_scan))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    val folderSummary = if (totalFolders > 0) {
                        buildString {
                            append(formattedStringResource(Res.string.folder_found, totalFolders))
                            if (ignoredFolders > 0) {
                                append(formattedStringResource(Res.string.folder_ignored, ignoredFolders))
                            }
                        }
                    } else {
                        stringResource(Res.string.folder_manage_hint)
                    }
                    ArrowPreference(
                        title = stringResource(Res.string.folder_manager),
                        summary = folderSummary,
                        onClick = { navigator.navigate(FolderManagerDestination) }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.artist_poster_folders),
                        summary = stringResource(
                            if (artistPosterFolder == null) {
                                Res.string.artist_poster_folder_not_selected
                            } else {
                                Res.string.artist_poster_folder_selected
                            }
                        ),
                        onClick = { navigator.navigate(ArtistPosterFoldersDestination) }
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.ignore_short_audio),
                        checked = ignoreShortAudio,
                        onCheckedChange = { settingsViewModel.setIgnoreShortAudio(it) }
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.lyric_index_title),
                        summary = stringResource(Res.string.lyric_index_summary),
                        checked = settingsUiState.lyricIndexEnabled,
                        onCheckedChange = { settingsViewModel.setLyricIndexEnabled(it) }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.settings_replay_gain_target_loudness),
                        summary = stringResource(Res.string.settings_replay_gain_target_loudness_summary),
                        endActions = {
                            Text(
                                text = formatLoudnessValue(settingsUiState.replayGainTargetLoudness),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = { showRgTargetDialog.value = true },
                        holdDownState = showRgTargetDialog.value
                    )
                    WindowDropdownPreference(
                        title = stringResource(Res.string.settings_replay_gain_peak_mode),
                        items = replayGainPeakModeItems,
                        selectedIndex = selectedReplayGainPeakModeIndex,
                        onSelectedIndexChange = { index ->
                            settingsViewModel.setReplayGainPeakMode(
                                ReplayGainPeakMode.entries[index]
                            )
                        }
                    )
                }
            }

            item(key = "search"){
                SmallTitle(text = stringResource(Res.string.section_search))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(Res.string.plugin_manager_title),
                        onClick = { navigator.navigate(PluginManagerDestination()) }
                    )
                    WindowDropdownPreference(
                        title = stringResource(Res.string.search_source_tab_style),
                        items = searchSourceTabStyleItems,
                        selectedIndex = selectedSearchSourceTabStyleIndex,
                        onSelectedIndexChange = { index ->
                            settingsViewModel.setSearchSourceTabStyle(
                                SearchSourceTabStyle.entries[index]
                            )
                        }
                    )
                    SliderPreference(
                        title = stringResource(Res.string.search_limit),
                        showKeyPoints = true,
                        valueText = tempSearchPageSize.intValue.toString(),
                        summary = stringResource(Res.string.search_limit_tip),
                        valueRange = minSearchSize.toFloat()..maxSearchSize.toFloat(),
                        steps = maxSearchSize - minSearchSize - 1,
                        value = tempSearchPageSize.intValue.toFloat(),
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        onValueChange = {
                            tempSearchPageSize.intValue = it.roundToInt()
                        },
                        onValueChangeFinished = {
                            settingsViewModel.setSearchPageSize(tempSearchPageSize.intValue)
                        }
                    )
                    WindowDropdownPreference(
                        title = stringResource(Res.string.artist_separator),
                        summary = stringResource(Res.string.artist_separator_hint),
                        items = artistSeparatorItems,
                        selectedIndex = selectedArtistSeparatorIndex,
                        onSelectedIndexChange = { index ->
                            settingsViewModel.setSeparator(artistSeparators[index])
                        }
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.show_all_search_result_fields),
                        summary = stringResource(Res.string.show_all_search_result_fields_summary),
                        checked = showAllSearchResultFields,
                        onCheckedChange = settingsViewModel::setShowAllSearchResultFields
                    )
                }
            }

            item(key = "lyrics"){
                SmallTitle(text = stringResource(Res.string.section_lyrics))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WindowDropdownPreference(
                        title = stringResource(Res.string.lyric_mode),
                        items = lyricFormatItems,
                        selectedIndex = selectedLyricFormatIndex,
                        onSelectedIndexChange = { index ->
                            settingsViewModel.setLyricFormat(LyricFormat.entries[index])
                        }
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.roma),
                        summary = stringResource(Res.string.roma_hint),
                        checked = romaEnabled,
                        onCheckedChange = { settingsViewModel.setRomaEnabled(it) }
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.translation),
                        summary = stringResource(Res.string.translation_hint),
                        checked = translationEnabled,
                        onCheckedChange = { settingsViewModel.setTranslationEnabled(it) }
                    )
                    AnimatedVisibility(visible = translationEnabled) {
                        SwitchPreference(
                            title = stringResource(Res.string.only_translation_if_available),
                            summary = stringResource(Res.string.only_translation_if_available_hint),
                            enabled = translationEnabled,
                            checked = onlyTranslationIfAvailable,
                            onCheckedChange = { settingsViewModel.setOnlyTranslationIfAvailable(it) }
                        )
                    }
                }
            }

            item(key = "text_processing"){
                SmallTitle(text = stringResource(Res.string.section_text_processing))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WindowDropdownPreference(
                        title = stringResource(Res.string.conversion_mode),
                        summary = stringResource(Res.string.conversion_mode_hint),
                        items = conversionModeItems,
                        selectedIndex = selectedConversionModeIndex,
                        onSelectedIndexChange = {
                            settingsViewModel.setConversionMode(ConversionMode.entries[it])
                        },
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.remove_empty_lines),
                        summary = stringResource(Res.string.remove_empty_lines_hint),
                        checked = removeEmptyLines,
                        onCheckedChange = { settingsViewModel.setRemoveEmptyLines(it) }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.non_lyrics_cleanup_rules_title),
                        summary = stringResource(Res.string.non_lyrics_cleanup_rules_summary),
                        onClick = { navigator.navigate(LyricsCleanupRulesDestination()) }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.artist_split_settings_title),
                        summary = stringResource(Res.string.artist_split_settings_summary),
                        onClick = { navigator.navigate(ArtistSplitSettingsDestination()) }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.edit_field_settings_title),
                        summary = stringResource(Res.string.edit_field_settings_summary),
                        onClick = { navigator.navigate(EditFieldSettingsDestination) }
                    )
                }
            }

            item(key = "backup"){
                SmallTitle(text = stringResource(Res.string.section_backup))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(Res.string.export_config),
                        summary = stringResource(Res.string.export_config_hint),
                        onClick = {
                            val currentTime = System.currentTimeMillis()
                            exportSettings()
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.import_config),
                        summary = stringResource(Res.string.import_config_hint),
                        onClick = {
                            importSettings()
                        }
                    )
                }
            }

            item(key = "other"){
                SmallTitle(text = stringResource(Res.string.section_other))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(Res.string.batch_task_list_title),
                        onClick = { navigator.navigate(BatchTaskListDestination()) }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.app_log_title),
                        summary = stringResource(Res.string.app_log_summary),
                        onClick = { navigator.navigate(AppLogsDestination()) }
                    )
                    // 安卓用 `android.text.format.Formatter`；桌面端是同一个 AOSP 算法的移植
                    // （`FileSizeFormatter`），单位阈值与大小写由 `FileSizeFormatterTest` 钉住。
                    val cacheSummary = formattedStringResource(
                        Res.string.cache_size_label,
                        FileSizeFormatter.format(settingsUiState.totalCacheSize)
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.clear_cache),
                        summary = cacheSummary,
                        onClick = { showClearCacheDialog.value = true },
                        holdDownState = showClearCacheDialog.value
                    )
                    if (BuildInfo.DEBUG) {
                        ArrowPreference(
                            title = stringResource(Res.string.clear_songs),
                            onClick = {
                                scope.launch {
                                    // Toast -> Snackbar，和这一页的其它反馈走同一个 host。
                                    val message = if (settingsViewModel.clearSongs()) {
                                        "已清空数据库"
                                    } else {
                                        "清空数据库失败"
                                    }
                                    snackbarHostState.showSnackbar(message)
                                }
                            }
                        )
                    }
                    ArrowPreference(
                        title = stringResource(Res.string.about_title),
                        onClick = { navigator.navigate(AboutDestination()) }
                    )
                }
            }
        }
        }
    }
}

private fun formatLoudnessNumber(value: Double): String {
    return if (value % 1.0 == 0.0) {
        "%.0f".format(java.util.Locale.US, value)
    } else {
        "%.2f".format(java.util.Locale.US, value)
    }
}

private fun formatLoudnessValue(value: Double): String = "${formatLoudnessNumber(value)} LUFS"

@Composable
private fun ReplayGainTargetLoudnessSheet(
    show: Boolean,
    currentLoudness: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    val presets = remember { listOf(-23.0, -18.0, -14.0) }
    val presetLabels = listOf(
        Res.string.replay_gain_target_preset_ebu,
        Res.string.replay_gain_target_preset_default,
        Res.string.replay_gain_target_preset_streaming
    )
    var selectedPreset by remember(show, currentLoudness) {
        mutableDoubleStateOf(if (currentLoudness in presets) currentLoudness else presets.first())
    }
    var customSelected by remember(show, currentLoudness) {
        mutableStateOf(currentLoudness !in presets)
    }
    var customText by remember(show, currentLoudness) {
        mutableStateOf(if (currentLoudness in presets) "" else formatLoudnessNumber(currentLoudness))
    }
    var invalid by remember(show) { mutableStateOf(false) }

    YesNoBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        onConfirm = {
            if (customSelected) {
                val customValue = customText.trim().toDoubleOrNull()
                if (customValue != null && customValue in -60.0..0.0) {
                    onSave(customValue)
                    onDismiss()
                } else {
                    invalid = true
                }
            } else {
                onSave(selectedPreset)
                onDismiss()
            }
        },
        content = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())){
                Card(
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.secondaryContainer
                    )
                ) {
                    presets.forEachIndexed { index, preset ->
                        RadioButtonPreference(
                            title = stringResource(presetLabels[index]),
                            selected = !customSelected && selectedPreset == preset,
                            onClick = {
                                selectedPreset = preset
                                customSelected = false
                                invalid = false
                            }
                        )
                    }
                    RadioButtonPreference(
                        title = stringResource(Res.string.replay_gain_target_preset_custom),
                        selected = customSelected,
                        onClick = {
                            customSelected = true
                            invalid = false
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                AnimatedVisibility(visible = customSelected) {
                    Card(
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        TextField(
                            value = customText,
                            onValueChange = {
                                customText = it
                                invalid = false
                            },
                            label = stringResource(Res.string.replay_gain_target_custom_hint),
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (invalid) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(Res.string.replay_gain_target_invalid),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    )
}
