package dev.phucngu.simpletype.ime

import android.text.InputType

/** Which fields get word suggestions, from the field's `inputType`. */
object SuggestionFields {

    /**
     * Free text where the strip may suggest words: not an email address, password or filter.
     *
     * The "no suggestions" flag doesn't hide them, as on Gboard: Chrome sets it for any web field
     * with `autocomplete=off` or `autocorrect=off`, which many sites put on chat and search boxes.
     * Web address fields get them too: the browser's address bar doubles as a search box.
     */
    fun showsSuggestions(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /**
     * Whether suggestions may learn the words typed: not in web addresses (browsing history), nor
     * where the app asked for no suggestions, which often marks usernames and codes.
     */
    fun learnsWords(inputType: Int): Boolean =
        showsSuggestions(inputType) &&
            inputType and InputType.TYPE_MASK_VARIATION != InputType.TYPE_TEXT_VARIATION_URI &&
            inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
}
