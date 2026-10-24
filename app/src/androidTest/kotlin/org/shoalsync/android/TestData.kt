package org.shoalsync.android

import java.io.File

/**
 * Empties the app's data directory, as for a fresh install, but keeps its log files: the engine
 * holds them open from when the app started, and would go on writing to deleted files.
 */
fun clearDataDir() {
    File(Peer.dataDir).listFiles()?.filter { it.name != "logs" }?.forEach { it.deleteRecursively() }
}
