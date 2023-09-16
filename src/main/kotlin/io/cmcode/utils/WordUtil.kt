package io.cmcode.utils

import java.io.File

val words = readWordlist("resources/programmers_wordlist.txt")

fun readWordlist(filename: String): List<String> {
    val inputStream = File(filename).inputStream()
    val words = mutableListOf<String>()
    inputStream.bufferedReader().forEachLine { words.add(it) }
    return words
}

fun getRandomWords(amount: Int): List<String> {
    var curAmount = 0
    val result = mutableListOf<String>()
    while (curAmount < amount) {
        val word = words.random()
        if (!result.contains(word)) {
            result.add(word)
            curAmount++
        }
    }
    return result
}

// apple juice
// _____ _____
// _ _ _ _ _   _ _ _ _ _
fun String.transformToUnderscores() =
    toCharArray().map {
        if (it != ' ') '_' else ' '
    }.joinToString(" ")