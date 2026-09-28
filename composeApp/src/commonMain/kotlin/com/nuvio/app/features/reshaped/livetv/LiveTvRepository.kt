package com.nuvio.app.features.reshaped.livetv

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.live_tv_error_file_empty
import nuvio.composeapp.generated.resources.live_tv_error_file_failed
import nuvio.composeapp.generated.resources.live_tv_error_file_no_channels
import nuvio.composeapp.generated.resources.live_tv_error_invalid_url
import nuvio.composeapp.generated.resources.live_tv_error_load_failed
import nuvio.composeapp.generated.resources.live_tv_error_no_channels
import nuvio.composeapp.generated.resources.live_tv_error_stalker_failed
import nuvio.composeapp.generated.resources.live_tv_error_stalker_invalid_url
import nuvio.composeapp.generated.resources.live_tv_error_stalker_no_channels
import nuvio.composeapp.generated.resources.live_tv_error_stalker_required
import nuvio.composeapp.generated.resources.live_tv_error_stalker_token
import nuvio.composeapp.generated.resources.live_tv_error_xtream_failed
import nuvio.composeapp.generated.resources.live_tv_error_xtream_invalid_url
import nuvio.composeapp.generated.resources.live_tv_error_xtream_no_channels
import nuvio.composeapp.generated.resources.live_tv_error_xtream_required
import org.jetbrains.compose.resources.getString

object LiveTvRepository {
    private val mutableUiState = MutableStateFlow(LiveTvUiState())
    val uiState = mutableUiState.asStateFlow()
    private val epgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initialized = false
    /** The storage profile the state was loaded for, so screen re-entries do not reload it. */
    private var loadedProfileId: Int? = null
    private var epgJob: Job? = null

