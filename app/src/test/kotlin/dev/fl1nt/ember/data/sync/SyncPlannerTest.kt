package dev.fl1nt.ember.data.sync

import com.google.common.truth.Truth.assertThat
import dev.fl1nt.ember.data.db.LedgerEntity
import dev.fl1nt.ember.data.db.LedgerState
import dev.fl1nt.ember.data.db.SourceType
import org.junit.Test

class SyncPlannerTest {

    private val now = 1_000_000_000L

    private fun item(key: String, name: String = "$key.jpg", added: Long = now - 60_000, modified: Long = added, size: Long? = 1024) =
        SourceItem(key = key, name = name, mimeType = "image/jpeg", size = size, uri = "content://media/$key", addedAtMillis = added, modifiedAtMillis = modified)

    private fun ledger(key: String, state: LedgerState) = key to LedgerEntity(1, key, "fp", state, null, updatedAt = now)

    @Test
    fun `uploads new files oldest first`() {
        val plan = SyncPlanner.plan(SourceType.MEDIA_ALBUM, 0, listOf(item("b", added = now - 1_000_00), item("a", added = now - 2_000_00)), emptyMap(), now)
        assertThat(plan.toUpload.map { it.key }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `skips files from before the source was added when only new files sync`() {
        val since = now - 30_000
        val plan = SyncPlanner.plan(
            SourceType.MEDIA_ALBUM,
            since,
            listOf(item("old", added = since - 1), item("new", added = since + 1, modified = since + 1)),
            emptyMap(),
            now,
        )
        assertThat(plan.toUpload.map { it.key }).containsExactly("new")
    }

    @Test
    fun `never repeats uploaded or permanently failed files`() {
        val plan = SyncPlanner.plan(
            SourceType.FOLDER,
            0,
            listOf(item("done"), item("failed"), item("fresh")),
            mapOf(ledger("done", LedgerState.UPLOADED), ledger("failed", LedgerState.FAILED)),
            now,
        )
        assertThat(plan.toUpload.map { it.key }).containsExactly("fresh")
    }

    @Test
    fun `waits for files that are still changing`() {
        val media = SyncPlanner.plan(SourceType.MEDIA_ALBUM, 0, listOf(item("writing", modified = now - 1_000)), emptyMap(), now)
        assertThat(media.toUpload).isEmpty()
        assertThat(media.settling).isEqualTo(1)

        // Folders have no in-progress flag, so they wait longer.
        val folder = SyncPlanner.plan(SourceType.FOLDER, 0, listOf(item("doc", modified = now - 10_000)), emptyMap(), now)
        assertThat(folder.toUpload).isEmpty()
        val settled = SyncPlanner.plan(SourceType.FOLDER, 0, listOf(item("doc", modified = now - 31_000)), emptyMap(), now)
        assertThat(settled.toUpload.map { it.key }).containsExactly("doc")
    }

    @Test
    fun `ignores hidden, partial and empty files`() {
        val plan = SyncPlanner.plan(
            SourceType.FOLDER,
            0,
            listOf(
                item("1", name = ".nomedia"),
                item("2", name = ".pending-123-IMG.jpg"),
                item("3", name = "video.mp4.part"),
                item("4", name = "download.crdownload"),
                item("5", name = "empty.txt", size = 0),
                item("6", name = "report.pdf"),
                item("7", name = "unknown-size.bin", size = null),
            ),
            emptyMap(),
            now,
        )
        assertThat(plan.toUpload.map { it.name }).containsExactly("report.pdf", "unknown-size.bin")
    }

    @Test
    fun `fingerprints change when a file is modified`() {
        assertThat(item("a", modified = 1).fingerprint).isNotEqualTo(item("a", modified = 2).fingerprint)
    }
}
