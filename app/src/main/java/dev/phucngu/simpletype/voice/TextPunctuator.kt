package dev.phucngu.simpletype.voice

/** Restores punctuation in uncased, punctuation-free ASR text (the Vietnamese model). */
interface TextPunctuator {
    /** Punctuates one stand-alone segment. */
    fun punctuate(text: String): String

    /**
     * Punctuates [text], spoken right after [previous] (already committed), using it as
     * context. [Continuation.boundary] is the mark the previous segment should really end with.
     */
    fun continueAfter(previous: String, text: String): Continuation
}

/** @property boundary `'.'`, `','` or `null` (no mark: the sentence runs on). */
data class Continuation(val boundary: Char?, val text: String)

/** How a dictated segment attaches to the dictated segment just before it. */
enum class SegmentJoin {
    /** Starts a new sentence (the previous segment keeps its period). */
    NONE,
    /** The previous segment's final period becomes a comma. */
    COMMA,
    /** The previous segment's final period is dropped; the sentence runs on. */
    CONTINUE,
}

/** A formatted segment ready to commit, and how it joins the previous one. */
data class Segment(val text: String, val join: SegmentJoin)
