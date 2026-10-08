@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.nuvio.app.features.reshaped.livetv.LiveTvPlaybackRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * ExoPlayer disk cache for [PlaybackDiskCacheSettings], ported from Nuvio TV's VOD disk cache
 * (PlayerMediaSourceFactory): what the player reads is also written to a cache on storage, so a
 * seek back into anything already loaded reads from disk instead of the network.
 *
 * As on TV: the size is set by hand or from free space (Auto), at least 1 GB of free space is
 * always left, only the last 1 GB behind the playhead is kept, and the cache is cleared a few
 * seconds after playback ends and at every launch. Writes go through [PlaybackDiskCacheWriteSink],
 * off the loading thread. Only the stream itself is cached (not subtitles), never HLS/DASH, live
 * streams or sources already on the device.
 */
internal object PlaybackDiskCache {
    private const val TAG = "PlaybackDiskCache"
    private const val DIR = "nuvio_disk_cache"
    private const val STALE_PREFIX = "nuvio_disk_cache_stale_"
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB
    private const val MIN_BYTES = 100L * MB
    private const val MAX_BYTES = 64L * GB
    private const val FREE_SPACE_RESERVE_BYTES = GB
    private const val AUTO_FLOOR_BYTES = 2L * GB
    // Larger fragments mean fewer files to create, index and delete on slow storage.
    const val FRAGMENT_BYTES = 8L * MB
    private const val RETAIN_BEHIND_BYTES = GB
    private const val TRIM_STEP_BYTES = 64L * MB
    // Deleting gigabytes the instant playback ends lands on the exit animation.
    private const val EVICTION_DELAY_MS = 5_000L

