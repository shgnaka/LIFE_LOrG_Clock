package com.example.orgclock.data

import android.net.Uri

interface RootAccessGateway {
    suspend fun openRoot(uri: Uri): Result<Unit>
}
