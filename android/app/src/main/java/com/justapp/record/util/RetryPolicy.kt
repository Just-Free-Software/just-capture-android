package com.justapp.record.util

import kotlin.math.min

/**
 * Retry policy shared by the upload + email queues. Delays grow exponentially
 * up to [MAX_DELAY_SEC], capping the inter-attempt gap so a transient outage
 * doesn't turn into a freeze. Total budget across all [MAX_ATTEMPTS] is roughly
 * the time to drain one full outage cycle.
 */
object RetryPolicy {
    const val INITIAL_DELAY_SEC = 5.0
    const val MAX_DELAY_SEC = 1200.0      // 20 minutes
    const val FACTOR = 2.0
    const val MAX_ATTEMPTS = 10

    /**
     * attempt 1 -> no prior delay (we just failed); attempt N returns the
     * delay to wait before attempt N+1.
     */
    fun delayForAttempt(attempt: Int): Double {
        var delay = INITIAL_DELAY_SEC
        for (i in 1 until attempt.coerceAtLeast(1)) {
            delay = min(MAX_DELAY_SEC, delay * FACTOR)
        }
        return delay
    }
}
