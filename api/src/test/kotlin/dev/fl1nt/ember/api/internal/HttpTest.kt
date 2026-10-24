package dev.fl1nt.ember.api.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FilterInputStream

class HttpTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `skipping reports a file that ends early, even when skip seeks past its end`() {
        val file = temp.newFile().apply { writeBytes(ByteArray(10) { it.toByte() }) }
        FileInputStream(file).use { assertThat(it.skipFully(11)).isFalse() }
        FileInputStream(file).use {
            assertThat(it.skipFully(10)).isTrue()
            assertThat(it.read()).isEqualTo(-1)
        }
        FileInputStream(file).use {
            assertThat(it.skipFully(4)).isTrue()
            assertThat(it.read()).isEqualTo(4)
        }
    }

    @Test
    fun `skipping reads through streams whose skip makes no progress`() {
        val stubborn = object : FilterInputStream(ByteArrayInputStream(ByteArray(6) { it.toByte() })) {
            override fun skip(n: Long): Long = 0
        }
        assertThat(stubborn.skipFully(5)).isTrue()
        assertThat(stubborn.read()).isEqualTo(5)
        assertThat(stubborn.skipFully(1)).isFalse()
        assertThat(ByteArrayInputStream(ByteArray(0)).skipFully(0)).isTrue()
    }

    @Test
    fun `reads at most the limit, growing the buffer as data arrives`() {
        val data = ByteArray(200_000) { (it % 7).toByte() }
        assertThat(ByteArrayInputStream(data).readAtMost(150_000)).isEqualTo(data.copyOf(150_000))
        assertThat(ByteArrayInputStream(data).readAtMost(300_000)).isEqualTo(data)
        assertThat(ByteArrayInputStream(data).readAtMost(0)).isEmpty()
        val target = ByteArray(10)
        assertThat(ByteArrayInputStream(data, 0, 4).readInto(target, 10)).isEqualTo(4)
    }
}
