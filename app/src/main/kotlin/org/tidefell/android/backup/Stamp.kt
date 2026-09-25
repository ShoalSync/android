package org.tidefell.android.backup

/**
 * What a camera-roll item looked like when it was backed up: its size in bytes and its
 * `MediaStore` modification time in seconds. An item whose stamp has changed since was edited
 * in place, so its backed-up copy is no longer the original (ADR 0020, 0022).
 */
data class Stamp(val size: Long, val modifiedS: Long) {
    fun encode(): String = "$size:$modifiedS"

    companion object {
        fun decode(value: String?): Stamp? {
            val (size, modified) = value?.split(':')?.takeIf { it.size == 2 } ?: return null
            return Stamp(size.toLongOrNull() ?: return null, modified.toLongOrNull() ?: return null)
        }
    }
}

/**
 * A remove-originals candidate as stored: the item's [Stamp] when it was backed up and its path
 * in the folder, as `<size>:<modified>|<path>`. Values from before stamps were kept have no
 * stamp and cannot be checked, so they decode to null.
 */
data class Candidate(val stamp: Stamp, val path: String) {
    fun encode(): String = "${stamp.encode()}|$path"

    companion object {
        fun decode(value: String): Candidate? {
            val bar = value.indexOf('|').takeIf { it > 0 } ?: return null
            val stamp = Stamp.decode(value.substring(0, bar)) ?: return null
            return Candidate(stamp, value.substring(bar + 1))
        }
    }
}
