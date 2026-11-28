package secretspace.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The update check reads versions as the PC's update_check does, from the list of releases. */
class UpdateCheckTest {
    @Test fun versionsCompareAsOnThePc() {
        assertEquals(listOf(4, 2, 0, 3, 0), UpdateCheck.versionKey("v4.2.0"))
        assertEquals(listOf(4, 2, 0, 2, 1), UpdateCheck.versionKey("4.2.0-rc.1"))
        assertNull(UpdateCheck.versionKey("4.2"))
        assertNull(UpdateCheck.versionKey("latest"))
        assertTrue(UpdateCheck.isNewer("v0.10.0", "0.9.0"), "10 is more than 9, not less as text")
        assertTrue(UpdateCheck.isNewer("1.0.0", "1.0.0-rc.2"))
        assertFalse(UpdateCheck.isNewer("0.9.0", "0.9.0"))
        assertFalse(UpdateCheck.isNewer("nonsense", "0.9.0"))
    }

    @Test fun theNewestReleaseIsPickedDraftsLeftOut() {
        val answer = """[
            {"tag_name": "v0.9.0", "draft": false, "prerelease": true, "html_url": "https://github.com/x/y/releases/tag/v0.9.0"},
            {"tag_name": "v0.11.0", "draft": true, "html_url": "https://github.com/x/y/releases/tag/v0.11.0"},
            {"tag_name": "v0.10.0", "draft": false, "prerelease": true, "html_url": "https://evil.example/v0.10.0"},
            {"tag_name": "not-a-version", "draft": false},
            "garbage"
        ]"""
        val r = UpdateCheck.newest(answer)!!
        assertEquals("0.10.0", r.version)
        assertEquals(UpdateCheck.PAGE, r.page, "only a github.com page is opened")
        assertNull(UpdateCheck.newest("[]"))
        assertFailsWith<java.io.IOException> { UpdateCheck.newest("""{"message": "rate limited"}""") }
    }
}
