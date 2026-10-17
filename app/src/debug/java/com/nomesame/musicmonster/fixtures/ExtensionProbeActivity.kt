package com.nomesame.musicmonster.fixtures

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Debug-only caller for real cross-package activity-result and scoped-grant tests. */
class ExtensionProbeActivity : ComponentActivity() {
    var onResult: (Int, Intent?) -> Unit = { _, _ -> }
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onResult(it.resultCode, it.data)
    }
    fun produce(intent: Intent) = launcher.launch(intent)
}
