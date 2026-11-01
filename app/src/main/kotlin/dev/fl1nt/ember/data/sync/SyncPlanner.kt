package dev.fl1nt.ember.data.sync

import dev.fl1nt.ember.data.db.LedgerEntity
import dev.fl1nt.ember.data.db.SourceType

/** A file found in a synced folder. */
data class SourceItem(
    /** Stable within its source: a MediaStore ID or a document ID. */
    val key: String,
    val name: String,
    val mimeType: String?,
    val size: Long?,
    val uri: String,
    val addedAtMillis: Long,
    val modifiedAtMillis: Long,
) {
    val fingerprint: String get() = "${size ?: -1}:$modifiedAtMillis"
}

/** What a sync run should do for one source. */
data class SyncPlan(
    val toUpload: List<SourceItem>,
    /** New files that are still being written; picked up on a later run. */
    val settling: Int,
)

/** The rules for which files a sync uploads. Pure, so it's easy to test. */
object SyncPlanner {
    /**
     * Android 10 and later hide MediaStore entries that are still being written, so
     * albums only need a settle window on older versions.
     */
    const val LEGACY_MEDIA_SETTLE_MILLIS = 5_000L

    /** Folders have no "still writing" flag, so wait until a file has stopped changing. */
    const val FOLDER_SETTLE_MILLIS = 30_000L

    /** How long a file must stay unchanged before it uploads. */
    fun settleMillis(type: SourceType, sdk: Int): Long = when {
        type == SourceType.FOLDER -> FOLDER_SETTLE_MILLIS
        sdk >= 29 -> 0
        else -> LEGACY_MEDIA_SETTLE_MILLIS
    }

    fun plan(
        type: SourceType,
        sinceMillis: Long,
        items: List<SourceItem>,
        ledger: Map<String, LedgerEntity>,
        now: Long,
        settleMillis: Long = settleMillis(type, sdk = 29),
        /** Files with an upload waiting to retry or resume. They finish whatever the start. */
        started: Set<String> = emptySet(),
    ): SyncPlan {
        var settling = 0
        val toUpload = items.asSequence()
            .filter { isWanted(it) }
            .filter { isAfterStart(sinceMillis, it) || it.key in started }
            // Uploaded or permanently failed files never repeat. "Retry failed" clears failures.
            .filter { ledger[it.key] == null }
            .filter { item ->
                // A date far in the future, like from a camera with a wrong clock, says nothing
                // about whether the file is still changing. Without this it would wait forever.
                val fresh = item.modifiedAtMillis <= now + FUTURE_TOLERANCE_MILLIS && now - item.modifiedAtMillis < settleMillis
                if (fresh) settling++
                !fresh
            }
            .sortedWith(compareBy<SourceItem> { it.addedAtMillis }.thenBy { it.name })
            .toList()
        return SyncPlan(toUpload, settling)
    }

    /**
     * Whether [item] is new enough for a source that starts at [sinceMillis]: `0` for
     * everything already there, or the time it was added for new files only.
     */
    fun isAfterStart(sinceMillis: Long, item: SourceItem): Boolean = sinceMillis <= 0 || item.addedAtMillis >= wholeSecond(sinceMillis)

    /** Whether [item] was already in a folder when it was added at [createdAt]. */
    fun wasAlreadyThere(createdAt: Long, item: SourceItem): Boolean = item.addedAtMillis < wholeSecond(createdAt)

    /** MediaStore records whole seconds, so a photo taken in the second a folder was added still counts as new. */
    private fun wholeSecond(millis: Long): Long = millis - millis % 1000

    private fun isWanted(item: SourceItem): Boolean {
        if (item.size == 0L) return false
        val name = item.name
        // Hidden files, Android's in-progress and trashed files, and common partial downloads.
        if (name.startsWith(".")) return false
        val lower = name.lowercase()
        return PARTIAL_SUFFIXES.none { lower.endsWith(it) }
    }

    private val PARTIAL_SUFFIXES = listOf(".tmp", ".part", ".crdownload", ".download", ".partial")

    private const val FUTURE_TOLERANCE_MILLIS = 60_000L
}
