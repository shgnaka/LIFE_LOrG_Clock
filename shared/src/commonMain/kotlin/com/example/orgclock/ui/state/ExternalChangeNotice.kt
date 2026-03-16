package com.example.orgclock.ui.state

data class ExternalChangeNotice(
    val revision: Long,
    val changedFileIds: Set<String> = emptySet(),
)
