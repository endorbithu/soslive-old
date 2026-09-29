package info.soslive.stream.drive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SosliveDriveTest {

    private val api = FakeDriveApi()
    private val cache = InMemoryDriveCache()
    private val drive = SosliveDrive(api, cache, "https://web.example/")

    @Test
    fun `oldest SOSlive folder wins and is cached`() = runTest {
        api.addFolder("newer", Instant.parse("2026-02-01T00:00:00Z"))
        api.addFolder("older", Instant.parse("2025-01-01T00:00:00Z"))
        assertEquals("older", drive.folderId())
        assertEquals("older", cache.folder)
    }

    @Test
    fun `folder is created when missing and searched again when the cached one was trashed`() = runTest {
        val created = drive.folderId()
        assertEquals(1, api.items.count { it.folder })
        api.trash(created)
        val again = drive.folderId()
        assertTrue(again != created)
        assertEquals(again, cache.folder)
    }

    @Test
    fun `event is created in the folder, shared and linked`() = runTest {
        val start = Instant.parse("2026-09-29T14:03:22Z")
        val created = drive.createEvent(start, EventDocument(stream = "https://s/x.m3u8"))
        assertEquals("2026-09-29 14:03:22.json", created.name)
        assertEquals("https://web.example/e/${created.fileId}", created.link)
        assertTrue(created.shared)
        val item = api.item(created.fileId)
        assertTrue(item.shared)
        assertEquals(DriveTags.EVENT, item.tag)
        assertEquals(cache.folder, item.parentId)
        assertEquals("https://s/x.m3u8", EventDocument.parse(item.content).stream)
    }

    @Test
    fun `share failure is reported but the event still exists`() = runTest {
        api.shareFailure = DriveException(403, "Sharing is not allowed by the domain policy")
        val created = drive.createEvent(Instant.now(), EventDocument())
        assertFalse(created.shared)
        assertTrue(created.shareError!!.contains("domain policy"))
    }

    @Test
    fun `config is created once then overwritten, and cached locally`() = runTest {
        assertNull(drive.readRemoteConfig())
        drive.saveConfig(SosConfig(notificationPhones = listOf("+36 30 123 4567")))
        drive.saveConfig(SosConfig(notificationPhones = listOf("+36 30 765 4321"), maxEvents = 3))
        val configs = api.items.filter { it.tag == DriveTags.CONFIG }
        assertEquals(1, configs.size)
        assertFalse(configs.single().shared)
        assertEquals(listOf("+36 30 765 4321"), drive.readRemoteConfig()!!.notificationPhones)
        assertEquals(3, drive.cachedConfig()!!.maxEvents)
    }

    @Test
    fun `rotation trashes events beyond max_events, oldest first`() = runTest {
        val ids = (0 until 5).map { drive.createEvent(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(it * 60L), EventDocument()).fileId }
        assertEquals(2, drive.rotate(3))
        val remaining = drive.listEvents().map { it.fileId }
        assertEquals(ids.takeLast(3).reversed(), remaining)
        assertTrue(api.item(ids[0]).file.trashed)
    }

    @Test
    fun `event creation recovers when the cached folder was deleted`() = runTest {
        drive.folderId()
        api.items.clear()
        val created = drive.createEvent(Instant.now(), EventDocument())
        assertEquals(cache.folder, api.item(created.fileId).parentId)
    }
}
