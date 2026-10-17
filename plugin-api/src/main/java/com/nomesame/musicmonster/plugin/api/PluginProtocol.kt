package com.nomesame.musicmonster.plugin.api

/** Stable wire constants shared by separately built APKs. Never renumber existing messages.
 * Version 1 includes additive capability and import-job messages.
 * Additive changes keep VERSION; incompatible wire changes require a new action/version.
 *
 * ## Nesting rule (framework-free)
 * This file is pure Kotlin: no Android imports, no `Parcelable`, no `Serializable`. Any
 * message payload is carried as `Message.arg1`/`arg2` (Int) and `Message.obj`, which is
 * always an `android.os.Bundle` of primitive values. Bare Strings are NOT Parcelable
 * and cannot cross processes in Message.obj. ID-only messages carry KEY_JOB_ID in a Bundle. Result
 * files are never passed as file paths or `file://` URLs — only as a temporary read-only
 * `content://` URI granted narrowly to [HOST_PACKAGE] (see DECISIONS.md §8). No tree grant.
 *
 * ## Message overview
 * Request/response running over `Messenger`. `arg1`/`arg2` carry integers; `obj` carries the
 * payload described per message; `replyTo` (a `Messenger`) is set only where a reply or a
 * progress/result stream is expected.
 *
 * | Message        | Direction        | arg1                    | arg2              | obj                       | replyTo |
 * |----------------|------------------|-------------------------|-------------------|---------------------------|---------|
 * | [HELLO]        | Host → Plugin    | [VERSION]               | –                 | –                         | host    |
 * | [HELLO_REPLY]  | Plugin → Host    | [VERSION]               | [READY]/[INCOMPATIBLE] | –                    | –       |
 * | [CAPS]         | Host → Plugin    | –                       | –                 | –                         | host    |
 * | [CAPS_REPLY]   | Plugin → Host    | bitmask of [CAP_IMPORT] | –                 | –                         | –       |
 * | [JOB_START]    | Host → Plugin    | –                       | –                 | `Bundle` ([KEY_JOB_ID], [KEY_URL]) | host |
 * | [JOB_REPLY]    | Plugin → Host    | [JOB_ACCEPTED]/[JOB_REJECTED] | [ErrorCode] on reject | `Bundle` job id | – |
 * | [JOB_STATUS]   | Plugin → Host    | [JobState]              | progress 0–100    | `Bundle` job id           | –       |
 * | [JOB_RESULT]   | Plugin → Host    | –                       | –                 | `Bundle` (see [JOB_RESULT]) | –     |
 * | [JOB_CANCEL]   | Host → Plugin    | –                       | –                 | `Bundle` job id           | –       |
 * | [JOB_QUERY]    | Host → Plugin    | –                       | –                 | `Bundle` job id           | host    |
 * | [JOB_RESULT_ACK]| Host → Plugin   | –                       | –                 | `Bundle` job id           | –       |
 *
 * [HELLO] means contract compatibility only. [READY] is **not** import capability; import
 * becomes available solely once [CAPS_REPLY] announces [CAP_IMPORT] (DECISIONS.md §4).
 */
object PluginProtocol {
    const val HOST_PACKAGE = "com.nomesame.musicmonster"
    const val BIND_ACTION = "$HOST_PACKAGE.plugin.BIND_V1"
    const val PERMISSION = "$HOST_PACKAGE.permission.BIND_PLUGIN"
    const val META_VERSION = "$HOST_PACKAGE.plugin.PROTOCOL_VERSION"
    const val VERSION = 1
    const val HELLO = 1
    const val HELLO_REPLY = 2
    const val READY = 0
    const val INCOMPATIBLE = 1
    const val TIMEOUT_MS = 3_000L

    // ---- Capability negotiation (additive, DECISIONS.md §4) --------------------------------

    /** Host → Plugin: "which of these do you support?". Sent only after [HELLO]/[HELLO_REPLY]
     *  reached [READY]. Sets `replyTo` to the host `Messenger` for the [CAPS_REPLY].
     *  A plugin that never answers [CAPS] stays import-incapable; the host keeps the import
     *  UI hidden rather than assuming capability from [READY].
     */
    const val CAPS = 3

