package com.nuvio.app.features.reshaped.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleToasts
import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.pillnav.PillNavRepository
import com.nuvio.app.features.reshaped.livetv.LiveTvRecentChannel
import com.nuvio.app.features.reshaped.livetv.LiveTvRepository
import com.nuvio.app.features.reshaped.livetv.LiveTvSourceType
import com.nuvio.app.features.reshaped.livetv.LiveTvStalkerSettings
import com.nuvio.app.features.reshaped.livetv.LiveTvStorage
import com.nuvio.app.features.reshaped.livetv.LiveTvSyncSource
import com.nuvio.app.features.reshaped.livetv.LiveTvTabSettings
import com.nuvio.app.features.reshaped.livetv.LiveTvXtreamSettings
import com.nuvio.app.features.reshaped.livetv.resolveLiveTvStorageProfileId
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The phone side of Reshaped sync: the same file in the Google account's hidden Drive app folder
 * as the TV app, merged key by key (see [SyncDoc]). The phone has one Live TV source, so it
 * keeps the TV's other sources in the file as they are and never deletes them.
 *
 * Syncs when the app comes to the front (at most once a minute), when it goes to the back if
 * something changed here, and on "Sync now". Nothing runs in the background.
 */
internal object ReshapedSyncAndroid : ReshapedSyncController {
    private const val TAG = "ReshapedSync"
    private const val PREFS = "nuvio_reshaped_sync"
    private const val KEY_SETTINGS = "sync_settings"
    private const val KEY_LIVE_TV = "sync_live_tv"
    private const val KEY_LAST_SYNC = "last_sync_ms"
    private const val FOREGROUND_MIN_GAP_MS = 60_000L
    /** The first sync waits until the app has finished starting. */
    private const val START_DELAY_MS = 8_000L
    private const val SHARED = "settings/shared"
    private const val MOBILE = "settings/mobile"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var appContext: Context
    private var installed = false
    @Volatile private var lastForegroundSyncMs = 0L
    @Volatile private var driveFileId: String? = null
    private var signInJob: Job? = null

    private val _syncSettings = MutableStateFlow(false)
    override val syncSettings: StateFlow<Boolean> = _syncSettings.asStateFlow()
    private val _syncLiveTv = MutableStateFlow(false)
    override val syncLiveTv: StateFlow<Boolean> = _syncLiveTv.asStateFlow()
    private val _status = MutableStateFlow(ReshapedSyncStatus())
    override val status: StateFlow<ReshapedSyncStatus> = _status.asStateFlow()
    private val _signIn = MutableStateFlow<ReshapedSignInState?>(null)
    override val signIn: StateFlow<ReshapedSignInState?> = _signIn.asStateFlow()
    override val email: StateFlow<String?> get() = GoogleAccount.email
    override val isConfigured: Boolean get() = GoogleAccount.isConfigured

