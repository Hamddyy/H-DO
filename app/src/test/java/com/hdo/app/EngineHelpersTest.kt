package com.hdo.app

import org.junit.Assert.*
import org.junit.Test

class EngineHelpersTest {
    @Test fun sanitizeReplacesIllegalChars() {
        assertEquals("a_b_c", Engine.sanitize("a/b:c"))
        assertEquals("file", Engine.sanitize("   ..  "))
    }
    @Test fun sanitizeLimitsLength() = assertEquals(10, Engine.sanitize("x".repeat(50), 10).length)

    @Test fun isNewerCompares() {
        assertTrue(Engine.isNewer("v1.6.0", "1.5.9"))
        assertTrue(Engine.isNewer("1.10.0", "1.9.9"))
        assertFalse(Engine.isNewer("v1.5.9", "1.5.9"))
        assertFalse(Engine.isNewer("1.5.8", "1.5.9"))
    }
    @Test fun parseTimeFormats() {
        assertEquals(90.0, Engine.parseTime("90")!!, 0.001)
        assertEquals(90.0, Engine.parseTime("1:30")!!, 0.001)
        assertEquals(3723.0, Engine.parseTime("01:02:03")!!, 0.001)
        assertNull(Engine.parseTime(""))
        assertNull(Engine.parseTime("a:b"))
    }
    @Test fun extractUrlsFindsLinks() {
        val u = Engine.extractUrls("look https://youtu.be/dQw4w9WgXcQ, and https://example.com/a.")
        assertTrue(u.any { it.contains("youtu.be/dQw4w9WgXcQ") })
        assertTrue(u.all { !it.endsWith(",") && !it.endsWith(".") })
    }
    @Test fun ytIdParses() {
        assertEquals("dQw4w9WgXcQ", Engine.ytId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Engine.ytId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("", Engine.ytId("https://example.com"))
    }
}
