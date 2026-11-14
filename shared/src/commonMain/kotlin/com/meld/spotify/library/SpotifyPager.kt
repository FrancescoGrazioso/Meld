package com.meld.spotify.library

import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [error] is the failure of the last request; the [items] loaded before it are kept. */
data class SpotifyPagerState<T>(
    val items: List<T> = emptyList(),
    val total: Int? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: SpotifyError? = null,
    val complete: Boolean = false,
) {
    fun <R> map(transform: (List<T>) -> List<R>): SpotifyPagerState<R> =
        SpotifyPagerState(transform(items), total, loading, refreshing, error, complete)
}

/**
 * A Spotify listing loaded page by page. Requests run in [scope], not in the caller's, so a page
 * asked for by a screen that has since closed still lands in the list.
 */
class SpotifyPager<T>(
    private val scope: CoroutineScope,
    private val fetch: suspend (offset: Int) -> SpotifyPage<T>,
) {
    private val mutex = Mutex()
    private var nextOffset: Int? = 0
    private val mutableState = MutableStateFlow(SpotifyPagerState<T>())

    val state: StateFlow<SpotifyPagerState<T>> = mutableState.asStateFlow()

    /** Loads the next page unless a request is already running; also the retry after a failure. */
    fun loadMore() {
        if (!mutex.isLocked) scope.launch { runCatching { loadNext() } }
    }

    fun loadAllInBackground() {
        scope.launch { runCatching { loadRemaining() } }
    }

    /** Loads one more page. Returns false when there was none left; throws the [SpotifyError] of a failed request. */
    suspend fun next(): Boolean = scope.async { loadNext() }.await()

    /** Loads every remaining page and returns the whole list; throws the [SpotifyError] of a failed request. */
    suspend fun loadAll(): List<T> = scope.async { loadRemaining() }.await()

    /** Reloads from the first page; the current items stay visible until it arrives, and after a failure. */
    fun refresh() {
        scope.launch {
            runCatching {
                mutex.withLock {
                    mutableState.update { it.copy(refreshing = true, error = null) }
                    request {
                        val page = fetch(0)
                        nextOffset = page.nextOffset
                        mutableState.value = SpotifyPagerState(page.items, page.total, complete = page.nextOffset == null)
                    }
                }
            }
        }
    }

    private suspend fun loadRemaining(): List<T> {
        do {
            val loaded = loadNext()
        } while (loaded)
        return state.value.items
    }

    private suspend fun loadNext(): Boolean =
        mutex.withLock {
            val offset = nextOffset
            if (offset == null) {
                mutableState.update { it.copy(error = null) }
                return false
            }
            mutableState.update { it.copy(loading = true, error = null) }
            request {
                val page = fetch(offset)
                nextOffset = page.nextOffset
                mutableState.update {
                    it.copy(
                        items = it.items + page.items,
                        total = page.total ?: it.total,
                        loading = false,
                        complete = page.nextOffset == null,
                    )
                }
            }
            true
        }

    private suspend fun request(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(loading = false, refreshing = false) }
            throw cancellation
        } catch (failure: Exception) {
            // Anything that is not a SpotifyError is a bug in reading the response: shown as such, never as raw text.
            val error = failure as? SpotifyError ?: SpotifyError.Changed("Unexpected failure", failure)
            mutableState.update { it.copy(loading = false, refreshing = false, error = error) }
            throw error
        }
    }
}
