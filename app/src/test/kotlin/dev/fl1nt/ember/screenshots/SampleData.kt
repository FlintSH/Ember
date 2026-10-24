package dev.fl1nt.ember.screenshots

import android.net.Uri
import dev.fl1nt.ember.api.Access
import dev.fl1nt.ember.api.Capabilities
import dev.fl1nt.ember.api.FlareCredential
import dev.fl1nt.ember.api.FlareOrigin
import dev.fl1nt.ember.api.RemoteFile
import dev.fl1nt.ember.api.RemoteFolder
import dev.fl1nt.ember.api.RemoteTag
import dev.fl1nt.ember.api.Visibility
import dev.fl1nt.ember.data.account.Account
import dev.fl1nt.ember.data.db.FailureKind
import dev.fl1nt.ember.data.db.LedgerCounts
import dev.fl1nt.ember.data.db.ShortLinkEntity
import dev.fl1nt.ember.data.db.SourceType
import dev.fl1nt.ember.data.db.SyncSourceEntity
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.data.sync.MediaAlbum
import dev.fl1nt.ember.ui.sync.SourceSummary

/** Demonstration data for screenshots. Nothing here is a real account or file. */
object SampleData {
    val now: Long = System.currentTimeMillis()
    private const val MINUTE = 60_000L

    val capabilities = Capabilities(Access.GRANTED, Access.GRANTED, Access.GRANTED, Access.GRANTED, Access.NOT_IN_TOKEN)

    val account = Account(
        origin = FlareOrigin.parse("files.northstar.example"),
        displayName = "Northstar",
        credentialKind = FlareCredential.Kind.NAMED_TOKEN,
        capabilities = capabilities,
        publicOrigin = null,
        connectedAt = now - 3 * 24 * 60 * MINUTE,
        checkedAt = now - 5 * MINUTE,
    )

    private fun upload(
        id: String,
        name: String,
        mime: String,
        size: Long,
        origin: UploadOrigin,
        status: UploadStatus,
        minutesAgo: Long,
        sent: Long = 0,
        failure: FailureKind? = null,
        message: String? = null,
    ) = UploadEntity(
        id = id,
        batchId = null,
        origin = origin,
        isPaste = origin == UploadOrigin.PASTE || origin == UploadOrigin.TEXT_SELECTION,
        fileName = name,
        mimeType = mime,
        sizeBytes = size,
        stagedPath = null,
        sourceUri = null,
        syncSourceId = null,
        syncItemKey = null,
        presetJson = UploadPreset().encode(),
        sealedPassword = null,
        status = status,
        bytesSent = sent,
        failureKind = failure,
        failureMessage = message,
        pageUrl = if (status == UploadStatus.DONE) "https://files.northstar.example/alex/$name" else null,
        rawUrl = if (status == UploadStatus.DONE) "https://files.northstar.example/api/files/alex/$name" else null,
        createdAt = now - minutesAgo * MINUTE,
        updatedAt = now - minutesAgo * MINUTE,
        completedAt = if (status == UploadStatus.DONE || status == UploadStatus.FAILED) now - minutesAgo * MINUTE else null,
    )

    val uploads = listOf(
        upload("1", "Screenshot_20261023_214512.png", "image/png", 812_000, UploadOrigin.SYNC, UploadStatus.RUNNING, 0, sent = 530_000),
        upload("2", "release-notes.md", "text/plain", 4_200, UploadOrigin.TEXT_SELECTION, UploadStatus.DONE, 3),
        upload("3", "IMG_20261023_183344.jpg", "image/jpeg", 3_400_000, UploadOrigin.SYNC, UploadStatus.DONE, 42),
        upload("4", "invoice-october.pdf", "application/pdf", 245_000, UploadOrigin.SHARE, UploadStatus.DONE, 95),
        upload("5", "build-logs.zip", "application/zip", 182_000_000, UploadOrigin.SHARE, UploadStatus.FAILED, 130, failure = FailureKind.TOO_LARGE, message = "The file exceeds the upload size limit."),
        upload("6", "trip-clip.mp4", "video/mp4", 48_000_000, UploadOrigin.PICKER, UploadStatus.DONE, 300),
    )

