package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.data.model.entity.AppLogEntity
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.log.LogRetentionOption
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.export_failed
import com.lonx.lyrico.resources.export_success
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed class AppLogEvent {
    data class ShowMessage(val message: UiMessage) : AppLogEvent()
}

data class AppLogUiState(
    val logRetentionOption: LogRetentionOption = LogRetentionOption.THIRTY_DAYS,
)

/**
 * The app-log screen's state holder.
 *
 * Desktop divergence (deliberate, one place only): [exportLogs] takes the destination `File` the user
 * picked rather than an Android `DocumentFile` `Uri`, and reads system facts from `BuildInfo` +
 * `System.getProperty` instead of `Build.*` / the `com.hjq.device.compat` Xiaomi market-name lookup.
 * The save-file picker itself is a UI concern, so the view model stays testable by writing a real file.
 */
class AppLogViewModel(
    private val appLogRepository: AppLogRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val logRetentionOption: StateFlow<LogRetentionOption> = settingsRepository.logRetentionOption
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LogRetentionOption.THIRTY_DAYS
        )
    val logs: StateFlow<List<AppLogEntity>> = appLogRepository.observeLatest().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    private val _events = MutableSharedFlow<AppLogEvent>()
    val events = _events.asSharedFlow()

    fun deleteLogs(ids: List<Long>) {
        viewModelScope.launch {
            appLogRepository.deleteByIds(ids)
        }
    }

    fun exportLogs(target: File, ids: List<Long>? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logsText = if (ids == null) {
                    appLogRepository.exportText()
                } else {
                    appLogRepository.exportText(ids)
                }
                val text = buildString {
                    appendLine(buildDiagnosticInfo())
                    appendLine()
                    append(logsText)
                }
                target.writeText(text, Charsets.UTF_8)
                _events.emit(AppLogEvent.ShowMessage(UiMessage.Localized(Res.string.export_success)))
            } catch (e: CancellationException) {
                // Android funnelled this into the generic handler below, so cancelling the export also wrote
                // a "failed to export" log row and popped an error toast. Rethrow instead: a cancelled job
                // did not fail.
                throw e
            } catch (e: Exception) {
                appLogRepository.logException(
                    type = AppLogType.APP,
                    tag = TAG,
                    message = "Failed to export logs",
                    throwable = e
                )
                _events.emit(
                    AppLogEvent.ShowMessage(
                        UiMessage.Localized(Res.string.export_failed, e.message ?: "Unknown error")
                    )
                )
            }
        }
    }

    private fun buildDiagnosticInfo(): String = buildString {
        appendLine("Lyrico diagnostic info")
        appendLine("App version: ${BuildInfo.VERSION_NAME} (${BuildInfo.VERSION_CODE})")
        appendLine("Build: ${BuildInfo.BUILD_TYPE} ${BuildInfo.COMMIT}")
        appendLine("System version: ${buildSystemVersion()}")
        appendLine("Architecture: ${System.getProperty("os.arch").orEmpty().ifBlank { "unknown" }}")
    }

    private fun buildSystemVersion(): String {
        val osName = System.getProperty("os.name").orEmpty().trim()
        val osVersion = System.getProperty("os.version").orEmpty().trim()
        return listOf(osName, osVersion)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Unknown" }
    }

    fun setLogRetentionOption(option: LogRetentionOption) {
        viewModelScope.launch {
            settingsRepository.saveLogRetentionOption(option)
            appLogRepository.applyRetentionPolicy()
        }
    }

    companion object {
        private const val TAG = "AppLogViewModel"
    }
}
