package com.termux.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `allows up to the limit then rejects`() {
        var now = 0L
        val limiter = RateLimiter(max = 3, windowMs = 1000, clock = { now })
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        now += 1000
        assertTrue(limiter.tryAcquire())
    }
}