    private val lock = Any()
    private var cache: SimpleCache? = null
    private var cacheCapBytes = -1L
    private var cacheDisabled = false
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private val liveSessions = LinkedHashSet<Session>()
    @Volatile private var liveSnapshot: List<Session> = emptyList()
    private var pendingEviction: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called at launch: nothing from an earlier run is kept. */
    fun cleanUp(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (cache == null) moveAside(File(appContext.cacheDir, DIR))
        }
        deleteStaleFolders(appContext)
    }

    /**
     * [upstream] with the disk cache in front of it for [sourceUrl] (and [audioUrl], a separate
     * audio track), or [upstream] itself when the setting is on Nuvio's default, [cacheable] is
     * false, the stream is HLS/DASH or live TV, or storage is short.
     */
    fun wrap(
        context: Context,
        sourceUrl: String,
        audioUrl: String?,
        cacheable: Boolean,
        upstream: DataSource.Factory,
    ): DataSource.Factory {
        val choice = PlaybackDiskCacheSettings.bufferMb.value
        if (choice == PlaybackDiskCacheSettings.NUVIO_DEFAULT_MB || !cacheable || !isRemoteHttp(sourceUrl) ||
            looksAdaptive(sourceUrl) || LiveTvPlaybackRegistry.isLiveTv(sourceUrl)
        ) return upstream
        val appContext = context.applicationContext
        val session = synchronized(lock) {
            pendingEviction?.cancel()
            pendingEviction = null
            val maxBytes = resolveMaxBytes(appContext, choice)
            if (maxBytes <= 0L) {
                Log.i(TAG, "DISK_CACHE: off, not enough free space")
                return upstream
            }
            val active = obtainCache(appContext, maxBytes) ?: return upstream
            Session(setOfNotNull(sourceUrl, audioUrl?.takeIf { isRemoteHttp(it) }), active).also {
                liveSessions += it
                liveSnapshot = liveSessions.toList()
                Log.i(TAG, "DISK_CACHE: on, cap=${cacheCapBytes / MB}MB")
            }
        }
        return DiskCacheDataSourceFactory(session, upstream)
    }

    /**
     * Whether Nuvio's own VOD cache stays off for this playback: the Seek buffer already caches it
     * ([wrapped] is not [network]), or it is Live TV or a stream already on the device.
     */
    fun skipsNuvioVodCache(wrapped: DataSource.Factory, network: DataSource.Factory, sourceUrl: String): Boolean =
        wrapped !== network || !isRemoteHttp(sourceUrl) || LiveTvPlaybackRegistry.isLiveTv(sourceUrl)

    /** Where playback is in [sourceUrl], so only the last 1 GB behind it is kept. Main thread. */
    fun onPlayhead(sourceUrl: String, positionMs: Long, durationMs: Long) {
        val sessions = liveSnapshot
        if (sessions.isEmpty()) return
        val fraction = if (durationMs > 0L && positionMs > 0L) positionMs.toDouble() / durationMs else 0.0
        for (session in sessions) {
            if (sourceUrl in session.keys) session.playheadFraction = fraction.coerceIn(0.0, 1.0)
        }
    }

    /** Called when the player built on [factory] is gone: its data is cleared shortly after. */
    fun release(factory: DataSource.Factory) {
        val session = (factory as? DiskCacheDataSourceFactory)?.session ?: return
        synchronized(lock) {
            if (!liveSessions.remove(session)) return
            liveSnapshot = liveSessions.toList()
            pendingEviction?.cancel()
            pendingEviction = scope.launch {
                delay(EVICTION_DELAY_MS)
                val active = synchronized(lock) { cache } ?: return@launch
                clearUnused(active)
            }
        }
    }

    /** Removes every title no live player is using (a quick rebuild of the same one keeps it). */
    private suspend fun clearUnused(active: SimpleCache) {
        val keys = runCatching { active.keys.toList() }.getOrNull() ?: return
        for (key in keys) {
            if (liveSnapshot.any { key in it.keys }) continue
            val spans = runCatching { active.getCachedSpans(key).toList() }.getOrNull() ?: continue
            for (span in spans) {
                // A span still being read stays until the next write evicts it.
                runCatching { active.removeSpan(span) }
                yield()
            }
        }
    }

    private fun resolveMaxBytes(context: Context, choice: Int): Long {
        // What the cache already holds is reclaimable, or the cap would shrink every playback.
        val free = context.cacheDir.usableSpace + runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)
        val runtimeMax = (if (free > FREE_SPACE_RESERVE_BYTES) free - FREE_SPACE_RESERVE_BYTES else free * 8 / 10)
            .coerceAtMost(MAX_BYTES)
        if (runtimeMax < MIN_BYTES) return 0L
        if (choice == PlaybackDiskCacheSettings.AUTO_MB) {
            return maxOf(AUTO_FLOOR_BYTES, free / 5).coerceIn(MIN_BYTES, runtimeMax)
        }
        return (choice * MB).coerceIn(MIN_BYTES, runtimeMax)
    }

    /** Under [lock]. The shared cache, rebuilt at a new size when no player is using it. */
    private fun obtainCache(context: Context, maxBytes: Long): SimpleCache? {
        if (cacheDisabled) return null
        val existing = cache
        if (existing != null) {
            // The evictor's cap is fixed for the life of a cache; a player still reading it keeps it.
            if (cacheCapBytes == maxBytes || liveSessions.isNotEmpty()) return existing
            runCatching { existing.release() }
            cache = null
        }
        val dir = File(context.cacheDir, DIR)
        // A crash can leave last run's files; a fresh folder never races their deletion.
        moveAside(dir)
        deleteStaleFolders(context)
        val created = try {
            dir.mkdirs()
            val provider = databaseProvider ?: StandaloneDatabaseProvider(context).also { databaseProvider = it }
            SimpleCache(dir, TrimmingEvictor(maxBytes), provider)
        } catch (failure: Throwable) {
            Log.w(TAG, "DISK_CACHE: unavailable, streaming direct", failure)
            cacheDisabled = true
            null
        }
        cache = created
        cacheCapBytes = if (created != null) maxBytes else -1L
        return created
    }

    private fun moveAside(dir: File) {
        if (dir.exists()) dir.renameTo(File(dir.parentFile, "$STALE_PREFIX${System.nanoTime()}"))
    }

    private fun deleteStaleFolders(context: Context) {
        scope.launch {
            val folders = context.cacheDir.listFiles { file -> file.name.startsWith(STALE_PREFIX) } ?: return@launch
            for (folder in folders) {
                val provider = synchronized(lock) {
                    databaseProvider ?: StandaloneDatabaseProvider(context).also { databaseProvider = it }
                }
                // Deleting only the files would leave the folder's index tables in the database.
                runCatching { SimpleCache.delete(folder, provider) }.onFailure { folder.deleteRecursively() }
            }
        }
    }

    /** Streams on the device (local proxies, torrents) need no cache. */
    private fun isRemoteHttp(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host != "localhost" && host != "::1" && host != "[::1]" && !host.startsWith("127.")
    }

    /** HLS/DASH playlists are re-read for updates; they are left alone, as on TV. */
    private fun looksAdaptive(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd") ||
            path.contains(".ism") || lower.contains("m3u8") || lower.contains("format=mpd")
    }

    /** One player's use of the cache: the urls it caches and where its playhead is. */
    class Session(val keys: Set<String>, val cache: SimpleCache) {
        @Volatile var playheadFraction = 0.0
        /**
         * Set when the server turns out to send a playlist or an endless live stream: nothing more
         * is written, and later reads go straight to the network.
         */
        @Volatile var bypass = false
    }

    /**
     * LRU eviction at the cap, plus Nuvio TV's trim: on each new cache file, spans more than
     * [RETAIN_BEHIND_BYTES] behind the playhead are dropped, in [TRIM_STEP_BYTES] steps.
     */
    private class TrimmingEvictor(maxBytes: Long) : CacheEvictor {
        private val delegate = LeastRecentlyUsedCacheEvictor(maxBytes)
        // Per key, so a separate audio track does not hold the video's trimming still.
        private val lastTrimPosition = HashMap<String, Long>()

        override fun requiresCacheSpanTouches(): Boolean = delegate.requiresCacheSpanTouches()
        override fun onCacheInitialized() = delegate.onCacheInitialized()

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            trimBehindPlayhead(cache, key)
            delegate.onStartFile(cache, key, position, length)
        }

        private fun trimBehindPlayhead(cache: Cache, key: String) {
            val fraction = liveSnapshot.firstOrNull { key in it.keys }?.playheadFraction ?: return
            if (fraction <= 0.0) return
            val length = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
                .getOrDefault(C.LENGTH_UNSET.toLong())
            if (length <= 0L) return
            val playhead = (length * fraction).toLong()
            val cutoff = playhead - RETAIN_BEHIND_BYTES
            if (cutoff <= 0L) return
            // A seek back leaves the playhead behind the last trim: hold still rather than drop
            // what the seek is about to play.
            if (playhead - (lastTrimPosition[key] ?: 0L) < TRIM_STEP_BYTES) return
            lastTrimPosition[key] = playhead
            val spans = runCatching { cache.getCachedSpans(key).toList() }.getOrNull() ?: return
            for (span in spans) {
                if (span.position + span.length > cutoff) break
                // A span still locked by a reader stays until the next write.
                if (runCatching { cache.removeSpan(span) }.isFailure) break
            }
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) = delegate.onSpanAdded(cache, span)
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) = delegate.onSpanRemoved(cache, span)
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) =
            delegate.onSpanTouched(cache, oldSpan, newSpan)
    }
}

