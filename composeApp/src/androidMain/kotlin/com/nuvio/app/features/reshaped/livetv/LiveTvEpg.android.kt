package com.nuvio.app.features.reshaped.livetv

import android.util.Xml
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import kotlin.coroutines.coroutineContext

private val epgHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

/** Streams the guide through a pull parser: memory stays flat however large the file is. */
internal actual suspend fun loadXmlTvSchedule(
    url: String,
    channelIds: Set<String>,
    nowEpochMs: Long,
): LiveTvSchedule = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "VLC/3.0.0 LibVLC/3.0.0")
        .build()
    epgHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("EPG HTTP ${response.code}")
        val body = response.body?.byteStream() ?: throw IOException("EPG empty")
        BufferedInputStream(body, BUFFER_BYTES).use { buffered ->
            val input: InputStream = if (buffered.startsWithGzipMagic()) {
                GZIPInputStream(buffered, BUFFER_BYTES)
            } else {
                buffered
            }
            val builder = LiveTvScheduleBuilder(channelIds, nowEpochMs)
            // A malformed tail (unknown entity, cut download) keeps what was read before it.
            runCatching { readProgrammes(input, builder) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            builder.build()
        }
    }
}

private suspend fun readProgrammes(input: InputStream, builder: LiveTvScheduleBuilder) {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    runCatching { parser.setFeature(RELAXED_FEATURE, true) }
    parser.setInput(input, null)
    var events = 0
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (++events % CANCEL_CHECK_EVENTS == 0) coroutineContext.ensureActive()
        if (event == XmlPullParser.START_TAG && parser.name.equals("programme", ignoreCase = true)) {
            val channelId = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
            if (channelId == null || !builder.wants(channelId)) {
                parser.skipElement()
            } else {
                val start = parser.getAttributeValue(null, "start")?.let(LiveTvClock::parseXmlTvTimestamp)
                val stop = parser.getAttributeValue(null, "stop")?.let(LiveTvClock::parseXmlTvTimestamp)
                val title = parser.readFirstTitle()
                if (start != null && stop != null && title != null) builder.add(channelId, title, start, stop)
            }
        }
        event = parser.next()
    }
}

/** From a START_TAG: moves to its matching END_TAG. */
private fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/** From a programme's START_TAG: its first title, leaving the parser on the programme's END_TAG. */
private fun XmlPullParser.readFirstTitle(): String? {
    var title: String? = null
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> {
                if (title == null && depth == 1 && name.equals("title", ignoreCase = true)) {
                    // nextText() ends on the title's END_TAG, so the depth is unchanged.
                    title = nextText().trim().takeIf(String::isNotBlank)
                } else {
                    depth++
                }
            }
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return title
        }
    }
    return title
}

private fun BufferedInputStream.startsWithGzipMagic(): Boolean {
    mark(2)
    val first = read()
    val second = read()
    reset()
    return first == 0x1f && second == 0x8b
}

private const val BUFFER_BYTES = 64 * 1024
private const val CANCEL_CHECK_EVENTS = 4096
private const val RELAXED_FEATURE = "http://xmlpull.org/v1/doc/features.html#relaxed"
