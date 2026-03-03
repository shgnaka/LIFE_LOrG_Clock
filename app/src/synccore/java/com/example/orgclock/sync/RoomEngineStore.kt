package com.example.orgclock.sync

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.shgnaka.synccore.api.CommandState
import io.github.shgnaka.synccore.api.DeliveryErrorCode
import io.github.shgnaka.synccore.api.DeliveryEvent
import io.github.shgnaka.synccore.api.SyncCommand
import io.github.shgnaka.synccore.api.SyncResult
import io.github.shgnaka.synccore.engine.EngineStore
import io.github.shgnaka.synccore.engine.OutgoingCommandRecord
import io.github.shgnaka.synccore.engine.RetentionCleanupResult
import io.github.shgnaka.synccore.engine.RetentionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

internal class RoomEngineStore(
    private val dao: SyncQueueDao,
) : EngineStore {
    override suspend fun healthCheck(): Result<Unit> = runCatching { dao.healthCheck() }

    override suspend fun insertOutgoing(command: SyncCommand): Result<Unit> = runCatching {
        dao.insertOutgoing(
            OutgoingCommandRow(
                commandId = command.commandId,
                topic = command.topic,
                payloadJson = command.payloadJson,
                targetPeerId = command.targetPeerId,
                createdAtEpochMs = command.createdAtEpochMs,
                expiresAtEpochMs = command.expiresAtEpochMs,
                state = CommandState.PENDING.name,
                retryCount = 0,
                nextRetryAtEpochMs = command.createdAtEpochMs,
                updatedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                lastErrorCode = null,
                lastErrorMessage = null,
            ),
        )
    }

    override suspend fun markState(commandId: String, state: CommandState): Result<Unit> = runCatching {
        dao.markState(commandId, state.name, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun recordResult(result: SyncResult): Result<Unit> = runCatching {
        dao.insertProcessed(
            ProcessedResultRow(
                commandId = result.commandId,
                status = result.status.name,
                errorCode = result.errorCode,
                errorMessage = result.errorMessage,
                appliedAtEpochMs = result.appliedAtEpochMs,
                recordedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    override suspend fun isProcessed(commandId: String): Boolean {
        return dao.countProcessed(commandId) > 0
    }

    override suspend fun listPendingCommands(limit: Int): List<SyncCommand> = withContext(Dispatchers.IO) {
        dao.listPending(limit).map { row ->
            SyncCommand(
                commandId = row.commandId,
                topic = row.topic,
                payloadJson = row.payloadJson,
                targetPeerId = row.targetPeerId,
                createdAtEpochMs = row.createdAtEpochMs,
                expiresAtEpochMs = row.expiresAtEpochMs,
            )
        }
    }

    override suspend fun listDueCommands(nowEpochMs: Long, limit: Int): List<OutgoingCommandRecord> = withContext(Dispatchers.IO) {
        dao.listDue(nowEpochMs, limit).map { row ->
            OutgoingCommandRecord(
                command = SyncCommand(
                    commandId = row.commandId,
                    topic = row.topic,
                    payloadJson = row.payloadJson,
                    targetPeerId = row.targetPeerId,
                    createdAtEpochMs = row.createdAtEpochMs,
                    expiresAtEpochMs = row.expiresAtEpochMs,
                ),
                state = runCatching { CommandState.valueOf(row.state) }.getOrDefault(CommandState.PENDING),
                retryCount = row.retryCount,
                nextRetryAtEpochMs = row.nextRetryAtEpochMs,
            )
        }
    }

    override suspend fun updateRetry(
        commandId: String,
        retryCount: Int,
        nextRetryAtEpochMs: Long,
        errorCode: DeliveryErrorCode?,
        errorMessage: String?,
    ): Result<Unit> = runCatching {
        dao.updateRetry(
            commandId = commandId,
            retryCount = retryCount,
            nextRetryAtEpochMs = nextRetryAtEpochMs,
            lastErrorCode = errorCode?.name,
            lastErrorMessage = errorMessage,
            updatedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    override suspend fun appendDeliveryEvent(event: DeliveryEvent): Result<Unit> = runCatching {
        dao.insertDeliveryEvent(
            DeliveryEventRow(
                commandId = event.commandId,
                peerId = event.peerId,
                state = event.state.name,
                occurredAtEpochMs = event.occurredAtEpochMs,
                errorCode = event.errorCode?.name,
                detail = event.detail,
            ),
        )
    }

    override suspend fun getQueueDepth(): Long {
        return dao.countQueueDepth().toLong()
    }

    override suspend fun cleanupRetention(
        nowEpochMs: Long,
        policy: RetentionPolicy,
    ): Result<RetentionCleanupResult> = runCatching {
        val deliveryEventCutoff = nowEpochMs - policy.deliveryEventsTtlMs
        val deletedDeliveryEvents = dao.countDeliveryEventsOlderThan(deliveryEventCutoff).toLong()
        dao.deleteDeliveryEventsOlderThan(deliveryEventCutoff)
        RetentionCleanupResult(
            deletedIncomingMessages = 0L,
            deletedNonces = 0L,
            deletedDeliveryEvents = deletedDeliveryEvents,
        )
    }
}

@Entity(tableName = "sync_outgoing_queue")
internal data class OutgoingCommandRow(
    @PrimaryKey val commandId: String,
    val topic: String,
    val payloadJson: String,
    val targetPeerId: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val state: String,
    val retryCount: Int,
    val nextRetryAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
)

@Entity(tableName = "sync_processed_results")
internal data class ProcessedResultRow(
    @PrimaryKey val commandId: String,
    val status: String,
    val errorCode: String?,
    val errorMessage: String?,
    val appliedAtEpochMs: Long?,
    val recordedAtEpochMs: Long,
)

@Entity(tableName = "sync_delivery_events")
internal data class DeliveryEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandId: String,
    val peerId: String,
    val state: String,
    val occurredAtEpochMs: Long,
    val errorCode: String?,
    val detail: String?,
)

@Dao
internal interface SyncQueueDao {
    @Query("SELECT 1")
    suspend fun healthCheck()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutgoing(row: OutgoingCommandRow)

    @Query("UPDATE sync_outgoing_queue SET state = :state, updatedAtEpochMs = :updatedAtEpochMs WHERE commandId = :commandId")
    suspend fun markState(commandId: String, state: String, updatedAtEpochMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProcessed(row: ProcessedResultRow)

    @Query("SELECT COUNT(*) FROM sync_processed_results WHERE commandId = :commandId")
    suspend fun countProcessed(commandId: String): Int

    @Query("SELECT * FROM sync_outgoing_queue WHERE state = 'PENDING' ORDER BY createdAtEpochMs LIMIT :limit")
    suspend fun listPending(limit: Int): List<OutgoingCommandRow>

    @Query(
        "SELECT * FROM sync_outgoing_queue " +
            "WHERE state = 'PENDING' AND nextRetryAtEpochMs <= :nowEpochMs " +
            "AND commandId NOT IN (SELECT commandId FROM sync_processed_results) " +
            "ORDER BY nextRetryAtEpochMs LIMIT :limit",
    )
    suspend fun listDue(nowEpochMs: Long, limit: Int): List<OutgoingCommandRow>

    @Query(
        "UPDATE sync_outgoing_queue " +
            "SET retryCount = :retryCount, nextRetryAtEpochMs = :nextRetryAtEpochMs, " +
            "lastErrorCode = :lastErrorCode, lastErrorMessage = :lastErrorMessage, updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE commandId = :commandId",
    )
    suspend fun updateRetry(
        commandId: String,
        retryCount: Int,
        nextRetryAtEpochMs: Long,
        lastErrorCode: String?,
        lastErrorMessage: String?,
        updatedAtEpochMs: Long,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeliveryEvent(row: DeliveryEventRow)

    @Query("SELECT COUNT(*) FROM sync_delivery_events WHERE occurredAtEpochMs < :cutoffEpochMs")
    suspend fun countDeliveryEventsOlderThan(cutoffEpochMs: Long): Int

    @Query("DELETE FROM sync_delivery_events WHERE occurredAtEpochMs < :cutoffEpochMs")
    suspend fun deleteDeliveryEventsOlderThan(cutoffEpochMs: Long)

    @Query("SELECT COUNT(*) FROM sync_outgoing_queue WHERE state IN ('PENDING', 'SENT', 'ACKED')")
    suspend fun countQueueDepth(): Int
}

@Database(
    entities = [OutgoingCommandRow::class, ProcessedResultRow::class, DeliveryEventRow::class],
    version = 1,
    exportSchema = false,
)
internal abstract class SyncQueueDatabase : RoomDatabase() {
    abstract fun syncQueueDao(): SyncQueueDao
}

internal object SyncQueueDatabaseFactory {
    fun create(appContext: Context): SyncQueueDatabase {
        return Room.databaseBuilder(
            appContext,
            SyncQueueDatabase::class.java,
            "orgclock_sync_queue.db",
        ).build()
    }
}