    /** Plugin → Host: bitmask of supported capabilities in `arg1` (0 = none). A plugin that
     *  does not understand [CAPS] simply does not answer; the host then assumes no capability.
     */
    const val CAPS_REPLY = 4

    /** Capability bit0: import (URL → temporary MP3 delivered to the host) is supported. */
    const val CAP_IMPORT = 1

    /** True when [capabilities] contains every bit of [capability]. Pure bitmask test; unknown
     *  bits in [capabilities] are ignored. [capability] is expected to be a single defined bit.
     */
    fun hasCapability(capabilities: Int, capability: Int): Boolean =
        (capabilities and capability) == capability

    // ---- Job messages (additive, DECISIONS.md §5/§6/§8) ------------------------------------

    /** Host → Plugin: begin exactly one job. `obj` = `Bundle` with [KEY_JOB_ID] and [KEY_URL].
     *  `replyTo` = host `Messenger` for [JOB_REPLY], live [JOB_STATUS] and the final [JOB_RESULT].
     *  At most one active job exists; a start during an active job must not spawn a second run
     *  (the plugin reports the existing job instead, DECISIONS.md §6).
     */
    const val JOB_START = 10

    /** Plugin → Host: the request's acceptance decision. `obj` = Bundle with KEY_JOB_ID; `arg1` is
     *  [JOB_ACCEPTED] or [JOB_REJECTED]; on rejection `arg2` is the reason as an [ErrorCode].
     */
    const val JOB_REPLY = 11

    /** Plugin → Host: progress snapshot. `obj` = Bundle with KEY_JOB_ID; `arg1` = a [JobState] value;
     *  `arg2` = overall progress 0–100. The value never decreases within a job (see
     *  [Progress.advance]) and lies inside the band of `arg1`'s phase.
     */
    const val JOB_STATUS = 12

    /** Plugin → Host: terminal outcome. `obj` = `Bundle` with [KEY_JOB_ID], [KEY_STATE]
     *  ([JobState], terminal), [KEY_ERROR] ([ErrorCode]); optionally [KEY_FILE_NAME] and
     *  [KEY_RESULT_URI] (a read-only `content://` string) on success. Never a file path or
     *  `file://` URL. The host writes the file into its own target folder and confirms with
     *  [JOB_RESULT_ACK]; that ACK frees the grant/temp file, not the plugin's success state.
     */
    const val JOB_RESULT = 13

    /** Host → Plugin: cancel the job named by KEY_JOB_ID in the obj Bundle. Only effective while the
     *  job is non-terminal; it then leads to [JobState.CANCELLED]. In a terminal state it is a
     *  no-op. Duplicate/unsafe replies to a dead host channel are dropped, never retried.
     */
    const val JOB_CANCEL = 14

    /** Host → Plugin: "what is the current state of this job?". `obj` = Bundle with KEY_JOB_ID; `replyTo` set to
     *  the host `Messenger` for the snapshot answer. Used to rebind after a UI/binder restart;
     *  it must never itself start a job (DECISIONS.md §6).
     */
    const val JOB_QUERY = 15

    /** Host → Plugin: "I persisted the delivered file". `obj` = Bundle with KEY_JOB_ID. After this the plugin
     *  revokes the URI grant and deletes the temp file. Sent within the ACK deadline (§8);
     *  a missing ACK also ends the grant.
     */
    const val JOB_RESULT_ACK = 16

    /** [JOB_REPLY] `arg1` value: the plugin accepted the job. */
    const val JOB_ACCEPTED = 0

    /** [JOB_REPLY] `arg1` value: the plugin declined the job; `arg2` carries an [ErrorCode]. */
    const val JOB_REJECTED = 1

    // ---- Bundle keys (stable wire names, never renamed) ------------------------------------

    /** `String` job id (a UUID). Present in [JOB_START] and [JOB_RESULT]. */
    const val KEY_JOB_ID = "job_id"

    /** `String` source URL. Present in [JOB_START]. */
    const val KEY_URL = "url"

    /** `Int` [JobState] value. Present in [JOB_RESULT]. */
    const val KEY_STATE = "state"

