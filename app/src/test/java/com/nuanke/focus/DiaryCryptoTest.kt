package com.nuanke.focus

import com.nuanke.focus.diary.*
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.UUID
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class DiaryCryptoTest {
    private val key get() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `local encryption is randomized and bound to record identity`() {
        val secret = key
        val plain = "今天想记录的私密中文".toByteArray()
        val first = DiaryCrypto.encrypt(plain, secret, "entry:first")
        val second = DiaryCrypto.encrypt(plain, secret, "entry:first")
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains("私密中文"))
        assertArrayEquals(plain, DiaryCrypto.decrypt(first, secret, "entry:first"))
        assertThrows(Exception::class.java) { DiaryCrypto.decrypt(first, secret, "entry:second") }
    }

    @Test fun `local encryption rejects tampering and wrong keys`() {
        val secret = key
        val cipher = DiaryCrypto.encrypt("test".toByteArray(), secret, "entry:id")
        assertThrows(Exception::class.java) { DiaryCrypto.decrypt(cipher, key, "entry:id") }
        cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { DiaryCrypto.decrypt(cipher, secret, "entry:id") }
    }

    @Test fun `backup round trip preserves photos text mood and trash`() {
        val photoId = UUID.randomUUID().toString()
        val entries = listOf(DiaryEntry(body = "很长的一天\n回家以后", mood = "平静", tags = "生活", photos = listOf(photoId)),
            DiaryEntry(title = "收起的一页", deletedAt = 123))
        val archive = DiaryArchive(entries = entries)
        val image = byteArrayOf(1, 2, 3, 4)
        val bytes = DiaryCrypto.backup(archive, "private-password".toCharArray()) { image }
        assertFalse(bytes.toString(Charsets.UTF_8).contains("回家以后"))
        val (restored, photos) = DiaryCrypto.restore(bytes, "private-password".toCharArray())
        assertEquals(archive, restored)
        assertArrayEquals(image, photos[photoId])
    }

    @Test fun `backup rejects wrong password truncated data and tampering`() {
        val bytes = DiaryCrypto.backup(DiaryArchive(entries = listOf(DiaryEntry(body = "记录"))), "abcdefgh".toCharArray()) { error("no photo") }
        assertThrows(Exception::class.java) { DiaryCrypto.restore(bytes, "wrong-password".toCharArray()) }
        assertThrows(Exception::class.java) { DiaryCrypto.restore(bytes.copyOf(bytes.size - 1), "abcdefgh".toCharArray()) }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 8).toByte()
        assertThrows(Exception::class.java) { DiaryCrypto.restore(bytes, "abcdefgh".toCharArray()) }
    }

    @Test fun `backup rejects weak password unknown format and repeated ids`() {
        val entry = DiaryEntry()
        assertThrows(Exception::class.java) { DiaryCrypto.backup(DiaryArchive(entries = listOf(entry)), "short".toCharArray()) { byteArrayOf() } }
        assertThrows(Exception::class.java) { DiaryArchive(2, listOf(entry)).validated() }
        assertThrows(Exception::class.java) { DiaryArchive(entries = listOf(entry, entry)).validated() }
    }

    @Test fun `invalid date file traversal and excess photos are rejected`() {
        assertThrows(Exception::class.java) { DiaryEntry(date = "2026-02-30").validated() }
        assertThrows(Exception::class.java) { DiaryEntry(photos = listOf("../../private")).validated() }
        assertThrows(Exception::class.java) { DiaryEntry(photos = List(7) { UUID.randomUUID().toString() }).validated() }
        assertThrows(Exception::class.java) { DiaryEntry(body = "a".repeat(100_001)).validated() }
    }

    @Test fun `search backdating and thirty day trash boundary`() {
        val entry = DiaryEntry(date = "2026-09-01", title = "Day One", body = "今天学习", mood = "平静", tags = "生活")
        assertEquals(LocalDate.of(2026, 9, 1).toString(), entry.validated().date)
        assertTrue(entry.matches("day one"))
        assertTrue(entry.matches("学习"))
        assertTrue(entry.matches("平静"))
        assertFalse(entry.matches("不存在"))
        val trashed = entry.copy(deletedAt = 1000L)
        assertFalse(trashed.expired(999))
        assertFalse(trashed.expired(1000 + DiaryEntry.TRASH_RETENTION - 1))
        assertTrue(trashed.expired(1000 + DiaryEntry.TRASH_RETENTION))
        assertFalse(entry.expired(Long.MAX_VALUE))
    }

    @Test fun `bounded reader rejects oversized input`() {
        assertThrows(Exception::class.java) { DiaryCrypto.readLimited(ByteArrayInputStream(ByteArray(11)), 10) }
        assertEquals(10, DiaryCrypto.readLimited(ByteArrayInputStream(ByteArray(10)), 10).size)
    }
}
