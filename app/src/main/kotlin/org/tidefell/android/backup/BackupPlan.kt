package org.tidefell.android.backup

import java.time.Instant
import java.time.ZoneId

/** Where backed-up photos and videos go in the folder (ADR 0020): `Camera/<year>/<month>/`. */
object BackupPlan {
    const val ROOT = "Camera"

    /**
     * The folder path for a photo or video called [displayName], taken at [takenMs] in [zone].
     * If the path is in [taken], a suffix before the extension makes it unique: `a.jpg`,
     * `a (2).jpg`, `a (3).jpg`.
     */
    fun targetPath(displayName: String, takenMs: Long, zone: ZoneId, taken: Set<String>): String {
        val date = Instant.ofEpochMilli(takenMs).atZone(zone)
        val path = "%s/%04d/%02d/%s".format(ROOT, date.year, date.monthValue, safeName(displayName))
        return candidates(path).first { it !in taken }
    }

    /** [path] and its suffixed names, as far as they are all in [taken]. */
    fun variants(path: String, taken: Set<String>): List<String> = candidates(path).takeWhile { it in taken }.toList()

    /** A file name without path separators or names the folder cannot hold. */
    fun safeName(displayName: String): String {
        val cleaned = displayName.replace('/', '_').replace('\\', '_').trim()
        return when (cleaned) {
            "", ".", ".." -> "media"
            else -> cleaned
        }
    }

    /** `dir/a.jpg`, `dir/a (2).jpg`, `dir/a (3).jpg`, … */
    private fun candidates(path: String): Sequence<String> {
        val slash = path.lastIndexOf('/')
        val dir = path.substring(0, slash + 1)
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { name.substring(0, it) } ?: name
        val extension = dot?.let { name.substring(it) } ?: ""
        return sequenceOf(path) + generateSequence(2) { it + 1 }.map { "$dir$stem ($it)$extension" }
    }
}