    fun ensureLoaded() {
        if (initialized) return
        initialized = true
        loadedProfileId = resolveLiveTvStorageProfileId()
        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvStorage.loadSourceType(),
            sourceUrl = LiveTvStorage.loadSourceUrl().orEmpty(),
            stalkerSettings = LiveTvStorage.loadStalkerSettings(),
            xtreamSettings = LiveTvStorage.loadXtreamSettings(),
            favoriteUrls = LiveTvStorage.loadFavoriteUrls(),
            recentChannel = LiveTvStorage.loadRecentChannel(),
        )
    }

    /** Resets the state only when the profile really changed; loaded channels survive screen re-entries. */
    fun onProfileChanged() {
        if (initialized && loadedProfileId == resolveLiveTvStorageProfileId()) return
        initialized = false
        stopEpg()
        LiveTvRepositoryStalker.clearSession()
        mutableUiState.value = LiveTvUiState()
        ensureLoaded()
    }

    suspend fun load(sourceUrl: String): Result<List<LiveTvChannel>> {
        val normalizedUrl = sourceUrl.trim()
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            val error = IllegalArgumentException(getString(Res.string.live_tv_error_invalid_url))
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceUrl = normalizedUrl,
            isLoading = true,
            errorMessage = null,
        )
        val noChannels = getString(Res.string.live_tv_error_no_channels)
        val loadFailed = getString(Res.string.live_tv_error_load_failed)

        return runCatching {
            if (normalizedUrl.looksLikeDirectVideoUrl()) {
                val channel = directStreamChannel(normalizedUrl)
                saveM3uSource(normalizedUrl, localPlaylistData = "")
                stopEpg()
                mutableUiState.value = LiveTvUiState(
                    sourceType = LiveTvSourceType.M3u,
                    sourceUrl = normalizedUrl,
                    stalkerSettings = mutableUiState.value.stalkerSettings,
                    xtreamSettings = mutableUiState.value.xtreamSettings,
                    channels = listOf(channel),
                    favoriteUrls = mutableUiState.value.favoriteUrls,
                    recentChannel = mutableUiState.value.recentChannel,
                    isLoaded = true,
                )
                return@runCatching listOf(channel)
            }

            val playlist = withContext(Dispatchers.Default) {
                val playlistData = httpGetTextWithHeaders(
                    url = normalizedUrl,
                    headers = M3U_PLAYLIST_REQUEST_HEADERS,
                )
                if (playlistData.looksLikeHlsManifest()) {
                    ParsedM3uPlaylist(
                        channels = listOf(directStreamChannel(normalizedUrl)),
                        epgUrls = emptyList(),
                    )
                } else {
                    parseM3uPlaylistData(playlistData)
                }
            }
            val channels = playlist.channels
            require(channels.isNotEmpty()) { noChannels }
            saveM3uSource(normalizedUrl, localPlaylistData = "")
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.M3u,
                sourceUrl = normalizedUrl,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isEpgLoading = playlist.epgUrls.isNotEmpty(),
                isLoaded = true,
            )
            startEpg(normalizedUrl, playlist.epgUrls, channels)
            channels
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: loadFailed,
            )
        }
    }

    suspend fun loadLocalPlaylist(fileName: String, playlistData: String): Result<List<LiveTvChannel>> {
        val trimmedData = playlistData.trim()
        val displayName = fileName.trim().ifBlank { "Local M3U playlist" }
        if (trimmedData.isBlank()) {
            val error = IllegalArgumentException(getString(Res.string.live_tv_error_file_empty))
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvSourceType.M3u,
            sourceUrl = displayName,
            isLoading = true,
            errorMessage = null,
        )
        val noChannels = getString(Res.string.live_tv_error_file_no_channels)
        val loadFailed = getString(Res.string.live_tv_error_file_failed)

        return runCatching {
            val playlist = withContext(Dispatchers.Default) {
                parseM3uPlaylistData(trimmedData)
            }
            val channels = playlist.channels
            require(channels.isNotEmpty()) { noChannels }
            saveM3uSource(displayName, localPlaylistData = trimmedData)
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.M3u,
                sourceUrl = displayName,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isEpgLoading = playlist.epgUrls.isNotEmpty(),
                isLoaded = true,
            )
            startEpg(displayName, playlist.epgUrls, channels)
            channels
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: loadFailed,
            )
        }
    }

    suspend fun loadStoredLocalPlaylist(): Result<List<LiveTvChannel>> {
        val playlistData = withContext(Dispatchers.Default) { LiveTvStorage.loadLocalPlaylistData().orEmpty() }
        if (playlistData.isBlank()) {
            return Result.failure(IllegalStateException("No saved M3U file"))
        }
        return loadLocalPlaylist(
            fileName = LiveTvStorage.loadSourceUrl().orEmpty().ifBlank { "Local M3U playlist" },
            playlistData = playlistData,
        )
    }

    suspend fun loadStalker(settings: LiveTvStalkerSettings): Result<List<LiveTvChannel>> {
        val normalizedSettings = settings.normalized()
        if (!normalizedSettings.isConfigured) {
            val error = IllegalArgumentException(getString(Res.string.live_tv_error_stalker_required))
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }
        if (!normalizedSettings.portalUrl.startsWith("http://") && !normalizedSettings.portalUrl.startsWith("https://")) {
            val error = IllegalArgumentException(getString(Res.string.live_tv_error_stalker_invalid_url))
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvSourceType.Stalker,
            sourceUrl = normalizedSettings.portalUrl,
            stalkerSettings = normalizedSettings,
            isLoading = true,
            errorMessage = null,
        )
        val noChannels = getString(Res.string.live_tv_error_stalker_no_channels)
        val loadFailed = getString(Res.string.live_tv_error_stalker_failed)

        return runCatching {
            val channels = withContext(Dispatchers.Default) {
                fetchStalkerChannels(normalizedSettings)
            }
            require(channels.isNotEmpty()) { noChannels }
            withContext(Dispatchers.Default) { LiveTvStorage.saveLocalPlaylistData("") }
            LiveTvStorage.saveSourceType(LiveTvSourceType.Stalker)
            LiveTvStorage.saveStalkerSettings(normalizedSettings)
            stopEpg()
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.Stalker,
                sourceUrl = normalizedSettings.portalUrl,
                stalkerSettings = normalizedSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isLoaded = true,
            )
            channels
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: loadFailed,
            )
        }
    }

    suspend fun loadXtream(settings: LiveTvXtreamSettings): Result<List<LiveTvChannel>> {
        val normalizedSettings = settings.normalized()
        if (!normalizedSettings.isConfigured) {
            val error = IllegalArgumentException(getString(Res.string.live_tv_error_xtream_required))
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }
        if (!normalizedSettings.serverUrl.startsWith("http://") && !normalizedSettings.serverUrl.startsWith("https://")) {
            val error = IllegalArgumentException(getString(Res.string.live_tv_error_xtream_invalid_url))
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvSourceType.Xtream,
            sourceUrl = normalizedSettings.serverUrl,
            xtreamSettings = normalizedSettings,
            isLoading = true,
            errorMessage = null,
        )
        val noChannels = getString(Res.string.live_tv_error_xtream_no_channels)
        val loadFailed = getString(Res.string.live_tv_error_xtream_failed)

        return runCatching {
            val channels = withContext(Dispatchers.Default) {
                fetchXtreamChannels(normalizedSettings)
            }
            require(channels.isNotEmpty()) { noChannels }
            withContext(Dispatchers.Default) { LiveTvStorage.saveLocalPlaylistData("") }
            LiveTvStorage.saveSourceType(LiveTvSourceType.Xtream)
            LiveTvStorage.saveXtreamSettings(normalizedSettings)
            stopEpg()
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.Xtream,
                sourceUrl = normalizedSettings.serverUrl,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = normalizedSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isLoaded = true,
            )
            channels
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: loadFailed,
            )
        }
    }

    /**
     * The channel as the player should open it: Stalker links are created per play. The URL is
     * registered so the fork's playback extras treat it as live TV.
     */
    suspend fun prepareForPlayback(channel: LiveTvChannel): LiveTvChannel {
        val playbackChannel =
            if (mutableUiState.value.sourceType == LiveTvSourceType.Stalker && !channel.stalkerCommand.isNullOrBlank()) {
                runCatching { resolveStalkerPlaybackChannel(channel) }.getOrDefault(channel)
            } else {
                channel
            }
        LiveTvPlaybackRegistry.register(playbackChannel.streamUrl, listUrl = channel.streamUrl)
        return playbackChannel
    }

    fun disconnect() {
        LiveTvStorage.saveSourceUrl("")
        LiveTvStorage.saveLocalPlaylistData("")
        LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
        LiveTvRepositoryStalker.clearSession()
        stopEpg()
        mutableUiState.value = LiveTvUiState(
            sourceType = LiveTvSourceType.M3u,
            stalkerSettings = LiveTvStorage.loadStalkerSettings(),
            xtreamSettings = LiveTvStorage.loadXtreamSettings(),
            favoriteUrls = mutableUiState.value.favoriteUrls,
            recentChannel = mutableUiState.value.recentChannel,
        )
    }

    fun toggleFavorite(channel: LiveTvChannel) {
        val favorites = mutableUiState.value.favoriteUrls.toMutableSet()
        if (!favorites.add(channel.streamUrl)) {
            favorites.remove(channel.streamUrl)
        }
        LiveTvStorage.saveFavoriteUrls(favorites)
        mutableUiState.value = mutableUiState.value.copy(favoriteUrls = favorites)
    }

    /** [channel] is the list's own entry (not a resolved Stalker link), so it can be found again. */
    fun recordRecentChannel(channel: LiveTvChannel) {
        val recentChannel = LiveTvRecentChannel(
            streamUrl = channel.streamUrl,
            name = channel.name,
            logoUrl = channel.logoUrl,
            group = channel.group,
            tvgId = channel.tvgId,
        )
        LiveTvStorage.saveRecentChannel(recentChannel)
        mutableUiState.value = mutableUiState.value.copy(recentChannel = recentChannel)
    }

    private suspend fun saveM3uSource(sourceUrl: String, localPlaylistData: String) {
        // The playlist can be megabytes: written off the main thread.
        withContext(Dispatchers.Default) { LiveTvStorage.saveLocalPlaylistData(localPlaylistData) }
        LiveTvStorage.saveSourceUrl(sourceUrl)
        LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
    }

    private fun stopEpg() {
        epgJob?.cancel()
        epgJob = null
    }

    /**
     * Reads the guide once, then moves each channel's "now playing" on as programmes end, and
     * reads the guide again when what it holds runs out.
     */
    private fun startEpg(sourceUrl: String, epgUrls: List<String>, channels: List<LiveTvChannel>) {
        stopEpg()
        val tvgIds = channels.mapNotNullTo(LinkedHashSet()) { it.tvgId?.takeIf(String::isNotBlank) }
        if (epgUrls.isEmpty() || tvgIds.isEmpty()) {
            if (epgUrls.isNotEmpty()) mutableUiState.value = mutableUiState.value.copy(isEpgLoading = false)
            return
        }
        val channelIds = tvgIds.mapTo(HashSet()) { it.lowercase() }
        epgJob = epgScope.launch {
            var schedule: LiveTvSchedule = emptyMap()
            var nextFetchAtMs = 0L
            while (isActive) {
                val nowMs = LiveTvClock.nowEpochMs()
                if (nowMs >= nextFetchAtMs) {
                    val loaded = HashMap<String, List<LiveTvProgramme>>()
                    for (epgUrl in epgUrls) {
                        runCatching { loadXmlTvSchedule(epgUrl, channelIds, nowMs) }
                            .onSuccess { part -> part.forEach { (id, list) -> if (id !in loaded) loaded[id] = list } }
                    }
                    schedule = loaded
                    // A guide that could not be read is tried again sooner.
                    nextFetchAtMs = nowMs + if (loaded.isEmpty()) EPG_RETRY_MS else EPG_REFRESH_MS
                }
                val current = currentProgrammes(schedule, tvgIds, nowMs)
                if (mutableUiState.value.sourceUrl != sourceUrl) return@launch
                mutableUiState.update { state ->
                    if (state.sourceUrl != sourceUrl) {
                        state
                    } else if (state.currentProgrammes != current || state.isEpgLoading) {
                        state.copy(currentProgrammes = current, isEpgLoading = false)
                    } else {
                        state
                    }
                }
                delay(EPG_TICK_MS)
            }
        }
    }
}

