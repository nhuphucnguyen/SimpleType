package dev.phucngu.simpletype.voice

/**
 * Tiny extractor for the JSON Vosk's recognizer returns, e.g.
 * `{"partial" : "hello wor"}` or `{"text" : "hello world"}`.
 *
 * Avoids pulling in a JSON library (and Android's `org.json`, which isn't on the JVM unit-
 * test classpath) for what is a single string field. Pure Kotlin, unit-testable.
 */
object VoskResult {

    /** Final transcript text from a `getResult()` / `getFinalResult()` payload. */
    fun finalText(json: String): String = field(json, "text") ?: ""

    /** Streaming hypothesis from a `getPartialResult()` payload. */
    fun partialText(json: String): String = field(json, "partial") ?: ""

    /** Extract a top-level string field's value, honouring backslash escapes. */
    private fun field(json: String, name: String): String? {
        val key = "\"$name\""
        val keyIndex = json.indexOf(key)
        if (keyIndex < 0) return null
        var i = json.indexOf(':', keyIndex + key.length)
        if (i < 0) return null
        i++
        while (i < json.length && json[i] != '"') {
            if (!json[i].isWhitespace()) return null // value isn't a string
            i++
        }
        if (i >= json.length) return null
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> { sb.append(json[i + 1]); i += 2 }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
