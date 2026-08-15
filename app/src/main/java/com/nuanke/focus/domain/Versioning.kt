package com.nuanke.focus.domain

object Versioning {
    fun isNewer(candidate: String, current: String): Boolean {
        val left = normalize(candidate)
        val right = normalize(current)
        val width = maxOf(left.size, right.size)
        for (index in 0 until width) {
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    private fun normalize(version: String): List<Int> = version
        .trim()
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}