private const val EPG_TICK_MS = 60_000L
/** The guide keeps 12 hours; it is read again before that runs out. */
private const val EPG_REFRESH_MS = 10L * 60 * 60 * 1000
private const val EPG_RETRY_MS = 30L * 60 * 1000

internal expect object LiveTvStorage {
    fun loadTabEnabled(): Boolean
    fun saveTabEnabled(enabled: Boolean)
    fun loadSourceType(): LiveTvSourceType
    fun saveSourceType(type: LiveTvSourceType)
    fun loadSourceUrl(): String?
    fun saveSourceUrl(url: String)
    /** Cheap: whether an imported playlist is saved, without reading it. */
    fun hasLocalPlaylistData(): Boolean
    /** Reads the saved playlist; can be megabytes, so call off the main thread. */
    fun loadLocalPlaylistData(): String?
    /** Can be megabytes: call off the main thread. Blank removes it. */
    fun saveLocalPlaylistData(data: String)
    fun loadStalkerSettings(): LiveTvStalkerSettings
    fun saveStalkerSettings(settings: LiveTvStalkerSettings)
    fun loadXtreamSettings(): LiveTvXtreamSettings
    fun saveXtreamSettings(settings: LiveTvXtreamSettings)
    fun loadFavoriteUrls(): Set<String>
    fun saveFavoriteUrls(urls: Set<String>)
    fun loadRecentChannel(): LiveTvRecentChannel?
    fun saveRecentChannel(channel: LiveTvRecentChannel?)
}

