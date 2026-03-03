package com.example.orgclock.sync

import com.example.orgclock.BuildConfig

interface SyncIntegrationFeatureFlag {
    fun isEnabled(): Boolean
}

class BuildConfigSyncIntegrationFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = BuildConfig.SYNC_INTEGRATION_ENABLED
}

class RuntimeSyncIntegrationFeatureFlag(
    private val buildConfigFlag: SyncIntegrationFeatureFlag,
    private val runtimePrefs: SyncRuntimePrefs,
) : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = buildConfigFlag.isEnabled() && runtimePrefs.isEnabled()
}
