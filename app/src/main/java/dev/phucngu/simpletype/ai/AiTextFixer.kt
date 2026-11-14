package dev.phucngu.simpletype.ai

import dev.phucngu.simpletype.voice.VoiceLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP POST: status code and response body. Swappable so the AI features are testable offline. */
fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResult
}

data class HttpResult(val code: Int, val body: String)

/** Plain [HttpURLConnection] transport; blocking, so callers run it off the main thread. */
object UrlConnectionTransport : HttpTransport {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 45_000

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Sends an [AiRequest] to the configured provider and returns the model's raw reply text.
 * Throws [AiException] on any failure.
 */
class AiClient(
    private val settings: AiSettings,
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    suspend fun complete(request: AiRequest): String = withContext(Dispatchers.IO) {
        if (!settings.isConfigured) throw AiException(AiException.Kind.NOT_CONFIGURED, "")
        val protocol = settings.provider.protocol
        val headers = buildMap {
            put("Authorization", "Bearer ${settings.apiKey.trim()}")
            put("Content-Type", "application/json; charset=utf-8")
            put("Accept", "application/json")
            put("Accept-Language", "en-US,en")
            protocol.extraHeaders().forEach { (k, v) -> put(k, v) }
        }
        val result = try {
            transport.post(settings.endpoint, headers, protocol.requestBody(settings, request))
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        when (result.code) {
            in 200..299 -> protocol.parseReply(result.body)
            401, 403 -> throw AiException(AiException.Kind.AUTH, ChatCompletion.errorMessage(result.body))
            429 -> throw AiException(AiException.Kind.RATE_LIMIT, ChatCompletion.errorMessage(result.body))
            else -> throw AiException(
                AiException.Kind.SERVER,
                "HTTP ${result.code}: ${ChatCompletion.errorMessage(result.body)}",
            )
        }
    }
}

/**
 * Asks the configured model to fix typos, punctuation and spelled-out numbers in a piece of
 * text, returning the corrected text. Throws [AiException] on any failure.
 */
class AiTextFixer(
    private val settings: AiSettings,
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    suspend fun fix(text: String, language: VoiceLanguage): String {
        val request = AiRequest(
            system = settings.systemPrompt,
            user = ChatCompletion.userMessage(text, language),
            maxTokens = maxTokensFor(text),
            temperature = 0.2,
        )
        return ChatCompletion.cleanReply(AiClient(settings, transport).complete(request), text)
    }

    companion object {
        /** Output tracks input length, with headroom for fixes. */
        fun maxTokensFor(text: String): Int = (text.length * 2 + 512).coerceIn(1024, 8192)
    }
}
