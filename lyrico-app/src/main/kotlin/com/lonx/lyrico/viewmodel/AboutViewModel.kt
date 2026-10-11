package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.data.dto.ContributorInfo
import com.lonx.lyrico.data.repository.GhContributorRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.platform.DesktopOpener
import com.lonx.lyrico.utils.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


sealed interface UiError {
    data object LoadFailed : UiError
    data class Message(val text: String) : UiError
}
class AboutViewModel(
    private val settingsRepository: SettingsRepository,
    private val updateManager: UpdateManager,
    private val contributorRepository: GhContributorRepository,
    private val desktopOpener: DesktopOpener,
) : ViewModel() {

    val checkUpdateEnabled: StateFlow<Boolean> =
        settingsRepository.checkUpdateEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val updateEffect = updateManager.effect

    /**
     * The release the update check found, or `null`.
     *
     * Android's shell hosted the update dialog app-wide; on desktop the about page is where the check is
     * triggered and where the result is shown, so the page needs the release itself, not just a message.
     */
    val releaseInfo = updateManager.state
        .map { it.releaseInfo }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _contributors = MutableStateFlow<List<ContributorInfo>>(emptyList())
    val contributors: StateFlow<List<ContributorInfo>> = _contributors

    private val _loadingContributors = MutableStateFlow(false)
    val loadingContributors: StateFlow<Boolean> = _loadingContributors

    private val _contributorsError = MutableStateFlow<UiError?>(null)
    val contributorsError: StateFlow<UiError?> = _contributorsError
    init {
        loadContributors()
    }
    fun setCheckUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveCheckUpdateEnabled(enabled)
        }
    }

    /**
     * Hands a link to the desktop's default browser.
     *
     * Android started an `ACTION_VIEW` intent with a `Context`; the JVM has no such thing, so this goes
     * through [DesktopOpener] (the same seam the play/reveal paths use) and takes no `Context` at all --
     * which is also what makes the call sites in `AboutScreen` one argument shorter.
     */
    fun openBrowser(url: String) {
        desktopOpener.browse(url)
    }

    /** Closes the update dialog and clears the found release. */
    fun dismissUpdate() {
        updateManager.resetUpdateState()
    }

    fun checkUpdate() {
        viewModelScope.launch {
            updateManager.checkForUpdate()
        }
    }
    fun loadContributors() {
        viewModelScope.launch {
            _loadingContributors.value = true
            _contributorsError.value = null

            val result = contributorRepository.getContributors(
                owner = BuildInfo.UPDATE_OWNER,
                repo = BuildInfo.UPDATE_REPO
            )

            result
                .onSuccess {
                    _contributors.value = it
                }
                .onFailure {
                    _contributorsError.value =
                        it.message?.let { msg -> UiError.Message(msg) }
                            ?: UiError.LoadFailed
                }

            _loadingContributors.value = false
        }
    }
}