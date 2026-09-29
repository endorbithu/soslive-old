package info.soslive.stream.drive

import info.soslive.stream.stream.StreamSettings
import info.soslive.stream.stream.UserStreamProvider
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
    fun `user stream provider joins url and key and fills the recording start`() = runTest {
        val settings = StreamSettings(
            rtmpUrl = "rtmps://live.example.com:443/app/",
            streamKey = " secret-key ",
            playbackUrl = "https://cdn.example.com/live.m3u8",
            pageUrl = "https://www.youtube.com/@me/live",
            recordingUrl = "https://rec.example.com/get?start={start}",
        )
        val start = Instant.parse("2026-09-29T14:03:22.456Z")
        val session = UserStreamProvider { settings }.createStream(start)!!
        assertEquals("rtmps://live.example.com:443/app/secret-key", session.publishUrl)
        assertEquals("https://cdn.example.com/live.m3u8", session.playbackUrl)
        assertEquals("https://www.youtube.com/@me/live", session.pageUrl)
        assertEquals("https://rec.example.com/get?start=2026-09-29T14:03:22Z", session.recordingUrl)

        assertEquals("rtmp://h/live/k", StreamSettings(rtmpUrl = "rtmp://h/live/k").publishUrl)
        assertEquals(null, UserStreamProvider { StreamSettings() }.createStream(start))
    }

    @Test
    fun `stream settings validation`() {
        assertTrue(StreamSettings().validate().isEmpty())
        assertTrue(StreamSettings(rtmpUrl = "rtmp://h/live", playbackUrl = "https://h/x.m3u8", recordingUrl = "https://h/{start}").validate().isEmpty())
        assertEquals(listOf(StreamSettings.Field.RTMP_URL), StreamSettings(rtmpUrl = "https://h/live").validate())
        assertEquals(listOf(StreamSettings.Field.RTMP_URL), StreamSettings(streamKey = "k").validate())
        assertEquals(
            listOf(StreamSettings.Field.PLAYBACK_URL, StreamSettings.Field.PAGE_URL),
            StreamSettings(rtmpUrl = "rtmps://h/live", playbackUrl = "rtmp://h", pageUrl = "javascript:alert(1)").validate(),
        )
    }

    @Test
    fun `stream_page and recording are written only when set`() {
        val plain = EventDocument(stream = "").toJson()
        assertFalse("stream_page" in plain)
        assertFalse("recording" in plain)
        val full = EventDocument(stream = "https://h/x.m3u8", streamPage = "https://yt/live", recording = "https://h/rec.mp4")
        val parsed = EventDocument.parse(full.toJson().toString().toByteArray())
        assertEquals("https://yt/live", parsed.streamPage)
        assertEquals("https://h/rec.mp4", parsed.recording)
        assertEquals("https://h/x.m3u8", parsed.stream)
    }
}
