package info.soslive.stream.drive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class EventWriterTest {

    private val api = FakeDriveApi()
    private var now = Instant.parse("2026-09-29T14:03:22Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private suspend fun TestScope.writer(onStopped: (Throwable) -> Unit = {}): EventWriter {
        val folder = api.createFolder(FOLDER_NAME, DriveTags.ROOT)
        val file = api.createFile("e.json", JSON_MIME, folder.id, DriveTags.EVENT, EventDocument().encode())
        return EventWriter(api, file.id, EventDocument(stream = "s"), this, clock,
            debounceMillis = 1_000, initialBackoffMillis = 2_000, onStopped = onStopped)
    }

    private fun lastUpload() = EventDocument.parse(api.uploads.last())

    @Test
    fun `entries created close together go up in one upload`() = runTest {
        val w = writer()
        w.addMessage("Anna", "one")
        w.addMessage("Anna", "two")
        w.setStream("https://x/y.m3u8")
        advanceUntilIdle()
        assertEquals(1, api.uploads.size)
        assertEquals(2, lastUpload().entries.size)
        assertEquals("https://x/y.m3u8", lastUpload().stream)
        assertFalse(w.hasPendingChanges)
    }

    @Test
    fun `network errors and 5xx are retried and the latest state is uploaded`() = runTest {
        val w = writer()
        api.updateFailures += IOException("offline")
        api.updateFailures += DriveException(503, "backend error")
        w.addMessage("Anna", "one")
        advanceTimeBy(1_500) // debounce passed, first attempt failed
        w.addMessage("Anna", "two")
        advanceUntilIdle()
        assertEquals(1, api.uploads.size)
        assertEquals(listOf("one", "two"), lastUpload().entries.map { it.string("text") })
    }

    @Test
    fun `404 stops writing and reports it`() = runTest {
        var stoppedWith: Throwable? = null
        val w = writer { stoppedWith = it }
        api.updateFailures += DriveNotFoundException("deleted")
        w.addMessage("Anna", "one")
        advanceUntilIdle()
        assertTrue(w.stopped)
        assertTrue(stoppedWith is DriveNotFoundException)
        w.addMessage("Anna", "ignored")
        advanceUntilIdle()
        assertEquals(0, api.uploads.size)
    }

    @Test
    fun `positions are recorded at most every 30 seconds`() = runTest {
        val w = writer()
        assertTrue(w.addPosition(47.0, 19.0))
        now = now.plusSeconds(10)
        assertFalse(w.addPosition(47.1, 19.1))
        now = now.plusSeconds(20)
        assertTrue(w.addPosition(47.2, 19.2))
        assertTrue(w.addPosition(47.3, 19.3, force = true))
        advanceUntilIdle()
        val entries = lastUpload().entries
        assertEquals(listOf(47.0, 47.2, 47.3), entries.map { it.double("lat") })
        assertEquals("2026-09-29T14:03:22Z", entries.first().time)
    }

    @Test
    fun `close uploads pending changes`() = runTest {
        val w = writer()
        w.addMessage("Anna", "bye")
        w.close()
        assertEquals(1, api.uploads.size)
        assertFalse(w.hasPendingChanges)
    }
}
