package com.nomesame.musicmonster

import android.graphics.Bitmap
import com.nomesame.musicmonster.data.MediaAppearanceRepository
import com.nomesame.musicmonster.model.ArtworkCrop
import com.nomesame.musicmonster.model.MediaAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ViewModel-owned editor state; source decode happens only while the editor is open. */
class ArtworkCropState(
    private val repository: MediaAppearanceRepository,
    private val scope: CoroutineScope,
    private val loadSource: (MediaAppearance) -> Bitmap?
) {
    private val _position = MutableStateFlow(repository.loadCrop())
    val position = _position.asStateFlow()
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview = _preview.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private var opened = false
    private var closed = false
    private var generation = 0L
    private var job: Job? = null
    private var sourceKey: Pair<Boolean, String?>? = null
    private val unsubscribe = repository.observe {
        _position.value = repository.loadCrop()
        if (opened) refreshSource()
    }

    fun setPosition(crop: ArtworkCrop) {
        repository.saveCrop(crop)
        _position.value = repository.loadCrop()
    }

    fun openPreview() {
        if (closed) return
        opened = true
        refreshSource()
    }

    private fun refreshSource() {
        val appearance = repository.load()
        val nextKey = appearance.backgroundEnabled to appearance.backgroundUri
        if (sourceKey == nextKey) return // Crop, accent and scrim updates do not decode again.
        sourceKey = nextKey
        val request = ++generation
        job?.cancel()
        _preview.value = null
        _loading.value = appearance.backgroundEnabled
        if (!appearance.backgroundEnabled) return
        job = scope.launch {
            val image = withContext(Dispatchers.IO) { runCatching { loadSource(appearance) }.getOrNull() }
            if (opened && request == generation) {
                _preview.value = image
                _loading.value = false
            } else image?.recycle()
        }
    }

    fun closePreview() {
        opened = false
        ++generation
        job?.cancel()
        sourceKey = null
        _preview.value = null
        _loading.value = false
        // Published preview bitmaps may still be used by Compose; leave them to GC.
    }

    fun close() {
        closed = true
        unsubscribe()
        closePreview()
    }
}
