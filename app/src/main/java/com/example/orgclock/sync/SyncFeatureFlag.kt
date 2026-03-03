package com.example.orgclock.sync

import com.example.orgclock.BuildConfig

interface SyncIntegrationFeatureFlag {
    fun isEnabled(): Boolean
}

class BuildConfigSyncIntegrationFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = BuildConfig.SYNC_INTEGRATION_ENABLED
}
