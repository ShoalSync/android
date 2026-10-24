package org.shoalsync.android.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import org.shoalsync.android.AppLog
import org.shoalsync.android.Peer
import org.shoalsync.ffi.Node

/**
 * The result of [read] on the running node: now, after every node event and after every value
 * of [refresh]. [read] runs on [Dispatchers.IO], because node calls and the `MediaStore` queries
 * some reads make can block. Before the node starts, and if [read] fails, the value is [initial].
 */
fun <T> CoroutineScope.nodeState(
    initial: T,
    refresh: Flow<Any?> = emptyFlow(),
    read: suspend (Node) -> T,
): StateFlow<T> =
    combine(Peer.node, merge(Peer.events, refresh).onStart { emit(Unit) }) { node, _ -> node }
        .conflate()
        .map { node -> if (node == null) initial else readOrInitial(node, initial, read) }
        .flowOn(Dispatchers.IO)
        .stateIn(this, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial)

private suspend fun <T> readOrInitial(node: Node, initial: T, read: suspend (Node) -> T): T =
    try {
        read(node)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w("NodeState", "reading from the node failed", e)
        initial
    }

/** Keeps reading for a moment after the screen goes away, so a rotation does not restart it. */
private const val STOP_TIMEOUT_MS = 5_000L