    /** Nuvio RS hook in MainActivity.onCreate. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        val prefs = prefs()
        _syncSettings.value = prefs.getBoolean(KEY_SETTINGS, false)
        _syncLiveTv.value = prefs.getBoolean(KEY_LIVE_TV, false)
        _status.value = ReshapedSyncStatus(lastSyncedAtMs = prefs.getLong(KEY_LAST_SYNC, 0L))
        GoogleAccount.ensureLoaded(appContext)
        ReshapedSyncBridge.controller = this
        (appContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) onForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) scope.launch { sync(onlyIfChanged = true) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun startSignIn() {
        signInJob?.cancel()
        _signIn.value = ReshapedSignInState.Starting
        signInJob = scope.launch {
            try {
                val code = GoogleAccount.requestDeviceCode()
                _signIn.value = ReshapedSignInState.Waiting(code.userCode, code.verificationUrl)
                GoogleAccount.awaitSignIn(appContext, code)
                driveFileId = null
                baseFile().delete()
                _signIn.value = null
                sync(onlyIfChanged = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: GoogleAuthException) {
                _signIn.value = ReshapedSignInState.Failed(declined = error.code == "access_denied", expired = error.code == "expired_token")
            } catch (error: Exception) {
                _signIn.value = ReshapedSignInState.Failed(declined = false, expired = false)
            }
        }
    }

    override fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        _signIn.value = null
    }

    override fun signOut() {
        scope.launch {
            GoogleAccount.signOut(appContext)
            driveFileId = null
            baseFile().delete()
            _status.update { it.copy(failed = null) }
        }
    }

    override fun setSyncSettings(enabled: Boolean) {
        _syncSettings.value = enabled
        prefs().edit().putBoolean(KEY_SETTINGS, enabled).apply()
        if (enabled) syncNow()
    }

    override fun setSyncLiveTv(enabled: Boolean) {
        _syncLiveTv.value = enabled
        prefs().edit().putBoolean(KEY_LIVE_TV, enabled).apply()
        if (enabled) syncNow()
    }

    override fun syncNow() {
        scope.launch { sync(onlyIfChanged = false) }
    }

    private fun onForeground() {
        val now = SystemClock.elapsedRealtime()
        val firstStart = lastForegroundSyncMs == 0L
        if (!firstStart && now - lastForegroundSyncMs < FOREGROUND_MIN_GAP_MS) return
        lastForegroundSyncMs = now
        scope.launch {
            if (firstStart) delay(START_DELAY_MS)
            sync(onlyIfChanged = false)
        }
    }

    private suspend fun sync(onlyIfChanged: Boolean) {
        val settingsOn = _syncSettings.value
        val liveTvOn = _syncLiveTv.value
        if (!settingsOn && !liveTvOn) return
        if (!GoogleAccount.isConfigured || !GoogleAccount.isSignedIn(appContext)) return
        mutex.withLock {
            val base = readBase()
            // Settings and Live TV are read and changed on the main thread, as their screens do.
            val (settings, profileId, liveTv) = withContext(Dispatchers.Main) {
                val settings = if (settingsOn) currentSettings() else emptyMap()
                val profileId = if (liveTvOn) resolveLiveTvStorageProfileId() else null
                Triple(settings, profileId, profileId?.let { currentLiveTv(it, base) }.orEmpty())
            }
            val current = settings + liveTv
            if (onlyIfChanged && SyncDoc.stamp(base, current, 0L) == base) return
            _status.update { it.copy(running = true) }
            try {
                val remoteFile = DriveAppFolder.read(appContext)
                val remote = remoteFile.others.fold(SyncDoc.decode(remoteFile.text)) { doc, (_, text) -> SyncDoc.merge(SyncDoc.decode(text), doc) }
                val now = SyncDoc.stampTime(System.currentTimeMillis(), base, remote)
                val merged = SyncDoc.prune(SyncDoc.merge(SyncDoc.stamp(base, current, now), remote), now)
                withContext(Dispatchers.Main) {
                    if (settingsOn) applySettings(settings, merged)
                    if (profileId != null) applyLiveTv(profileId, liveTv, merged)
                }
                driveFileId = if (merged != remote || remoteFile.id == null || remoteFile.others.isNotEmpty()) {
                    DriveAppFolder.write(appContext, remoteFile.id ?: driveFileId, SyncDoc.encode(merged))
                } else {
                    remoteFile.id
                }
                remoteFile.others.forEach { (id, _) -> runCatching { DriveAppFolder.delete(appContext, id) } }
                writeBase(merged)
                val syncedAt = System.currentTimeMillis()
                prefs().edit().putLong(KEY_LAST_SYNC, syncedAt).apply()
                _status.value = ReshapedSyncStatus(lastSyncedAtMs = syncedAt)
            } catch (cancel: CancellationException) {
                _status.update { it.copy(running = false) }
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Sync failed", error)
                val failure = when (error) {
                    is DriveAppFolder.SignedOutException -> ReshapedSyncFailure.SignedOut
                    is SyncDoc.NewerFormatException -> ReshapedSyncFailure.NewerVersion
                    else -> ReshapedSyncFailure.Network
                }
                _status.update { it.copy(running = false, failed = failure) }
            }
        }
    }

    // region Settings

    private fun currentSettings(): Map<String, Map<String, JsonElement>> {
        LiveTvTabSettings.ensureLoaded()
        AutoSyncPreferencesRepository.ensureLoaded()
        LibraryDisplaySettingsRepository.ensureLoaded()
        val shared = buildMap<String, JsonElement> {
            put("live_tv", JsonPrimitive(LiveTvTabSettings.enabled.value))
            if (AutoSyncBubbleToasts.isAvailable) put("autosync_bubble", JsonPrimitive(AutoSyncBubbleToasts.enabled.value))
            put("autosync_tolerance_ms", JsonPrimitive(AutoSyncPreferencesRepository.syncToleranceMs.value))
        }
        val mobile = buildMap<String, JsonElement> {
            if (PillNavRepository.isAvailable) put("pill_nav", JsonPrimitive(PillNavRepository.enabled.value))
            put("library_calendar", JsonPrimitive(LibraryDisplaySettingsRepository.uiState.value.calendarEnabled))
        }
        return mapOf(SHARED to shared, MOBILE to mobile)
    }

    private fun applySettings(current: Map<String, Map<String, JsonElement>>, merged: SyncSections) {
        fun changed(section: String, key: String): JsonPrimitive? {
            if (current[section]?.containsKey(key) != true) return null
            val value = SyncDoc.values(merged, section)[key] as? JsonPrimitive ?: return null
            return value.takeIf { it != current[section]?.get(key) }
        }
        changed(SHARED, "live_tv")?.booleanOrNull?.let(LiveTvTabSettings::setEnabled)
        changed(SHARED, "autosync_bubble")?.booleanOrNull?.let(AutoSyncBubbleToasts::setEnabled)
        changed(SHARED, "autosync_tolerance_ms")?.intOrNull
            ?.takeIf { it in AutoSyncPreferencesRepository.syncToleranceOptionsMs }
            ?.let(AutoSyncPreferencesRepository::setSyncToleranceMs)
        changed(MOBILE, "pill_nav")?.booleanOrNull?.let(PillNavRepository::setEnabled)
        changed(MOBILE, "library_calendar")?.booleanOrNull?.let(LibraryDisplaySettingsRepository::setCalendarEnabled)
    }

    // endregion

    // region Live TV (same sections as the TV app's LiveTvSections)

    private fun sources(p: Int) = "live_tv/$p/sources"
    private fun favorites(p: Int) = "live_tv/$p/favorites"
    private fun recent(p: Int) = "live_tv/$p/recent"
    private val TRUE = JsonPrimitive(true)

    /**
     * The phone's Live TV as sections. Its sources section is the file's sources plus this
     * phone's own: the phone holds one source, and must not delete the TV's others.
     */
    private fun currentLiveTv(profileId: Int, base: SyncSections): Map<String, Map<String, JsonElement>> {
        LiveTvRepository.ensureLoaded()
        val state = LiveTvRepository.uiState.value
        val known = SyncDoc.values(base, sources(profileId))
        val own = LiveTvRepository.syncedSource()
        val sourceSection = if (own == null) known else {
            val id = (known[own.identity] as? JsonObject)?.text("id").orEmpty()
            known + (own.identity to own.toJson(id))
        }
        return mapOf(
            sources(profileId) to sourceSection,
            favorites(profileId) to state.favoriteUrls.associateWith { TRUE },
            recent(profileId) to (state.recentChannel?.let { mapOf("channel" to it.toJson()) } ?: emptyMap()),
        )
    }

