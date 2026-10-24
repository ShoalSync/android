package org.shoalsync.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.shoalsync.ffi.Priorities
import org.shoalsync.ffi.PriorityEntry
import org.shoalsync.ffi.PriorityRule

class StorageTest {
    @Test
    fun theTreeListsDirectoriesThenFilesUnderTheirParents() {
        val rows = storageRows(
            Priorities(
                rules = listOf(PriorityRule(200u, "/Photos/"), PriorityRule(0u, "*.iso")),
                entries = listOf(
                    PriorityEntry("Photos", true, 200u, 0u),
                    PriorityEntry("Photos/a.jpg", false, 200u, 0u),
                    PriorityEntry("Photos/raw", true, 200u, 0u),
                    PriorityEntry("Photos/raw/b.cr2", false, 200u, 0u),
                    PriorityEntry("disc.iso", false, 0u, 1u),
                    PriorityEntry("notes.md", false, 128u, null),
                ),
            ),
        )
        assertEquals(
            listOf(
                Triple("Photos", 0, "set here"),
                Triple("Photos/raw", 1, "from the rule for /Photos/"),
                Triple("Photos/raw/b.cr2", 2, "from the rule for /Photos/"),
                Triple("Photos/a.jpg", 1, "from the rule for /Photos/"),
                Triple("disc.iso", 0, "from the rule for *.iso"),
                Triple("notes.md", 0, "the default"),
            ),
            rows.map { Triple(it.path, it.depth, it.source) },
        )
        assertEquals(listOf(true, false, false, false, false, false), rows.map { it.setHere })
    }

    @Test
    fun aPatternIsForExactlyOnePath() {
        assertEquals("/Photos/", patternFor("Photos", true))
        assertEquals("/a/b.txt", patternFor("a/b.txt", false))
        assertEquals("/what\\?\\[1\\]\\*.txt", patternFor("what?[1]*.txt", false))
        assertEquals("/x\\\\y", patternFor("x\\y", false))
        assertEquals("/a b\\ ", patternFor("a b ", false))
    }

    @Test
    fun aPriorityInAWord() {
        assertEquals("never stored", priorityWord(0))
        assertEquals("low", priorityWord(50))
        assertEquals("normal", priorityWord(128))
        assertEquals("high", priorityWord(200))
    }
}
