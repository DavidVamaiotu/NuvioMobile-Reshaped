package com.nuvio.app.features.reshaped.livetv

import android.content.Context
import android.content.SharedPreferences
import java.io.File

actual object LiveTvStorage {
    private const val preferencesName = "nuvio_live_tv"
    private const val tabEnabledKey = "reshaped_live_tv_tab_enabled"
    private const val sourceTypeKey = "source_type"
    private const val sourceUrlKey = "m3u_source_url"
    private const val localPlaylistDataKey = "m3u_local_playlist_data"
    private const val stalkerPortalUrlKey = "stalker_portal_url"
    private const val stalkerMacAddressKey = "stalker_mac_address"
    private const val stalkerUsernameKey = "stalker_username"
    private const val stalkerPasswordKey = "stalker_password"
    private const val xtreamServerUrlKey = "xtream_server_url"
    private const val xtreamUsernameKey = "xtream_username"
    private const val xtreamPasswordKey = "xtream_password"
    private const val favoriteUrlsKey = "favorite_channel_urls"
    private const val recentChannelUrlKey = "recent_channel_url"
    private const val recentChannelNameKey = "recent_channel_name"
    private const val recentChannelLogoKey = "recent_channel_logo"
    private const val recentChannelGroupKey = "recent_channel_group"
    private const val recentChannelTvgIdKey = "recent_channel_tvg_id"

    private var preferences: SharedPreferences? = null
    /** Imported playlists can be megabytes, so they live in files, not in the preferences. */
    private var playlistDir: File? = null

    private fun resolvedProfileId(): Int = resolveLiveTvStorageProfileId()

    private fun scopedKey(baseKey: String, profileId: Int = resolvedProfileId()): String = "${baseKey}_$profileId"

    private fun SharedPreferences.getScopedString(baseKey: String): String? {
        val profileId = resolvedProfileId()
        return getString(scopedKey(baseKey, profileId), null)
            ?: if (profileId == 1) getString(baseKey, null) else null
    }

    private fun SharedPreferences.Editor.putScopedString(baseKey: String, value: String?) {
        val profileId = resolvedProfileId()
        val profileKey = scopedKey(baseKey, profileId)
        if (value.isNullOrBlank()) {
            remove(profileKey)
            if (profileId == 1) remove(baseKey)
        } else {
            putString(profileKey, value)
            if (profileId == 1) putString(baseKey, value)
        }
    }

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences = prefs
        val dir = File(context.filesDir, "live_tv")
        playlistDir = dir
        // Earlier builds kept imported playlists in the preferences; move them out once.
        Thread({ migratePlaylistsFromPreferences(prefs, dir) }, "NuvioLiveTvStorage").apply {
            isDaemon = true
        }.start()
    }

    private fun migratePlaylistsFromPreferences(prefs: SharedPreferences, dir: File) {
        runCatching {
            val legacy = prefs.all.filterKeys { it == localPlaylistDataKey || it.startsWith("${localPlaylistDataKey}_") }
            if (legacy.isEmpty()) return
            legacy.forEach { (key, value) ->
                val data = value as? String ?: return@forEach
                val profileId = if (key == localPlaylistDataKey) 1 else key.substringAfterLast('_').toIntOrNull() ?: return@forEach
                val file = playlistFile(dir, profileId)
                if (data.isNotBlank() && !file.exists()) writePlaylist(file, data)
            }
            prefs.edit().apply { legacy.keys.forEach(::remove) }.apply()
        }
    }

    private fun playlistFile(dir: File, profileId: Int): File = File(dir, "playlist_$profileId.m3u")

    private fun writePlaylist(file: File, data: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(data)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    actual fun loadTabEnabled(): Boolean = preferences?.getBoolean(tabEnabledKey, false) ?: false

    actual fun saveTabEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(tabEnabledKey, enabled)?.apply()
    }

    actual fun loadSourceType(): LiveTvSourceType =
        when (preferences?.getScopedString(sourceTypeKey) ?: LiveTvSourceType.M3u.name) {
            LiveTvSourceType.Stalker.name -> LiveTvSourceType.Stalker
            LiveTvSourceType.Xtream.name -> LiveTvSourceType.Xtream
            else -> LiveTvSourceType.M3u
        }

    actual fun saveSourceType(type: LiveTvSourceType) {
        preferences?.edit()?.apply {
            putScopedString(sourceTypeKey, type.name)
        }?.apply()
    }

    actual fun loadSourceUrl(): String? =
        preferences?.getScopedString(sourceUrlKey)

    actual fun saveSourceUrl(url: String) {
        preferences?.edit()?.apply {
            putScopedString(sourceUrlKey, url)
        }?.apply()
    }

    actual fun hasLocalPlaylistData(): Boolean {
        val dir = playlistDir ?: return false
        return playlistFile(dir, resolvedProfileId()).let { it.exists() && it.length() > 0L } ||
            preferences?.getScopedString(localPlaylistDataKey)?.isNotBlank() == true
    }

    /** Reads a file: call off the main thread. */
    actual fun loadLocalPlaylistData(): String? {
        val dir = playlistDir ?: return null
        val file = playlistFile(dir, resolvedProfileId())
        return runCatching { file.takeIf(File::exists)?.readText() }.getOrNull()?.takeIf(String::isNotBlank)
            ?: preferences?.getScopedString(localPlaylistDataKey) // not migrated yet
    }

    /** Writes a file: call off the main thread. Blank removes the playlist. */
    actual fun saveLocalPlaylistData(data: String) {
        val dir = playlistDir ?: return
        val file = playlistFile(dir, resolvedProfileId())
        if (data.isBlank()) {
            file.delete()
            if (preferences?.getScopedString(localPlaylistDataKey) != null) {
                preferences?.edit()?.apply { putScopedString(localPlaylistDataKey, null) }?.apply()
            }
        } else {
            runCatching { writePlaylist(file, data) }
        }
    }

    actual fun loadStalkerSettings(): LiveTvStalkerSettings =
        LiveTvStalkerSettings(
            portalUrl = preferences?.getScopedString(stalkerPortalUrlKey).orEmpty(),
            macAddress = preferences?.getScopedString(stalkerMacAddressKey).orEmpty(),
            username = preferences?.getScopedString(stalkerUsernameKey).orEmpty(),
            password = preferences?.getScopedString(stalkerPasswordKey).orEmpty(),
        )

    actual fun saveStalkerSettings(settings: LiveTvStalkerSettings) {
        preferences?.edit()?.apply {
            putScopedString(stalkerPortalUrlKey, settings.portalUrl)
            putScopedString(stalkerMacAddressKey, settings.macAddress)
            putScopedString(stalkerUsernameKey, settings.username)
            putScopedString(stalkerPasswordKey, settings.password)
        }?.apply()
    }

    actual fun loadXtreamSettings(): LiveTvXtreamSettings =
        LiveTvXtreamSettings(
            serverUrl = preferences?.getScopedString(xtreamServerUrlKey).orEmpty(),
            username = preferences?.getScopedString(xtreamUsernameKey).orEmpty(),
            password = preferences?.getScopedString(xtreamPasswordKey).orEmpty(),
        )

    actual fun saveXtreamSettings(settings: LiveTvXtreamSettings) {
        preferences?.edit()?.apply {
            putScopedString(xtreamServerUrlKey, settings.serverUrl)
            putScopedString(xtreamUsernameKey, settings.username)
            putScopedString(xtreamPasswordKey, settings.password)
        }?.apply()
    }

    actual fun loadFavoriteUrls(): Set<String> =
        preferences?.getScopedString(favoriteUrlsKey)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()

    actual fun saveFavoriteUrls(urls: Set<String>) {
        preferences?.edit()?.apply {
            putScopedString(favoriteUrlsKey, urls.sorted().joinToString("\n"))
        }?.apply()
    }

    actual fun loadRecentChannel(): LiveTvRecentChannel? {
        val prefs = preferences ?: return null
        val streamUrl = prefs.getScopedString(recentChannelUrlKey).orEmpty().trim()
        val name = prefs.getScopedString(recentChannelNameKey).orEmpty().trim()
        if (streamUrl.isBlank() || name.isBlank()) return null
        return LiveTvRecentChannel(
            streamUrl = streamUrl,
            name = name,
            logoUrl = prefs.getScopedString(recentChannelLogoKey)?.takeIf(String::isNotBlank),
            group = prefs.getScopedString(recentChannelGroupKey).orEmpty(),
            tvgId = prefs.getScopedString(recentChannelTvgIdKey)?.takeIf(String::isNotBlank),
        )
    }

    actual fun saveRecentChannel(channel: LiveTvRecentChannel?) {
        preferences?.edit()?.apply {
            putScopedString(recentChannelUrlKey, channel?.streamUrl)
            putScopedString(recentChannelNameKey, channel?.name)
            putScopedString(recentChannelLogoKey, channel?.logoUrl)
            putScopedString(recentChannelGroupKey, channel?.group)
            putScopedString(recentChannelTvgIdKey, channel?.tvgId)
        }?.apply()
    }
}
