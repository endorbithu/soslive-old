package info.soslive.stream.drive

import info.soslive.stream.stream.TemplateStreamProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DriveModelsTest {

    @Test
    fun `config keeps unknown fields and applies defaults`() {
        val config = SosConfig.parse(
            """{"v":1,"notification_emails":["mom@example.com"],"notification_phones":["+36 30 123 4567"],"future":{"x":1}}""".toByteArray(),
        )
        assertEquals(listOf("mom@example.com"), config.notificationEmails)
        assertEquals(listOf("+36 30 123 4567"), config.notificationPhones)
        assertEquals(100, config.maxEvents)

        val written = DriveJson.parseToJsonElement(config.copy(maxEvents = 5).encode().decodeToString()).jsonObject
        assertEquals(JsonPrimitive(5), written["max_events"])
        assertEquals(JsonPrimitive(1), written["v"])
        assertTrue(written["future"] is JsonObject)
    }

    @Test
    fun `invalid max_events falls back to 100`() {
        assertEquals(100, SosConfig.parse("""{"max_events":0}""".toByteArray()).maxEvents)
    }

    @Test
    fun `event file name is the UTC start time`() {
        assertEquals("2026-09-29 14:03:22.json", eventFileName(Instant.parse("2026-09-29T14:03:22.987Z")))
        assertEquals("2026-09-29 14:03:22", eventTitle("2026-09-29 14:03:22.json"))
    }

    @Test
    fun `event document round trip matches the format`() {
        val t = Instant.parse("2026-09-29T14:03:22Z")
        val doc = EventDocument(
            stream = "https://s/live/abc.m3u8",
            entries = listOf(
                EventEntry.position(t, 47.4979, 19.0402),
                EventEntry.message(t.plusSeconds(18), "Anna", "Elindultam haza"),
                EventEntry.image(t.plusSeconds(43), "https://img/abc.jpg"),
            ),
        )
        val json = doc.encode().decodeToString()
        assertTrue(json, json.contains(""""t":"2026-09-29T14:03:22Z","type":"pos","lat":47.4979,"lng":19.0402"""))
        assertTrue(json, json.startsWith("""{"v":1,"stream":"https://s/live/abc.m3u8","entries":["""))

        val parsed = EventDocument.parse(json.toByteArray())
        assertEquals(3, parsed.entries.size)
        assertEquals("msg", parsed.entries[1].type)
        assertEquals("Anna", parsed.entries[1].string("name"))
        assertEquals(19.0402, parsed.entries[0].double("lng")!!, 0.0)
    }

    @Test
    fun `contact validation rules`() {
        assertTrue(ContactRules.isValidPhone("+36 30 123 4567"))
        assertTrue(ContactRules.isValidPhone("+36(30)123-4567"))
        assertFalse(ContactRules.isValidPhone("06301234567"))
        assertFalse(ContactRules.isValidPhone("+36 30 abc"))
        assertEquals("+36301234567", ContactRules.dialable("+36 (30) 123-4567"))
        assertTrue(ContactRules.isValidEmail("mom@example.com"))
        assertFalse(ContactRules.isValidEmail("mom@example"))
        assertFalse(ContactRules.isValidEmail("a".repeat(120) + "@example.com"))
        assertEquals(listOf("a@b.hu", "c@d.hu"), ContactRules.split(" a@b.hu ,c@d.hu;\n a@b.hu \n"))
    }

    @Test
    fun `template stream provider builds publish and playback urls from one random key`() = runTest {
        val provider = TemplateStreamProvider("rtmp://h:1935/live/", "https://h/live/{key}/index.m3u8")
        val session = provider.createStream()
        val key = session.publishUrl.removePrefix("rtmp://h:1935/live/")
        assertTrue(key.matches(Regex("[0-9a-f]{32}")))
        assertEquals("https://h/live/$key/index.m3u8", session.playbackUrl)
        assertFalse(key == provider.newKey())
    }
}
