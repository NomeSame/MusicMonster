package com.nomesame.musicmonster

/** Latest deferred transport intent while the library is loading. */
internal class PendingPlaybackRequest {
    data class Request(val mediaId: String?)
    private var wanted = false
    private var mediaId: String? = null
    fun play(id: String? = null) {
        wanted = true
        if (id != null) mediaId = id
    }
    fun pause() {
        wanted = false
        mediaId = null
    }
    fun consume(): Request? {
        if (!wanted) return null
        val result = Request(mediaId)
        wanted = false
        mediaId = null
        return result
    }
}
