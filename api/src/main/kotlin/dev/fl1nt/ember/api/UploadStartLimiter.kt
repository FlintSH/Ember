package dev.fl1nt.ember.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Paces upload starts for one Flare instance.
 *
 * Flare allows 30 `POST /api/files` and `POST /api/files/chunks` requests per
 * minute for each client address, before authentication, and answers the rest
 * with `429`. A 429 can arrive while a large body is still being written, which
 * surfaces as a broken connection instead of a readable response, so Ember
 * stays under the limit rather than relying on the error. Share one limiter for
 * every client that talks to the same instance.
 */
class UploadStartLimiter(
    private val maxStarts: Int = 25,
    private val windowMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val starts = ArrayDeque<Long>()
    private var blockedUntil = 0L

    init {
        require(maxStarts > 0) { "maxStarts must be positive" }
        require(windowMillis > 0) { "windowMillis must be positive" }
    }

    /** Suspends until another upload may start, then records the start. */
    suspend fun acquire() {
        while (true) {
            val waitMillis = mutex.withLock {
                val now = clock()
                while (starts.isNotEmpty() && now - starts.first() >= windowMillis) starts.removeFirst()
                when {
                    now < blockedUntil -> blockedUntil - now
                    starts.size < maxStarts -> {
                        starts.addLast(now)
                        0L
                    }
                    else -> windowMillis - (now - starts.first())
                }
            }
            if (waitMillis <= 0) return
            delay(waitMillis)
        }
    }

    /** Pauses every start after Flare answered `429 Retry-After`. */
    suspend fun pauseFor(seconds: Long) {
        mutex.withLock {
            blockedUntil = maxOf(blockedUntil, clock() + seconds.coerceAtLeast(1) * 1000)
        }
    }
}
