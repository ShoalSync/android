package org.tidefell.android.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoveOriginalsTest {
    @Test
    fun onlyConfirmedCandidatesAreEligible() {
        val candidates = linkedMapOf(
            "image:1" to "Camera/2026/09/a.jpg",
            "image:2" to "Camera/2026/09/b.jpg",
            "video:3" to "Camera/2026/09/c.mp4",
        )
        val confirmed = setOf("Camera/2026/09/a.jpg", "Camera/2026/09/c.mp4", "Camera/2026/09/other.jpg")
        assertEquals(listOf("image:1", "video:3"), RemoveOriginals.eligibleKeys(candidates, confirmed))
    }

    @Test
    fun nothingIsEligibleWithoutCandidatesOrConfirmations() {
        assertEquals(emptyList<String>(), RemoveOriginals.eligibleKeys(emptyMap(), setOf("Camera/2026/09/a.jpg")))
        assertEquals(emptyList<String>(), RemoveOriginals.eligibleKeys(mapOf("image:1" to "Camera/2026/09/a.jpg"), emptySet()))
    }

    @Test
    fun keysParseToKindAndId() {
        assertEquals("image" to 42L, RemoveOriginals.parseKey("image:42"))
        assertEquals("video" to 7L, RemoveOriginals.parseKey("video:7"))
        assertNull(RemoveOriginals.parseKey("audio:1"))
        assertNull(RemoveOriginals.parseKey("image:"))
        assertNull(RemoveOriginals.parseKey("image"))
        assertNull(RemoveOriginals.parseKey("image:x"))
    }
}
