package com.nuanke.focus.diary

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class DiaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: String = LocalDate.now().toString(),
    val title: String = "",
    val body: String = "",
    val mood: String = "",
    val tags: String = "",
    val photos: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
) {
    val isEmpty: Boolean get() = title.isBlank() && body.isBlank() && photos.isEmpty() && mood.isBlank() && tags.isBlank()
    fun matches(query: String): Boolean = query.isBlank() ||
        listOf(title, body, tags, mood).any { it.contains(query.trim(), ignoreCase = true) }
    fun expired(now: Long): Boolean = deletedAt?.let { now >= it && now - it >= TRASH_RETENTION } ?: false
    fun validated(): DiaryEntry = apply {
        require(UUID.fromString(id).toString() == id)
        require(LocalDate.parse(date).toString() == date)
        require(title.length <= 120 && body.length <= 100_000 && mood.length <= 20 && tags.length <= 120)
        require(photos.size <= MAX_PHOTOS && photos.distinct().size == photos.size)
        photos.forEach { require(UUID.fromString(it).toString() == it) }
        require(createdAt >= 0 && updatedAt >= 0 && (deletedAt == null || deletedAt >= 0))
    }
    companion object {
        const val MAX_PHOTOS = 6
        const val TRASH_RETENTION = 30L * 24 * 60 * 60 * 1_000
        const val MAX_TOTAL_CHARS = 4_000_000
    }
}

@Serializable
data class DiaryArchive(val format: Int = 1, val entries: List<DiaryEntry>) {
    fun validated(): DiaryArchive = apply {
        require(format == 1 && entries.size <= 10_000)
        require(entries.map { it.id }.distinct().size == entries.size)
        entries.forEach { it.validated() }
        require(entries.sumOf { it.title.length.toLong() + it.body.length + it.mood.length + it.tags.length } <= DiaryEntry.MAX_TOTAL_CHARS)
        val photos = entries.flatMap { it.photos }
        require(photos.distinct().size == photos.size)
    }
}
