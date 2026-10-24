package dev.fl1nt.ember.data

import android.os.Process
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UploadCopiesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `clears copies left by processes that are gone, and keeps this one's`() {
        val root = temp.newFolder("upload-copies")
        val copies = UploadCopies(root)
        val mine = File(copies.directory(), "ember-upload-1.tmp").apply { writeText("in use") }
        val leftover = File(root, "${Process.myPid() + 1}/ember-upload-2.tmp").apply { parentFile!!.mkdirs(); writeText("old") }

        copies.clearLeftovers()

        assertThat(mine.exists()).isTrue()
        assertThat(leftover.parentFile!!.exists()).isFalse()
    }
}
