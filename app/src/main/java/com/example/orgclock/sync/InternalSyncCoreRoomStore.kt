package com.example.orgclock.sync

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.StoreHealth
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncStore
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingState
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreMetrics
import io.github.shgnaka.orgclock.synccore.api.SyncStoreOutgoingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.Topic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

@Entity(
    tableName = "sync_outgoing_queue",
    indices = [
        Index(value = ["commandId"], unique = true),
        Index(value = ["state", "nextRetryAtEpochMs", "sequence"], name = "index_sync_outgoing_due"),
        Index(value = ["targetPeerId", "sequence"], name = "index_sync_outgoing_peer_order"),
        Index(value = ["terminalAtEpochMs"], name = "index_sync_outgoing_terminal"),
    ],
)
internal data class InternalSyncOutgoingRow(
    @PrimaryKey val commandId: String,
    val topic: String,
    val payloadJson: String,
    val targetPeerId: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val state: String,
    val retryCount: Int,
    val nextRetryAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val contentSha256: String,
    val sequence: Long,
    val leaseUntilEpochMs: Long?,
    val cancelRequested: Boolean,
    val terminalAtEpochMs: Long?,
    val lastAttemptAtEpochMs: Long?,
)

@Entity(
    tableName = "sync_incoming_inbox",
    indices = [
        Index(value = ["senderPeerId", "messageId"], unique = true),
        Index(value = ["processingState", "processingLeaseUntilEpochMs", "receivedAtEpochMs"], name = "index_sync_incoming_claim"),
        Index(value = ["processedAtEpochMs"], name = "index_sync_incoming_terminal"),
    ],
)
internal data class InternalSyncIncomingInboxRow(
    @PrimaryKey val receiptId: String,
    val senderPeerId: String,
    val targetPeerId: String,
    val messageId: String,
    val topic: String,
    val payloadJson: String,
    val contentSha256: String,
    val receivedAtEpochMs: Long,
    val processingState: String,
    val processingLeaseUntilEpochMs: Long?,
    val deliveryCount: Int,
    val processedAtEpochMs: Long?,
    val lastErrorCode: String?,
    val lastErrorDetail: String?,
)

@Entity(
    tableName = "sync_result_routes",
    indices = [Index(value = ["expiresAtEpochMs", "resolvedAtEpochMs"], name = "index_sync_result_routes_expiry")],
)
internal data class InternalSyncResultRouteRow(
    @PrimaryKey val commandMessageId: String,
    val senderPeerId: String,
    val incomingReceiptId: String,
    val createdAtEpochMs: Long,
    val resolvedAtEpochMs: Long?,
    val expiresAtEpochMs: Long,
)

@Entity(tableName = "sync_metrics")
internal data class InternalSyncMetricRow(
    @PrimaryKey val metricKey: String,
    val metricValue: Long,
)

@Entity(tableName = "sync_peer_metrics")
internal data class InternalSyncPeerMetricRow(
    @PrimaryKey val peerId: String,
    val lastSuccessfulDispatchAtEpochMs: Long?,
)

@Entity(
    tableName = "sync_delivery_events",
    indices = [
        Index(value = ["sequence"], name = "index_sync_delivery_events_sequence"),
        Index(value = ["occurredAtEpochMs"], name = "index_sync_delivery_events_retention"),
    ],
)
internal data class InternalSyncDeliveryEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandId: String,
    val peerId: String,
    val state: String,
    val occurredAtEpochMs: Long,
    val errorCode: String?,
    val detail: String?,
    val sequence: Long,
    val topic: String,
    val attempt: Int,
)

@Entity(tableName = "sync_processed_results")
internal data class InternalSyncProcessedResultRow(
    @PrimaryKey val commandId: String,
    val status: String,
    val errorCode: String?,
    val errorMessage: String?,
    val appliedAtEpochMs: Long?,
    val recordedAtEpochMs: Long,
)

@Entity(
    tableName = "sync_incoming_replay",
    primaryKeys = ["senderDeviceId", "commandId"],
    indices = [Index(value = ["registeredAtEpochMs"], name = "index_sync_incoming_replay_registeredAtEpochMs")],
)
internal data class InternalSyncIncomingReplayRow(
    val senderDeviceId: String,
    val commandId: String,
    val registeredAtEpochMs: Long,
)

