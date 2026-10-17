package com.nomesame.musicmonster.state

import android.content.IntentSender
import com.nomesame.musicmonster.Song
import com.nomesame.musicmonster.SongRenameRules
import com.nomesame.musicmonster.data.RenameError
import com.nomesame.musicmonster.data.RenameOutcome
import com.nomesame.musicmonster.data.SongReferenceRepository
import com.nomesame.musicmonster.data.SongRenamer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RenameForm(
    val song: Song,
    val original: String = "",
    val input: String = "",
    val busy: Boolean = true,
    val error: RenameError? = null,
)

sealed interface RenamePermission {
    data class System(val sender: IntentSender) : RenamePermission
    object LegacyStorage : RenamePermission
}

/** One modal edit operation; filesystem and reference updates always run off the UI thread. */
class SongRenameController(
    private val renamer: SongRenamer,
    private val references: SongReferenceRepository,
    private val scope: CoroutineScope,
    private val onRenamed: (Song, Song) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow<RenameForm?>(null)
    val state = mutable.asStateFlow()
    private val requests = Channel<RenamePermission>(Channel.BUFFERED)
    val permissions = requests.receiveAsFlow()

    fun open(song: Song) {
        if (mutable.value != null) return
        mutable.value = RenameForm(song)
        scope.launch {
            val original = withContext(io) { runCatching { renamer.name(song) }.getOrNull() }
            if (mutable.value?.song?.id != song.id) return@launch
            mutable.value = RenameForm(song, original.orEmpty(), original?.let(SongRenameRules::baseName).orEmpty(),
                busy = false, error = if (original == null) RenameError.MISSING else null)
        }
    }

    fun edit(text: String) {
        val current = mutable.value ?: return
        if (!current.busy) mutable.value = current.copy(input = text.take(240), error = null)
    }

    fun dismiss() {
        if (mutable.value?.busy != true) mutable.value = null
    }

    fun submit(mayRequestPermission: Boolean = true) {
        val current = mutable.value ?: return
        if (current.busy || current.original.isEmpty()) return
        val target = SongRenameRules.fileName(current.input, current.original)
        if (target == null) {
            mutable.value = current.copy(error = RenameError.INVALID_NAME)
            return
        }
        mutable.value = current.copy(busy = true, error = null)
        scope.launch {
            val (outcome, committed) = withContext(NonCancellable + io) { references.exclusive {
                if (!references.prepare(current.song, target)) {
                    RenameOutcome.Error(RenameError.REFERENCES) to false
                } else {
                    val result = runCatching { renamer.rename(current.song, current.original, current.input, mayRequestPermission) }
                        .getOrElse { RenameOutcome.Error(RenameError.FAILED) }
                    val saved = when (result) {
                        is RenameOutcome.Success -> references.remap(current.song.id, result.song.id)
                        is RenameOutcome.Error -> {
                            if (renamer.name(current.song) == current.original) references.abandon(current.song.id)
                            false
                        }
                        else -> false
                    }
                    result to saved
                }
            } }
            when (outcome) {
                is RenameOutcome.Success -> {
                    onRenamed(current.song, outcome.song)
                    mutable.value = if (committed) null else current.copy(song = outcome.song,
                        original = outcome.song.fileName.orEmpty(), busy = false, error = RenameError.REFERENCES)
                }
                is RenameOutcome.Error -> mutable.value = current.copy(busy = false, error = outcome.reason)
                is RenameOutcome.Consent -> requests.send(RenamePermission.System(outcome.sender))
                RenameOutcome.LegacyPermission -> requests.send(RenamePermission.LegacyStorage)
            }
        }
    }

    fun permissionResult(granted: Boolean) {
        val current = mutable.value ?: return
        if (!current.busy) return
        mutable.value = current.copy(busy = false, error = if (granted) null else RenameError.READ_ONLY)
        if (granted) submit(mayRequestPermission = false)
        else scope.launch { withContext(io) { references.abandon(current.song.id) } }
    }
}
