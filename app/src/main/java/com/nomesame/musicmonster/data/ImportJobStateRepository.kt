package com.nomesame.musicmonster.data

import android.content.SharedPreferences

/**
 * Persistence of the one active import job id and original target, so a host restart can ask the
 * plugin for the current snapshot (`JOB_QUERY`) instead of starting a second job.
 *
 * Layer: **Data**. It stores opaque strings and knows nothing about the plugin
 * wire contract; the state layer decides when a job is finished and clears it. The
 * capability/trust answer is deliberately **not** stored here — it is re-negotiated on
 * every activity start.
 */
interface ActiveImportJobStore {

    /** The persisted job id, or null when no job is remembered. */
    fun load(): String?

    /** Remembers [jobId] as the currently running job. */
    fun save(jobId: String, targetUri: String)

    /** Original destination, persisted together with the job id. */
    fun loadTarget(): String?

    /** Forgets the persisted job (terminal state, lost capability). */
    fun clear()
}

/** SharedPreferences-backed [ActiveImportJobStore] using the app's existing prefs pattern. */
class ImportJobStateRepository(private val prefs: SharedPreferences) : ActiveImportJobStore {

    override fun load(): String? = prefs.stringOr(KEY_ACTIVE_JOB_ID, null)?.takeIf { it.isNotBlank() }

    override fun save(jobId: String, targetUri: String) {
        prefs.edit().putString(KEY_ACTIVE_JOB_ID, jobId).putString(KEY_TARGET, targetUri).apply()
    }

    override fun loadTarget(): String? = prefs.stringOr(KEY_TARGET, null)?.takeIf { it.isNotBlank() }

    override fun clear() {
        prefs.edit().remove(KEY_ACTIVE_JOB_ID).remove(KEY_TARGET).apply()
    }

    companion object {
        private const val KEY_TARGET = "import_active_target_uri"
        const val KEY_ACTIVE_JOB_ID = "import_active_job_id"
    }
}
