package com.example.orgclock.shared

fun iosCoreFlowListFilesSummary(): String = IosCoreFlowFacade().listFilesSummary()

fun iosCoreFlowVerifyDailyReadWriteRoundTrip(): String = IosCoreFlowFacade().verifyDailyReadWriteRoundTrip()

fun iosCoreFlowListHeadingsSummaryForFirstFile(): String = IosCoreFlowFacade().listHeadingsSummaryForFirstFile()
