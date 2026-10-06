package com.nomesame.musicmonster

import org.junit.Assert.*
import org.junit.Test

class ServiceTimingBoundaryTest {
    @Test fun fadeUsesElapsedTimeAndNeverFinishesEarly() {
        val plan = SleepTimerPlan(0, 1000)
        assertEquals(0.8f, plan.volumeAt(0.8f, 0), 0f)
        assertEquals(0.4f, plan.volumeAt(0.8f, 500), 0f)
        assertTrue(plan.volumeAt(0.8f, 999) > 0f)
        assertEquals(0f, plan.volumeAt(0.8f, 1000), 0f)
    }
    @Test fun delayedFadeCallbackStillFinishesAtZero() {
        assertEquals(0f, SleepTimerPlan(0, 1000).volumeAt(1f, Long.MAX_VALUE), 0f)
    }
    @Test fun extremeFadeHasNoIntegerStepOverflow() {
        val volume = SleepTimerPlan(0, Long.MAX_VALUE).volumeAt(1f, Long.MAX_VALUE / 2)
        assertEquals(0.5f, volume, 0.0001f)
    }
    @Test fun normalTimerReservesTheFadeWindow() {
        assertEquals(SleepTimerPlan(5000, 10000), SleepTimerPlan.create(15000, 10000))
    }
    @Test fun negativeFadeDoesNotExtendTimer() {
        assertEquals(SleepTimerPlan(1000, 0), SleepTimerPlan.create(1000, -1000))
    }
    @Test fun minimumFadeCannotOverflowWaitDuration() {
        assertEquals(SleepTimerPlan(Long.MAX_VALUE, 0), SleepTimerPlan.create(Long.MAX_VALUE, Long.MIN_VALUE))
    }
    @Test fun fadeCannotBeLongerThanTimer() {
        assertEquals(SleepTimerPlan(0, 1000), SleepTimerPlan.create(1000, Long.MAX_VALUE))
    }
    @Test fun zeroOrNegativeTimerIsRejected() {
        assertNull(SleepTimerPlan.create(0, 10))
        assertNull(SleepTimerPlan.create(Long.MIN_VALUE, 10))
    }
    @Test fun pauseCancelsDeferredPlayDuringColdStart() {
        val pending = PendingPlaybackRequest()
        pending.play()
        pending.pause()
        assertNull(pending.consume())
    }
    @Test fun pauseCancelsDeferredTrackSelectionDuringColdStart() {
        val pending = PendingPlaybackRequest()
        pending.play("track")
        pending.pause()
        assertNull(pending.consume())
    }
    @Test fun deferredPlayIsConsumedExactlyOnce() {
        val pending = PendingPlaybackRequest()
        pending.play()
        assertEquals(PendingPlaybackRequest.Request(null), pending.consume())
        assertNull(pending.consume())
    }
    @Test fun latestTrackSelectionWinsDuringLoading() {
        val pending = PendingPlaybackRequest()
        pending.play("first")
        pending.play("last")
        assertEquals(PendingPlaybackRequest.Request("last"), pending.consume())
    }
    @Test fun genericPlayKeepsTheSelectedDeferredTrack() {
        val pending = PendingPlaybackRequest()
        pending.play("track")
        pending.play()
        assertEquals(PendingPlaybackRequest.Request("track"), pending.consume())
    }
    @Test fun newPlayAfterPauseIsHonored() {
        val pending = PendingPlaybackRequest()
        pending.play("old")
        pending.pause()
        pending.play("new")
        assertEquals(PendingPlaybackRequest.Request("new"), pending.consume())
    }
}
