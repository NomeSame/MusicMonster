package com.nomesame.musicmonster

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.nomesame.musicmonster.data.SongRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real audio/video metadata through SAF, without playing every library entry. */
class MediaDurationInstrumentedTest {
    @Test fun documentTreeReadsAudioAndVideoLengthsAndKeepsUnreadableFiles() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val folder = File(context.cacheDir, "rename-fixtures").apply { mkdirs() }
        val prefix = "duration-${java.util.UUID.randomUUID()}"
        val video = File(folder, "$prefix.mp4")
        val audio = File(folder, "$prefix.wav")
        val broken = File(folder, "$prefix-broken.mp3")
        val prefsName = "owned-duration-${java.util.UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, 0)
        try {
            inst.context.assets.open("owned-video.mp4").use { input -> video.outputStream().use(input::copyTo) }
            val dataSize = 8000 * 2 * 2 // Two seconds, 8kHz mono PCM16 silence.
            val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
            audio.writeBytes(wav.array())
            broken.writeText("not an mp3")
            val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + ".rename-fixture", "root")
            assertTrue(prefs.edit().putString("library_tree_uri", tree.toString()).commit())
            val songs = SongRepository(context, prefs).load().associateBy { it.fileName }
            assertTrue(songs.getValue(video.name).durationMs in 7900..8200)
            assertTrue(songs.getValue(audio.name).durationMs in 1900..2100)
            assertEquals(0L, songs.getValue(broken.name).durationMs)
        } finally {
            listOf(video, audio, broken).forEach { assertTrue(it.delete()) }
            assertTrue(context.deleteSharedPreferences(prefsName))
        }
    }
}
