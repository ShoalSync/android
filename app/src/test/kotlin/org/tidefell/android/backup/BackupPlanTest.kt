package org.tidefell.android.backup

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPlanTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val taken = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun filesByYearAndMonth() {
        assertEquals("Camera/2026/03/IMG_1.jpg", BackupPlan.targetPath("IMG_1.jpg", taken, zone, emptySet()))
    }

    @Test
    fun monthFollowsTheZone() {
        // 23:30 on 31 December in Los Angeles is already January in UTC.
        val newYearsEve = ZonedDateTime.of(2025, 12, 31, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Camera/2025/12/a.jpg", BackupPlan.targetPath("a.jpg", newYearsEve, zone, emptySet()))
        assertEquals("Camera/2026/01/a.jpg", BackupPlan.targetPath("a.jpg", newYearsEve, ZoneId.of("UTC"), emptySet()))
    }

    @Test
    fun takenNamesGetASuffix() {
        val existing = setOf("Camera/2026/03/a.jpg", "Camera/2026/03/a (2).jpg")
        assertEquals("Camera/2026/03/a (3).jpg", BackupPlan.targetPath("a.jpg", taken, zone, existing))
        assertEquals("Camera/2026/03/b.jpg", BackupPlan.targetPath("b.jpg", taken, zone, existing))
    }

    @Test
    fun suffixWithoutAnExtension() {
        assertEquals("Camera/2026/03/clip (2)", BackupPlan.targetPath("clip", taken, zone, setOf("Camera/2026/03/clip")))
        assertEquals("Camera/2026/03/.hidden (2)", BackupPlan.targetPath(".hidden", taken, zone, setOf("Camera/2026/03/.hidden")))
    }

    @Test
    fun variantsStopAtTheFirstFreeName() {
        val existing = setOf("Camera/2026/03/a.jpg", "Camera/2026/03/a (2).jpg", "Camera/2026/03/a (4).jpg")
        assertEquals(listOf("Camera/2026/03/a.jpg", "Camera/2026/03/a (2).jpg"), BackupPlan.variants("Camera/2026/03/a.jpg", existing))
        assertEquals(emptyList<String>(), BackupPlan.variants("Camera/2026/03/b.jpg", existing))
    }

    @Test
    fun unsafeNames() {
        assertEquals("a_b_c.jpg", BackupPlan.safeName("a/b\\c.jpg"))
        assertEquals("media", BackupPlan.safeName(""))
        assertEquals("media", BackupPlan.safeName(".."))
        assertEquals("media", BackupPlan.safeName(" . "))
    }
}
