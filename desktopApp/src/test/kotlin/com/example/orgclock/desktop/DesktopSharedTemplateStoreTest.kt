package com.example.orgclock.desktop

import com.example.orgclock.template.SharedTemplateRevision
import com.example.orgclock.template.TemplatePushResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DesktopSharedTemplateStoreTest {
    private val roots = mutableListOf<Path>()
    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun cleanup() {
        roots.forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        nodes.forEach { runCatching { it.removeNode() } }
    }

    @Test
    fun externalEdit_becomesChildOfPreviouslyObservedRevision() = runTest {
        val root = createTempDirectory("template-store").also(roots::add)
        val node = testNode()
        root.resolve(".orgclock-template.org").writeText("* First\n")
        val store = DesktopSharedTemplateStore(root, "desktop-a", node)
        val first = store.readRevision().getOrThrow()!!

        root.resolve(".orgclock-template.org").writeText("* Second\n")
        val second = store.readRevision().getOrThrow()!!

        assertEquals(first.revisionId, second.parentRevisionId)
    }

    @Test
    fun writeRevision_rejectsStaleExpectedRevision() = runTest {
        val root = createTempDirectory("template-store").also(roots::add)
        val node = testNode()
        root.resolve(".orgclock-template.org").writeText("* Local\n")
        val store = DesktopSharedTemplateStore(root, "desktop-a", node)
        val current = store.readRevision().getOrThrow()!!
        val incoming = SharedTemplateRevision(
            revisionId = "remote",
            parentRevisionId = null,
            content = "* Remote",
            contentHash = sha256("* Remote"),
            updatedAt = Instant.parse("2026-06-12T00:00:00Z"),
            updatedByDeviceId = "android-a",
        )

        val result = store.writeRevision(incoming, "wrong").getOrThrow()

        assertIs<TemplatePushResult.Conflict>(result)
        assertEquals("* Local\n", root.resolve(".orgclock-template.org").readText())
        assertEquals(current.revisionId, store.readRevision().getOrThrow()!!.revisionId)
    }

    private fun testNode(): Preferences =
        Preferences.userRoot().node("com/example/orgclock/desktop/template-test/${System.nanoTime()}")
            .also(nodes::add)

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
