@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Ported from Nuvio TV (VodCacheWriteSink). CacheDataSource writes through its sink on the read
 * path, so a slow write would delay the next read; here writes are handed to one background
 * writer instead. Nothing may throw once the span is open, because CacheDataSource treats any
 * sink exception as a cache failure and stops using the cache for the rest of the source's life.
 * Writes stop when [session] turns out not to be cacheable (see [PlaybackDiskCache.Session.bypass]).
 */
internal class PlaybackDiskCacheWriteSink(
    private val delegate: DataSink,
    private val session: PlaybackDiskCache.Session,
) : DataSink {
    @Volatile private var failed = false
    private val queuedBytes = AtomicLong(0L)

    // Only the loading thread touches these, and only between open and close.
    private var pending: ByteArray? = null
    private var pendingLength = 0

    override fun open(dataSpec: DataSpec) {
        failed = false
        queuedBytes.set(0L)
        pending = null
        pendingLength = 0
        delegate.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (failed) return
        if (session.bypass) {
            failed = true
            return
        }
        // Dropping writes instead of waiting would leave CacheDataSink committing a range whose
        // bytes are not the ones it claims.
        val waitUntilMs = SystemClock.elapsedRealtime() + MAX_BACKPRESSURE_WAIT_MS
        while (queuedBytes.get() + length > MAX_QUEUED_BYTES && SystemClock.elapsedRealtime() < waitUntilMs) {
            try {
                Thread.sleep(BACKPRESSURE_SLEEP_MS)
            } catch (e: InterruptedException) {
                // A seek cancels the load by interrupting this thread: stop waiting, keep the interrupt.
                Thread.currentThread().interrupt()
                break
            }
        }
        if (queuedBytes.get() + length > MAX_QUEUED_BYTES) {
            // Storage has been behind for the whole wait: stop caching this span. The writer runs
            // in order, so what is committed is a contiguous prefix.
            failed = true
            return
        }
        // Reads arrive around a kilobyte each, so one buffer is filled before it goes to the writer.
        var consumed = 0
        while (consumed < length) {
            val target = pending ?: takeBuffer().also {
                pending = it
                pendingLength = 0
            }
            val chunk = minOf(BUFFER_BYTES - pendingLength, length - consumed)
            System.arraycopy(buffer, offset + consumed, target, pendingLength, chunk)
            pendingLength += chunk
            consumed += chunk
            if (pendingLength == BUFFER_BYTES) flushPending()
        }
    }

    private fun flushPending() {
        val buffer = pending ?: return
        val length = pendingLength
        pending = null
        pendingLength = 0
        if (length == 0) {
            recycleBuffer(buffer)
            return
        }
        queuedBytes.addAndGet(length.toLong())
        writer.execute { drainOne(buffer, length) }
    }

    override fun close() {
        // The tail of the span has to reach the writer before the latch, or the delegate would
        // commit a span missing its last bytes.
        if (failed) {
            pending?.let(::recycleBuffer)
            pending = null
            pendingLength = 0
        } else {
            flushPending()
        }
        // The writer is ordered, so this runs after the span's writes and before the commit.
        val done = CountDownLatch(1)
        writer.execute { done.countDown() }
        var interrupted = Thread.interrupted()
        val drained = try {
            done.await(MAX_CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            interrupted = true
            false
        }
        if (!drained) {
            // Queued writes skip once failed is set, so only the write already running is left.
            failed = true
            while (true) {
                try {
                    done.await()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        }
        try {
            delegate.close()
        } catch (e: IOException) {
            Log.w(TAG, "DISK_CACHE: sink close failed", e)
            throw e
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun drainOne(data: ByteArray, length: Int) {
        if (!failed) {
            try {
                delegate.write(data, 0, length)
            } catch (e: IOException) {
                Log.w(TAG, "DISK_CACHE: sink write failed", e)
                failed = true
            }
        }
        queuedBytes.addAndGet(-length.toLong())
        recycleBuffer(data)
    }

    private companion object {
        const val TAG = "PlaybackDiskCache"
        const val MAX_QUEUED_BYTES = 24L * 1024L * 1024L
        const val BACKPRESSURE_SLEEP_MS = 2L
        const val MAX_BACKPRESSURE_WAIT_MS = 2_000L
        // A seek or exit waits on close from the loading thread, so slow storage only gets this long.
        const val MAX_CLOSE_WAIT_MS = 500L
        const val POOL_CAPACITY = 64
        const val BUFFER_BYTES = 64 * 1024

        // One writer for every sink, in the background group so it does not compete with decoding.
        private val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "NuvioDiskCacheWrite")
        }

        private val bufferPool = ArrayDeque<ByteArray>()

        fun takeBuffer(): ByteArray =
            synchronized(bufferPool) { bufferPool.removeLastOrNull() } ?: ByteArray(BUFFER_BYTES)

        fun recycleBuffer(buffer: ByteArray) {
            if (buffer.size != BUFFER_BYTES) return
            synchronized(bufferPool) {
                if (bufferPool.size < POOL_CAPACITY) bufferPool.addLast(buffer)
            }
        }
    }
}
