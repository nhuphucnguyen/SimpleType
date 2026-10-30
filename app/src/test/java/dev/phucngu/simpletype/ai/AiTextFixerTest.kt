package dev.phucngu.simpletype.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.phucngu.simpletype.voice.VoiceLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric because the wire format uses Android's org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiTextFixerTest {

    private val glm = AiSettings(AiProvider.GLM_CODING_PLAN, "sk-test", "glm-5.3", AiProvider.GLM_CODING_PLAN.defaultBaseUrl)

    private fun reply(content: String) = JSONObject()
        .put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content))))
        .toString()

    private class Recorder(val result: HttpResult) : HttpTransport {
        var url = ""
        var headers = emptyMap<String, String>()
        var body = ""
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            this.url = url; this.headers = headers; this.body = body
            return result
        }
    }

    @Test
    fun sends_openai_chat_request_to_coding_plan_endpoint() = runBlocking {
        val t = Recorder(HttpResult(200, reply("I have 2 apples.")))
        AiTextFixer(glm, t).fix("i have two apples", VoiceLanguage.ENGLISH)

        assertEquals("https://api.z.ai/api/coding/paas/v4/chat/completions", t.url)
        assertEquals("Bearer sk-test", t.headers["Authorization"])
        val body = JSONObject(t.body)
        assertEquals("glm-5.3", body.getString("model"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("<text>\ni have two apples\n</text>"))
    }

    @Test
    fun custom_provider_omits_glm_thinking_flag_and_accepts_full_url() = runBlocking {
        val custom = AiSettings(AiProvider.CUSTOM, "k", "gpt-x", "https://example.com/v1/chat/completions/")
        val t = Recorder(HttpResult(200, reply("ok")))
        AiTextFixer(custom, t).fix("ok", VoiceLanguage.ENGLISH)

        assertEquals("https://example.com/v1/chat/completions", t.url)
        assertFalse(JSONObject(t.body).has("thinking"))
    }

    @Test
    fun returns_fixed_text_keeping_original_surrounding_whitespace() = runBlocking {
        val t = Recorder(HttpResult(200, reply("Call me at 3 pm.")))
        assertEquals("Call me at 3 pm. ", AiTextFixer(glm, t).fix("call me at three pm ", VoiceLanguage.ENGLISH))
    }

    @Test
    fun unauthorized_maps_to_auth_error_with_server_message() = runBlocking {
        val t = Recorder(HttpResult(401, """{"error":{"code":"1000","message":"Invalid API key"}}"""))
        try {
            AiTextFixer(glm, t).fix("x", VoiceLanguage.ENGLISH)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.AUTH, e.kind)
            assertEquals("Invalid API key", e.message)
        }
    }

    @Test
    fun network_failure_maps_to_network_error() = runBlocking {
        val t = HttpTransport { _, _, _ -> throw java.net.UnknownHostException("api.z.ai") }
        try {
            AiTextFixer(glm, t).fix("x", VoiceLanguage.ENGLISH)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.NETWORK, e.kind)
        }
    }

    @Test
    fun missing_key_is_not_configured_and_makes_no_request() = runBlocking {
        var called = false
        val t = HttpTransport { _, _, _ -> called = true; HttpResult(200, "") }
        try {
            AiTextFixer(glm.copy(apiKey = " "), t).fix("x", VoiceLanguage.ENGLISH)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.NOT_CONFIGURED, e.kind)
        }
        assertFalse(called)
    }

    @Test
    fun clean_reply_strips_wrappers_models_add() {
        assertEquals("Hi.", ChatCompletion.cleanReply("<think>hmm</think>\nHi.", "hi"))
        assertEquals("Hi.", ChatCompletion.cleanReply("```\nHi.\n```", "hi"))
        assertEquals("Hi.", ChatCompletion.cleanReply("<text>\nHi.\n</text>", "hi"))
        assertEquals("Hi.", ChatCompletion.cleanReply("\"Hi.\"", "hi"))
        // Quotes the user actually wrote are kept.
        assertEquals("\"Hi.\"", ChatCompletion.cleanReply("\"Hi.\"", "\"hi\""))
    }

    @Test
    fun settings_round_trip_per_provider() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("ai_test", Context.MODE_PRIVATE)
        AiSettings.save(prefs, AiSettings(AiProvider.CUSTOM, " key ", "m", "https://x/v1"))
        AiSettings.save(prefs, glm)

        val loaded = AiSettings.load(prefs)
        assertEquals(glm, loaded)
        val custom = AiSettings.forProvider(prefs, AiProvider.CUSTOM)
        assertEquals("key", custom.apiKey)
        assertEquals("https://x/v1", custom.baseUrl)
        // Untouched presets fall back to their defaults.
        assertEquals("glm-5.3-flash", AiSettings.forProvider(prefs, AiProvider.GLM_FLASH).model)
    }
}
