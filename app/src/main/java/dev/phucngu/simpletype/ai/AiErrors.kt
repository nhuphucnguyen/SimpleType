package dev.phucngu.simpletype.ai

import android.content.Context
import dev.phucngu.simpletype.R

/** A short, user-facing description of this failure for the keyboard status line or settings. */
fun AiException.describe(context: Context): String = when (kind) {
    AiException.Kind.NOT_CONFIGURED -> context.getString(R.string.ai_error_not_configured)
    AiException.Kind.AUTH -> context.getString(R.string.ai_error_auth, message)
    AiException.Kind.RATE_LIMIT -> context.getString(R.string.ai_error_rate_limit, message)
    AiException.Kind.NETWORK -> context.getString(R.string.ai_error_network)
    AiException.Kind.SERVER -> context.getString(R.string.ai_error_server, message)
    AiException.Kind.BAD_RESPONSE -> context.getString(R.string.ai_error_bad_response)
}
