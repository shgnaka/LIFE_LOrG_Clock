package com.example.orgclock.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class IosCoreFlowFacadeTest {
    private val facade = IosCoreFlowFacade()

    @Test
    fun listFilesSummary_hasExpectedPrefix() {
        val summary = facade.listFilesSummary()
        assertTrue(summary.startsWith("listFiles="))
    }

    @Test
    fun verifyDailyReadWriteRoundTrip_reportsStatus() {
        val summary = facade.verifyDailyReadWriteRoundTrip()
        assertTrue(summary.startsWith("daily="))
    }

    @Test
    fun listHeadingsSummaryForFirstFile_reportsStatus() {
        val summary = facade.listHeadingsSummaryForFirstFile()
        assertTrue(summary.startsWith("headings="))
    }
}