    private fun applyLiveTv(profileId: Int, before: Map<String, Map<String, JsonElement>>, merged: SyncSections) {
        val beforeSources = before[sources(profileId)].orEmpty()
        val afterSources = SyncDoc.values(merged, sources(profileId))
        val own = LiveTvRepository.syncedSource()
        when {
            own != null && own.identity in afterSources -> {
                val updated = (afterSources[own.identity] as? JsonObject)?.toSource()
                if (updated != null && updated != own) LiveTvRepository.applySyncedSource(updated)
            }
            own != null && own.identity in beforeSources -> {
                // Removed on another device: take another synced source, or none.
                LiveTvRepository.applySyncedSource(afterSources.values.firstNotNullOfOrNull { (it as? JsonObject)?.toSource() })
            }
            own == null && LiveTvRepository.uiState.value.sourceUrl.isBlank() && !LiveTvStorage.hasLocalPlaylistData() -> {
                // No source here yet: take one another device added.
                val added = afterSources.filterKeys { it !in beforeSources || beforeSources.isEmpty() }
                added.values.firstNotNullOfOrNull { (it as? JsonObject)?.toSource() }?.let(LiveTvRepository::applySyncedSource)
            }
        }
        val beforeFavorites = before[favorites(profileId)].orEmpty().keys
        val afterFavorites = SyncDoc.values(merged, favorites(profileId)).keys
        LiveTvRepository.applySyncedFavorites(beforeFavorites, afterFavorites)
        val beforeRecent = before[recent(profileId)]?.get("channel")
        val afterRecent = SyncDoc.values(merged, recent(profileId))["channel"]
        if (afterRecent != null && afterRecent != beforeRecent) {
            (afterRecent as? JsonObject)?.toRecent()?.let(LiveTvRepository::applySyncedRecent)
        }
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun LiveTvSyncSource.toJson(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type.name)
        put("url", url)
        when (type) {
            LiveTvSourceType.M3u -> Unit
            LiveTvSourceType.Xtream -> {
                put("server", xtream.serverUrl)
                put("user", xtream.username)
                put("password", xtream.password)
            }
            LiveTvSourceType.Stalker -> {
                put("portal", stalker.portalUrl)
                put("mac", stalker.macAddress)
                put("user", stalker.username)
                put("password", stalker.password)
            }
        }
    }

