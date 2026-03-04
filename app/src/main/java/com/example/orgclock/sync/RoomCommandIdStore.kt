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
import java.util.concurrent.TimeUnit

@Entity(tableName = "processed_command_ids")
internal data class ProcessedCommandIdEntity(
    @PrimaryKey val commandId: String,
    val processedAtEpochMs: Long,
)

@Dao
internal interface ProcessedCommandIdDao {
    @Query("SELECT EXISTS(SELECT 1 FROM processed_command_ids WHERE commandId = :commandId)")
    fun exists(commandId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entity: ProcessedCommandIdEntity)

    @Query("DELETE FROM processed_command_ids WHERE processedAtEpochMs < :epochMs")
    fun deleteOlderThan(epochMs: Long): Int

    @Query("SELECT COUNT(*) FROM processed_command_ids")
    fun count(): Long

    @Query(
        "DELETE FROM processed_command_ids " +
            "WHERE commandId IN (" +
            "SELECT commandId FROM processed_command_ids " +
            "ORDER BY processedAtEpochMs ASC LIMIT :limit" +
            ")",
    )
    fun deleteOldest(limit: Int): Int
}

@Database(
    entities = [ProcessedCommandIdEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class ProcessedCommandIdDatabase : RoomDatabase() {
    abstract fun dao(): ProcessedCommandIdDao
}

internal class RoomCommandIdStore(
    private val dao: ProcessedCommandIdDao,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val retentionMs: Long = TimeUnit.DAYS.toMillis(7),
    private val maxRows: Long = 20_000L,
) : CommandIdStore {
    override fun contains(commandId: String): Boolean = dao.exists(commandId)

    override fun markProcessed(commandId: String) {
        dao.insert(
            ProcessedCommandIdEntity(
                commandId = commandId,
                processedAtEpochMs = nowEpochMs(),
            ),
        )
        pruneInternal()
    }

    override fun pruneOlderThan(epochMs: Long): Int = dao.deleteOlderThan(epochMs)

    override fun size(): Long = dao.count()

    private fun pruneInternal() {
        pruneOlderThan(nowEpochMs() - retentionMs)
        val overflow = size() - maxRows
        if (overflow > 0) {
            dao.deleteOldest(overflow.toInt())
        }
    }

    companion object {
        fun create(appContext: Context): RoomCommandIdStore {
            val database = Room.databaseBuilder(
                appContext,
                ProcessedCommandIdDatabase::class.java,
                "sync-processed-command-ids.db",
            ).build()
            return RoomCommandIdStore(database.dao())
        }
    }
}
