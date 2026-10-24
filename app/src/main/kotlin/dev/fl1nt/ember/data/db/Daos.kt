package dev.fl1nt.ember.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {
    @Insert
    suspend fun insert(uploads: List<UploadEntity>)

    @Update
    suspend fun update(upload: UploadEntity)

    @Query("SELECT * FROM uploads WHERE id = :id")
    suspend fun get(id: String): UploadEntity?

    @Query("SELECT * FROM uploads WHERE id = :id")
    fun observe(id: String): Flow<UploadEntity?>

    @Query("SELECT * FROM uploads ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE batchId = :batchId ORDER BY createdAt, fileName")
    fun observeBatch(batchId: String): Flow<List<UploadEntity>>

    /** The next queued share, paste, or picker upload. Sync uploads run in their own worker. */
    @Query("SELECT * FROM uploads WHERE status = 'QUEUED' AND origin != 'SYNC' ORDER BY createdAt LIMIT 1")
    suspend fun nextQueued(): UploadEntity?

    @Query("SELECT * FROM uploads WHERE syncSourceId = :sourceId AND syncItemKey = :key ORDER BY createdAt DESC LIMIT 1")
    suspend fun findSyncUpload(sourceId: Long, key: String): UploadEntity?

    @Query("SELECT * FROM uploads WHERE syncSourceId = :sourceId ORDER BY createdAt DESC LIMIT :limit")
    fun observeForSource(sourceId: Long, limit: Int): Flow<List<UploadEntity>>

    @Query("SELECT COUNT(*) FROM uploads WHERE status IN ('QUEUED', 'RUNNING') AND origin != 'SYNC'")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM uploads WHERE status = 'QUEUED' AND origin != 'SYNC'")
    suspend fun queuedCount(): Int

    @Query("SELECT COUNT(*) FROM uploads WHERE status IN ('QUEUED', 'RUNNING')")
    fun observeActiveCount(): Flow<Int>

    /** Uploads interrupted by process death go back to the queue. */
    @Query("UPDATE uploads SET status = 'QUEUED', bytesSent = 0 WHERE status = 'RUNNING' AND origin != 'SYNC'")
    suspend fun requeueInterrupted()

    /** The same for sync uploads, which only the sync engine runs. */
    @Query("UPDATE uploads SET status = 'QUEUED', bytesSent = 0 WHERE status = 'RUNNING' AND origin = 'SYNC'")
    suspend fun requeueInterruptedSync()

    /** Unfinished sync uploads whose folder was removed can never run, so they go. */
    @Query(
        "DELETE FROM uploads WHERE origin = 'SYNC' AND status != 'DONE' " +
            "AND (syncSourceId IS NULL OR syncSourceId NOT IN (SELECT id FROM sync_sources))",
    )
    suspend fun deleteOrphanedSync()

    @Query("SELECT * FROM uploads WHERE syncSourceId = :sourceId AND status = 'QUEUED'")
    suspend fun queuedForSource(sourceId: Long): List<UploadEntity>

    @Query("DELETE FROM uploads WHERE syncSourceId = :sourceId AND status IN ('QUEUED', 'FAILED', 'CANCELLED')")
    suspend fun deleteUnfinishedForSource(sourceId: Long)

    @Query("SELECT id FROM uploads WHERE stagedPath IS NOT NULL")
    suspend fun stagedIds(): List<String>

    @Query("UPDATE uploads SET bytesSent = :bytes, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, bytes: Long, now: Long)

    @Query("SELECT * FROM uploads WHERE status IN ('DONE', 'FAILED', 'CANCELLED') AND stagedPath IS NOT NULL")
    suspend fun finishedWithStagedFiles(): List<UploadEntity>

    @Query("DELETE FROM uploads WHERE status IN ('DONE', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()

    @Query("DELETE FROM uploads WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SyncSourceDao {
    @Query("SELECT * FROM sync_sources ORDER BY createdAt")
    fun observeAll(): Flow<List<SyncSourceEntity>>

    @Query("SELECT * FROM sync_sources ORDER BY createdAt")
    suspend fun all(): List<SyncSourceEntity>

    @Query("SELECT * FROM sync_sources WHERE id = :id")
    suspend fun get(id: Long): SyncSourceEntity?

    @Query("SELECT * FROM sync_sources WHERE id = :id")
    fun observe(id: Long): Flow<SyncSourceEntity?>

    @Insert
    suspend fun insert(source: SyncSourceEntity): Long

    @Update
    suspend fun update(source: SyncSourceEntity)

    @Query("UPDATE sync_sources SET lastScanAt = :at, pendingCount = :pending, lastError = :error WHERE id = :id")
    suspend fun recordScan(id: Long, at: Long, pending: Int, error: String?)

    @Query("DELETE FROM sync_sources WHERE id = :id")
    suspend fun delete(id: Long)
}

data class LedgerCounts(val uploaded: Int, val failed: Int)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM sync_ledger WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<LedgerEntity>

    @Upsert
    suspend fun upsert(entry: LedgerEntity)

    @Query("SELECT * FROM sync_ledger WHERE sourceId = :sourceId AND itemKey = :itemKey")
    suspend fun get(sourceId: Long, itemKey: String): LedgerEntity?

    @Query("DELETE FROM sync_ledger WHERE sourceId = :sourceId AND itemKey = :itemKey")
    suspend fun delete(sourceId: Long, itemKey: String)

    @Query(
        "SELECT SUM(CASE WHEN state = 'UPLOADED' THEN 1 ELSE 0 END) AS uploaded, " +
            "SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END) AS failed FROM sync_ledger WHERE sourceId = :sourceId",
    )
    fun observeCounts(sourceId: Long): Flow<LedgerCounts?>

    @Query("SELECT * FROM sync_ledger WHERE sourceId = :sourceId AND state = 'FAILED' ORDER BY updatedAt DESC")
    fun observeFailures(sourceId: Long): Flow<List<LedgerEntity>>

    /** Lets every failed file in a source try again on the next sync. */
    @Query("DELETE FROM sync_ledger WHERE sourceId = :sourceId AND state = 'FAILED'")
    suspend fun clearFailures(sourceId: Long)

    @Query("DELETE FROM sync_ledger WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

@Dao
interface ShortLinkDao {
    @Query("SELECT * FROM short_links ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ShortLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: ShortLinkEntity)

    @Transaction
    suspend fun replaceAll(links: List<ShortLinkEntity>) {
        clear()
        links.forEach { upsert(it) }
    }

    @Query("DELETE FROM short_links")
    suspend fun clear()

    @Query("DELETE FROM short_links WHERE id = :id")
    suspend fun delete(id: String)
}
