package com.nomesame.musicmonster

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Regression: denied POST_NOTIFICATIONS must not freeze an exempt media card at startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34])
class MediaNotificationPermissionTest {
    @Test fun deniedPermissionStillPublishesUpdatedMediaTitle() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PackageManager.PERMISSION_DENIED,
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val lifecycle = Robolectric.buildService(MusicService::class.java).create()
        val service = lifecycle.get()
        try {
            // Wait for the real scan worker, then apply its queued result before our fixture.
            val scanner = MusicService::class.java.getDeclaredField("loadExecutor").apply {
                isAccessible = true
            }.get(service) as java.util.concurrent.ExecutorService
            scanner.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val title = "Notification regression — ü"
            MusicService::class.java.getDeclaredField("titles").apply {
                isAccessible = true
                set(service, listOf(title))
            }
            val player = MusicService::class.java.getDeclaredField("player").apply {
                isAccessible = true
            }.get(service) as androidx.media3.exoplayer.ExoPlayer
            // Selecting an item creates a current index; no prepare/play or actual audio I/O.
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri("file:///notification-fixture.wav"))
            assertEquals(0, player.currentMediaItemIndex)
            MusicService::class.java.getDeclaredMethod("updateNotification", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val notification = shadowOf(service.getSystemService(NotificationManager::class.java))
                .getNotification(MusicService.NOTIFICATION_ID)
            assertNotNull("Media update was blocked by the ordinary notification permission", notification)
            assertEquals(title, notification.extras.getString("android.title"))
            assertEquals(title, notification.publicVersion.extras.getString("android.title"))
            assertNotNull(notification.extras.getParcelable<android.os.Parcelable>("android.mediaSession"))
        } finally { lifecycle.destroy() }
    }
}
