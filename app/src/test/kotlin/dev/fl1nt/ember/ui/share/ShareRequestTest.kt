package dev.fl1nt.ember.ui.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareRequestTest {

    private val photo = "content://media/external/images/media/42".toUri()
    private val doc = "content://com.android.providers.downloads.documents/document/7".toUri()

    @Test
    fun `a single shared file`() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, photo)
        assertThat(ShareRequest.from(intent)).isEqualTo(ShareRequest.Files(listOf(photo)))
    }

    @Test
    fun `several files, including ones only in clip data`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>(photo))
        intent.clipData = ClipData.newRawUri("doc", doc).apply { addItem(ClipData.Item(photo)) }
        assertThat(ShareRequest.from(intent)).isEqualTo(ShareRequest.Files(listOf(photo, doc)))
    }

    @Test
    fun `file paths are refused because they would be read with Ember's permissions`() {
        val inside = Uri.parse("file:///data/data/dev.fl1nt.ember/files/datastore/account.preferences_pb")
        val only = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, inside)
        assertThat(ShareRequest.from(only)).isEqualTo(ShareRequest.Unsupported(rejectedFiles = 1))

        val mixed = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, inside))
        assertThat(ShareRequest.from(mixed)).isEqualTo(ShareRequest.Files(listOf(photo), rejected = 1))
    }

    @Test
    fun `shared and selected text`() {
        val shared = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com")
        assertThat(ShareRequest.from(shared)).isEqualTo(ShareRequest.Text("https://example.com", ShareRequest.TextMode.SHARE))

        val editable = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "some log").putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        assertThat(ShareRequest.from(editable)).isEqualTo(ShareRequest.Text("some log", ShareRequest.TextMode.SELECTION, readOnly = false))

        val readOnly = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "quote")
        assertThat((ShareRequest.from(readOnly) as ShareRequest.Text).readOnly).isTrue()
    }

    @Test
    fun `the paste tile and empty shares`() {
        assertThat(ShareRequest.from(Intent(ShareActivity.ACTION_PASTE_CLIPBOARD))).isEqualTo(ShareRequest.Clipboard)
        assertThat(ShareRequest.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "   "))).isInstanceOf(ShareRequest.Unsupported::class.java)
        assertThat(ShareRequest.from(null)).isInstanceOf(ShareRequest.Unsupported::class.java)
    }

    @Test
    fun `caps very large shares`() {
        val uris = ArrayList((1..150).map { "content://media/external/images/media/$it".toUri() })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        assertThat((ShareRequest.from(intent) as ShareRequest.Files).uris).hasSize(ShareRequest.MAX_FILES)
    }
}