@Dao
internal interface InternalSyncCoreDao {
    @Query("SELECT 1")
    suspend fun healthCheck(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutgoing(row: InternalSyncOutgoingRow)

    @Query("SELECT * FROM sync_outgoing_queue WHERE commandId = :messageId")
    suspend fun outgoingByMessageId(messageId: String): InternalSyncOutgoingRow?

    @Query("SELECT * FROM sync_outgoing_queue ORDER BY sequence ASC LIMIT :limit")
    suspend fun listOutgoing(limit: Int): List<InternalSyncOutgoingRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIncoming(row: InternalSyncIncomingInboxRow)

    @Query("SELECT * FROM sync_incoming_inbox WHERE senderPeerId = :senderPeerId AND messageId = :messageId")
    suspend fun incomingBySenderAndMessage(senderPeerId: String, messageId: String): InternalSyncIncomingInboxRow?

    @Query("SELECT COUNT(*) FROM sync_incoming_inbox WHERE processingState NOT IN ('processed', 'rejected')")
    suspend fun countUnprocessedIncoming(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetric(row: InternalSyncMetricRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeerMetric(row: InternalSyncPeerMetricRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResultRoute(row: InternalSyncResultRouteRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeliveryEvent(row: InternalSyncDeliveryEventRow)

    @Query("SELECT * FROM sync_incoming_inbox ORDER BY receivedAtEpochMs ASC")
    suspend fun listIncoming(): List<InternalSyncIncomingInboxRow>

    @Query("SELECT * FROM sync_delivery_events ORDER BY sequence ASC")
    suspend fun listDeliveryEvents(): List<InternalSyncDeliveryEventRow>

    @Query("SELECT * FROM sync_metrics")
    suspend fun listMetrics(): List<InternalSyncMetricRow>

    @Query("SELECT * FROM sync_peer_metrics")
    suspend fun listPeerMetrics(): List<InternalSyncPeerMetricRow>

    @Query("DELETE FROM sync_outgoing_queue")
    suspend fun clearOutgoing()

    @Query("DELETE FROM sync_incoming_inbox")
    suspend fun clearIncoming()

    @Query("DELETE FROM sync_result_routes")
    suspend fun clearResultRoutes()

    @Query("DELETE FROM sync_metrics")
    suspend fun clearMetrics()

    @Query("DELETE FROM sync_peer_metrics")
    suspend fun clearPeerMetrics()

    @Query("DELETE FROM sync_delivery_events")
    suspend fun clearDeliveryEvents()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetric(row: InternalSyncMetricRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeerMetric(row: InternalSyncPeerMetricRow)

    @Transaction
    suspend fun replaceSnapshot(
        outgoing: List<InternalSyncOutgoingRow>,
        incoming: List<InternalSyncIncomingInboxRow>,
        deliveryEvents: List<InternalSyncDeliveryEventRow>,
        metrics: List<InternalSyncMetricRow>,
        peerMetrics: List<InternalSyncPeerMetricRow>,
    ) {
        clearOutgoing()
        clearIncoming()
        clearResultRoutes()
        clearMetrics()
        clearPeerMetrics()
        clearDeliveryEvents()
        outgoing.forEach { insertOutgoing(it) }
        incoming.forEach { insertIncoming(it) }
        deliveryEvents.forEach { insertDeliveryEvent(it) }
        metrics.forEach { insertMetric(it) }
        peerMetrics.forEach { insertPeerMetric(it) }
    }
    @Transaction
    suspend fun insertIncomingWithRoute(
        incoming: InternalSyncIncomingInboxRow,
        route: InternalSyncResultRouteRow,
    ) {
        insertIncoming(incoming)
        insertResultRoute(route)
    }
}

@Database(
    entities = [
        InternalSyncOutgoingRow::class,
        InternalSyncIncomingInboxRow::class,
        InternalSyncResultRouteRow::class,
        InternalSyncMetricRow::class,
        InternalSyncPeerMetricRow::class,
        InternalSyncDeliveryEventRow::class,
        InternalSyncProcessedResultRow::class,
        InternalSyncIncomingReplayRow::class,
    ],
    version = 3,
    exportSchema = false,
)
internal abstract class InternalSyncCoreDatabase : RoomDatabase() {
    abstract fun syncCoreDao(): InternalSyncCoreDao
}

internal class AndroidRoomSyncStore(
    private val dao: InternalSyncCoreDao,
) : SyncStore {
    override suspend fun load(): SyncStoreLoadResult = withContext(Dispatchers.IO) {
        runCatching {
            val outgoing = dao.listOutgoing(Int.MAX_VALUE).map { it.toStoreOutgoingRecord() }
            val incoming = dao.listIncoming().map { it.toStoreIncomingRecord() }
            val events = dao.listDeliveryEvents().map { it.toStoreDeliveryEvent() }
            val metricRows = dao.listMetrics().associateBy { it.metricKey }
            val peerMetrics = dao.listPeerMetrics()
                .mapNotNull { row -> row.lastSuccessfulDispatchAtEpochMs?.let { PeerId(row.peerId) to it } }
                .toMap()
            SyncStoreSnapshot(
                outgoing = outgoing,
                incoming = incoming,
                deliveryEvents = events,
                metrics = SyncStoreMetrics(
                    submittedTotal = metricRows[METRIC_SUBMITTED]?.metricValue ?: 0L,
                    acceptedTotal = metricRows[METRIC_ACCEPTED]?.metricValue ?: 0L,
                    rejectedTotal = metricRows[METRIC_REJECTED]?.metricValue ?: 0L,
                    retryAttemptsTotal = metricRows[METRIC_RETRY_ATTEMPTS]?.metricValue ?: 0L,
                    incomingRejectedTotal = metricRows[METRIC_INCOMING_REJECTED]?.metricValue ?: 0L,
                    expiredLeaseRecoveryTotal = metricRows[METRIC_EXPIRED_LEASE_RECOVERY]?.metricValue ?: 0L,
                    persistenceErrorTotal = metricRows[METRIC_PERSISTENCE_ERROR]?.metricValue ?: 0L,
                    lastSuccessfulDispatchByPeer = peerMetrics,
                ),
                nextRecordSequence = outgoing.maxOfOrNull { it.sequence }?.plus(1) ?: 1L,
                nextEventSequence = events.maxOfOrNull { it.sequence }?.plus(1) ?: 1L,
                nextReceiptSequence = incoming.maxOfOrNull { it.receiptId.removePrefix("in-").toLongOrNull() ?: 0L }?.plus(1) ?: 1L,
            )
        }.fold(
            onSuccess = { SyncStoreLoadResult.Loaded(it) },
            onFailure = { SyncStoreLoadResult.Failed(SyncError(SyncErrorCode.StoreReadFailed, it.message)) },
        )
    }

    override suspend fun save(snapshot: SyncStoreSnapshot): SyncStoreSaveResult = withContext(Dispatchers.IO) {
        runCatching {
            dao.replaceSnapshot(
                outgoing = snapshot.outgoing.map { it.toRoomRow() },
                incoming = snapshot.incoming.map { it.toRoomRow() },
                deliveryEvents = snapshot.deliveryEvents.map { it.toRoomRow() },
                metrics = snapshot.metrics.toRoomMetricRows(),
                peerMetrics = snapshot.metrics.lastSuccessfulDispatchByPeer.map { (peerId, lastSuccess) ->
                    InternalSyncPeerMetricRow(peerId = peerId.value, lastSuccessfulDispatchAtEpochMs = lastSuccess)
                },
            )
        }.fold(
            onSuccess = { SyncStoreSaveResult.Saved },
            onFailure = { SyncStoreSaveResult.Failed(SyncError(SyncErrorCode.StoreWriteFailed, it.message)) },
        )
    }

    override suspend fun healthCheck(): StoreHealth = withContext(Dispatchers.IO) {
        runCatching { dao.healthCheck() }.fold(
            onSuccess = { StoreHealth.Healthy },
            onFailure = { StoreHealth.Unavailable(SyncError(SyncErrorCode.StoreUnavailable, it.message)) },
        )
    }
}
internal object InternalSyncCoreDatabaseFactory {
    fun create(appContext: Context): InternalSyncCoreDatabase = Room.databaseBuilder(
        appContext,
        InternalSyncCoreDatabase::class.java,
        DATABASE_NAME,
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    const val DATABASE_NAME = "orgclock_sync_queue.db"

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_incoming_replay (" +
                    "senderDeviceId TEXT NOT NULL, " +
                    "commandId TEXT NOT NULL, " +
                    "registeredAtEpochMs INTEGER NOT NULL, " +
                    "PRIMARY KEY(senderDeviceId, commandId))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_sync_incoming_replay_registeredAtEpochMs " +
                    "ON sync_incoming_replay(registeredAtEpochMs)",
            )
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrateOutgoingQueue(db)
            createIncomingInbox(db)
            createResultRoutes(db)
            createMetrics(db)
            migrateDeliveryEvents(db)
            validateV3(db)
        }
    }
}

private fun migrateOutgoingQueue(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE sync_outgoing_queue_v3 (" +
            "commandId TEXT NOT NULL PRIMARY KEY, " +
            "topic TEXT NOT NULL, " +
            "payloadJson TEXT NOT NULL, " +
            "targetPeerId TEXT NOT NULL, " +
            "createdAtEpochMs INTEGER NOT NULL, " +
            "expiresAtEpochMs INTEGER, " +
            "state TEXT NOT NULL, " +
            "retryCount INTEGER NOT NULL, " +
            "nextRetryAtEpochMs INTEGER, " +
            "updatedAtEpochMs INTEGER NOT NULL, " +
            "lastErrorCode TEXT, " +
            "lastErrorMessage TEXT, " +
            "contentSha256 TEXT NOT NULL, " +
            "sequence INTEGER NOT NULL, " +
            "leaseUntilEpochMs INTEGER, " +
            "cancelRequested INTEGER NOT NULL DEFAULT 0, " +
            "terminalAtEpochMs INTEGER, " +
            "lastAttemptAtEpochMs INTEGER)",
    )
    db.query(
        "SELECT commandId, topic, payloadJson, targetPeerId, createdAtEpochMs, expiresAtEpochMs, " +
            "state, retryCount, nextRetryAtEpochMs, updatedAtEpochMs, lastErrorCode, lastErrorMessage " +
            "FROM sync_outgoing_queue ORDER BY createdAtEpochMs ASC, commandId ASC",
    ).use { cursor ->
        var sequence = 1L
        while (cursor.moveToNext()) {
            val row = LegacyOutgoingMigrationRow(
                commandId = cursor.getString(0),
                topic = cursor.getString(1),
                payloadJson = cursor.getString(2),
                targetPeerId = cursor.getString(3),
                createdAtEpochMs = cursor.getLong(4),
                expiresAtEpochMs = cursor.getNullableLong(5),
                state = cursor.getString(6),
                retryCount = cursor.getInt(7),
                nextRetryAtEpochMs = cursor.getNullableLong(8),
                updatedAtEpochMs = cursor.getLong(9),
                lastErrorCode = cursor.getNullableString(10),
                lastErrorMessage = cursor.getNullableString(11),
            )
            db.insertOutgoingV3(row.toV3OutgoingRow(sequence))
            sequence += 1
        }
    }
    db.execSQL("DROP TABLE sync_outgoing_queue")
    db.execSQL("ALTER TABLE sync_outgoing_queue_v3 RENAME TO sync_outgoing_queue")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outgoing_queue_commandId ON sync_outgoing_queue(commandId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outgoing_due ON sync_outgoing_queue(state, nextRetryAtEpochMs, sequence)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outgoing_peer_order ON sync_outgoing_queue(targetPeerId, sequence)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outgoing_terminal ON sync_outgoing_queue(terminalAtEpochMs)")
}

private fun createIncomingInbox(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sync_incoming_inbox (" +
            "receiptId TEXT NOT NULL PRIMARY KEY, " +
            "senderPeerId TEXT NOT NULL, " +
            "targetPeerId TEXT NOT NULL, " +
            "messageId TEXT NOT NULL, " +
            "topic TEXT NOT NULL, " +
            "payloadJson TEXT NOT NULL, " +
            "contentSha256 TEXT NOT NULL, " +
            "receivedAtEpochMs INTEGER NOT NULL, " +
            "processingState TEXT NOT NULL, " +
            "processingLeaseUntilEpochMs INTEGER, " +
            "deliveryCount INTEGER NOT NULL, " +
            "processedAtEpochMs INTEGER, " +
            "lastErrorCode TEXT, " +
            "lastErrorDetail TEXT)",
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_incoming_inbox_senderPeerId_messageId ON sync_incoming_inbox(senderPeerId, messageId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_incoming_claim ON sync_incoming_inbox(processingState, processingLeaseUntilEpochMs, receivedAtEpochMs)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_incoming_terminal ON sync_incoming_inbox(processedAtEpochMs)")
}

private fun createResultRoutes(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sync_result_routes (" +
            "commandMessageId TEXT NOT NULL PRIMARY KEY, " +
            "senderPeerId TEXT NOT NULL, " +
            "incomingReceiptId TEXT NOT NULL, " +
            "createdAtEpochMs INTEGER NOT NULL, " +
            "resolvedAtEpochMs INTEGER, " +
            "expiresAtEpochMs INTEGER NOT NULL)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_result_routes_expiry ON sync_result_routes(expiresAtEpochMs, resolvedAtEpochMs)")
}

private fun createMetrics(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS sync_metrics (metricKey TEXT NOT NULL PRIMARY KEY, metricValue INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS sync_peer_metrics (peerId TEXT NOT NULL PRIMARY KEY, lastSuccessfulDispatchAtEpochMs INTEGER)")
}

private fun migrateDeliveryEvents(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE sync_delivery_events_v3 (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "commandId TEXT NOT NULL, " +
            "peerId TEXT NOT NULL, " +
            "state TEXT NOT NULL, " +
            "occurredAtEpochMs INTEGER NOT NULL, " +
            "errorCode TEXT, " +
            "detail TEXT, " +
            "sequence INTEGER NOT NULL, " +
            "topic TEXT NOT NULL, " +
            "attempt INTEGER NOT NULL DEFAULT 0)",
    )
    db.query(
        "SELECT id, commandId, peerId, state, occurredAtEpochMs, errorCode, detail " +
            "FROM sync_delivery_events ORDER BY occurredAtEpochMs ASC, id ASC",
    ).use { cursor ->
        var sequence = 1L
        while (cursor.moveToNext()) {
            db.insertDeliveryEventV3(
                LegacyDeliveryEventMigrationRow(
                    id = cursor.getLong(0),
                    commandId = cursor.getString(1),
                    peerId = cursor.getString(2),
                    state = cursor.getString(3),
                    occurredAtEpochMs = cursor.getLong(4),
                    errorCode = cursor.getNullableString(5),
                    detail = cursor.getNullableString(6),
                ).toV3DeliveryEventRow(sequence),
            )
            sequence += 1
        }
    }
    db.execSQL("DROP TABLE sync_delivery_events")
    db.execSQL("ALTER TABLE sync_delivery_events_v3 RENAME TO sync_delivery_events")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_delivery_events_sequence ON sync_delivery_events(sequence)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_delivery_events_retention ON sync_delivery_events(occurredAtEpochMs)")
}

private fun validateV3(db: SupportSQLiteDatabase) {
    val missingContent = db.singleLong("SELECT COUNT(*) FROM sync_outgoing_queue WHERE contentSha256 IS NULL OR contentSha256 = ''")
    require(missingContent == 0L) { SyncErrorCode.MigrationFailed.name }
    val duplicateSequences = db.singleLong(
        "SELECT COUNT(*) FROM (SELECT sequence FROM sync_outgoing_queue GROUP BY sequence HAVING COUNT(*) > 1)",
    )
    require(duplicateSequences == 0L) { SyncErrorCode.MigrationFailed.name }
}

internal data class LegacyOutgoingMigrationRow(
    val commandId: String,
    val topic: String,
    val payloadJson: String,
    val targetPeerId: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val state: String,
    val retryCount: Int,
    val nextRetryAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
) {
    fun contentSha256(): String = sha256Hex(
        listOf(
            commandId,
            topic,
            payloadJson,
            targetPeerId,
            createdAtEpochMs.toString(),
            expiresAtEpochMs?.toString().orEmpty(),
        ).joinToString("\n"),
    )
}

internal data class LegacyDeliveryEventMigrationRow(
    val id: Long,
    val commandId: String,
    val peerId: String,
    val state: String,
    val occurredAtEpochMs: Long,
    val errorCode: String?,
    val detail: String?,
)

internal fun LegacyOutgoingMigrationRow.toV3OutgoingRow(sequence: Long): InternalSyncOutgoingRow {
    require(sequence > 0) { "sequence must be > 0." }
    val mapped = state.toV3OutgoingState()
    return InternalSyncOutgoingRow(
        commandId = commandId,
        topic = topic,
        payloadJson = payloadJson,
        targetPeerId = targetPeerId,
        createdAtEpochMs = createdAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        state = mapped.state,
        retryCount = retryCount,
        nextRetryAtEpochMs = nextRetryAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        lastErrorCode = lastErrorCode,
        lastErrorMessage = lastErrorMessage,
        contentSha256 = contentSha256(),
        sequence = sequence,
        leaseUntilEpochMs = mapped.leaseUntilEpochMs,
        cancelRequested = false,
        terminalAtEpochMs = mapped.terminalAtEpochMs(updatedAtEpochMs),
        lastAttemptAtEpochMs = null,
    )
}

internal fun LegacyDeliveryEventMigrationRow.toV3DeliveryEventRow(sequence: Long): InternalSyncDeliveryEventRow {
    require(sequence > 0) { "sequence must be > 0." }
    return InternalSyncDeliveryEventRow(
        id = id,
        commandId = commandId,
        peerId = peerId,
        state = state.toV3OutgoingState().state,
        occurredAtEpochMs = occurredAtEpochMs,
        errorCode = errorCode,
        detail = detail,
        sequence = sequence,
        topic = "clock.command.v1",
        attempt = 0,
    )
}

private fun SupportSQLiteDatabase.insertOutgoingV3(row: InternalSyncOutgoingRow) {
    execSQL(
        "INSERT INTO sync_outgoing_queue_v3 (" +
            "commandId, topic, payloadJson, targetPeerId, createdAtEpochMs, expiresAtEpochMs, " +
            "state, retryCount, nextRetryAtEpochMs, updatedAtEpochMs, lastErrorCode, lastErrorMessage, " +
            "contentSha256, sequence, leaseUntilEpochMs, cancelRequested, terminalAtEpochMs, lastAttemptAtEpochMs) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf(
            row.commandId,
            row.topic,
            row.payloadJson,
            row.targetPeerId,
            row.createdAtEpochMs,
            row.expiresAtEpochMs,
            row.state,
            row.retryCount,
            row.nextRetryAtEpochMs,
            row.updatedAtEpochMs,
            row.lastErrorCode,
            row.lastErrorMessage,
            row.contentSha256,
            row.sequence,
            row.leaseUntilEpochMs,
            row.cancelRequested,
            row.terminalAtEpochMs,
            row.lastAttemptAtEpochMs,
        ),
    )
}

private fun SupportSQLiteDatabase.insertDeliveryEventV3(row: InternalSyncDeliveryEventRow) {
    execSQL(
        "INSERT INTO sync_delivery_events_v3 (id, commandId, peerId, state, occurredAtEpochMs, errorCode, detail, sequence, topic, attempt) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf(
            row.id,
            row.commandId,
            row.peerId,
            row.state,
            row.occurredAtEpochMs,
            row.errorCode,
            row.detail,
            row.sequence,
            row.topic,
            row.attempt,
        ),
    )
}
private data class V3StateMapping(
    val state: String,
    val leaseUntilEpochMs: Long?,
    val terminal: Boolean,
) {
    fun terminalAtEpochMs(updatedAtEpochMs: Long): Long? = if (terminal) updatedAtEpochMs else null
}

private fun String.toV3OutgoingState(): V3StateMapping = when (this) {
    "PENDING", "pending" -> V3StateMapping("pending", null, false)
    "SENT", "dispatching" -> V3StateMapping("dispatching", 0L, false)
    "ACKED", "acked" -> V3StateMapping("acked", null, true)
    "REJECTED", "rejected" -> V3StateMapping("rejected", null, true)
    "FAILED", "failed" -> V3StateMapping("failed", null, true)
    "EXPIRED", "expired" -> V3StateMapping("expired", null, true)
    "CANCELLED", "cancelled" -> V3StateMapping("cancelled", null, true)
    "RETRY_WAIT", "retry_wait" -> V3StateMapping("retry_wait", null, false)
    else -> error("Unsupported legacy sync state: $this")
}

private fun SupportSQLiteDatabase.singleLong(sql: String): Long = query(sql).use { cursor ->
    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
}

private fun android.database.Cursor.getNullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private fun android.database.Cursor.getNullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)

private const val METRIC_SUBMITTED = "submittedTotal"
private const val METRIC_ACCEPTED = "acceptedTotal"
private const val METRIC_REJECTED = "rejectedTotal"
private const val METRIC_RETRY_ATTEMPTS = "retryAttemptsTotal"
private const val METRIC_INCOMING_REJECTED = "incomingRejectedTotal"
private const val METRIC_EXPIRED_LEASE_RECOVERY = "expiredLeaseRecoveryTotal"
private const val METRIC_PERSISTENCE_ERROR = "persistenceErrorTotal"

private fun InternalSyncOutgoingRow.toStoreOutgoingRecord(): SyncStoreOutgoingRecord = SyncStoreOutgoingRecord(
    sequence = sequence,
    message = SyncMessage(
        messageId = MessageId(commandId),
        topic = Topic(topic),
        payloadJson = payloadJson,
        targetPeerId = PeerId(targetPeerId),
        createdAtEpochMs = createdAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
    ),
    state = state.toDeliveryState(),
    attempt = retryCount,
    nextAttemptAtEpochMs = nextRetryAtEpochMs,
    lastError = lastErrorCode?.let { SyncError(it.toSyncErrorCode(), lastErrorMessage) },
    leaseUntilEpochMs = leaseUntilEpochMs,
    terminalAtEpochMs = terminalAtEpochMs,
)

private fun SyncStoreOutgoingRecord.toRoomRow(): InternalSyncOutgoingRow = InternalSyncOutgoingRow(
    commandId = message.messageId.value,
    topic = message.topic.value,
    payloadJson = message.payloadJson,
    targetPeerId = message.targetPeerId.value,
    createdAtEpochMs = message.createdAtEpochMs,
    expiresAtEpochMs = message.expiresAtEpochMs,
    state = state.toStorageState(),
    retryCount = attempt,
    nextRetryAtEpochMs = nextAttemptAtEpochMs,
    updatedAtEpochMs = lastError?.retryAtEpochMs ?: message.createdAtEpochMs,
    lastErrorCode = lastError?.code?.name,
    lastErrorMessage = lastError?.detail,
    contentSha256 = sha256Hex(
        listOf(
            message.messageId.value,
            message.topic.value,
            message.payloadJson,
            message.targetPeerId.value,
            message.createdAtEpochMs.toString(),
            message.expiresAtEpochMs?.toString().orEmpty(),
        ).joinToString("\n"),
    ),
    sequence = sequence,
    leaseUntilEpochMs = leaseUntilEpochMs,
    cancelRequested = state == DeliveryState.Cancelled,
    terminalAtEpochMs = if (state.isTerminal()) terminalAtEpochMs else null,
    lastAttemptAtEpochMs = null,
)

private fun InternalSyncIncomingInboxRow.toStoreIncomingRecord(): SyncStoreIncomingRecord = SyncStoreIncomingRecord(
    receiptId = receiptId,
    senderPeerId = PeerId(senderPeerId),
    targetPeerId = PeerId(targetPeerId),
    messageId = MessageId(messageId),
    topic = Topic(topic),
    payloadJson = payloadJson,
    payloadSha256 = contentSha256,
    receivedAtEpochMs = receivedAtEpochMs,
    state = processingState.toIncomingState(),
    availableAtEpochMs = processingLeaseUntilEpochMs,
    deliveryCount = deliveryCount,
    lastError = lastErrorCode?.let { SyncError(it.toSyncErrorCode(), lastErrorDetail) },
    processedAtEpochMs = processedAtEpochMs,
)

private fun SyncStoreIncomingRecord.toRoomRow(): InternalSyncIncomingInboxRow = InternalSyncIncomingInboxRow(
    receiptId = receiptId,
    senderPeerId = senderPeerId.value,
    targetPeerId = targetPeerId.value,
    messageId = messageId.value,
    topic = topic.value,
    payloadJson = payloadJson,
    contentSha256 = payloadSha256,
    receivedAtEpochMs = receivedAtEpochMs,
    processingState = state.toStorageState(),
    processingLeaseUntilEpochMs = availableAtEpochMs,
    deliveryCount = deliveryCount,
    processedAtEpochMs = if (state == SyncStoreIncomingState.Processed || state == SyncStoreIncomingState.Rejected) processedAtEpochMs else null,
    lastErrorCode = lastError?.code?.name,
    lastErrorDetail = lastError?.detail,
)

private fun InternalSyncDeliveryEventRow.toStoreDeliveryEvent(): DeliveryEvent = DeliveryEvent(
    sequence = sequence,
    messageId = MessageId(commandId),
    peerId = PeerId(peerId),
    topic = Topic(topic),
    state = state.toDeliveryState(),
    attempt = attempt,
    occurredAtEpochMs = occurredAtEpochMs,
    error = errorCode?.let { SyncError(it.toSyncErrorCode(), detail) },
)

private fun DeliveryEvent.toRoomRow(): InternalSyncDeliveryEventRow = InternalSyncDeliveryEventRow(
    commandId = messageId.value,
    peerId = peerId.value,
    state = state.toStorageState(),
    occurredAtEpochMs = occurredAtEpochMs,
    errorCode = error?.code?.name,
    detail = error?.detail,
    sequence = sequence,
    topic = topic.value,
    attempt = attempt,
)

private fun SyncStoreMetrics.toRoomMetricRows(): List<InternalSyncMetricRow> = listOf(
    InternalSyncMetricRow(METRIC_SUBMITTED, submittedTotal),
    InternalSyncMetricRow(METRIC_ACCEPTED, acceptedTotal),
    InternalSyncMetricRow(METRIC_REJECTED, rejectedTotal),
    InternalSyncMetricRow(METRIC_RETRY_ATTEMPTS, retryAttemptsTotal),
    InternalSyncMetricRow(METRIC_INCOMING_REJECTED, incomingRejectedTotal),
    InternalSyncMetricRow(METRIC_EXPIRED_LEASE_RECOVERY, expiredLeaseRecoveryTotal),
    InternalSyncMetricRow(METRIC_PERSISTENCE_ERROR, persistenceErrorTotal),
)

private fun String.toDeliveryState(): DeliveryState = when (this) {
    "pending", "PENDING" -> DeliveryState.Pending
    "dispatching", "SENT" -> DeliveryState.Dispatching
    "retry_wait", "RETRY_WAIT" -> DeliveryState.RetryWait
    "acked", "ACKED" -> DeliveryState.Acked
    "rejected", "REJECTED" -> DeliveryState.Rejected
    "failed", "FAILED" -> DeliveryState.Failed
    "expired", "EXPIRED" -> DeliveryState.Expired
    "cancelled", "CANCELLED" -> DeliveryState.Cancelled
    else -> error("Unsupported delivery state: $this")
}

private fun DeliveryState.toStorageState(): String = when (this) {
    DeliveryState.Pending -> "pending"
    DeliveryState.Dispatching -> "dispatching"
    DeliveryState.RetryWait -> "retry_wait"
    DeliveryState.Acked -> "acked"
    DeliveryState.Rejected -> "rejected"
    DeliveryState.Failed -> "failed"
    DeliveryState.Expired -> "expired"
    DeliveryState.Cancelled -> "cancelled"
}

private fun String.toIncomingState(): SyncStoreIncomingState = when (this) {
    "available" -> SyncStoreIncomingState.Available
    "claimed" -> SyncStoreIncomingState.Claimed
    "retry_later" -> SyncStoreIncomingState.RetryLater
    "processed" -> SyncStoreIncomingState.Processed
    "rejected" -> SyncStoreIncomingState.Rejected
    else -> error("Unsupported incoming state: $this")
}

private fun SyncStoreIncomingState.toStorageState(): String = when (this) {
    SyncStoreIncomingState.Available -> "available"
    SyncStoreIncomingState.Claimed -> "claimed"
    SyncStoreIncomingState.RetryLater -> "retry_later"
    SyncStoreIncomingState.Processed -> "processed"
    SyncStoreIncomingState.Rejected -> "rejected"
}

private fun String.toSyncErrorCode(): SyncErrorCode = runCatching { SyncErrorCode.valueOf(this) }
    .getOrDefault(SyncErrorCode.ProtocolError)

private fun DeliveryState.isTerminal(): Boolean = when (this) {
    DeliveryState.Acked,
    DeliveryState.Rejected,
    DeliveryState.Failed,
    DeliveryState.Expired,
    DeliveryState.Cancelled -> true
    DeliveryState.Pending,
    DeliveryState.Dispatching,
    DeliveryState.RetryWait -> false
}
private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}





