package com.example.orgclock.shared

expect fun platformName(): String

fun sharedBootstrapMessage(): String = "OrgClock shared bootstrap on ${platformName()}"
