package com.nomesame.musicmonster

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import com.nomesame.musicmonster.playback.VideoPlaybackConnection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual launcher -> library -> video -> Android PiP, always using an owned silent fixture. */
class VideoPipInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var details: () -> String = { "" }
    private fun await(condition: () -> Boolean) {
        // Binder callbacks update Compose state asynchronously. A wall-clock sleep alone
        // leaves the test frame clock paused and never attaches the AndroidView player.
        try { compose.waitUntil(15_000, condition) }
        catch (error: ComposeTimeoutException) {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val windows = instrumentation.uiAutomation.windows.joinToString { window ->
                val bounds = android.graphics.Rect()
                window.getBoundsInScreen(bounds)
                "type=${window.type}, package=${window.root?.packageName}, bounds=$bounds"
            }
            throw AssertionError("PiP condition timed out: ${details()}; windows=$windows", error)
        }
    }
    @Test fun buttonAndHomeUsePipAndMaximizeKeepsTheSamePlayerWhileClosingPauses() {
        PlayerLaunchFixture().use { fixture ->
            val inst = fixture.inst
            val accessibilityFlags = inst.uiAutomation.serviceInfo.flags
            inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            fun <T> main(block: () -> T): T {
                var value: T? = null
                inst.runOnMainSync { value = block() }
                @Suppress("UNCHECKED_CAST") return value as T
            }
            val host = fixture.launch()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(fixture.name).fetchSemanticsNodes().isNotEmpty() }
            val model = main { ViewModelProvider(host)[MainViewModel::class.java] }
            val song = model.songs.value.single { it.title == fixture.name }
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("song_row_" + song.id) and hasText("0:08")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("song_row_" + song.id).assert(hasText("0:08"))
            val connection = VideoPlaybackConnection(fixture.context)
            main { connection.connect() }
            await { connection.player.value != null }
            val player = requireNotNull(connection.player.value)
            val oldVolume = main { player.volume }
            val opaque = java.io.File.createTempFile("owned-opaque-video-", ".bin", fixture.context.cacheDir)
            inst.context.assets.open("owned-video.mp4").use { input -> opaque.outputStream().use(input::copyTo) }
            var video: VideoActivity? = null
            try {
                main { player.volume = 0f }
                compose.onNodeWithTag("song_row_" + song.id).performClick()
                // Video size is reported once a surface is attached; the library starts audio first.
                await { main { player.isPlaying && player.currentMediaItem?.mediaId == song.id } }
                compose.onNodeWithTag("show_video").performClick()
                video = awaitResumedTestActivity(VideoActivity::class.java)
                details = { main {
                    val activity = requireNotNull(video)
                    fun views(v: android.view.View): List<android.view.View> = listOf(v) +
                        if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
                        else emptyList()
                    val view = views(activity.window.decorView).filterIsInstance<androidx.media3.ui.PlayerView>().singleOrNull()
                    val observed = VideoActivity::class.java.getDeclaredField("observed").apply { isAccessible = true }.get(activity)
                    "state=${activity.lifecycle.currentState}, finishing=${activity.isFinishing}, " +
                        "focus=${activity.hasWindowFocus()}, playing=${player.isPlaying}, state=${player.playbackState}, " +
                        "tracks=${player.currentTracks.groups.map { it.type }}, size=${player.videoSize.width}x${player.videoSize.height}, " +
                        "binderSame=${observed === player}, viewSame=${view?.player === player}, view=${view?.width}x${view?.height}"
                } }
                await { main { player.videoSize.height == 240 } }
                // Document-provider IDs can be opaque. A new video without a .mp4 URI suffix
                // must keep the video UI open; file extensions cannot classify player tracks.
                main {
                    player.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(song.id)
                        .setUri(android.net.Uri.fromFile(opaque)).build())
                    player.prepare()
                    player.play()
                }
                await { main { player.isPlaying && player.videoSize.height == 240 } }
                assertFalse(main { requireNotNull(video).isFinishing })
                compose.onNodeWithTag("video_close").assertIsDisplayed()
                val supported = Build.VERSION.SDK_INT >= 26 && fixture.context.packageManager
                    .hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
                if (supported) {
                    compose.onNodeWithTag("video_shrink").performClick()
                    await { main { requireNotNull(video).isInPictureInPictureMode } }
                    assertTrue(main { player.isPlaying })
                    // The same immutable PendingIntent installed in Android's native PiP action.
                    val toggle = PendingIntent.getBroadcast(fixture.context, 0,
                        Intent(fixture.context.packageName + ".VIDEO_PIP_TOGGLE").setPackage(fixture.context.packageName),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    assertNotNull(toggle)
                    toggle.send()
                    await { main { !player.playWhenReady } }
                    toggle.send()
                    await { main { player.isPlaying } }
                    main { host.startActivity(Intent(host, VideoActivity::class.java)) }
                    assertSame(video, awaitResumedTestActivity(VideoActivity::class.java))
                    await { main { !requireNotNull(video).isInPictureInPictureMode } }
                    assertEquals(song.id, main { player.currentMediaItem?.mediaId })
                    assertSame(player, connection.player.value)
                } else {
                    compose.onNodeWithTag("video_shrink").assertDoesNotExist()
                }
                compose.onNodeWithTag("video_close").performClick()
                awaitResumedTestActivity(MainActivity::class.java)
                assertTrue(main { player.playWhenReady }) // Continue with audio never pauses.
                val previousVideo = video
                compose.onNodeWithTag("show_video").performClick()
                video = awaitResumedTestActivity(VideoActivity::class.java, previousVideo)
                if (supported) {
                    inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use {
                        java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
                    }
                    await { main { requireNotNull(video).isInPictureInPictureMode } }
                    assertTrue(main { player.playWhenReady })
                    // Open Android's actual PiP menu and click its X. Calling Activity.finish()
                    // during the entry animation is not equivalent to a system-window dismissal.
                    // PiP transforms/scales the activity surface. Its decor coordinates
                    // need not match the system window on screen (notably on MIUI).
                    // Tap the actual accessibility window after its entry bounds settle.
                    var pipBounds = android.graphics.Rect()
                    var boundsChangedAt = SystemClock.uptimeMillis()
                    await {
                        val window = inst.uiAutomation.windows.firstOrNull {
                            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                                it.root?.packageName?.toString() == fixture.context.packageName
                        }
                        val current = android.graphics.Rect()
                        window?.getBoundsInScreen(current)
                        if (current != pipBounds) {
                            pipBounds = current
                            boundsChangedAt = SystemClock.uptimeMillis()
                        }
                        // App display metrics can themselves shrink to the PiP window.
                        // isInPictureInPictureMode was already asserted above.
                        !current.isEmpty && SystemClock.uptimeMillis() - boundsChangedAt >= 200
                    }
                    val position = pipBounds.centerX() to pipBounds.centerY()
                    inst.uiAutomation.executeShellCommand("input tap ${position.first} ${position.second}").use {
                        java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
                    }
                    fun nodes(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
                        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
                    var close: android.view.accessibility.AccessibilityNodeInfo? = null
                    await {
                        close = inst.uiAutomation.windows.flatMap { nodes(it.root) }.firstOrNull {
                            it.packageName?.toString() == "com.android.systemui" &&
                                it.viewIdResourceName?.substringAfterLast('/') == "dismiss" && it.isVisibleToUser
                        }
                        close != null
                    }
                    assertTrue(requireNotNull(close).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    await { main { !player.playWhenReady } }
                } else {
                    compose.onNodeWithTag("video_close").performClick()
                }
            } finally {
                main { video?.finish(); player.volume = oldVolume; connection.disconnect() }
                inst.uiAutomation.serviceInfo = inst.uiAutomation.serviceInfo.apply { flags = accessibilityFlags }
                assertTrue(opaque.delete())
            }
        }
    }
}