private data class StalkerSession(
    val settings: LiveTvStalkerSettings,
    val token: String,
)

private suspend fun fetchStalkerChannels(settings: LiveTvStalkerSettings): List<LiveTvChannel> =
    LiveTvRepositoryStalker.withSession(settings) { session ->
        val genres = LiveTvRepositoryStalker.getGenres(session)
        LiveTvRepositoryStalker.getChannels(session, genres)
    }

private suspend fun resolveStalkerPlaybackChannel(channel: LiveTvChannel): LiveTvChannel {
    val settings = LiveTvRepository.uiState.value.stalkerSettings.normalized()
    if (!settings.isConfigured) return channel
    return LiveTvRepositoryStalker.withSession(settings) { session ->
        // An expired session answers without a link: that counts as a failure, so it is renewed once.
        val resolvedUrl = requireNotNull(LiveTvRepositoryStalker.createLink(session, channel.stalkerCommand.orEmpty())) {
            "no link"
        }
        channel.copy(
            streamUrl = resolvedUrl,
            headers = channel.headers + LiveTvRepositoryStalker.playbackHeaders(session),
        )
    }
}

private suspend fun fetchXtreamChannels(settings: LiveTvXtreamSettings): List<LiveTvChannel> {
    val categories = LiveTvRepositoryXtream.getLiveCategories(settings)
    return LiveTvRepositoryXtream.getLiveStreams(settings, categories)
}

/** Only the fields Live TV reads: the rest of each (large) entry is skipped while decoding. */
@Serializable
private data class XtreamLiveStream(
    val name: JsonElement? = null,
    @SerialName("stream_id") val streamId: JsonElement? = null,
    val id: JsonElement? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
    @SerialName("epg_channel_id") val epgChannelId: JsonElement? = null,
    @SerialName("tvg_id") val tvgId: JsonElement? = null,
    @SerialName("stream_icon") val streamIcon: JsonElement? = null,
    val logo: JsonElement? = null,
)

