package com.nomesame.musicmonster.extension.api

/** Optional additive v1 capability. Each commit closes one document and requests one successor.
 * No source, job, directory or producer-specific data crosses this channel.
 * Older producers ignore EXTRA_CHANNEL and keep the original single-document result flow.
 */
object DocumentSequence {
    const val EXTRA_CHANNEL = "extension_document_sequence_v1"
    const val COMMIT_NEXT = 1
    const val OK = 1
    const val ERROR = 0
    const val REQUEST = "request"
    const val NONCE = "nonce"
    const val OUTPUT = "output"
    const val NAME = "name"
    const val REPLY = "reply"
}
