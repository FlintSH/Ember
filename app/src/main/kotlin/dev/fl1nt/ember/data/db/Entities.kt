package dev.fl1nt.ember.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where an upload came from. */
enum class UploadOrigin { SHARE, PICKER, PASTE, TEXT_SELECTION, CLIPBOARD, SYNC }

enum class UploadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** Why an upload failed, so Ember can say what to do next. */
enum class FailureKind {
    /** The token was rejected. Reconnect Ember. */
    AUTH,

    /** The Flare account's roles or a bound profile forbid it. */
    PERMISSION,

    /** Bigger than the instance allows, or over quota. */
    TOO_LARGE,

    /** Flare rejected the request itself, such as a file type mismatch. */
    REJECTED,

    /** The original file is gone, such as after it was deleted. */
    SOURCE_GONE,

    /** Ember lost permission to read the original file. */
    NO_ACCESS,

    /** Temporary: offline, rate limited, or the server is down. Retried automatically. */
    TRANSIENT,

    OTHER,
}

/** One file Ember sent, or will send, to Flare. */
@Entity(
    tableName = "uploads",
    indices = [Index("status"), Index("createdAt"), Index("batchId"), Index(value = ["syncSourceId", "syncItemKey"])],
)
data class UploadEntity(
    @PrimaryKey val id: String,
    val batchId: String?,
    val origin: UploadOrigin,
    val isPaste: Boolean,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    /** A file Ember copied into its own storage, for shares and pastes. */
    val stagedPath: String?,
    /** A content URI Ember can read directly, for synced files. */
    val sourceUri: String?,
    val syncSourceId: Long?,
    val syncItemKey: String?,
    /** The resolved [dev.fl1nt.ember.data.settings.UploadPreset] for this upload. */
    val presetJson: String,
    /** An optional file password, encrypted like the token. Kept until the upload finishes or is removed, so a retry still sends it. */
    val sealedPassword: String?,
    val status: UploadStatus,
    val bytesSent: Long = 0,
    val attempts: Int = 0,
    val failureKind: FailureKind? = null,
    val failureMessage: String? = null,
    val pageUrl: String? = null,
    val rawUrl: String? = null,
    val copyLink: Boolean = false,
    val notify: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
)

enum class SourceType {
    /** A photo and video album from MediaStore, such as Camera or Screenshots. */
    MEDIA_ALBUM,

    /** Any folder chosen through the system picker. */
    FOLDER,
}

/** A folder Ember watches and uploads from. */
@Entity(tableName = "sync_sources")
data class SyncSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SourceType,
    val name: String,
    /** MediaStore bucket for albums. */
    val bucketId: String? = null,
    /** A readable location, such as `DCIM/Camera`. */
    val location: String? = null,
    /** The persisted document tree for folders. */
    val treeUri: String? = null,
    val includeSubfolders: Boolean = true,
    val includeImages: Boolean = true,
    val includeVideos: Boolean = true,
    val enabled: Boolean = true,
    /** Files older than this are never uploaded. `0` uploads everything already there. */
    val sinceMillis: Long,
    val presetJson: String,
    /** Copy each new file's link, like a screenshot tool. */
    val copyLink: Boolean = false,
    /** Notify for every uploaded file instead of a summary. */
    val notifyEach: Boolean = false,
    val createdAt: Long,
    val lastScanAt: Long? = null,
    val pendingCount: Int = 0,
    val lastError: String? = null,
)

enum class LedgerState { UPLOADED, FAILED, SKIPPED }

/** Remembers each synced file so it's uploaded once. */
@Entity(tableName = "sync_ledger", primaryKeys = ["sourceId", "itemKey"], indices = [Index("sourceId")])
data class LedgerEntity(
    val sourceId: Long,
    val itemKey: String,
    val fingerprint: String,
    val state: LedgerState,
    val uploadId: String?,
    val attempts: Int = 0,
    val message: String? = null,
    val updatedAt: Long,
)

/** Short links created from this device, so they're listed even without `urls:read`. */
@Entity(tableName = "short_links", indices = [Index("createdAt")])
data class ShortLinkEntity(
    @PrimaryKey val id: String,
    val shortCode: String,
    val targetUrl: String,
    val shortUrl: String,
    @ColumnInfo(defaultValue = "0") val clicks: Long = 0,
    val createdAt: Long,
)
