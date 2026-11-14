package dev.phucngu.simpletype.ai

import dev.phucngu.simpletype.voice.VoiceLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiWriterTest {

    private val glm = AiSettings(AiProvider.GLM_CODING_PLAN, "sk-test", "glm-5.3-flash", AiProvider.GLM_CODING_PLAN.defaultBaseUrl)
    private val claude = AiSettings(AiProvider.CLAUDE_SUBSCRIPTION, "sk-ant-oat01-test", "claude-haiku-5-5", AiProvider.CLAUDE_SUBSCRIPTION.defaultBaseUrl)

    private fun reply(content: String) = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))))
        .toString()

    private fun anthropicReply(text: String) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .toString()

    private class Recorder(val result: HttpResult) : HttpTransport {
        var body = ""
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            this.body = body
            return result
        }
    }

    @Test
    fun sends_writer_prompt_not_proofreader_even_with_custom_fix_prompt() = runBlocking {
        val t = Recorder(HttpResult(200, reply("On my way!")))
        val settings = glm.copy(promptOverride = true, customPrompt = "Be a pirate.")
        AiWriter(settings, t).write("tell him I'm coming", WriterContext())
        val messages = JSONObject(t.body).getJSONArray("messages")
        assertEquals(AiWriter.SYSTEM_PROMPT, messages.getJSONObject(0).getString("content"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("<request>\ntell him I'm coming\n</request>"))
    }

    @Test
    fun claude_writer_keeps_billing_tag_and_structured_output() = runBlocking {
        val t = Recorder(HttpResult(200, anthropicReply("{\"text\":\"Dạ, em tới liền.\"}")))
        val draft = AiWriter(claude, t).write("nói là em tới liền", WriterContext(language = VoiceLanguage.VIETNAMESE))
        assertEquals("Dạ, em tới liền.", draft)
        val body = JSONObject(t.body)
        val system = body.getJSONArray("system")
        assertTrue(system.getJSONObject(0).getString("text").startsWith("x-anthropic-billing-header:"))
        assertEquals(AiWriter.SYSTEM_PROMPT, system.getJSONObject(1).getString("text"))
        assertEquals(AiWriter.MAX_TOKENS, body.getInt("max_tokens"))
        assertTrue(body.has("output_config"))
        assertFalse(body.has("temperature"))
    }

    @Test
    fun user_message_marks_cursor_in_field_text() {
        val msg = AiWriter.userMessage(
            "reply yes",
            WriterContext(before = "Are you free Friday?\n", after = "", app = "com.whatsapp", fieldHint = "Message"),
        )
        assertTrue(msg.contains("<field>\nAre you free Friday?\n${AiWriter.CURSOR}\n</field>"))
        assertTrue(msg.contains("App: com.whatsapp"))
        assertTrue(msg.contains("Field: Message"))
    }

    @Test
    fun user_message_puts_selection_up_front() {
        val msg = AiWriter.userMessage("make it formal", WriterContext(before = "Hi. ", selected = "gimme the file", after = " Thanks"))
        assertTrue(msg.contains("<selected>\ngimme the file\n</selected>"))
        assertTrue(msg.contains("Hi. ${AiWriter.CURSOR} Thanks"))
    }

    @Test
    fun empty_field_is_stated() {
        assertTrue(AiWriter.userMessage("hi", WriterContext()).contains("The field is empty."))
    }

    @Test
    fun clean_draft_strips_wrappers_and_preamble() {
        assertEquals("See you at 5.", AiWriter.cleanDraft("Here's a message you can send:\n\"See you at 5.\""))
        assertEquals("See you at 5.", AiWriter.cleanDraft("<think>x</think>```\nSee you at 5.\n```"))
        // Inner quotes stay.
        assertEquals("\"Hi\" she said, \"bye\"", AiWriter.cleanDraft("\"Hi\" she said, \"bye\""))
        // Multi-line drafts keep their line breaks.
        assertEquals("Hi Anna,\n\nThanks!", AiWriter.cleanDraft("Hi Anna,\n\nThanks!"))
    }

    @Test
    fun blank_draft_is_a_bad_response() = runBlocking {
        try {
            AiWriter(glm, Recorder(HttpResult(200, reply("<think>hmm</think>  ")))).write("x", WriterContext())
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.BAD_RESPONSE, e.kind)
        }
    }
}