    /** `Int` [ErrorCode] value. Present in [JOB_RESULT]. */
    const val KEY_ERROR = "error"

    /** `String` suggested file name (metadata-based, sanitized). Optional in [JOB_RESULT]. */
    const val KEY_FILE_NAME = "file_name"

    /** `String` read-only `content://` URI for the delivered MP3. Optional in [JOB_RESULT]. */
    const val KEY_RESULT_URI = "result_uri"

    /** `Boolean` acceptance flag, when a boolean answer is preferred over [JOB_REPLY] `arg1`. */
    const val KEY_ACCEPTED = "accepted"

    // HELLO: arg1 = protocol version, arg2 = status; job messages use framework Bundles.
    fun accepts(version: Int): Boolean = version == VERSION

    /** Job lifecycle states carried in [JOB_STATUS] `arg1` and [JOB_RESULT] [KEY_STATE].
     *  Happy path: VALIDATING → DOWNLOADING → CONVERTING → DELIVERING → SUCCEEDED.
     *  Only the next defined state (or FAILED/CANCELLED from any non-terminal state) is legal;
     *  backward and skipping transitions are forbidden (DECISIONS.md §5).
     */
    object JobState {
        const val VALIDATING = 1
        const val DOWNLOADING = 2
        const val CONVERTING = 3
        const val DELIVERING = 4
        const val SUCCEEDED = 5
        const val FAILED = 6
        const val CANCELLED = 7

        /** True for a defined state value (1..7). Unknown values sort as not known. */
        fun isKnown(state: Int): Boolean = state in VALIDATING..CANCELLED

        /** True for an end state: [SUCCEEDED], [FAILED] or [CANCELLED]. Terminal states are
         *  never left; [JOB_CANCEL] is a no-op in them (DECISIONS.md §5/§6).
         */
        fun isTerminal(state: Int): Boolean =
            state == SUCCEEDED || state == FAILED || state == CANCELLED
    }

    /** Distinguishable failure reasons carried in [JOB_REPLY] `arg2` and [JOB_RESULT] [KEY_ERROR].
     *  Fine-grained coding beyond this list is out of scope for v1 (DECISIONS.md §5).
     */
    object ErrorCode {
        const val NONE = 0
        const val INVALID_INPUT = 1
        const val UNSUPPORTED_SOURCE = 2
        const val SOURCE_UNAVAILABLE = 3
        const val NETWORK = 4
        const val CONVERSION_FAILED = 5
        const val STORAGE = 6
        const val TOO_LARGE = 7
        const val TIMEOUT = 8
        const val INTERNAL = 9

        /** Another job is already active and a second, different source was requested. The
         *  active job is untouched; the caller either waits, cancels it or reuses it when the
         *  source is identical (DECISIONS.md §6). Additive value, no existing number changed.
         */
        const val BUSY = 10

        /** The source exists or may exist, but refuses access (authentication/HTTP 401/403).
         *  Does not imply that the media is missing. Additive; older hosts use their generic fallback.
         */
        const val SOURCE_ACCESS_DENIED = 11

        /** Accepts only known codes; anything else is rejected. */
        fun isValid(code: Int): Boolean = code in NONE..SOURCE_ACCESS_DENIED
    }

    /** Phase bands for the overall progress 0–100 (DECISIONS.md §5):
     *  [VALIDATING] 0, [DOWNLOADING] 5..70, [CONVERTING] 70..95, [DELIVERING] 95..100.
     *  Framework-free helpers so both APKs compute progress identically.
     */
    object Progress {
        const val VALIDATING = 0
        const val DOWNLOADING_FROM = 5
        const val DOWNLOADING_TO = 70
        const val CONVERTING_FROM = 70
        const val CONVERTING_TO = 95
        const val DELIVERING_FROM = 95
        const val DELIVERING_TO = 100

        /** Clamps any input into the legal 0–100 range. */
        fun clamp(progress: Int): Int = progress.coerceIn(0, 100)

        /** Monotonic advance: returns the larger of the clamped [current] and clamped [next],
         *  so progress can never move backwards within a job.
         */
        fun advance(current: Int, next: Int): Int = maxOf(clamp(current), clamp(next))
    }
}
