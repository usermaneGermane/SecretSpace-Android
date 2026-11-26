package secretspace.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Dates as the PC writes them on screen (history_ui.local_time and short_when). */
class TimesTest {
    private val sofia = ZoneId.of("Europe/Sofia")

    @Test fun aChangeIsShownInLocalTime() {
        assertEquals("26 Nov 2026, 22:20", Times.long("2026-11-26T20:20:38Z", sofia))
        assertEquals("07 Jan 2026, 02:05", Times.long("2026-01-07T00:05:00.123456Z", sofia), "the day has two digits, as %d")
        assertEquals("unknown time", Times.long("", sofia))
        assertEquals("unknown time", Times.long("yesterday", sofia))
    }

    @Test fun aListRowIsShort() {
        val now = Instant.parse("2026-11-26T21:00:00Z")
        assertEquals("22:20", Times.short("2026-11-26T20:20:38Z", now, sofia))
        assertEquals("7 Jan", Times.short("2026-01-07T10:00:00Z", now, sofia))
        assertEquals("Dec 2025", Times.short("2025-12-31T10:00:00Z", now, sofia))
        assertEquals("27 Nov", Times.short("2026-11-26T22:30:00Z", Instant.parse("2026-11-26T12:00:00Z"), sofia),
            "past midnight in Sofia is another day")
        assertEquals("", Times.short(null, now, sofia))
    }
}
