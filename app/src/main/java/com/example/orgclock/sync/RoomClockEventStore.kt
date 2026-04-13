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
import com.example.orgclock.model.HeadingPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

@Entity(
    tableName = "clock_events",
    indices = [Index(value = ["eventId"], unique = true)],
)
internal data class ClockEventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val eventId: String,
    val schema: String,
    val eventType: String,
    val deviceId: String,
    val createdAt: String,
    val logicalDay: String,
    val fileName: String,
    val headingPath: String,
    val causalKind: String,
    val causalCounter: Long,
)

@Entity(tableName = "clock_event_sync_state")
internal data class ClockEventSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastSyncedCursor: Long,
)

@Dao
internal interface ClockEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: ClockEventEntity): Long

    @Query("SELECT seq FROM clock_events WHERE eventId = :eventId")
    fun findCursorByEventId(eventId: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM clock_events WHERE eventId = :eventId)")
    fun exists(eventId: String): Boolean

    @Query(
        """
        SELECT seq, eventId, schema, eventType, deviceId, createdAt, logicalDay, fileName,
               headingPath, causalKind, causalCounter
        FROM clock_events
        ORDER BY seq ASC
        """,
    )
    fun readAll(): List<ClockEventEntity>

    @Query(
        """
        SELECT seq, eventId, schema, eventType, deviceId, createdAt, logicalDay, fileName,
               headingPath, causalKind, causalCounter
        FROM clock_events
        WHERE seq > :cursorExclusive
        ORDER BY seq ASC
        LIMIT :limit
        """,
    )
    fun listSince(cursorExclusive: Long, limit: Int): List<ClockEventEntity>

    @Query("SELECT MAX(seq) FROM clock_events")
    fun findLastCursor(): Long?

    @Query("SELECT COUNT(*) FROM clock_events WHERE seq > :cursorExclusive")
    fun countAfter(cursorExclusive: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM clock_events WHERE seq = :cursor)")
    fun cursorExists(cursor: Long): Boolean

    @Query("SELECT * FROM clock_event_sync_state WHERE id = 1")
    fun readSyncState(): ClockEventSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSyncState(state: ClockEventSyncStateEntity)
}

@Database(
    entities = [ClockEventEntity::class, ClockEventSyncStateEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class ClockEventDatabase : RoomDatabase() {
    abstract fun dao(): ClockEventDao
}

internal class RoomClockEventStore(
    private val dao: ClockEventDao,
) : ClockEventStore {
    override suspend fun append(event: ClockEvent): AppendClockEventResult {
        return withContext(Dispatchers.IO) {
            val inserted = dao.insert(event.toEntity())
            if (inserted != -1L) {
                return@withContext AppendClockEventResult.Appended(ClockEventCursor(inserted))
            }
            AppendClockEventResult.Duplicate(
                dao.findCursorByEventId(event.eventId)?.let(::ClockEventCursor),
            )
        }
    }

    override suspend fun contains(eventId: String): Boolean = withContext(Dispatchers.IO) {
        dao.exists(eventId)
    }

    override suspend fun readAllForReplay(): List<StoredClockEvent> = withContext(Dispatchers.IO) {
        dao.readAll().map { it.toStoredEvent() }
    }

    override suspend fun listSince(
        cursorExclusive: ClockEventCursor?,
        limit: Int,
    ): List<StoredClockEvent> {
        require(limit > 0) { "List limit must be > 0." }
        return withContext(Dispatchers.IO) {
            dao.listSince(cursorExclusive?.value ?: 0L, limit).map { it.toStoredEvent() }
        }
    }

    override suspend fun readSnapshot(): ClockEventStoreSnapshot {
        return withContext(Dispatchers.IO) {
            val lastCursor = dao.findLastCursor()?.let(::ClockEventCursor)
            val lastSyncedCursor = dao.readSyncState()?.lastSyncedCursor?.let(::ClockEventCursor)
            val pendingSyncCount = dao.countAfter(lastSyncedCursor?.value ?: 0L)
            ClockEventStoreSnapshot(
                lastCursor = lastCursor,
                lastSyncedCursor = lastSyncedCursor,
                pendingSyncCount = pendingSyncCount,
            )
        }
    }

    override suspend fun updateSyncCheckpoint(cursorInclusive: ClockEventCursor) {
        withContext(Dispatchers.IO) {
            require(dao.cursorExists(cursorInclusive.value)) {
                "Cannot update sync checkpoint for unknown cursor ${cursorInclusive.value}."
            }
            dao.saveSyncState(ClockEventSyncStateEntity(lastSyncedCursor = cursorInclusive.value))
        }
    }

    companion object {
        fun create(appContext: Context): RoomClockEventStore {
            val database = Room.databaseBuilder(
                appContext,
                ClockEventDatabase::class.java,
                "clock-events.db",
            ).build()
            return RoomClockEventStore(database.dao())
        }
    }
}

internal fun ClockEvent.toEntity(): ClockEventEntity = ClockEventEntity(
    eventId = eventId,
    schema = schema,
    eventType = eventType.wireValue,
    deviceId = deviceId,
    createdAt = createdAt.toString(),
    logicalDay = logicalDay.toString(),
    fileName = fileName,
    headingPath = headingPath.toString(),
    causalKind = causalOrder.kind,
    causalCounter = causalOrder.counter,
)

internal fun ClockEventEntity.toStoredEvent(): StoredClockEvent = StoredClockEvent(
    cursor = ClockEventCursor(seq),
    event = ClockEvent(
        schema = schema,
        eventId = eventId,
        eventType = ClockEventType.fromWireValue(eventType)
            ?: error("Unsupported event type in Room store."),
        deviceId = deviceId,
        createdAt = Instant.parse(createdAt),
        logicalDay = LocalDate.parse(logicalDay),
        fileName = fileName,
        headingPath = HeadingPath.parse(headingPath),
        causalOrder = ClockEventCausalOrder(
            kind = causalKind,
            counter = causalCounter,
        ),
    ),
)
