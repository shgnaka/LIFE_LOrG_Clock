package com.example.orgclock.ui.store

import com.example.orgclock.presentation.RootReference
import com.example.orgclock.ui.state.OrgClockUiState
import kotlinx.coroutines.flow.MutableStateFlow

class OrgClockRecoveryCoordinator(
    private val uiState: MutableStateFlow<OrgClockUiState>,
    private val refreshFilesAndRoute: suspend () -> Unit,
    private val refreshAutoGenerationRuntimeState: suspend (RootReference) -> Unit,
) {
    suspend fun reloadFromDisk() {
        refreshFilesAndRoute()
        uiState.value.rootReference?.let { refreshAutoGenerationRuntimeState(it) }
    }
}
