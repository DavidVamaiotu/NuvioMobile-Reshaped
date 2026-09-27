package com.nuvio.app.features.reshaped.livetv

import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun loadXmlTvSchedule(
    url: String,
    channelIds: Set<String>,
    nowEpochMs: Long,
): LiveTvSchedule {
    val content = httpGetText(url)
    return withContext(Dispatchers.Default) { parseXmlTvSchedule(content, channelIds, nowEpochMs) }
}
