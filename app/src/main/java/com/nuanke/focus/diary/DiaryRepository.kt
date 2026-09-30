package com.nuanke.focus.diary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-owned autosave queue survives Activity destruction. No diary text is ever logged. */
class DiaryRepository internal constructor(private val context: Context, private val db: DiaryDatabase) {
    constructor(context: Context) : this(context, DiaryDatabase(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val stateLock = Any()
    private val pending = linkedMapOf<String, DiaryEntry>()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val mutableEntries = MutableStateFlow<List<DiaryEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val mutableStatus = MutableStateFlow("正在打开…")
    val status = mutableStatus.asStateFlow()
    @Volatile private var loaded = false

    init {
        scope.launch {
            for (ignored in changes) {
                delay(250)
                runCatching { mutex.withLock { flushLocked() } }
                    .onFailure { mutableStatus.value = "保存失败，内容仍在本次会话中；请勿退出，点此重试" }
            }
        }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                val records = db.entries()
                synchronized(stateLock) { mutableEntries.value = records; loaded = true }
            }
            flushLocked()
            val expired = mutableEntries.value.filter { it.expired(System.currentTimeMillis()) }
            if (expired.isNotEmpty()) {
                db.transaction { expired.forEach { db.remove(it.id) } }
                synchronized(stateLock) { mutableEntries.value = mutableEntries.value.filterNot { entry -> expired.any { it.id == entry.id } } }
            }
            db.cleanPhotos(mutableEntries.value.flatMap { it.photos }.toSet())
            mutableStatus.value = "已保存到本机"
        }
    }

    fun create(): String {
        val entry = DiaryEntry()
        synchronized(stateLock) {
            check(loaded && mutableEntries.value.size < 10_000)
            mutableEntries.value = listOf(entry) + mutableEntries.value
            enqueue(entry)
        }
        return entry.id
    }

    fun edit(id: String, transform: (DiaryEntry) -> DiaryEntry) {
        synchronized(stateLock) {
            check(loaded)
            val current = mutableEntries.value.firstOrNull { it.id == id } ?: return
            val next = transform(current).copy(id = id, updatedAt = System.currentTimeMillis()).validated()
            val textSize = mutableEntries.value.sumOf {
                val item = if (it.id == id) next else it
                item.title.length.toLong() + item.body.length + item.mood.length + item.tags.length
            }
            if (textSize > DiaryEntry.MAX_TOTAL_CHARS) {
                mutableStatus.value = "保存失败：随记文字空间已满，请先备份并清理回收站。"
                return
            }
            mutableEntries.value = mutableEntries.value.map { if (it.id == id) next else it }
            enqueue(next)
        }
    }

    private fun enqueue(entry: DiaryEntry) {
        pending[entry.id] = entry
        mutableStatus.value = "保存中…"
        changes.trySend(Unit)
    }
    fun retrySave() { changes.trySend(Unit) }

    private fun flushLocked() {
        val batch = synchronized(stateLock) { pending.toMap().also { pending.clear() } }
        try {
            if (batch.isNotEmpty()) db.transaction { batch.values.forEach(db::write) }
        } catch (failure: Exception) {
            synchronized(stateLock) { batch.forEach { (id, entry) -> pending.putIfAbsent(id, entry) } }
            throw failure
        }
        synchronized(stateLock) { if (pending.isEmpty()) mutableStatus.value = "已保存到本机" }
    }

    suspend fun permanentlyDelete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            flushLocked()
            synchronized(stateLock) {
                val entry = mutableEntries.value.firstOrNull { it.id == id } ?: return@withLock
                check(entry.deletedAt != null)
                db.transaction { db.remove(id) }
                pending.remove(id)
                mutableEntries.value = mutableEntries.value.filterNot { it.id == id }
            }
            db.cleanPhotos(mutableEntries.value.flatMap { it.photos }.toSet())
        }
    }

    suspend fun addPhotos(id: String, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutableEntries.value.firstOrNull { it.id == id && it.deletedAt == null } ?: return@withLock 0
            val selected = uris.take(DiaryEntry.MAX_PHOTOS - current.photos.size)
            // Decode before the transaction; no raw source file or location metadata is retained.
            val images = selected.map { UUID.randomUUID().toString() to importPhoto(it) }
            flushLocked()
            synchronized(stateLock) {
                val latest = mutableEntries.value.first { it.id == id }
                check(latest.deletedAt == null)
                val next = latest.copy(photos = latest.photos + images.map { it.first }, updatedAt = System.currentTimeMillis()).validated()
                db.transaction {
                    images.forEach { (photoId, bytes) -> db.putPhoto(photoId, bytes) }
                    db.write(next)
                }
                // The new snapshot incorporates edits made while the source image was decoding.
                pending.remove(id)
                mutableEntries.value = mutableEntries.value.map { if (it.id == id) next else it }
            }
            images.size
        }
    }

    suspend fun image(id: String): Bitmap = withContext(Dispatchers.IO) {
        val bytes = mutex.withLock { db.photo(id) }
        // Keep six visible previews from allocating six full-resolution ARGB bitmaps.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = if (maxOf(bounds.outWidth, bounds.outHeight) > 1000) 2 else 1
        }
        requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    }

    private fun importPhoto(uri: Uri): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            require(info.size.width.toLong() * info.size.height <= 150_000_000L)
            val scale = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return try {
            val output = ByteArrayOutputStream()
            for (quality in listOf(85, 65, 45)) {
                output.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= DiaryCrypto.MAX_PHOTO) return output.toByteArray()
            }
            error("Photo too large")
        } finally { bitmap.recycle() }
    }

    suspend fun export(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                flushLocked()
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                    DiaryCrypto.backupTo(it, DiaryArchive(entries = db.entries()), password, db::photo)
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun restore(uri: Uri, password: CharArray): Int = withContext(Dispatchers.IO) {
        try {
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { DiaryCrypto.readLimited(it, DiaryCrypto.MAX_BACKUP) }
            val (archive, photos) = DiaryCrypto.restore(bytes, password)
            mutex.withLock {
                flushLocked()
                val existing = mutableEntries.value.map { it.id }.toSet()
                // Merge only absent IDs. Never overwrite a newer local diary or resurrect local trash.
                val added = archive.entries.filter { it.id !in existing && !it.expired(System.currentTimeMillis()) }
                require(mutableEntries.value.size + added.size <= 10_000)
                require((mutableEntries.value + added).sumOf { it.title.length.toLong() + it.body.length + it.mood.length + it.tags.length } <= DiaryEntry.MAX_TOTAL_CHARS)
                val imported = mutableListOf<DiaryEntry>()
                db.transaction {
                    added.forEach { entry ->
                        val newPhotoIds = entry.photos.map { oldId ->
                            val photo = photos.getValue(oldId)
                            // Ensure a restored attachment is an actual small image before accepting it.
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
                            require(bounds.outWidth in 1..1600 && bounds.outHeight in 1..1600)
                            UUID.randomUUID().toString().also { db.putPhoto(it, photo) }
                        }
                        entry.copy(photos = newPhotoIds).also { db.write(it); imported.add(it) }
                    }
                }
                synchronized(stateLock) { mutableEntries.value = mutableEntries.value + imported }
                imported.size
            }
        } finally { password.fill('\u0000') }
    }
}
