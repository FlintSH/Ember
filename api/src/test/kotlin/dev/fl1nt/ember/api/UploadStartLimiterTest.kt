package dev.fl1nt.ember.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadStartLimiterTest {

    @Test
    fun `allows a burst up to the limit, then waits for the window`() = runTest {
        val limiter = UploadStartLimiter(maxStarts = 3, windowMillis = 60_000, clock = { testScheduler.currentTime })
        var started = 0
        launch { repeat(4) { limiter.acquire(); started++ } }
        runCurrent()
        assertThat(started).isEqualTo(3)
        advanceTimeBy(59_999)
        runCurrent()
        assertThat(started).isEqualTo(3)
        advanceTimeBy(1)
        runCurrent()
        assertThat(started).isEqualTo(4)
    }

    @Test
    fun `pauses every start after the server asks to retry later`() = runTest {
        val limiter = UploadStartLimiter(maxStarts = 25, clock = { testScheduler.currentTime })
        limiter.pauseFor(30)
        var started = false
        launch { limiter.acquire(); started = true }
        advanceTimeBy(29_999)
        runCurrent()
        assertThat(started).isFalse()
        advanceTimeBy(1)
        runCurrent()
        assertThat(started).isTrue()
    }
}
