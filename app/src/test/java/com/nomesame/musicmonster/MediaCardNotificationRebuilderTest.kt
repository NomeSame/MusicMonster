package com.nomesame.musicmonster

import com.nomesame.musicmonster.playback.MediaCardNotificationRebuilder
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaCardNotificationRebuilderTest {
    @Test fun onlyVerifiedXiaomiAndroid13UsesRecreation() {
        assertTrue(MediaCardNotificationRebuilder.isAffected("Xiaomi", 33))
        assertTrue(MediaCardNotificationRebuilder.isAffected("xiaomi", 33))
        assertFalse(MediaCardNotificationRebuilder.isAffected("Google", 33))
        assertFalse(MediaCardNotificationRebuilder.isAffected("Xiaomi", 34))
        assertFalse(MediaCardNotificationRebuilder.isAffected("Xiaomi", 32))
    }

    @Test fun otherDevicesPublishNormallyEvenWhenArtworkChanges() {
        var posted = 0
        val subject = MediaCardNotificationRebuilder(false, { fail("Removed another OEM card") },
            { fail("Recreated another OEM card") })
        subject.publish(true) { posted++ }
        assertEquals(1, posted)
        subject.close()
    }

    @Test fun playbackAndTitleUpdatesNeverRemoveTheCard() {
        var posted = 0
        val subject = MediaCardNotificationRebuilder(true, { fail("Removed unchanged artwork") },
            { fail("Recreated unchanged artwork") })
        subject.publish(false) { posted++ }
        assertEquals(1, posted)
        subject.close()
    }

    @Test fun imageChangesAndPlaybackUpdatesCoalesceIntoLatestRestore() {
        var removed = 0
        var restored = 0
        var latest = "first"
        var rendered = ""
        val subject = MediaCardNotificationRebuilder(true, { removed++ }, {
            restored++; rendered = latest
        })
        subject.publish(true) { fail("Posted before OEM panel was removed") }
        latest = "last"
        subject.publish(true) { fail("Second image must coalesce") }
        subject.publish(false) { fail("Playback update must coalesce") }
        assertEquals(1, removed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(749))
        assertEquals(0, restored)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, restored)
        assertEquals("last", rendered)
        subject.close()
    }

    @Test fun subsequentChangeCanRecreateAgainAndNormalUpdatesStillPost() {
        var removed = 0
        var restored = 0
        var posted = 0
        val subject = MediaCardNotificationRebuilder(true, { removed++ }, { restored++ })
        repeat(2) {
            subject.publish(true) { fail("Expected recreation") }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(750))
        }
        subject.publish(false) { posted++ }
        assertEquals(2, removed)
        assertEquals(2, restored)
        assertEquals(1, posted)
        subject.close()
    }

    @Test fun serviceDestructionCancelsRestoreAndNeverResurrectsNotification() {
        val subject = MediaCardNotificationRebuilder(true, {}, { fail("Restored after destruction") })
        subject.publish(true) { fail("Posted early") }
        subject.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        subject.publish(false) { fail("Posted after destruction") }
        subject.close()
    }
}