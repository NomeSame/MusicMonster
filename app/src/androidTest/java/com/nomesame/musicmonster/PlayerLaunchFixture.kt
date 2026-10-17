package com.nomesame.musicmonster

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.nomesame.musicmonster.data.LanguageRepository
import java.io.File

/** Real launcher tests with an owned MP4 and folder, restoring pre-existing preference values. */
class PlayerLaunchFixture : AutoCloseable {
    val inst = InstrumentationRegistry.getInstrumentation()
    val context get() = inst.targetContext
    val folder = File(context.cacheDir, "rename-fixtures").apply { mkdirs() }
    val name = "startup-${java.util.UUID.randomUUID()}"
    private val video = File(folder, "$name.mp4")
    private val prefs = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
    private val language = context.getSharedPreferences(LanguageRepository.PREFS_NAME, Context.MODE_PRIVATE)
    private val saved = prefs.all.toMap()
    private val savedLanguage = language.all.toMap()
    var host: MainActivity? = null
    init {
        TestPermissions.grantAll()
        check(context.getSharedPreferences("extension_output", 0).all.isEmpty())
        inst.context.assets.open("owned-video.mp4").use { input -> video.outputStream().use(input::copyTo) }
        val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + ".rename-fixture", "root")
        check(prefs.edit().clear().putString("library_tree_uri", tree.toString()).commit())
        check(language.edit().putString(LanguageRepository.KEY_LANGUAGE, "en").commit())
    }
    fun launch(): MainActivity {
        // A launcher icon is reached via Home, not by starting an already-foreground task.
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
            .use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
        inst.uiAutomation.executeShellCommand("am start -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER -f 0x10200000 -n ${context.packageName}/${MainActivity::class.java.name}")
            .use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
        return awaitResumedTestActivity(MainActivity::class.java).also { host = it }
    }
    override fun close() {
        inst.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filter { it !== host && it.packageName == context.packageName }.forEach { it.finish() }
            host?.let { androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java].extensions.cancel() }
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        val journal = context.getSharedPreferences("extension_output", 0)
        while (journal.all.isNotEmpty() && android.os.SystemClock.uptimeMillis() < deadline)
            android.os.SystemClock.sleep(25)
        check(journal.all.isEmpty()) { "Owned extension stage was not cleaned up" }
        inst.runOnMainSync { host?.finish() }
        context.stopService(Intent(context, MusicService::class.java))
        restore(prefs, saved)
        restore(language, savedLanguage)
        check(video.delete())
    }
    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
    }
}
