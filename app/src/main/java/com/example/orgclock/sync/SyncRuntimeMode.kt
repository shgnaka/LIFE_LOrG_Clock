package com.example.orgclock.sync

interface SyncRuntimeController {
    suspend fun enableStandardMode()
    suspend fun enableActiveMode()
    suspend fun stop()
    suspend fun flushNow()
}

class DefaultSyncRuntimeController(
    private val syncCoreClient: OrgSyncCoreClient,
) : SyncRuntimeController {
    override suspend fun enableStandardMode() {
        syncCoreClient.start()
    }

    override suspend fun enableActiveMode() {
        syncCoreClient.start()
    }

    override suspend fun stop() {
        syncCoreClient.stop()
    }

    override suspend fun flushNow() {
        syncCoreClient.flushNow()
    }
}
