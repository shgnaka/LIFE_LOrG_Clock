package com.example.orgclock.shared

import com.example.orgclock.parser.OrgParser
import kotlinx.datetime.TimeZone

/**
 * Minimal surface for iOS host integration in M3.
 * M4 will replace sample input with real repository-backed flow.
 */
class IosHostFacade {
    private val parser = OrgParser()

    fun bootstrapMessage(): String = sharedBootstrapMessage()

    fun sampleParseSummary(): String {
        val sampleLines = listOf(
            "* Work",
            "** Project A",
            "CLOCK: [2026-03-01 Sun 09:00:00]",
            "** Project B",
        )
        val openClockHeadings = parser.parseHeadingsWithOpenClock(sampleLines, TimeZone.UTC)
            .count { it.node.level == 2 && it.openClock != null }
        return "openClockHeadings=$openClockHeadings"
    }
}

fun iosHostBootstrapMessage(): String = IosHostFacade().bootstrapMessage()

fun iosHostSampleParseSummary(): String = IosHostFacade().sampleParseSummary()
