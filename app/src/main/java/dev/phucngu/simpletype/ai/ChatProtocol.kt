package dev.phucngu.simpletype.ai

import dev.phucngu.simpletype.voice.VoiceLanguage

/**
 * The wire protocol an [AiProvider] speaks: how a fix request is encoded and how a successful
 * reply is decoded. Implement this for a new backend family and reference the instance from an
 * [AiProvider] entry; [AiTextFixer] picks it up with no further changes.
 *
 * Cross-protocol concerns live elsewhere on purpose: prompts and reply cleanup in
 * [ChatCompletion] (shared by every protocol), error-message extraction and HTTP-status mapping
 * in [AiTextFixer] (both families speak `error.message` and the same status semantics).
 */
interface ChatProtocol {
    /** Path appended to the provider's base URL, e.g. "/v1/messages". */
    val endpointPath: String

    /** Headers this protocol needs beyond the shared auth/content-type set. */
    fun extraHeaders(): Map<String, String> = emptyMap()

    fun requestBody(settings: AiSettings, text: String, language: VoiceLanguage): String

    /** The assistant's reply text, or throws [AiException] describing an error payload. */
    fun parseReply(json: String): String
}
