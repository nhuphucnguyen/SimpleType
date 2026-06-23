package dev.phucngu.simpletype.ime.keyboard.layout

import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyboardRow

internal fun row(vararg keys: Key) = KeyboardRow(keys.toList())

internal fun letter(char: Char) = Key(char.code, char.toString())

internal fun lettersRow(chars: String, sideWeight: Float = 0f) =
    KeyboardRow(chars.map(::letter), sideWeight)
