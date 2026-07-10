package com.example.orgclock.desktop

import com.example.orgclock.sync.SyncTransportCredential
import com.example.orgclock.sync.SyncTransportCredentialCodec
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSyncCoreCredentialStoreTest {
    private val tempRoots = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanup() {
        tempRoots.asReversed().forEach { root ->
            if (!root.exists()) return@forEach
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempRoots.clear()
    }

    @Test
    fun encryptedStoreRoundTripsCredentialWithoutPlaintextDuplicate() {
        val root = createTempDirectory("desktop-sync-core-credential-store-test").also(tempRoots::add)
        val storePath = root.resolve(".orgclock").resolve("sync-core-transport-credentials.properties")
        val store = DesktopEncryptedSyncCoreTransportCredentialStore(root)
        val credential = SyncTransportCredential(
            pairingSecret = "secret-${System.nanoTime()}",
            certificateSha256 = "ab".repeat(32),
        )

        store.put(" peer-a ", credential)

        assertEquals(credential, store.get("peer-a"))
        assertTrue(Files.exists(storePath))
        val persisted = Files.readString(storePath)
        assertFalse(persisted.contains(credential.pairingSecret))
        assertFalse(persisted.contains(SyncTransportCredentialCodec.encode(credential)))
        Files.getFileAttributeView(storePath, PosixFileAttributeView::class.java)
            ?.readAttributes()
            ?.permissions()
            ?.let { permissions ->
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    permissions,
                )
            }

        store.delete("peer-a")

        assertNull(store.get("peer-a"))
    }
}
