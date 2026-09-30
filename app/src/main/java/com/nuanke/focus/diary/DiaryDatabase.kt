package com.nuanke.focus.diary

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only random identifiers and authenticated ciphertext reach SQLite, including its journal. */
internal class DiaryDatabase(context: Context, private val testKey: SecretKey? = null) :
    SQLiteOpenHelper(context, "private_diary.db", null, 1) {
    private val json = Json
    private val key: SecretKey by lazy { testKey ?: loadKey() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE photos (id TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun entries(): List<DiaryEntry> = readableDatabase.rawQuery("SELECT id,payload FROM entries", null).use { c ->
        buildList {
            var total = 0L
            while (c.moveToNext()) {
                val id = c.getString(0)
                val decoded = DiaryCrypto.decrypt(c.getBlob(1), key, "entry:$id")
                val entry = json.decodeFromString<DiaryEntry>(decoded.toString(Charsets.UTF_8)).validated()
                check(entry.id == id)
                total += entry.title.length + entry.body.length + entry.mood.length + entry.tags.length
                check(size < 10_000 && total <= DiaryEntry.MAX_TOTAL_CHARS)
                add(entry)
            }
        }
    }

    fun write(entry: DiaryEntry) {
        entry.validated()
        writableDatabase.insertWithOnConflict("entries", null, ContentValues().apply {
            put("id", entry.id)
            put("payload", DiaryCrypto.encrypt(json.encodeToString(entry).toByteArray(Charsets.UTF_8), key, "entry:${entry.id}"))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    fun photo(id: String): ByteArray = readableDatabase.rawQuery("SELECT payload FROM photos WHERE id=?", arrayOf(id)).use { c ->
        check(c.moveToFirst())
        DiaryCrypto.decrypt(c.getBlob(0), key, "photo:$id")
    }
    fun putPhoto(id: String, data: ByteArray) {
        require(data.size <= DiaryCrypto.MAX_PHOTO)
        writableDatabase.insertOrThrow("photos", null, ContentValues().apply {
            put("id", id)
            put("payload", DiaryCrypto.encrypt(data, key, "photo:$id"))
        })
    }
    fun remove(id: String) { writableDatabase.delete("entries", "id=?", arrayOf(id)) }
    fun cleanPhotos(referenced: Set<String>) {
        val orphanIds = readableDatabase.rawQuery("SELECT id FROM photos", null).use { c ->
            buildList { while (c.moveToNext()) { if (c.getString(0) !in referenced) add(c.getString(0)) } }
        }
        orphanIds.forEach { writableDatabase.delete("photos", "id=?", arrayOf(it)) }
    }
    fun transaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun loadKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // Never silently replace a lost key for an existing vault.
        readableDatabase.rawQuery("SELECT count(*) FROM entries", null).use { c ->
            c.moveToFirst(); check(c.getInt(0) == 0) { "Diary key unavailable" }
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    companion object { private const val KEY_ALIAS = "nuanke.diary.v1" }
}
