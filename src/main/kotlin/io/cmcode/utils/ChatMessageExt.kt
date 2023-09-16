package io.cmcode.utils

import io.cmcode.data.models.ChatMessage
import java.util.*

fun ChatMessage.matchesWord(word: String): Boolean {
    return message.lowercase(Locale.US).trim() == word.lowercase(Locale.US).trim()
}