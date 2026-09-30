package com.nuanke.focus

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import com.nuanke.focus.diary.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], application = NuankeApplication::class)
class DiaryRuntimeTest {
    private val app get() = RuntimeEnvironment.getApplication() as NuankeApplication
    private fun database(): DiaryDatabase = DiaryDatabase(app, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Test fun `main to diary handoff keeps own activity visible until both stop`() {
        val main = Any()
        val diary = Any()
        val health = com.nuanke.focus.diagnostics.ServiceDiagnostics
        health.activityVisible(main, true)
        health.activityVisible(diary, true)
        health.activityVisible(main, false)
        assertTrue(health.activityVisible)
        health.activityVisible(diary, false)
        assertFalse(health.activityVisible)
    }

    @Test fun `entries and photos are encrypted in sqlite and transaction failure rolls back`() {
        val db = database()
        try {
            val photo = java.util.UUID.randomUUID().toString()
            val entry = DiaryEntry(title = "private-title-not-in-db", body = "private-body-not-in-db", photos = listOf(photo))
            db.transaction { db.putPhoto(photo, byteArrayOf(10, 20)); db.write(entry) }
            assertEquals(entry, db.entries().single())
            assertArrayEquals(byteArrayOf(10, 20), db.photo(photo))
            db.readableDatabase.rawQuery("SELECT payload FROM entries", null).use { c ->
                c.moveToFirst()
                assertFalse(c.getBlob(0).toString(Charsets.UTF_8).contains(entry.title))
                assertFalse(c.getBlob(0).toString(Charsets.UTF_8).contains(entry.body))
            }
            assertThrows(Exception::class.java) {
                db.transaction { db.write(entry.copy(body = "overwrite")); error("forced failure") }
            }
            assertEquals(entry, db.entries().single())
        } finally { db.close() }
    }

    @Test fun `rapid edits persist newest value and trash can restore`() = runBlocking {
        val db = database()
        val repo = DiaryRepository(app, db)
        repo.load()
        val id = repo.create()
        repeat(100) { value -> repo.edit(id) { it.copy(body = "draft-$value") } }
        repo.load() // Flush all accepted writes through the same repository lock.
        assertEquals("draft-99", db.entries().single().body)
        repo.edit(id) { it.copy(deletedAt = System.currentTimeMillis()) }
        repo.load()
        assertNotNull(db.entries().single().deletedAt)
        repo.edit(id) { it.copy(deletedAt = null) }
        repo.load()
        assertNull(db.entries().single().deletedAt)
    }

    @Test fun `expired trash cleans only its own photos and permanent delete requires trash`() = runBlocking {
        val db = database()
        val removedPhoto = java.util.UUID.randomUUID().toString()
        val keepPhoto = java.util.UUID.randomUUID().toString()
        val keep = DiaryEntry(body = "keep", photos = listOf(keepPhoto))
        db.putPhoto(removedPhoto, byteArrayOf(1)); db.putPhoto(keepPhoto, byteArrayOf(2))
        db.write(DiaryEntry(photos = listOf(removedPhoto), deletedAt = System.currentTimeMillis() - DiaryEntry.TRASH_RETENTION))
        db.write(keep)
        val repo = DiaryRepository(app, db)
        repo.load()
        assertEquals(listOf(keep), repo.entries.value)
        assertThrows(Exception::class.java) { db.photo(removedPhoto) }
        assertArrayEquals(byteArrayOf(2), db.photo(keepPhoto))
        try { repo.permanentlyDelete(keep.id); fail("active record must be retained") } catch (_: IllegalStateException) { }
        repo.edit(keep.id) { it.copy(deletedAt = System.currentTimeMillis()) }
        repo.permanentlyDelete(keep.id)
        assertTrue(db.entries().isEmpty())
    }

    @Test fun `save failure retains pending text and retry persists latest draft`() = runBlocking {
        val db = database()
        val repo = DiaryRepository(app, db)
        repo.load()
        val id = repo.create()
        repo.load()
        db.writableDatabase.execSQL("CREATE TRIGGER reject_write BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        repo.edit(id) { it.copy(body = "first unsaved draft") }
        try { repo.load(); fail("expected injected failure") } catch (_: android.database.SQLException) { }
        assertEquals("first unsaved draft", repo.entries.value.single().body)
        repo.edit(id) { it.copy(body = "latest unsaved draft") }
        db.writableDatabase.execSQL("DROP TRIGGER reject_write")
        repo.load()
        assertEquals("latest unsaved draft", db.entries().single().body)
    }

    @Test fun `backup merge never overwrites local diary and wrong password changes nothing`() = runBlocking {
        val db = database()
        val repo = DiaryRepository(app, db)
        repo.load()
        val id = repo.create()
        repo.edit(id) { it.copy(body = "local newer content") }
        repo.load()
        val original = repo.entries.value.single()
        val added = DiaryEntry(body = "new from backup")
        val archive = DiaryArchive(entries = listOf(original.copy(body = "old content"), added))
        val bytes = DiaryCrypto.backup(archive, "backup-password".toCharArray()) { error("no photo") }
        val file = java.io.File(app.cacheDir, "merge-test.nkdiary").apply { writeBytes(bytes) }
        val uri = android.net.Uri.fromFile(file)
        try { repo.restore(uri, "wrong-password".toCharArray()); fail("must authenticate archive") } catch (_: Exception) { }
        assertEquals(listOf(original), db.entries())
        assertEquals(1, repo.restore(uri, "backup-password".toCharArray()))
        assertEquals("local newer content", db.entries().first { it.id == id }.body)
        assertEquals(0, repo.restore(uri, "backup-password".toCharArray()))
        assertEquals(2, db.entries().size)
        val output = java.io.File(app.cacheDir, "export-test.nkdiary")
        val password = "export-password".toCharArray()
        repo.export(android.net.Uri.fromFile(output), password)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(2, DiaryCrypto.restore(output.readBytes(), "export-password".toCharArray()).first.entries.size)
    }

    @Test fun `wrong vault key fails closed without resetting existing entries`() {
        val db = database()
        db.write(DiaryEntry(body = "must not erase"))
        db.close()
        val wrong = database()
        try {
            assertThrows(Exception::class.java) { wrong.entries() }
            wrong.readableDatabase.rawQuery("SELECT count(*) FROM entries", null).use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
        } finally { wrong.close() }
    }

    @Test fun `diary starts locked blocks screenshots and saves no content or unlock flag`() {
        val controller = Robolectric.buildActivity(DiaryActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(activity.unlocked)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val state = Bundle()
        controller.saveInstanceState(state)
        assertNull(state.get("unlocked"))
        assertNull(state.get("body"))
        controller.pause().stop().destroy()
    }

    @Test fun `leaving diary relocks and missing device credential never unlocks`() {
        val controller = Robolectric.buildActivity(DiaryActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(false)
        activity.authenticate()
        assertFalse(activity.unlocked)
        assertFalse(activity.authBusy)
        assertTrue(activity.authMessage.contains("锁屏密码"))
        val state = ReflectionHelpers.getField<MutableState<Boolean>>(activity, "unlocked\$delegate")
        state.value = true
        assertTrue(activity.unlocked)
        controller.pause().stop()
        assertFalse(activity.unlocked)
        controller.destroy()
    }
}
