package com.example.orgclock.data

import android.net.Uri

data class RootAccess(
    val rootUri: Uri,
    val displayName: String,
)

interface OrgRepository : ClockRepository {
    suspend fun openRoot(uri: Uri): Result<RootAccess>
}
