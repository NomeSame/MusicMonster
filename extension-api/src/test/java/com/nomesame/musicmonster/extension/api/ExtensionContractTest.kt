package com.nomesame.musicmonster.extension.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** Frozen v1 wire values: updating a player must not require rebuilding this APK. */
class ExtensionContractTest {
    @Test fun activityV1RemainsBinaryCompatible() {
        assertEquals(1, ExtensionContract.VERSION)
        assertEquals("com.nomesame.musicmonster.extension.PRODUCE_DOCUMENT_V1", ExtensionContract.ACTION)
        assertEquals("com.nomesame.musicmonster.permission.BIND_PLUGIN", ExtensionContract.PERMISSION)
        assertEquals("com.nomesame.musicmonster.extension.VERSION", ExtensionContract.META_VERSION)
        assertEquals("com.nomesame.musicmonster.extension.OUTPUT", ExtensionContract.META_OUTPUT)
        assertEquals("audio", ExtensionContract.AUDIO)
        assertEquals("extension_version", ExtensionContract.EXTRA_VERSION)
        assertEquals("extension_request", ExtensionContract.EXTRA_REQUEST)
        assertEquals("extension_name", ExtensionContract.EXTRA_NAME)
        assertEquals("extension_language", ExtensionContract.EXTRA_LANGUAGE)
    }
}