private fun JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private object LiveTvRepositoryXtream {
    suspend fun getLiveCategories(settings: LiveTvXtreamSettings): Map<String, String> {
        val data = stalkerJson.parseToJsonElement(request(settings, action = "get_live_categories")).jsonArrayOrEmpty()
        return data.associateNotNull { element ->
            val obj = element as? JsonObject ?: return@associateNotNull null
            val id = obj.stringValue("category_id") ?: obj.stringValue("id") ?: return@associateNotNull null
            val name = obj.stringValue("category_name") ?: obj.stringValue("name") ?: return@associateNotNull null
            id to name
        }
    }

    suspend fun getLiveStreams(
        settings: LiveTvXtreamSettings,
        categories: Map<String, String>,
    ): List<LiveTvChannel> {
        val extension = liveExtension(settings)
        val payload = request(settings, action = "get_live_streams")
        val streams = if (payload.trimStart().startsWith("[")) {
            stalkerJson.decodeFromString(ListSerializer(XtreamLiveStream.serializer()), payload)
        } else {
            stalkerJson.parseToJsonElement(payload).jsonArrayOrEmpty().mapNotNull { element ->
                runCatching { stalkerJson.decodeFromJsonElement(XtreamLiveStream.serializer(), element) }.getOrNull()
            }
        }
        return streams.mapIndexedNotNull { index, stream ->
            val name = stream.name.text() ?: return@mapIndexedNotNull null
            val streamId = stream.streamId.text() ?: stream.id.text() ?: return@mapIndexedNotNull null
            // Always the panel's own link, as IPTV players use: "direct_source" is often the
            // panel's upstream origin, which refuses clients, and the panel redirects to it when it is meant to be used.
            val streamUrl = settings.liveStreamUrl(streamId, extension)
            LiveTvChannel(
                id = "xtream-$streamId-$index",
                name = name,
                streamUrl = streamUrl,
                tvgId = stream.epgChannelId.text() ?: stream.tvgId.text(),
                logoUrl = stream.streamIcon.text() ?: stream.logo.text(),
                group = stream.categoryId.text()?.let(categories::get).orEmpty(),
                headers = M3U_STREAM_REQUEST_HEADERS,
            )
        }.distinctBy { it.streamUrl }
    }

    /**
     * The live format this account may use: MPEG-TS, as IPTV players prefer, unless the account only
     * allows HLS ("allowed_output_formats" in the login reply); a TS link then fails on every channel.
     */
    private suspend fun liveExtension(settings: LiveTvXtreamSettings): String {
        val formats = try {
            val login = stalkerJson.parseToJsonElement(request(settings, action = null)) as? JsonObject
            (login?.get("user_info") as? JsonObject)?.get("allowed_output_formats")?.jsonArrayOrEmpty().orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
        return if (formats.isEmpty() || "ts" in formats || "m3u8" !in formats) "ts" else "m3u8"
    }

    /** A player_api call; no [action] is the login call, which describes the account. */
    private suspend fun request(settings: LiveTvXtreamSettings, action: String?): String {
        val parameters = buildMap {
            put("username", settings.username)
            put("password", settings.password)
            if (action != null) put("action", action)
        }
        val url = settings.playerApiEndpoint() + parameters.entries.joinToString(
            separator = "&",
            prefix = "?",
        ) { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        return httpGetTextWithHeaders(url, M3U_PLAYLIST_REQUEST_HEADERS)
    }
}

private object LiveTvRepositoryStalker {
    private const val MAX_PAGES = 500
    private const val PARALLEL_PAGES = 4

    @Volatile private var cachedSession: StalkerSession? = null

    fun clearSession() {
        cachedSession = null
    }

    /**
     * Runs [block] with a portal session. Sessions expire on the portal's side without notice, so
     * a failure with a cached session is retried once with a fresh handshake.
     */
    suspend fun <T> withSession(settings: LiveTvStalkerSettings, block: suspend (StalkerSession) -> T): T {
        val cached = cachedSession?.takeIf { it.settings == settings && it.token.isNotBlank() }
        if (cached != null) {
            val result = runCatching { block(cached) }
            if (result.isSuccess) return result.getOrThrow()
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            if (cachedSession === cached) cachedSession = null
        }
        return block(session(settings))
    }

    private suspend fun session(settings: LiveTvStalkerSettings): StalkerSession {
        val token = request(settings, type = "stb", action = "handshake")
            .stalkerJs()
            .stringValue("token")
            .orEmpty()
            .trim()
        if (token.isBlank()) throw IllegalStateException(getString(Res.string.live_tv_error_stalker_token))
        // Many portals only list channels once the device profile was requested with the new token.
        runCatching { request(settings, token, type = "stb", action = "get_profile") }
        return StalkerSession(settings = settings, token = token).also {
            cachedSession = it
        }
    }

    suspend fun getGenres(session: StalkerSession): Map<String, String> {
        val data = request(session.settings, session.token, type = "itv", action = "get_genres")
            .stalkerJs()
            .arrayValue("data")
        return data.associateNotNull { element ->
            val obj = element as? JsonObject ?: return@associateNotNull null
            val id = obj.stringValue("id") ?: obj.stringValue("alias") ?: return@associateNotNull null
            val title = obj.stringValue("title") ?: obj.stringValue("name") ?: return@associateNotNull null
            id to title
        }
    }

    /**
     * The whole channel list: one get_all_channels request where the portal supports it,
     * otherwise every page of get_ordered_list, a few pages at a time.
     */
    suspend fun getChannels(
        session: StalkerSession,
        genres: Map<String, String>,
    ): List<LiveTvChannel> {
        val all = runCatching {
            request(session.settings, session.token, type = "itv", action = "get_all_channels")
                .stalkerJs()
                .arrayValue("data")
        }.getOrDefault(emptyList())
        val entries = all.ifEmpty { orderedListEntries(session) }
        return entries.mapIndexedNotNull { index, element -> element.toStalkerChannel(session, genres, index) }
            .distinctBy { it.id.ifBlank { it.streamUrl } }
    }

    private suspend fun orderedListEntries(session: StalkerSession): List<JsonElement> {
        suspend fun page(number: Int): JsonObject = request(
            settings = session.settings,
            token = session.token,
            type = "itv",
            action = "get_ordered_list",
            extraParameters = mapOf("p" to number.toString()),
        ).stalkerJs()

        val first = page(1)
        val firstData = first.arrayValue("data")
        if (firstData.isEmpty()) return emptyList()
        val total = first.intValue("total_items")
        val perPage = first.intValue("max_page_items")?.takeIf { it > 0 } ?: firstData.size
        val entries = firstData.toMutableList()
        if (total != null && perPage > 0) {
            // Known page count: fetch the rest a few at a time.
            val lastPage = ((total + perPage - 1) / perPage).coerceAtMost(MAX_PAGES)
            (2..lastPage).chunked(PARALLEL_PAGES).forEach { pages ->
                val results = coroutineScope {
                    pages.map { number -> async { runCatching { page(number).arrayValue("data") }.getOrDefault(emptyList()) } }
                        .awaitAll()
                }
                results.forEach(entries::addAll)
            }
        } else {
            // Unknown count: until the first empty page.
            for (number in 2..MAX_PAGES) {
                val data = page(number).arrayValue("data")
                if (data.isEmpty()) break
                entries += data
            }
        }
        return entries
    }

    private fun JsonElement.toStalkerChannel(
        session: StalkerSession,
        genres: Map<String, String>,
        index: Int,
    ): LiveTvChannel? {
        val obj = this as? JsonObject ?: return null
        val name = obj.stringValue("name") ?: obj.stringValue("title") ?: return null
        val command = obj.stringValue("cmd") ?: obj.stringValue("mc_cmd") ?: obj.stringValue("url") ?: return null
        val streamUrl = command.toStalkerPlayableUrl()
        if (streamUrl.isBlank()) return null
        val genreId = obj.stringValue("tv_genre_id") ?: obj.stringValue("genre_id")
        return LiveTvChannel(
            id = obj.stringValue("id") ?: "stalker-$index-${streamUrl.hashCode()}",
            name = name,
            streamUrl = streamUrl,
            tvgId = obj.stringValue("xmltv_id") ?: obj.stringValue("tvg_id"),
            logoUrl = obj.stringValue("logo") ?: obj.stringValue("logo_url"),
            group = genreId?.let(genres::get).orEmpty(),
            headers = playbackHeaders(session),
            stalkerCommand = command,
        )
    }

    suspend fun createLink(session: StalkerSession, command: String): String? {
        val data = request(
            settings = session.settings,
            token = session.token,
            type = "itv",
            action = "create_link",
            extraParameters = mapOf("cmd" to command),
        ).stalkerJs()
        return (data.stringValue("cmd") ?: data.stringValue("url") ?: data.stringValue("stream_url"))
            ?.toStalkerPlayableUrl()
            ?.takeIf { it.isNotBlank() }
    }

    fun playbackHeaders(session: StalkerSession): Map<String, String> =
        baseHeaders(session.settings) + mapOf(
            "Authorization" to "Bearer ${session.token}",
        )

    private suspend fun request(
        settings: LiveTvStalkerSettings,
        token: String? = null,
        type: String,
        action: String,
        extraParameters: Map<String, String> = emptyMap(),
    ): JsonObject {
        val parameters = buildMap {
            put("type", type)
            put("action", action)
            put("JsHttpRequest", "1-xml")
            if (!token.isNullOrBlank()) put("token", token)
            if (settings.username.isNotBlank()) put("login", settings.username)
            if (settings.password.isNotBlank()) put("password", settings.password)
            putAll(extraParameters)
        }
        val url = settings.portalEndpoint() + parameters.entries.joinToString(
            separator = "&",
            prefix = if (settings.portalEndpoint().contains("?")) "&" else "?",
        ) { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        val payload = httpGetTextWithHeaders(url, baseHeaders(settings) + tokenHeader(token))
        return stalkerJson.parseToJsonElement(payload).jsonObject
    }

    private fun baseHeaders(settings: LiveTvStalkerSettings): Map<String, String> =
        mapOf(
            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; MAG254; en) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3",
            "X-User-Agent" to "Model: MAG254; Link: Ethernet",
            "Referer" to settings.portalBaseUrl(),
            "Cookie" to "mac=${settings.macAddress}; stb_lang=en; timezone=${LiveTvClock.timeZoneId().encodeURLParameter()}",
        )

    private fun tokenHeader(token: String?): Map<String, String> =
        if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
}

private fun JsonObject.intValue(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

private val stalkerJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun LiveTvStalkerSettings.normalized(): LiveTvStalkerSettings =
    copy(
        portalUrl = portalUrl.trim().trimEnd('/'),
        macAddress = macAddress.trim().uppercase(),
        username = username.trim(),
        password = password.trim(),
    )

private fun LiveTvXtreamSettings.normalized(): LiveTvXtreamSettings =
    copy(
        serverUrl = serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/'),
        username = username.trim(),
        password = password.trim(),
    )

private fun LiveTvXtreamSettings.playerApiEndpoint(): String =
    "${serverUrl.trim().trimEnd('/')}/player_api.php"

private fun LiveTvXtreamSettings.liveStreamUrl(streamId: String, extension: String): String =
    buildString {
        append(serverUrl.trim().trimEnd('/'))
        append("/live/")
        append(username.encodeURLParameter())
        append("/")
        append(password.encodeURLParameter())
        append("/")
        append(streamId.encodeURLParameter())
        append(".")
        append(extension.trim().trimStart('.').ifBlank { "ts" })
    }

private fun LiveTvStalkerSettings.portalEndpoint(): String {
    val normalized = portalUrl.trim().trimEnd('/')
    return when {
        normalized.endsWith("portal.php", ignoreCase = true) -> normalized
        normalized.contains("portal.php?", ignoreCase = true) -> normalized
        else -> "$normalized/portal.php"
    }
}

private fun LiveTvStalkerSettings.portalBaseUrl(): String =
    portalUrl.trim().substringBefore("/portal.php").trimEnd('/') + "/c/"

private fun String.toStalkerPlayableUrl(): String =
    trim()
        .removePrefix("ffmpeg ")
        .removePrefix("auto ")
        .substringBefore(' ')
        .trim()

private fun JsonObject.stalkerJs(): JsonObject =
    (this["js"] as? JsonObject) ?: this

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.arrayValue(name: String): List<JsonElement> =
    (this[name] as? JsonArray)?.toList().orEmpty()

private fun JsonElement.jsonArrayOrEmpty(): List<JsonElement> =
    (this as? JsonArray)?.toList()
        ?: (this as? JsonObject)?.arrayValue("data")
        ?: emptyList()

private inline fun <K, V> Iterable<JsonElement>.associateNotNull(transform: (JsonElement) -> Pair<K, V>?): Map<K, V> =
    mapNotNull(transform).toMap()


internal fun parseM3uPlaylist(content: String): List<LiveTvChannel> =
    parseM3uPlaylistData(content).channels

internal data class ParsedM3uPlaylist(
    val channels: List<LiveTvChannel>,
    val epgUrls: List<String>,
)

internal fun parseM3uPlaylistData(content: String): ParsedM3uPlaylist {
    val channels = mutableListOf<LiveTvChannel>()
    val epgUrls = linkedSetOf<String>()
    var metadata: ParsedM3uMetadata? = null
    var pendingHeaders = emptyMap<String, String>()

    content.lineSequence().forEach { rawLine ->
        val line = rawLine.trim().removePrefix("\uFEFF")
        when {
            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                val attributes = parseM3uAttributes(line)
                listOfNotNull(attributes["url-tvg"], attributes["x-tvg-url"])
                    .flatMap { it.split(',', ';') }
                    .map(String::trim)
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .forEach(epgUrls::add)
            }

            line.startsWith("#EXTINF", ignoreCase = true) -> {
                metadata = parseExtInf(line)
            }

            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + ("User-Agent" to line.substringAfter('=').trim())
            }

            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + ("Referer" to line.substringAfter('=').trim())
            }

            line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + parseExtHttpHeaders(line.substringAfter(':'))
            }

            line.isNotEmpty() && !line.startsWith("#") -> {
                val parsedUrl = parseStreamUrl(line)
                val current = metadata ?: ParsedM3uMetadata(
                    name = "Channel ${channels.size + 1}",
                    tvgId = null,
                    logoUrl = null,
                    group = "",
                )
                channels += LiveTvChannel(
                    id = "${parsedUrl.url}#${channels.size}",
                    name = current.name.ifBlank { "Channel ${channels.size + 1}" },
                    streamUrl = parsedUrl.url,
                    tvgId = current.tvgId,
                    logoUrl = current.logoUrl,
                    group = current.group,
                    headers = defaultM3uStreamHeaders(parsedUrl.url) + pendingHeaders + parsedUrl.headers,
                    streamType = parsedUrl.url.inferM3uStreamType(),
                )
                metadata = null
                pendingHeaders = emptyMap()
            }
        }
    }

    return ParsedM3uPlaylist(
        channels = channels
            .distinctBy { it.streamUrl }
            .filterNot { isLikelyCategoryHeading(it.name) },
        epgUrls = epgUrls.toList(),
    )
}

