package org.tidefell.android.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoveOriginalsTest {
    private fun candidate(path: String) = Candidate(Stamp(10, 20), path)

    @Test
    fun editedOrMissingOriginalsAreNotUnchanged() {
        val recorded = Stamp(size = 1000, modifiedS = 1_700_000_000)
        assertTrue(RemoveOriginals.unchanged(recorded, Stamp(1000, 1_700_000_000)))
        assertFalse("edited in place", RemoveOriginals.unchanged(recorded, Stamp(1000, 1_700_000_100)))
        assertFalse("resized", RemoveOriginals.unchanged(recorded, Stamp(999, 1_700_000_000)))
        assertFalse("gone", RemoveOriginals.unchanged(recorded, null))
    }

    @Test
    fun candidatesRoundTripAndLegacyValuesAreUnverifiable() {
        val c = Candidate(Stamp(1234, 5678), "Camera/2026/09/a|b.jpg")
        assertEquals("1234:5678|Camera/2026/09/a|b.jpg", c.encode())
        assertEquals(c, Candidate.decode(c.encode()))
        // Values stored before stamps were kept are only a path.
        assertNull(Candidate.decode("Camera/2026/09/a.jpg"))
        assertNull(Candidate.decode("a|b"))
        assertNull(Candidate.decode("|Camera/x.jpg"))
    }

    @Test
    fun stampsRoundTrip() {
        assertEquals(Stamp(1, 2), Stamp.decode(Stamp(1, 2).encode()))
        assertNull(Stamp.decode(null))
        assertNull(Stamp.decode("1"))
        assertNull(Stamp.decode("x:2"))
        assertNull(Stamp.decode("1:2:3"))
    }

    @Test
    fun onlyConfirmedCandidatesAreEligible() {
        val candidates = linkedMapOf(
            "image:1" to candidate("Camera/2026/09/a.jpg"),
            "image:2" to candidate("Camera/2026/09/b.jpg"),
            "video:3" to candidate("Camera/2026/09/c.mp4"),
        )
        val confirmed = setOf("Camera/2026/09/a.jpg", "Camera/2026/09/c.mp4", "Camera/2026/09/other.jpg")
        assertEquals(listOf("image:1", "video:3"), RemoveOriginals.eligibleKeys(candidates, confirmed))
    }

    @Test
    fun nothingIsEligibleWithoutCandidatesOrConfirmations() {
        assertEquals(emptyList<String>(), RemoveOriginals.eligibleKeys(emptyMap(), setOf("Camera/2026/09/a.jpg")))
        assertEquals(emptyList<String>(), RemoveOriginals.eligibleKeys(mapOf("image:1" to candidate("Camera/2026/09/a.jpg")), emptySet()))
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
