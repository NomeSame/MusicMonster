package com.nomesame.musicmonster

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRefreshGateTest {
    @Test fun firstPostIsImmediate() {
        assertEquals(0L, NotificationRefreshGate().delayAt(1000L))
    }
    @Test fun postsAreLimitedToTwoPerSecond() {
        val gate = NotificationRefreshGate()
        gate.markPosted(1000L)
        assertEquals(500L, gate.delayAt(1000L))
        assertEquals(1L, gate.delayAt(1499L))
        assertEquals(0L, gate.delayAt(1500L))
    }
    @Test fun frequentRequestsDoNotPushTheExistingDeadlineBack() {
        val gate = NotificationRefreshGate()
        gate.markPosted(1000L)
        (1000L..1500L step 10L).forEach { assertEquals(1500L, it + gate.delayAt(it)) }
    }
    @Test fun actualPostStartsTheNextInterval() {
        val gate = NotificationRefreshGate()
        gate.markPosted(1000L)
        gate.markPosted(1500L)
        assertEquals(400L, gate.delayAt(1600L))
    }
    @Test fun backwardOrInvalidClockCannotProduceNegativeOrUnboundedDelays() {
        val gate = NotificationRefreshGate()
        gate.markPosted(Long.MAX_VALUE)
        assertEquals(500L, gate.delayAt(Long.MIN_VALUE))
        gate.markPosted(Long.MIN_VALUE)
        assertEquals(0L, gate.delayAt(Long.MAX_VALUE))
        assertEquals(500L, gate.delayAt(-1L))
    }
}
