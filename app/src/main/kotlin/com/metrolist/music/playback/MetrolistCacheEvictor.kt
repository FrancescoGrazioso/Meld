package com.metrolist.music.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MetrolistCacheEvictor(
    private val wrappedEvictor: CacheEvictor,
    private val database: MusicDatabase
) : CacheEvictor {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var cache: Cache? = null

    override fun requiresCacheSpanTouches(): Boolean {
        return wrappedEvictor.requiresCacheSpanTouches()
    }

    override fun onCacheInitialized() {
        wrappedEvictor.onCacheInitialized()
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        this.cache = cache
        wrappedEvictor.onStartFile(cache, key, position, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        wrappedEvictor.onSpanAdded(cache, span)
        checkSpanAndSync(cache, span)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        this.cache = cache
        wrappedEvictor.onSpanRemoved(cache, span)
        checkSpanAndSync(cache, span)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        this.cache = cache
        wrappedEvictor.onSpanTouched(cache, oldSpan, newSpan)
    }

    private fun checkSpanAndSync(cache: Cache, span: CacheSpan) {
        val mediaId = span.key
        scope.launch {
            try {
                val entity = database.getSongById(mediaId)
                    if (entity != null) {
                        val length = if (entity.song.duration > 0) {
                            androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                                cache.getContentMetadata(mediaId)
                            )
                        } else {
                            -1L
                        }

                        val contentLength = androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                                cache.getContentMetadata(mediaId)
                            )

                        if (contentLength != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
                            val cachedSpans = cache.getCachedSpans(mediaId)
                            var cachedBytes = 0L
                            for (s in cachedSpans) {
                                cachedBytes += s.length
                            }
                            val isCached = cachedBytes > 0 && cachedBytes >= (contentLength * 0.99).toLong()
                            if (entity.song.isCached != isCached) {
                                database.updateCachedInfo(mediaId, isCached)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
            }
        }
    }
}
