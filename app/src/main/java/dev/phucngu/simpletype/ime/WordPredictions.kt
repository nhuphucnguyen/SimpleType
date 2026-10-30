package dev.phucngu.simpletype.ime

import android.content.res.AssetManager
import dev.phucngu.simpletype.predict.NextWordPredictor
import dev.phucngu.simpletype.predict.NgramModel
import dev.phucngu.simpletype.predict.TypingContext
import dev.phucngu.simpletype.predict.UserHistory
import dev.phucngu.simpletype.voice.VoiceLanguage
import java.io.File

/**
 * Owns the per-language prediction models and the user's learned history, and turns the
 * text before the cursor into suggestion-strip words. Models load off the main thread
 * ([load]); until then [suggest] returns nothing. Everything else runs on the main thread.
 */
class WordPredictions(private val dir: File) {

    private var models: Map<VoiceLanguage, NgramModel> = emptyMap()
    private var histories: Map<VoiceLanguage, UserHistory> = emptyMap()
    private var predictors: Map<VoiceLanguage, NextWordPredictor> = emptyMap()

    /** Blocking; call from a background thread, then [publish] the result on the main thread. */
    fun load(assets: AssetManager): Loaded {
        val models = HashMap<VoiceLanguage, NgramModel>()
        val histories = HashMap<VoiceLanguage, UserHistory>()
        for (lang in VoiceLanguage.entries) {
            try {
                models[lang] = assets.open(assetFor(lang)).use { NgramModel.parse(it) }
            } catch (e: Exception) {
                android.util.Log.w("WordPredictions", "Failed to load prediction model for $lang", e)
            }
            histories[lang] = runCatching { UserHistory.parse(historyFile(lang).readText()) }
                .getOrElse { UserHistory() }
        }
        return Loaded(models, histories)
    }

    class Loaded(val models: Map<VoiceLanguage, NgramModel>, val histories: Map<VoiceLanguage, UserHistory>)

    fun publish(loaded: Loaded) {
        models = loaded.models
        histories = loaded.histories
        rebuild()
    }

    private fun rebuild() {
        predictors = histories.mapValues { (lang, h) -> NextWordPredictor(models[lang], h) }
    }

    /** Suggestions for the cursor position, best first (see [NextWordPredictor.suggest]). */
    fun suggest(language: VoiceLanguage, ctx: TypingContext): List<String> =
        predictors[language]?.suggest(ctx) ?: emptyList()

    fun learn(language: VoiceLanguage, ctx: TypingContext, word: String) {
        predictors[language]?.learn(ctx, word)
    }

    /** Snapshots of histories with unsaved changes, to write with [write] off the main thread. */
    fun pendingSaves(): Map<VoiceLanguage, String> =
        histories.filterValues { it.dirty }.mapValues { it.value.serialize() }

    fun write(saves: Map<VoiceLanguage, String>) {
        for ((lang, text) in saves) {
            runCatching {
                val file = historyFile(lang)
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    /** Drops the in-memory history after Settings deleted the files ([forget]). */
    fun forgetInMemory() {
        if (histories.isEmpty()) return
        histories = histories.mapValues { UserHistory() }
        rebuild()
    }

    private fun historyFile(lang: VoiceLanguage) = historyFile(dir, lang)

    companion object {
        /** Bumped by Settings when the user clears learned words, so the keyboard drops its copy. */
        const val PREF_HISTORY_GENERATION = "predict_history_generation"
        const val PREF_ENABLED = "predict_enabled"

        private fun historyFile(dir: File, lang: VoiceLanguage) =
            File(dir, "predict_history_${lang.name.lowercase()}.txt")

        /** Deletes the learned words of every language. */
        fun forget(dir: File) {
            VoiceLanguage.entries.forEach { historyFile(dir, it).delete() }
        }

        fun assetFor(lang: VoiceLanguage) = when (lang) {
            VoiceLanguage.ENGLISH -> "predict/en.txt"
            VoiceLanguage.VIETNAMESE -> "predict/vi.txt"
        }
    }
}
