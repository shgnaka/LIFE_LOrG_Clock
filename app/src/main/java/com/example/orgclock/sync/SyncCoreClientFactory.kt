package com.example.orgclock.sync

import android.content.Context
import com.example.orgclock.data.ClockRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.time.ClockEnvironment

interface SyncCoreClientFactory {
    fun create(
        appContext: Context,
        repository: ClockRepository,
        clockService: ClockService,
        clockEnvironment: ClockEnvironment,
    ): OrgSyncCoreClient
}

class NoOpSyncCoreClientFactory : SyncCoreClientFactory {
    override fun create(
        appContext: Context,
        repository: ClockRepository,
        clockService: ClockService,
        clockEnvironment: ClockEnvironment,
    ): OrgSyncCoreClient {
        return NoOpOrgSyncCoreClient()
    }
}