private class DiskCacheDataSourceFactory(
    val session: PlaybackDiskCache.Session,
    val upstream: DataSource.Factory,
) : DataSource.Factory {
    private val cached = CacheDataSource.Factory()
        .setCache(session.cache)
        .setCacheWriteDataSinkFactory {
            PlaybackDiskCacheWriteSink(
                CacheDataSink.Factory().setCache(session.cache).setFragmentSize(PlaybackDiskCache.FRAGMENT_BYTES).createDataSink(),
                session,
            )
        }
        .setUpstreamDataSourceFactory { SniffingDataSource(upstream.createDataSource(), session) }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    override fun createDataSource(): DataSource = RoutingDataSource(
        session = session,
        cached = PlaybackBufferedReadDataSource(cached.createDataSource()),
        direct = upstream.createDataSource(),
    )
}

/** The stream (and its audio track) through the cache; subtitles and everything else direct. */
private class RoutingDataSource(
    private val session: PlaybackDiskCache.Session,
    private val cached: DataSource,
    private val direct: DataSource,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val useCache = !session.bypass && dataSpec.uri.toString() in session.keys
        val source = if (useCache) cached else direct
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (current ?: throw java.io.IOException("not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = current ?: return
        current = null
        source.close()
    }
}

/**
 * The network side of the cache. When the server answers with a playlist or an endless stream
 * (no length, no byte ranges), caching stops for the rest of this playback.
 */
private class SniffingDataSource(
    private val delegate: DataSource,
    private val session: PlaybackDiskCache.Session,
) : DataSource by delegate {
    override fun open(dataSpec: DataSpec): Long {
        val length = delegate.open(dataSpec)
        val headers = delegate.responseHeaders
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
        val contentType = header("Content-Type").firstOrNull()?.lowercase(Locale.US).orEmpty()
        val adaptive = "mpegurl" in contentType || "dash+xml" in contentType
        val endless = length == C.LENGTH_UNSET.toLong() && dataSpec.position == 0L &&
            header("Accept-Ranges").none { it.contains("bytes", ignoreCase = true) }
        if (adaptive || endless) session.bypass = true
        return length
    }
}
