package com.example.orgclock.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSyncCorePlatformAdapterInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val databaseNames = mutableListOf<String>()
    private val keyAliases = mutableListOf<String>()

    @After
    fun tearDown() {
        databaseNames.forEach { context.deleteDatabase(it) }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyAliases.forEach { alias ->
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }
    }

    @Test
    fun transportCredentialStoreSealsPlaintextCredentialInAndroidKeystore() {
        val prefsName = "sync-core-credential-test-${System.nanoTime()}"
        val keyAlias = "orgclock.sync_core.test.credential.${System.nanoTime()}"
        keyAliases += keyAlias
        val preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val store = AndroidKeystoreSyncCoreTransportCredentialStore(
            context = context,
            preferences = preferences,
            keyAlias = keyAlias,
        )
        val credential = SyncTransportCredential(
            pairingSecret = "secret-${System.nanoTime()}",
            certificateSha256 = "ab".repeat(32),
        )

        store.put(" peer-a ", credential)

        assertEquals(credential, store.get("peer-a"))
        val persisted = preferences.all.values.singleOrNull() as? String
        assertNotNull(persisted)
        assertFalse(persisted!!.contains(credential.pairingSecret))
        assertFalse(persisted.contains(SyncTransportCredentialCodec.encode(credential)))

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey = keyStore.getKey(keyAlias, null)
        assertNotNull(secretKey)
        assertNull(secretKey.encoded)
    }

    @Test
    fun envelopeSignerUsesNonExportableAndroidKeystoreP256KeyForEs256() {
        val alias = "orgclock.sync_core.test.signing.${System.nanoTime()}"
        keyAliases += alias
        val canonicalInput = "schemaVersion=1\nalg=ES256\nmessageId=cmd-1\npayloadSha256=${"ab".repeat(32)}"
        val signer = AndroidKeystoreSyncCoreEnvelopeSigner(
            deviceIdProvider = fixedDeviceIdProvider(),
            keyAliasProvider = { alias },
        )

        val signatureBase64 = signer.signCanonical(canonicalInput).getOrThrow()
        val publicKeyBase64 = signer.publicKeyBase64().getOrThrow()

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        assertNull(entry.privateKey.encoded)
        assertTrue(verifyEs256(publicKeyBase64, canonicalInput, signatureBase64))
    }

    @Test
    fun tlsIdentityUsesNonExportableAndroidKeystoreKeyAndReportsCertificatePin() {
        val alias = "orgclock.sync_core.test.tls.${System.nanoTime()}"
        keyAliases += alias
        val identity = AndroidKeystoreSyncCoreTlsIdentityStore(keyAlias = alias).loadOrCreate()

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry

        assertNotNull(identity.sslServerSocketFactory)
        assertTrue(identity.certificateSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertNull(entry.privateKey.encoded)
        assertEquals(identity.certificateSha256, sha256Hex(entry.certificate.encoded))
    }

    @Test
    fun roomMigrationFromV1ToCurrentPreservesLegacyRows() = runBlocking {
        val name = nextDatabaseName("sync-core-v1")
        createLegacyV1Database(name)

        val database = openMigratedDatabase(name)
        try {
            assertMigratedLegacyRows(database, expectedReplayRows = 0)
        } finally {
            database.close()
        }
    }

    @Test
    fun roomMigrationFromV2ToCurrentPreservesLegacyRowsAndReplayRegistry() = runBlocking {
        val name = nextDatabaseName("sync-core-v2")
        createLegacyV2Database(name)

        val database = openMigratedDatabase(name)
        try {
            assertMigratedLegacyRows(database, expectedReplayRows = 1)
        } finally {
            database.close()
        }
    }

    @Test
    fun roomMigrationFailureLeavesLegacyDatabaseReadable() {
        val name = nextDatabaseName("sync-core-v2-invalid")
        createLegacyV2Database(name, outgoingState = "UNKNOWN")
        val databaseFile = context.getDatabasePath(name)

        val failure = runCatching { openMigratedDatabase(name).close() }.exceptionOrNull()

        assertNotNull(failure)
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.query(
                "sync_outgoing_queue",
                arrayOf("commandId", "state"),
                "commandId = ?",
                arrayOf("cmd-a"),
                null,
                null,
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("cmd-a", cursor.getString(0))
                assertEquals("UNKNOWN", cursor.getString(1))
            }
        }
    }

    private fun nextDatabaseName(prefix: String): String {
        val name = "$prefix-${System.nanoTime()}.db"
        databaseNames += name
        context.deleteDatabase(name)
        return name
    }

    private fun openMigratedDatabase(name: String): InternalSyncCoreDatabase =
        Room.databaseBuilder(context, InternalSyncCoreDatabase::class.java, name)
            .addMigrations(
                InternalSyncCoreDatabaseFactory.MIGRATION_1_2,
                InternalSyncCoreDatabaseFactory.MIGRATION_2_3,
            )
            .build()
            .also { database -> runBlocking { database.syncCoreDao().healthCheck() } }

    private fun createLegacyV1Database(name: String) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            createLegacyV1Schema(db)
            insertLegacySentinels(db)
            db.setVersion(1)
        }
    }

    private fun createLegacyV2Database(name: String, outgoingState: String = "PENDING") {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            createLegacyV1Schema(db)
            createLegacyReplayTable(db)
            insertLegacySentinels(db, outgoingState)
            db.execSQL(
                "INSERT INTO sync_incoming_replay(senderDeviceId, commandId, registeredAtEpochMs) VALUES (?, ?, ?)",
                arrayOf("peer-a", "cmd-a", 1_700_000_000_000L),
            )
            db.setVersion(2)
        }
    }

    private fun createLegacyV1Schema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sync_outgoing_queue (" +
                "commandId TEXT NOT NULL PRIMARY KEY, " +
                "topic TEXT NOT NULL, " +
                "payloadJson TEXT NOT NULL, " +
                "targetPeerId TEXT NOT NULL, " +
                "createdAtEpochMs INTEGER NOT NULL, " +
                "expiresAtEpochMs INTEGER, " +
                "state TEXT NOT NULL, " +
                "retryCount INTEGER NOT NULL, " +
                "nextRetryAtEpochMs INTEGER NOT NULL, " +
                "updatedAtEpochMs INTEGER NOT NULL, " +
                "lastErrorCode TEXT, " +
                "lastErrorMessage TEXT)",
        )
        db.execSQL(
            "CREATE TABLE sync_processed_results (" +
                "commandId TEXT NOT NULL PRIMARY KEY, " +
                "status TEXT NOT NULL, " +
                "errorCode TEXT, " +
                "errorMessage TEXT, " +
                "appliedAtEpochMs INTEGER, " +
                "recordedAtEpochMs INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE sync_delivery_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "commandId TEXT NOT NULL, " +
                "peerId TEXT NOT NULL, " +
                "state TEXT NOT NULL, " +
                "occurredAtEpochMs INTEGER NOT NULL, " +
                "errorCode TEXT, " +
                "detail TEXT)",
        )
    }

    private fun createLegacyReplayTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sync_incoming_replay (" +
                "senderDeviceId TEXT NOT NULL, " +
                "commandId TEXT NOT NULL, " +
                "registeredAtEpochMs INTEGER NOT NULL, " +
                "PRIMARY KEY(senderDeviceId, commandId))",
        )
        db.execSQL(
            "CREATE INDEX index_sync_incoming_replay_registeredAtEpochMs " +
                "ON sync_incoming_replay(registeredAtEpochMs)",
        )
    }

    private fun insertLegacySentinels(db: SQLiteDatabase, outgoingState: String = "PENDING") {
        db.execSQL(
            "INSERT INTO sync_outgoing_queue(" +
                "commandId, topic, payloadJson, targetPeerId, createdAtEpochMs, expiresAtEpochMs, " +
                "state, retryCount, nextRetryAtEpochMs, updatedAtEpochMs, lastErrorCode, lastErrorMessage) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                "cmd-a",
                "clock.command.v1",
                "{\"op\":\"start\"}",
                "peer-a",
                1_700_000_000_000L,
                1_700_086_400_000L,
                outgoingState,
                2,
                1_700_000_001_000L,
                1_700_000_002_000L,
                "Timeout",
                "retry later",
            ),
        )
        db.execSQL(
            "INSERT INTO sync_processed_results(commandId, status, errorCode, errorMessage, appliedAtEpochMs, recordedAtEpochMs) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf("cmd-a", "applied", null, null, 1_700_000_003_000L, 1_700_000_004_000L),
        )
        db.execSQL(
            "INSERT INTO sync_delivery_events(id, commandId, peerId, state, occurredAtEpochMs, errorCode, detail) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(7, "cmd-a", "peer-a", "SENT", 1_700_000_005_000L, "Timeout", "retry"),
        )
    }

    private fun assertMigratedLegacyRows(database: InternalSyncCoreDatabase, expectedReplayRows: Int) {
        val db = database.openHelper.readableDatabase
        db.query(
            "SELECT commandId, topic, payloadJson, targetPeerId, state, retryCount, contentSha256, sequence " +
                "FROM sync_outgoing_queue WHERE commandId = 'cmd-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("cmd-a", cursor.getString(0))
            assertEquals("clock.command.v1", cursor.getString(1))
            assertEquals("{\"op\":\"start\"}", cursor.getString(2))
            assertEquals("peer-a", cursor.getString(3))
            assertEquals("pending", cursor.getString(4))
            assertEquals(2, cursor.getInt(5))
            assertTrue(cursor.getString(6).matches(Regex("^[0-9a-f]{64}$")))
            assertEquals(1L, cursor.getLong(7))
        }
        db.query(
            "SELECT id, commandId, peerId, state, sequence, topic, attempt FROM sync_delivery_events WHERE id = 7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7L, cursor.getLong(0))
            assertEquals("cmd-a", cursor.getString(1))
            assertEquals("peer-a", cursor.getString(2))
            assertEquals("dispatching", cursor.getString(3))
            assertEquals(1L, cursor.getLong(4))
            assertEquals("clock.command.v1", cursor.getString(5))
            assertEquals(0, cursor.getInt(6))
        }
        db.query("SELECT status FROM sync_processed_results WHERE commandId = 'cmd-a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("applied", cursor.getString(0))
        }
        db.query("SELECT COUNT(*) FROM sync_incoming_replay").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedReplayRows, cursor.getInt(0))
        }
        assertTableExists(db, "sync_incoming_inbox")
        assertTableExists(db, "sync_result_routes")
        assertTableExists(db, "sync_metrics")
        assertTableExists(db, "sync_peer_metrics")
    }

    private fun assertTableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, tableName: String) {
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(tableName)).use { cursor ->
            assertTrue("Missing table: $tableName", cursor.moveToFirst())
        }
    }

    private fun fixedDeviceIdProvider(): DeviceIdProvider = object : DeviceIdProvider {
        override fun getOrCreate(): String = "device-local"
    }

    private fun verifyEs256(publicKeyBase64: String, canonicalInput: String, signatureBase64: String): Boolean {
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(canonicalInput.toByteArray(Charsets.UTF_8))
        return verifier.verify(es256RawToDer(Base64.getDecoder().decode(signatureBase64)))
    }

    private fun es256RawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "ES256 signature must be 64 bytes" }
        val r = derInteger(raw.copyOfRange(0, 32))
        val s = derInteger(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    private fun derInteger(value: ByteArray): ByteArray {
        val strippedBytes = value.dropWhile { it == 0.toByte() }.toByteArray()
        val stripped = if (strippedBytes.isEmpty()) byteArrayOf(0) else strippedBytes
        val positive = if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0) + stripped else stripped
        return byteArrayOf(0x02, positive.size.toByte()) + positive
    }

    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
