package com.example.orgclock.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class IosHostFacadeTest {
    @Test
    fun bootstrapMessage_includesPlatformToken() {
        val message = IosHostFacade().bootstrapMessage()
        assertTrue(message.contains("OrgClock shared bootstrap on"))
    }

    @Test
    fun sampleParseSummary_returnsOpenClockCount() {
        val summary = IosHostFacade().sampleParseSummary()
        assertTrue(summary.startsWith("openClockHeadings="))
    }

    @Test
    fun topLevelBridgeFunctions_returnExpectedShape() {
        assertTrue(iosHostBootstrapMessage().contains("OrgClock shared bootstrap on"))
        assertTrue(iosHostSampleParseSummary().startsWith("openClockHeadings="))
    }
}
