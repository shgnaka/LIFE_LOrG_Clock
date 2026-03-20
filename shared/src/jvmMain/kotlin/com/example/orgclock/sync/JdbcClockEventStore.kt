package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class JdbcClockEventStore(
    private val connectionFactory: () -> Connection,
) : ClockEventStore {

    init {
        initializeSchema()
    }

    override suspend fun append(event: ClockEvent): AppendClockEventResult = try {
        withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO clock_events(
                    event_id,
                    schema,
                    event_type,
                    device_id,
                    created_at,
                    logical_day,
                    file_name,
                    heading_path,
                    causal_kind,
                    causal_counter
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                PreparedStatement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.bindClockEvent(event)
                statement.executeUpdate()
                statement.generatedKeys.use { generatedKeys ->
                    if (!generatedKeys.next()) {
                        error("Clock event insert succeeded but generated cursor was missing.")
                    }
                    AppendClockEventResult.Appended(ClockEventCursor(generatedKeys.getLong(1)))
                }
            }
        }
    } catch (error: SQLException) {
        if (!error.isUniqueConstraintViolation()) throw error
        AppendClockEventResult.Duplicate(findCursorByEventId(event.eventId))
    }

    override suspend fun contains(eventId: String): Boolean = withConnection { connection ->
        connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM clock_events WHERE event_id = ?)",
        ).use { statement ->
            statement.setString(1, eventId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1) != 0
            }
        }
    }

    override suspend fun readAllForReplay(): List<StoredClockEvent> = withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT seq, event_id, schema, event_type, device_id, created_at, logical_day, file_name,
                   heading_path, causal_kind, causal_counter
            FROM clock_events
            ORDER BY seq ASC
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use(::mapStoredEvents)
        }
    }

    override suspend fun listSince(
        cursorExclusive: ClockEventCursor?,
        limit: Int,
    ): List<StoredClockEvent> {
        require(limit > 0) { "List limit must be > 0." }
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT seq, event_id, schema, event_type, device_id, created_at, logical_day, file_name,
                       heading_path, causal_kind, causal_counter
                FROM clock_events
                WHERE seq > ?
                ORDER BY seq ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, cursorExclusive?.value ?: 0L)
                statement.setInt(2, limit)
                statement.executeQuery().use(::mapStoredEvents)
            }
        }
    }

    override suspend fun readSnapshot(): ClockEventStoreSnapshot = withConnection { connection ->
        val lastCursor = connection.queryCursor("SELECT MAX(seq) FROM clock_events")
        val lastSyncedCursor = connection.queryCursor(
            "SELECT last_synced_cursor FROM clock_event_sync_state WHERE id = 1",
        )
        val pendingSyncCount = connection.prepareStatement(
            "SELECT COUNT(*) FROM clock_events WHERE seq > ?",
        ).use { statement ->
            statement.setLong(1, lastSyncedCursor?.value ?: 0L)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }
        ClockEventStoreSnapshot(
            lastCursor = lastCursor,
            lastSyncedCursor = lastSyncedCursor,
            pendingSyncCount = pendingSyncCount,
        )
    }

    override suspend fun updateSyncCheckpoint(cursorInclusive: ClockEventCursor) {
        withConnection { connection ->
            require(connection.cursorExists(cursorInclusive)) {
                "Cannot update sync checkpoint for unknown cursor ${cursorInclusive.value}."
            }
            connection.prepareStatement(
                """
                INSERT INTO clock_event_sync_state(id, last_synced_cursor)
                VALUES (1, ?)
                ON CONFLICT(id) DO UPDATE SET last_synced_cursor = excluded.last_synced_cursor
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, cursorInclusive.value)
                statement.executeUpdate()
            }
        }
    }

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS clock_events(
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT NOT NULL UNIQUE,
                        schema TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        logical_day TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        heading_path TEXT NOT NULL,
                        causal_kind TEXT NOT NULL,
                        causal_counter INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS clock_event_sync_state(
                        id INTEGER PRIMARY KEY CHECK(id = 1),
                        last_synced_cursor INTEGER
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun findCursorByEventId(eventId: String): ClockEventCursor? = withConnection { connection ->
        connection.prepareStatement(
            "SELECT seq FROM clock_events WHERE event_id = ?",
        ).use { statement ->
            statement.setString(1, eventId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    ClockEventCursor(resultSet.getLong(1))
                }
            }
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T = connectionFactory().use(block)

    private fun mapStoredEvents(resultSet: ResultSet): List<StoredClockEvent> {
        val events = mutableListOf<StoredClockEvent>()
        while (resultSet.next()) {
            events += StoredClockEvent(
                cursor = ClockEventCursor(resultSet.getLong("seq")),
                event = ClockEvent(
                    schema = resultSet.getString("schema"),
                    eventId = resultSet.getString("event_id"),
                    eventType = ClockEventType.fromWireValue(resultSet.getString("event_type"))
                        ?: error("Unsupported event type in store."),
                    deviceId = resultSet.getString("device_id"),
                    createdAt = Instant.parse(resultSet.getString("created_at")),
                    logicalDay = LocalDate.parse(resultSet.getString("logical_day")),
                    fileName = resultSet.getString("file_name"),
                    headingPath = HeadingPath.parse(resultSet.getString("heading_path")),
                    causalOrder = ClockEventCausalOrder(
                        kind = resultSet.getString("causal_kind"),
                        counter = resultSet.getLong("causal_counter"),
                    ),
                ),
            )
        }
        return events
    }

    private fun PreparedStatement.bindClockEvent(event: ClockEvent) {
        setString(1, event.eventId)
        setString(2, event.schema)
        setString(3, event.eventType.wireValue)
        setString(4, event.deviceId)
        setString(5, event.createdAt.toString())
        setString(6, event.logicalDay.toString())
        setString(7, event.fileName)
        setString(8, event.headingPath.toString())
        setString(9, event.causalOrder.kind)
        setLong(10, event.causalOrder.counter)
    }

    private fun Connection.queryCursor(sql: String): ClockEventCursor? = prepareStatement(sql).use { statement ->
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                return@use null
            }
            val raw = resultSet.getLong(1)
            if (resultSet.wasNull()) null else ClockEventCursor(raw)
        }
    }

    private fun Connection.cursorExists(cursor: ClockEventCursor): Boolean = prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM clock_events WHERE seq = ?)",
    ).use { statement ->
        statement.setLong(1, cursor.value)
        statement.executeQuery().use { resultSet ->
            resultSet.next()
            resultSet.getInt(1) != 0
        }
    }

    private fun SQLException.isUniqueConstraintViolation(): Boolean {
        val message = message.orEmpty()
        return message.contains("UNIQUE constraint failed", ignoreCase = true) ||
            message.contains("SQLITE_CONSTRAINT_UNIQUE", ignoreCase = true)
    }

    companion object {
        fun create(databasePath: Path): JdbcClockEventStore {
            Class.forName("org.sqlite.JDBC")
            val normalized = databasePath.toAbsolutePath().normalize()
            normalized.parent?.toFile()?.mkdirs()
            return JdbcClockEventStore {
                DriverManager.getConnection("jdbc:sqlite:$normalized")
            }
        }
    }
}