private data class ParsedM3uMetadata(
    val name: String,
    val tvgId: String?,
    val logoUrl: String?,
    val group: String,
)

private data class ParsedStreamUrl(
    val url: String,
    val headers: Map<String, String>,
)

private val m3uAttributeRegex = Regex("""([\w-]+)="([^"]*)"""")

private fun parseExtInf(line: String): ParsedM3uMetadata {
    val attributes = parseM3uAttributes(line.substringBeforeLast(',', line))
    val displayName = line.substringAfterLast(',', "").trim()
        .ifBlank { attributes["tvg-name"].orEmpty() }

    return ParsedM3uMetadata(
        name = displayName,
        tvgId = attributes["tvg-id"]?.takeIf(String::isNotBlank),
        logoUrl = attributes["tvg-logo"]?.takeIf { it.isNotBlank() },
        group = attributes["group-title"].orEmpty(),
    )
}

private fun parseM3uAttributes(line: String): Map<String, String> =
    m3uAttributeRegex
        .findAll(line)
        .associate { match -> match.groupValues[1].lowercase() to match.groupValues[2].trim() }

private fun parseStreamUrl(line: String): ParsedStreamUrl {
    val url = line.substringBefore('|').trim()
    val headers = line.substringAfter('|', "")
        .split('&')
        .mapNotNull { entry ->
            val key = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()
    return ParsedStreamUrl(url = url, headers = headers)
}

private fun parseExtHttpHeaders(value: String): Map<String, String> {
    val trimmed = value.trim().removePrefix("{").removeSuffix("}")
    return trimmed.split(',')
        .mapNotNull { entry ->
            val key = entry.substringBefore(':').trim().trim('"')
            val headerValue = entry.substringAfter(':', "").trim().trim('"')
            if (key.isBlank() || headerValue.isBlank()) null else key to headerValue
        }
        .toMap()
}

private fun defaultM3uStreamHeaders(url: String): Map<String, String> {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyMap()
    return M3U_STREAM_REQUEST_HEADERS
}

private fun directStreamChannel(url: String): LiveTvChannel =
    LiveTvChannel(
        id = "direct-${url.hashCode()}",
        name = url.substringBefore('?').substringAfterLast('/').ifBlank { "Live stream" },
        streamUrl = url,
        group = "Direct stream",
        headers = defaultM3uStreamHeaders(url),
        streamType = url.inferM3uStreamType(),
    )

internal fun String.looksLikeDirectVideoUrl(): Boolean {
    val normalized = substringBefore('#').substringBefore('?').lowercase()
    if (normalized.endsWith(".m3u") || normalized.endsWith(".m3u8")) return false
    return listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".ts", ".mpeg", ".mpg")
        .any(normalized::endsWith)
}

private fun String.inferM3uStreamType(): String? {
    val normalized = substringBefore('#').substringBefore('?').lowercase()
    return when {
        normalized.endsWith(".m3u8") -> "hls"
        normalized.endsWith(".mkv") -> "matroska"
        normalized.endsWith(".mp4") || normalized.endsWith(".m4v") -> "mp4"
        normalized.endsWith(".webm") -> "webm"
        normalized.endsWith(".ts") || normalized.endsWith(".mts") || normalized.endsWith(".m2ts") -> "mpegts"
        else -> null
    }
}

internal fun String.looksLikeHlsManifest(): Boolean =
    lineSequence().any { it.trim().startsWith("#EXT-X-", ignoreCase = true) }

private val M3U_PLAYLIST_REQUEST_HEADERS = mapOf(
    "User-Agent" to "VLC/3.0.0 LibVLC/3.0.0",
    "Accept" to "application/x-mpegURL, application/vnd.apple.mpegurl, audio/mpegurl, text/plain, */*",
)

private val M3U_STREAM_REQUEST_HEADERS = mapOf(
    "User-Agent" to "VLC/3.0.0 LibVLC/3.0.0",
)

private val categoryHeadingRegex = Regex("""^\s*#+\s*.+\s*#+\s*$""")

internal fun isLikelyCategoryHeading(name: String): Boolean {
    val normalized = name.trim()
    // A cheap check first: this runs for every channel whenever a list is filtered.
    return normalized.length >= 8 && normalized.startsWith('#') && categoryHeadingRegex.matches(normalized)
}

internal expect object LiveTvClock {
    fun nowEpochMs(): Long
    fun parseXmlTvTimestamp(value: String): Long?
    /** The device's time zone id, e.g. Europe/Bucharest. */
    fun timeZoneId(): String
    /** [epochMs] as a short local time, e.g. 21:30. */
    fun formatClock(epochMs: Long): String
}
