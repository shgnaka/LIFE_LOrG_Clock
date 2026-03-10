package com.example.orgclock.data

import com.example.orgclock.presentation.RootReference

interface RootAccessGateway {
    suspend fun openRoot(rootReference: RootReference): Result<Unit>
}
