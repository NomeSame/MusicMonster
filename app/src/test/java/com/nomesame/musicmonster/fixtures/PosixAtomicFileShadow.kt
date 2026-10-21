package com.nomesame.musicmonster.fixtures

import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Android's rename replaces existing files; Java File.renameTo on Windows does not.
 * Keep the real AtomicFile write/sync/close, emulate only the POSIX replacement.
 * Actual Android cache persistence is additionally covered by the device suite.
 */
@Implements(AtomicFile::class)
class PosixAtomicFileShadow {
    @RealObject private lateinit var actual: AtomicFile
    @Implementation fun finishWrite(stream: FileOutputStream?) {
        Shadow.directlyOn<Any>(actual, AtomicFile::class.java.name, "finishWrite",
            ClassParameter.from(FileOutputStream::class.java, stream))
        val temporary = File(actual.baseFile.path + ".new")
        if (System.getProperty("os.name").orEmpty().startsWith("Windows") && temporary.exists())
            Files.move(temporary.toPath(), actual.baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
