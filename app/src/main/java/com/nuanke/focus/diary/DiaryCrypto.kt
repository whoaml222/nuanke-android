package com.nuanke.focus.diary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versioned authenticated envelopes. Caller never stores decrypted data in cache/files. */
object DiaryCrypto {
    private val random = SecureRandom()
    private val localHeader = "NKD1".toByteArray(Charsets.US_ASCII)
    private val backupHeader = "NUANKE-DIARY-1".toByteArray(Charsets.US_ASCII)
    const val MAX_BACKUP = 64 * 1024 * 1024
    const val MAX_PHOTO = 1536 * 1024
    private val json = Json { ignoreUnknownKeys = false }

    fun encrypt(bytes: ByteArray, key: SecretKey, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return localHeader + cipher.iv + cipher.doFinal(bytes)
    }

    fun decrypt(bytes: ByteArray, key: SecretKey, aad: String, offset: Int = 0): ByteArray {
        require(offset >= 0 && bytes.size - offset >= 32 && bytes.copyOfRange(offset, offset + 4).contentEquals(localHeader))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(offset + 4, offset + 16)))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, offset + 16, bytes.size - offset - 16)
    }

    fun backup(archive: DiaryArchive, password: CharArray, photo: (String) -> ByteArray): ByteArray {
        val bytes = LimitedOutput(MAX_BACKUP)
        backupTo(bytes, archive, password, photo)
        return bytes.toByteArray()
    }

    fun backupTo(output: OutputStream, archive: DiaryArchive, password: CharArray, photo: (String) -> ByteArray) {
        require(password.size in 8..128)
        archive.validated()
        val manifest = json.encodeToString(archive).toByteArray(Charsets.UTF_8)
        require(manifest.size <= 16 * 1024 * 1024)
        var total = manifest.size.toLong()
        val salt = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey(password, salt))
        cipher.updateAAD("backup-v1".toByteArray(Charsets.UTF_8))
        val bounded = object : FilterOutputStream(output) {
            var written = 0L
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                require(written + len <= MAX_BACKUP)
                out.write(bytes, off, len); written += len
            }
            override fun write(b: Int) {
                require(written < MAX_BACKUP)
                out.write(b); written++
            }
        }
        bounded.write(backupHeader + salt + localHeader + cipher.iv)
        // Stream encrypted output directly to the user-selected destination; no plaintext temp ZIP.
        ZipOutputStream(CipherOutputStream(bounded, cipher)).use { zip ->
            zip.putNextEntry(ZipEntry("diary.json"))
            zip.write(manifest)
            zip.closeEntry()
            archive.entries.flatMap { it.photos }.forEach { id ->
                zip.putNextEntry(ZipEntry("photos/$id"))
                val data = photo(id)
                require(data.size <= MAX_PHOTO)
                total += data.size
                require(total <= MAX_BACKUP)
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    fun restore(bytes: ByteArray, password: CharArray): Pair<DiaryArchive, Map<String, ByteArray>> {
        require(bytes.size <= MAX_BACKUP && bytes.size > backupHeader.size + 48)
        require(bytes.copyOfRange(0, backupHeader.size).contentEquals(backupHeader))
        val saltEnd = backupHeader.size + 16
        val key = passwordKey(password, bytes.copyOfRange(backupHeader.size, saltEnd))
        // Verify the GCM tag before parsing or committing any imported record.
        val plain = decrypt(bytes, key, "backup-v1", saltEnd)
        var archive: DiaryArchive? = null
        val photos = mutableMapOf<String, ByteArray>()
        var total = 0
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && names.add(entry.name) && names.size <= 60_001)
                val content = readLimited(zip, if (entry.name == "diary.json") 16 * 1024 * 1024 else MAX_PHOTO)
                total += content.size
                require(total <= MAX_BACKUP)
                if (entry.name == "diary.json") {
                    archive = json.decodeFromString<DiaryArchive>(content.toString(Charsets.UTF_8)).validated()
                } else {
                    require(entry.name.startsWith("photos/"))
                    val id = entry.name.removePrefix("photos/")
                    require(java.util.UUID.fromString(id).toString() == id)
                    photos[id] = content
                }
            }
        }
        val result = requireNotNull(archive)
        require(result.entries.flatMap { it.photos }.toSet() == photos.keys)
        return result to photos
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = LimitedOutput(limit)
        input.copyTo(output)
        return output.toByteArray()
    }

    private fun passwordKey(password: CharArray, salt: ByteArray): SecretKey {
        require(password.size in 8..128)
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    private class LimitedOutput(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) {
            require(count.toLong() + len <= limit) { "Diary size limit" }
            super.write(b, off, len)
        }
        override fun write(b: Int) {
            require(count < limit) { "Diary size limit" }
            super.write(b)
        }
    }
}
