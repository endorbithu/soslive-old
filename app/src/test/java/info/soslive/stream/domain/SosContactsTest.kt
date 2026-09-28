package info.soslive.stream.domain

import info.soslive.stream.auth.sso.SimulatedSso
import info.soslive.stream.domain.model.ActiveEvent
import info.soslive.stream.domain.model.EventType
import info.soslive.stream.sms.SosSmsSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosContactsTest {

    @Test
    fun `parses separators, strips spaces and dashes, removes duplicates`() {
        val result = SosContacts.parse("+36 30 123-4567, +36301234567;\n+441234567890\n\n")
        assertEquals(SosContacts.ParseResult.Valid(listOf("+36301234567", "+441234567890")), result)
    }

    @Test
    fun `rejects numbers without international prefix`() {
        val result = SosContacts.parse("06301234567\n+36301234567")
        assertEquals(SosContacts.ParseResult.Invalid(listOf("06301234567")), result)
    }

    @Test
    fun `empty input is a valid empty list`() {
        assertEquals(SosContacts.ParseResult.Valid(emptyList()), SosContacts.parse("  "))
    }

    @Test
    fun `too many numbers`() {
        val input = (0 until SosContacts.MAX_CONTACTS + 1).joinToString("\n") { "+3630123456$it".padEnd(12, '0') }
        assertEquals(SosContacts.ParseResult.TooMany, SosContacts.parse(input))
    }

    @Test
    fun `sms message uses fallback when template is blank`() {
        assertEquals("Help - http://x/e/1", SosSmsSender.buildMessage(" ", "Help", "http://x/e/1"))
        assertEquals("Mine - http://x/e/1", SosSmsSender.buildMessage("Mine ", "Help", "http://x/e/1"))
    }

    @Test
    fun `simulated sso token format matches the mock backend`() {
        assertEquals("mock:a@b.hu|Anna", SimulatedSso.token(" a@b.hu ", " Anna "))
        assertEquals("mock:a@b.hu", SimulatedSso.token("a@b.hu", ""))
    }

    @Test
    fun `active event expires after the window`() {
        val event = ActiveEvent(1, EventType.PHOTO, startedAtMillis = 1_000, shareUrl = "")
        assertFalse(event.isExpired(nowMillis = 1_000 + 999, windowMillis = 1_000))
        assertTrue(event.isExpired(nowMillis = 1_000 + 1_000, windowMillis = 1_000))
    }
}
