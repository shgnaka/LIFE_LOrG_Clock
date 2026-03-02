package com.example.orgclock.shared

expect class IosCoreFlowFacade() {
    fun listFilesSummary(): String
    fun verifyDailyReadWriteRoundTrip(): String
    fun listHeadingsSummaryForFirstFile(): String
}

fun iosCoreFlowListFilesSummary(): String = IosCoreFlowFacade().listFilesSummary()

fun iosCoreFlowVerifyDailyReadWriteRoundTrip(): String = IosCoreFlowFacade().verifyDailyReadWriteRoundTrip()

fun iosCoreFlowListHeadingsSummaryForFirstFile(): String = IosCoreFlowFacade().listHeadingsSummaryForFirstFile()
