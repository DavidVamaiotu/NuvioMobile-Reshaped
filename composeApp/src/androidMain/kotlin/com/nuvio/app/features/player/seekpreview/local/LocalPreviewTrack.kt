@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.seekpreview.local

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import com.nuvio.app.features.player.seekpreview.SeekPreviewTrack
import com.nuvio.app.features.player.seekpreview.SeekrThumbnail
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seek-preview thumbnails generated on the device from the stream that is playing, one per
 * [SLOT_MS] slot, filled only from the buffer tap ([onKeyframe]): keyframes playback downloads
 * anyway, so it costs no extra requests. Reading keyframes over separate connections was tried
 * and dropped: debrid CDNs rate limit the burst of range requests it makes.
 *
 * Lookups return the nearest filled slot marked approximate (shown blurred, or replaced by a
 * Seekr frame when one is loaded). Thumbnails are small JPEGs kept in memory and in a disk cache
 * per title/release, so a rewatch starts with every part watched before.
 *
 * Keyframes are not decoded while the video plays: a software decode of a 4K or HEVC keyframe
 * takes every core for a moment and playback drops frames (a judder every 10-20 s). They are
 * kept compressed in a spool file instead and decoded while paused or scrubbing, the ones
 * nearest the scrub position first.
 */
internal class LocalPreviewTrack(
    private val source: LocalPreviewSource,
    private val cacheKey: String,
    private val durationMs: Long,
    /** False while paused; see [LocalPreviewSource.playbackActive]. */
    private val playbackActive: () -> Boolean = { true },
) : SeekPreviewTrack {
    override val isLocal: Boolean get() = true

    @Volatile
    override var offsetMs: Long = 0L

    private val slotCount = ((durationMs + SLOT_MS - 1) / SLOT_MS).toInt().coerceAtLeast(1)
    private val lock = Any()
    private val jpegs = arrayOfNulls<ByteArray>(slotCount)
    private val frameMs = LongArray(slotCount) { -1L }
    private val slotState = ByteArray(slotCount)
    private var filledCount = 0
    /** Keyframe time (ms) → slot holding its thumbnail, so a keyframe is never fetched twice. */
    private val keyframeSlots = HashMap<Long, Int>()
    private val decoded = object : LinkedHashMap<Int, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>?) = size > MAX_DECODED
    }
    @Volatile private var closed = false

    private val sinceSave = AtomicInteger()

    private val decoder = KeyframeThumbnailDecoder()
    private val tapExecutor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(TAP_QUEUE),
        { runnable -> Thread(runnable, "NuvioPreviewTap").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = Channel<Unit>(Channel.CONFLATED)

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    private val cacheFile: File = File(File(source.context.cacheDir, CACHE_DIR), sha1(cacheKey) + ".bin")

    /** A keyframe copied while playing, waiting in the spool to be decoded. */
    private class SpooledKeyframe(val format: Format, val timeUs: Long, val position: Long, val size: Int)

    /** Slot → its spooled keyframe; the slot stays CLAIMED meanwhile. Guarded by [lock]. */
    private val spooled = HashMap<Int, SpooledKeyframe>()
    private val spoolFile = File(
        File(source.context.cacheDir, CACHE_DIR),
        sha1(cacheKey) + "-" + SystemClock.elapsedRealtimeNanos() + SPOOL_SUFFIX,
    )
    // Spool file state: only touched on the tap thread.
    private var spool: RandomAccessFile? = null
    private var spoolEnd = 0L
    private var spoolLimit = SPOOL_LIMIT_BYTES
    private val drainScheduled = AtomicBoolean(false)
    /** Slot the viewer last scrubbed to, so its keyframes are decoded first. */
    @Volatile private var focusSlot = -1
    @Volatile private var lastLookupAtMs = 0L

    fun start() {
        // Coalesce UI updates: at most a few revisions per second however fast frames land.
        scope.launch {
            for (unit in changes) {
                _revision.value = _revision.value + 1
                delay(UI_UPDATE_INTERVAL_MS)
            }
        }
        scope.launch(Dispatchers.IO) {
            deleteStaleSpools()
            loadCache()
            notifyChanged()
        }
    }

    /** The viewer paused: decode what was spooled while playing. */
    fun onPlaybackPaused() = scheduleDrain()

    override fun close() {
        if (closed) return
        closed = true
        tapExecutor.shutdownNow()
        Thread({
            runCatching { tapExecutor.awaitTermination(3, TimeUnit.SECONDS) }
            decoder.release()
            runCatching { spool?.close() }
            spool = null
            spoolFile.delete()
            saveCache()
            scope.cancel()
        }, "NuvioPreviewClose").apply { isDaemon = true }.start()
    }

    // ---- Lookups -------------------------------------------------------------------------

    override suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val corrected = (positionMs + offsetMs).coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        val slot = (corrected / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
        focusSlot = slot
        lastLookupAtMs = SystemClock.elapsedRealtime()
        if (synchronized(lock) { spooled.isNotEmpty() }) scheduleDrain()
        val found = synchronized(lock) { nearestFilled(slot) } ?: return null
        val bitmap = bitmapFor(found) ?: return null
        val cueStart = slot * SLOT_MS
        val cueEnd = minOf(cueStart + SLOT_MS, durationMs).coerceAtLeast(cueStart + 1)
        return SeekrThumbnail(
            sheet = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            cueStartMs = cueStart,
            cueEndMs = cueEnd,
            approximate = found != slot,
        )
    }

    private fun nearestFilled(slot: Int): Int? {
        if (jpegs[slot] != null) return slot
        for (distance in 1 until slotCount) {
            val before = slot - distance
            val after = slot + distance
            if (before < 0 && after >= slotCount) break
            if (before >= 0 && jpegs[before] != null) return before
            if (after < slotCount && jpegs[after] != null) return after
        }
        return null
    }

    private suspend fun bitmapFor(slot: Int): ImageBitmap? {
        synchronized(lock) { decoded[slot] }?.let { return it }
        val bytes = synchronized(lock) { jpegs[slot] } ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        } ?: return null
        synchronized(lock) { decoded[slot] = bitmap }
        return bitmap
    }

    // ---- Buffer tap ----------------------------------------------------------------------

    fun wantsKeyframes(): Boolean = !closed && synchronized(lock) { filledCount < slotCount }

    fun wantsKeyframe(timeUs: Long): Boolean {
        if (closed) return false
        val keyMs = timeUs / 1_000L
        val slot = slotFor(keyMs) ?: return false
        return synchronized(lock) { slotState[slot] == EMPTY && keyMs !in keyframeSlots }
    }

    fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
        val keyMs = timeUs / 1_000L
        val slot = slotFor(keyMs) ?: return
        // Decoding runs behind playback's loader thread; when it falls behind, skip frames.
        if (tapExecutor.queue.remainingCapacity() == 0) return
        if (!claim(slot)) return
        val copy = data.copyOfRange(offset, offset + size)
        val submitted = runCatching {
            tapExecutor.execute {
                if (mayDecodeNow()) {
                    decodeInto(slot, format, copy, timeUs)
                } else if (!spoolKeyframe(slot, format, timeUs, copy)) {
                    release(slot)
                }
                if (mayDecodeNow()) scheduleDrain()
            }
        }.isSuccess
        if (!submitted) release(slot)
    }

    /** Tap thread. */
    private fun decodeInto(slot: Int, format: Format, bytes: ByteArray, timeUs: Long) {
        val frame = runCatching {
            decoder.decode(MediaFormatUtil.createMediaFormatFromFormat(format), bytes, 0, bytes.size, timeUs)
        }.getOrNull()
        if (frame != null) {
            store(slot, frame.jpeg, timeUs / 1_000L)
        } else {
            release(slot)
        }
    }

    /** Paused, or scrubbing: the viewer is not watching frames go by. */
    private fun mayDecodeNow(): Boolean =
        !playbackActive() || SystemClock.elapsedRealtime() - lastLookupAtMs < SCRUB_WINDOW_MS

    // ---- Spool (tap thread only) -------------------------------------------------------

    /** Keeps [bytes] compressed until it may be decoded; false when the spool is full or unwritable. */
    private fun spoolKeyframe(slot: Int, format: Format, timeUs: Long, bytes: ByteArray): Boolean {
        if (spoolEnd + bytes.size > spoolLimit) return false
        return runCatching {
            val file = spool ?: run {
                val dir = spoolFile.parentFile
                dir?.mkdirs()
                // Never take more than a quarter of the free storage.
                val free = runCatching { dir?.usableSpace ?: 0L }.getOrDefault(0L)
                spoolLimit = minOf(SPOOL_LIMIT_BYTES, free / 4)
                if (spoolEnd + bytes.size > spoolLimit) return false
                RandomAccessFile(spoolFile, "rw").also { it.setLength(0L); spool = it }
            }
            file.seek(spoolEnd)
            file.write(bytes)
            synchronized(lock) { spooled[slot] = SpooledKeyframe(format, timeUs, spoolEnd, bytes.size) }
            spoolEnd += bytes.size
        }.onFailure { Log.w(TAG, "spool not written: ${it.message}") }.isSuccess
    }

    private fun scheduleDrain() {
        if (closed || !drainScheduled.compareAndSet(false, true)) return
        val submitted = runCatching { tapExecutor.execute(::drainOne) }.isSuccess
        if (!submitted) drainScheduled.set(false)
    }

    /** Decodes one spooled keyframe, nearest the scrub position first, then schedules the next. */
    private fun drainOne() {
        drainScheduled.set(false)
        if (closed || !mayDecodeNow()) return
        val next = synchronized(lock) {
            val focus = focusSlot
            val slot = if (focus >= 0) spooled.keys.minByOrNull { abs(it - focus) } else spooled.keys.minOrNull()
            slot?.let { it to spooled.remove(it)!! }
        }
        if (next == null) {
            // Everything decoded: start the spool over so it never grows past one pause's worth.
            if (spoolEnd > 0L) {
                spoolEnd = 0L
                runCatching { spool?.setLength(0L) }
            }
            return
        }
        val (slot, entry) = next
        val bytes = runCatching {
            ByteArray(entry.size).also { buffer ->
                val file = spool ?: error("no spool")
                file.seek(entry.position)
                file.readFully(buffer)
            }
        }.getOrNull()
        if (bytes != null) decodeInto(slot, entry.format, bytes, entry.timeUs) else release(slot)
        scheduleDrain()
    }

    private fun deleteStaleSpools() {
        val now = System.currentTimeMillis()
        spoolFile.parentFile?.listFiles { file ->
            file.name.endsWith(SPOOL_SUFFIX) && file != spoolFile && now - file.lastModified() > STALE_SPOOL_MS
        }?.forEach { it.delete() }
    }

    /** Nearest slot to a keyframe, or null when the keyframe is closer to no slot start. */
    private fun slotFor(keyMs: Long): Int? {
        if (keyMs < 0 || keyMs > durationMs + SLOT_MS) return null
        return ((keyMs + SLOT_MS / 2) / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
    }

    // ---- Slot bookkeeping ----------------------------------------------------------------

    private fun claim(slot: Int): Boolean = synchronized(lock) {
        if (slotState[slot] != EMPTY) return@synchronized false
        slotState[slot] = CLAIMED
        true
    }

    private fun release(slot: Int) {
        synchronized(lock) {
            if (slotState[slot] == CLAIMED) slotState[slot] = EMPTY
        }
    }

    private fun store(slot: Int, jpeg: ByteArray, keyMs: Long) {
        synchronized(lock) {
            if (slotState[slot] != FILLED) filledCount++
            slotState[slot] = FILLED
            jpegs[slot] = jpeg
            frameMs[slot] = keyMs
            keyframeSlots.putIfAbsent(keyMs, slot)
            decoded.remove(slot)
        }
        if (sinceSave.incrementAndGet() >= SAVE_EVERY) {
            sinceSave.set(0)
            scope.launch(Dispatchers.IO) { saveCache() }
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        changes.trySend(Unit)
    }

    // ---- Disk cache ----------------------------------------------------------------------

    private fun loadCache() {
        if (!cacheFile.exists()) return
        runCatching {
            DataInputStream(cacheFile.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_MAGIC || input.readInt().toLong() != SLOT_MS) return
                val count = input.readInt()
                repeat(count) {
                    val slot = input.readInt()
                    val keyMs = input.readLong()
                    val bytes = ByteArray(input.readInt())
                    input.readFully(bytes)
                    if (slot in 0 until slotCount && claim(slot)) {
                        synchronized(lock) {
                            filledCount++
                            slotState[slot] = FILLED
                            jpegs[slot] = bytes
                            frameMs[slot] = keyMs
                            keyframeSlots.putIfAbsent(keyMs, slot)
                        }
                    }
                }
            }
            cacheFile.setLastModified(System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "cache unreadable: ${it.message}") }
    }

    @Synchronized
    private fun saveCache() {
        val entries = synchronized(lock) {
            (0 until slotCount).mapNotNull { slot ->
                val bytes = jpegs[slot]
                if (slotState[slot] == FILLED && bytes != null) Triple(slot, frameMs[slot], bytes) else null
            }
        }
        if (entries.isEmpty()) return
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.path + ".tmp")
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(SLOT_MS.toInt())
                output.writeInt(entries.size)
                for ((slot, keyMs, bytes) in entries) {
                    output.writeInt(slot)
                    output.writeLong(keyMs)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
            temp.renameTo(cacheFile)
            pruneCache(cacheFile.parentFile)
        }.onFailure { Log.w(TAG, "cache not saved: ${it.message}") }
    }

    private fun pruneCache(dir: File?) {
        val files = dir?.listFiles { file -> file.name.endsWith(".bin") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > CACHE_LIMIT_BYTES && file != cacheFile) file.delete()
        }
    }

    companion object {
        private const val TAG = "NuvioLocalPreviews"
        const val SLOT_MS = 10_000L
        private const val MAX_DECODED = 48
        private const val TAP_QUEUE = 3
        private const val UI_UPDATE_INTERVAL_MS = 250L
        private const val SAVE_EVERY = 60
        private const val CACHE_DIR = "seek_previews"
        private const val CACHE_MAGIC = 0x4E535031 // "NSP1"
        private const val CACHE_LIMIT_BYTES = 200L * 1_000_000L
        private const val SPOOL_SUFFIX = ".spool"
        /** About 10 min of 4K keyframes, or a whole film at 1080p. */
        private const val SPOOL_LIMIT_BYTES = 192L * 1024 * 1024
        /** A spool left by a crash; a live one is never this old without being written. */
        private const val STALE_SPOOL_MS = 12L * 60 * 60 * 1000
        /** Lookups this recent mean the viewer is scrubbing. */
        private const val SCRUB_WINDOW_MS = 2_000L

        private const val EMPTY: Byte = 0
        private const val CLAIMED: Byte = 1
        private const val FILLED: Byte = 2

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