    val sources = listOf(
        SyncSourceEntity(id = 1, type = SourceType.MEDIA_ALBUM, name = "Screenshots", bucketId = "1", location = "Pictures/Screenshots", sinceMillis = 0, presetJson = UploadPreset().encode(), copyLink = true, notifyEach = true, createdAt = now, lastScanAt = now - 2 * MINUTE, pendingCount = 1),
        SyncSourceEntity(id = 2, type = SourceType.MEDIA_ALBUM, name = "Camera", bucketId = "2", location = "DCIM/Camera", sinceMillis = now, presetJson = UploadPreset(visibility = Visibility.PRIVATE).encode(), createdAt = now, lastScanAt = now - 2 * MINUTE),
        SyncSourceEntity(id = 3, type = SourceType.FOLDER, name = "Recordings", location = "Recordings", treeUri = "content://tree", sinceMillis = now, presetJson = UploadPreset().encode(), enabled = false, createdAt = now),
    )

    val summaries = listOf(
        SourceSummary(sources[0], LedgerCounts(uploaded = 214, failed = 0)),
        SourceSummary(sources[1], LedgerCounts(uploaded = 1_480, failed = 2)),
        SourceSummary(sources[2], LedgerCounts(uploaded = 37, failed = 0)),
    )

    val links = listOf(
        ShortLinkEntity("l1", "aB3_dE", "https://github.com/FlintSH/Flare/releases/tag/v3.0.0", "https://files.northstar.example/u/aB3_dE", 128, now - 60 * MINUTE),
        ShortLinkEntity("l2", "Qx9-pL", "https://flintsh.github.io/Flare/guide/uploading.html", "https://files.northstar.example/u/Qx9-pL", 17, now - 26 * 60 * MINUTE),
        ShortLinkEntity("l3", "m4Z7sT", "https://example.com/a/very/long/path/that/needs/shortening?with=query&params=true", "https://files.northstar.example/u/m4Z7sT", 0, now - 4 * 24 * 60 * MINUTE),
    )

    /** An ISO timestamp [minutes] before now, so relative times render the same on every run. */
    private fun ago(minutes: Long): String = java.time.Instant.ofEpochMilli(now - minutes * MINUTE).toString()

    val remoteFiles = listOf(
        RemoteFile("f1", "IMG_20261023_183344.jpg", "/alex/IMG_20261023_183344.jpg", "image/jpeg", 3.2, ago(42), "PRIVATE", tags = listOf(RemoteTag("t", "Camera"))),
        RemoteFile("f2", "invoice-october.pdf", "/alex/invoice-october.pdf", "application/pdf", 0.23, ago(95), "PUBLIC", hasPassword = true),
        RemoteFile("f3", "release-notes.md", "/alex/release-notes.md", "text/plain", 0.004, ago(3), "PUBLIC"),
        RemoteFile("f4", "trip-clip.mp4", "/alex/trip-clip.mp4", "video/mp4", 45.8, ago(26 * 60), "PUBLIC"),
        RemoteFile("f5", "dotfiles.tar.gz", "/alex/dotfiles.tar.gz", "application/gzip", 1.4, ago(4 * 24 * 60), "PRIVATE"),
    )

    val folders = listOf(RemoteFolder("a", "Screenshots"), RemoteFolder("b", "Projects"), RemoteFolder("c", "Website", parentId = "b"))

    val albums = listOf(
        MediaAlbum("1", "Camera", "DCIM/Camera", 1_482, null, now),
        MediaAlbum("2", "Screenshots", "Pictures/Screenshots", 215, null, now),
        MediaAlbum("3", "WhatsApp Images", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 3_120, null, now - 60 * MINUTE),
        MediaAlbum("4", "Download", "Download", 64, null, now - 3 * 60 * MINUTE),
    )

    val sharedUri: Uri = Uri.parse("content://media/external/images/media/42")
}
