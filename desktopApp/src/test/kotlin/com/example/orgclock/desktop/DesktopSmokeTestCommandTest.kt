package com.example.orgclock.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopSmokeTestCommandTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        roots.forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun smokeCommand_createsPassingReportAndRuntimeArtifacts() {
        val root = createTempDirectory("desktop-smoke-command").also(roots::add)
        val report = root.resolve("report.json")

        DesktopSmokeTestCommand.run(
            arrayOf(
                "--smoke-test",
                "--root",
                root.resolve("org root 日本語").toString(),
                "--report",
                report.toString(),
            ),
        )

        val orgRoot = root.resolve("org root 日本語")
        assertTrue(report.exists())
        assertTrue(report.readText().contains("\"passed\": true"))
        assertTrue(orgRoot.resolve(".orgclock/clock-events.db").exists())
        assertTrue(orgRoot.resolve("2026-01-02.org").readText().contains("CLOCK:"))
    }
}
