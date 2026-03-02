package com.example.orgclock.shared

actual class IosCoreFlowFacade actual constructor() {
    actual fun listFilesSummary(): String = "listFiles=unsupported-on-android"

    actual fun verifyDailyReadWriteRoundTrip(): String = "daily=unsupported-on-android"

    actual fun listHeadingsSummaryForFirstFile(): String = "headings=unsupported-on-android"
}
