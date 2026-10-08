package com.nomesame.musicmonster

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.media.session.MediaButtonReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime contract of the media notification. These can only be answered on a
 * real runtime (see ARCHITECTURE.md, "Tests in echter Laufzeitumgebung"):
 * whether the framework actually resolves our media-button PendingIntents, and
 * whether the foreground service posts its notification inside the framework's
 * 5s deadline.
 *
 * Regression guard: `MediaButtonReceiver.buildMediaButtonPendingIntent` returns
 * **null** when no component in the manifest handles `ACTION_MEDIA_BUTTON`.
 * A null actionIntent makes the notification's Prev/Play/Next buttons silently
 * dead — visible on API 24-30 (which render our own actions) while API 31+
 * still looks fine because the system draws its own MediaSession controls.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NotificationContractTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mediaButtonPendingIntentsResolve() {
        val actions = longArrayOf(
            PlaybackStateCompat.ACTION_PLAY_PAUSE,
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
        )
        for (action in actions) {
            assertNotNull(
                "No manifest component handles ACTION_MEDIA_BUTTON, so the " +
                    "notification button for action $action would do nothing.",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, action)
            )
        }
    }

    @Test
    fun serviceGoesForegroundWithinFrameworkDeadline() {
        val notification = awaitServiceNotification()
        assertNotNull("MusicService never posted its foreground notification", notification)
    }

    @Test
    fun everyNotificationActionHasAnIntent() {
        val notification = awaitServiceNotification()
            ?: throw AssertionError("MusicService never posted its foreground notification")
        val actions = notification.actions ?: emptyArray()
        assertTrue("Media notification has no actions at all", actions.isNotEmpty())
        for (action in actions) {
            assertNotNull(
                "Notification action '${action.title}' has a null actionIntent " +
                    "-> tapping it does nothing (dead button).",
                action.actionIntent
            )
        }
    }

    @Test
    fun bothNotificationCardsOpenOurMainActivity() {
        val notification = awaitServiceNotification()
            ?: throw AssertionError("MusicService never posted its foreground notification")
        val expected = expectedPlayerIntent()
        assertEquals("Notification must open our own MainActivity", expected, notification.contentIntent)
        val publicVersion = notification.publicVersion
            ?: throw AssertionError("Missing public lock-screen notification")
        assertEquals("Lock-screen card must open our own MainActivity", expected, publicVersion.contentIntent)
    }

    @Test
    fun systemMediaSessionOpensTheSameMainActivityAsNotification() {
        val notification = awaitServiceNotification()
            ?: throw AssertionError("MusicService never posted its foreground notification")
        val token = MusicService.sessionToken
            ?: throw AssertionError("MusicService did not publish a session token")
        val controller = MediaControllerCompat(context, token)
        val sessionActivity = controller.sessionActivity
        assertEquals("System media card must open our own MainActivity", expectedPlayerIntent(), sessionActivity)
        assertEquals("Session and notification must use the same destination", notification.contentIntent, sessionActivity)
    }

    private fun expectedPlayerIntent(): PendingIntent {
        // NO_CREATE cannot manufacture the missing destination and hide a regression.
        // Framework PendingIntent identity includes the explicit component, action
        // and categories; a YouTube destination cannot match this MainActivity.
        val expected = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: throw AssertionError("No existing PendingIntent targets our MainActivity")
        assertEquals(context.packageName, expected.creatorPackage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("Player destination must launch an Activity", expected.isActivity)
            assertTrue("Player destination must be immutable", expected.isImmutable)
        }
        return expected
    }

    @Test
    fun notificationChannelDoesNotInterruptOnEveryUpdate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        awaitServiceNotification()
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(MusicService.CHANNEL_ID)
        assertNotNull("Playback notification channel was never created", channel)
        // A media transport channel must not be IMPORTANCE_HIGH: the service
        // re-posts the notification on every track change / position tick, and
        // a high-importance channel turns each of those into a heads-up popup
        // (and, on many OEM builds, a sound) for the whole listening session.
        assertTrue(
            "Channel importance ${channel!!.importance} makes every notification " +
                "update a heads-up interruption",
            channel.importance <= NotificationManager.IMPORTANCE_DEFAULT
        )
    }

    companion object {
        private const val DEADLINE_MS = 5_000L

        @BeforeClass
        @JvmStatic
        fun startService() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            TestPermissions.grantAll()
            val intent = Intent(context, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Waits up to the framework's own startForeground deadline for the
         * service's notification to appear. Returns null if it never does.
         */
        private fun awaitServiceNotification(): Notification? {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val nm = context.getSystemService(NotificationManager::class.java)
            val deadline = SystemClock.uptimeMillis() + DEADLINE_MS
            while (SystemClock.uptimeMillis() < deadline) {
                nm.activeNotifications
                    .firstOrNull { it.id == MusicService.NOTIFICATION_ID }
                    ?.let { return it.notification }
                SystemClock.sleep(100L)
            }
            return null
        }
    }
}