    private fun JsonObject.toSource(): LiveTvSyncSource? {
        val type = LiveTvSourceType.entries.firstOrNull { it.name == text("type") } ?: return null
        return when (type) {
            LiveTvSourceType.M3u -> LiveTvSyncSource(type, text("url")).takeIf { it.url.isNotBlank() }
            LiveTvSourceType.Xtream -> LiveTvSyncSource(
                type, text("url"), xtream = LiveTvXtreamSettings(text("server"), text("user"), text("password")),
            ).takeIf { it.xtream.isConfigured }
            LiveTvSourceType.Stalker -> LiveTvSyncSource(
                type, text("url"), stalker = LiveTvStalkerSettings(text("portal"), text("mac"), text("user"), text("password")),
            ).takeIf { it.stalker.isConfigured }
        }
    }

    private fun LiveTvRecentChannel.toJson(): JsonObject = buildJsonObject {
        put("url", streamUrl)
        put("name", name)
        logoUrl?.let { put("logo", it) }
        put("group", group)
        tvgId?.let { put("tvg_id", it) }
    }

    private fun JsonObject.toRecent(): LiveTvRecentChannel? {
        val url = text("url").ifBlank { return null }
        val name = text("name").ifBlank { return null }
        return LiveTvRecentChannel(url, name, text("logo").ifBlank { null }, text("group"), text("tvg_id").ifBlank { null })
    }

    // endregion

    private fun baseFile() = File(appContext.filesDir, "reshaped_sync/base.json")

    private fun readBase(): SyncSections =
        runCatching { SyncDoc.decode(baseFile().takeIf(File::isFile)?.readText()) }.getOrDefault(emptyMap())

    private fun writeBase(sections: SyncSections) {
        val target = baseFile()
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        temp.writeText(SyncDoc.encode(sections))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
