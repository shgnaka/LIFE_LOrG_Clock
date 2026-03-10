package com.example.orgclock.shared

actual class IosCoreFlowFacade actual constructor() {
    actual fun listFilesSummary(): String = "listFiles=unsupported-on-jvm"

    actual fun verifyDailyReadWriteRoundTrip(): String = "daily=unsupported-on-jvm"

    actual fun listHeadingsSummaryForFirstFile(): String = "headings=unsupported-on-jvm"
}
