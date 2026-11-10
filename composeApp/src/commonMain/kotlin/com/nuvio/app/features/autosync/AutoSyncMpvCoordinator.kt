package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_failed
import nuvio.composeapp.generated.resources.autosync_toast_failed_no_reference
import nuvio.composeapp.generated.resources.autosync_toast_analyzing
import nuvio.composeapp.generated.resources.autosync_toast_in_sync
import nuvio.composeapp.generated.resources.autosync_toast_retry_none
import nuvio.composeapp.generated.resources.autosync_toast_retry_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced_replaced
import nuvio.composeapp.generated.resources.autosync_toast_no_fit
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal const val LIBMPV_AUTOSYNC_FILE_PREFIX = "autosync_"

internal interface AutoSyncMpvPlatform {
    val isLiveTv: Boolean get() = false
    suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String?
    fun showMessage(text: String)
    fun showMessage(kind: AutoSyncBubbleKind, text: String) = showMessage(text)
}

internal class AutoSyncMpvCoordinator(
    private val scope: CoroutineScope,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getPreferredLanguage: () -> String?,
    private val platform: AutoSyncMpvPlatform,
) {
    private var job: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    private val _retryState = MutableStateFlow(AutoSyncRetryUiState())
    val retryState = _retryState.asStateFlow()
    private var retryContext: RetryContext? = null
    private var appliedDelayMs: Int? = null

    private data class RetryContext(
        val resolved: AutoSyncResolvedTimeline,
        val originalBody: String,
        val language: String?,
        val base: PlayerEngineController,
        val replaceExternalSubtitle: (String, String) -> Unit,
        val rejectedKeys: Set<String> = emptySet(),
    )

    /**
     * [replaceExternalSubtitle] loads a local file in place of the external track that was loaded
     * from a URL, keeping that track's label.
     */
    fun wrap(
        controller: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ): PlayerEngineController =
        AutoSyncMpvEngineController(controller, this, replaceExternalSubtitle)

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    fun cancel() {
        operationToken++
        job?.cancel()
        job = null
        retryContext = null
        appliedDelayMs = null
        _retryState.value = AutoSyncRetryUiState()
    }

    fun onManualSubtitleDelayChanged(delayMs: Int) {
        // The runtime echoes our applied delay back through the controller. That is not a
        // manual adjustment and must keep the successful result available for a reference retry.
        if (appliedDelayMs == delayMs) return
        cancel()
        AutoSyncSyncedSubtitle.clear()
    }

    fun dispose() {
        cancel()
        appliedListener = null
    }

    private fun showMessage(token: Long, kind: AutoSyncBubbleKind, resource: StringResource) {
        scope.launch {
            val text = getString(resource)
            if (operationToken == token) platform.showMessage(kind, text)
        }
    }

    private fun showFailure(token: Long, resource: StringResource) =
        showMessage(token, AutoSyncBubbleKind.Failure, resource)

    fun retryWithAnotherReference() {
        val snapshot = retryContext ?: return
        if (_retryState.value.busy || _retryState.value.exhausted) return
        operationToken++
        job?.cancel()
        val retry = snapshot.copy(rejectedKeys = mergeRejectedReferenceKeys(
            snapshot.rejectedKeys,
            snapshot.resolved.reference.key,
            snapshot.resolved.reference.equivalentKeys,
        ))
        retryContext = retry
        _retryState.value = AutoSyncRetryUiState(available = true, busy = true, status = AutoSyncRetryStatus.TRYING)
        runSync(snapshot.resolved.subtitleUrl, AutoSyncCandidateScope.SELECTED_ONLY,
            snapshot.base, snapshot.replaceExternalSubtitle, retry)
    }

    fun start(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        base: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ) {
        cancel()
        base.setSubtitleUri(url)
        AutoSyncPreferencesRepository.ensureLoaded()
        if (decideAutoSyncStart(AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) ==
            AutoSyncStartAction.ATTACH_ORIGINAL ||
            platform.isLiveTv ||
            !(sourceUrl.startsWith("https://", true) || sourceUrl.startsWith("http://", true))) {
            return
        }

        runSync(url, candidateScope, base, replaceExternalSubtitle)
    }

    private fun runSync(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        base: PlayerEngineController,
        replaceExternalSubtitle: (String, String) -> Unit,
        retry: RetryContext? = null,
    ) {
        val token = operationToken
        val subtitleHeaders = retry?.resolved?.subtitleHeaders ?: getSubtitleHeaders(url)
        val subtitleLanguage = retry?.language ?: candidates.firstOrNull { it.url == url }?.language
            ?.takeIf { it.isNotBlank() } ?: getPreferredLanguage()
        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)

        job = scope.launch {
            try {
                showMessage(token, AutoSyncBubbleKind.Working, Res.string.autosync_toast_analyzing)
                var outcome: AutoSyncAnalysisOutcome? = null
                var searchOutcome: AutoSyncReferenceSearchOutcome? = null
                suspend fun search(searchScope: AutoSyncCandidateScope) = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = url,
                    selectedSubtitleHeaders = subtitleHeaders,
                    preferredLanguage = subtitleLanguage,
                    alternativeSubtitles = searchScope.alternativeCandidates(candidates),
                    alternativeSubtitlesProvider = if (searchScope.usesAlternativeProvider) {
                        { candidates }
                    } else null,
                    onAnalysisOutcome = { outcome = it },
                    selectedSubtitleBodyDeferred = retry?.let { CompletableDeferred(it.originalBody) },
                    excludedReferenceKeys = retry?.rejectedKeys.orEmpty(),
                    requiredReferenceSource = retry?.resolved?.reference?.source,
                    onReferenceSearchOutcome = { searchOutcome = it },
                    allowLiveReference = false,
                )
                var resolved = search(candidateScope)
                currentCoroutineContext().ensureActive()
                if (retry == null && resolved == null && token == operationToken &&
                    candidateScope.searchAfterSelectedFailed(outcome)
                ) {
                    resolved = search(AutoSyncCandidateScope.STARTUP_SEARCH)
                }
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (resolved == null) {
                    if (retry != null) {
                        val exhausted = searchOutcome == AutoSyncReferenceSearchOutcome.EXHAUSTED
                        _retryState.value = AutoSyncRetryUiState(
                            available = true,
                            exhausted = exhausted,
                            status = if (exhausted) AutoSyncRetryStatus.EXHAUSTED else AutoSyncRetryStatus.FAILED,
                        )
                        showFailure(token, Res.string.autosync_toast_retry_none)
                        return@launch
                    }
                    showFailure(token, when (outcome) {
                        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
                        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE -> Res.string.autosync_toast_failed_no_reference
                        AutoSyncAnalysisOutcome.NO_FIT -> Res.string.autosync_toast_no_fit
                        else -> Res.string.autosync_toast_failed
                    })
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val withinTolerance = retry == null && tolerance > 0 && chosenUrl == url &&
                    resolved.timeline.maxAlignmentShiftMs() <= tolerance
                // Always retain original timestamps: retries must never retime an already shifted file.
                val originalBody = retry?.originalBody ?: resolved.subtitleBody
                    ?: AutomaticSubtitleSync.downloadSubtitleBody(
                        url = chosenUrl,
                        headers = resolved.subtitleHeaders.ifEmpty { getSubtitleHeaders(chosenUrl) },
                    )
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (!withinTolerance) {
                    val path = writeRetimedSubtitle(chosenUrl, resolved.copy(subtitleBody = originalBody))
                    currentCoroutineContext().ensureActive()
                    if (token != operationToken) return@launch
                    if (path == null) {
                        showFailure(token, Res.string.autosync_toast_failed)
                        return@launch
                    }
                    replaceExternalSubtitle(url, path)
                }
                base.setSubtitleDelayMs(0)
                appliedDelayMs = 0
                retryContext = RetryContext(resolved, originalBody, subtitleLanguage, base,
                    replaceExternalSubtitle, retry?.rejectedKeys.orEmpty())
                _retryState.value = AutoSyncRetryUiState(available = true,
                    status = if (retry == null) AutoSyncRetryStatus.IDLE else AutoSyncRetryStatus.UPDATED)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(chosenUrl)
                showMessage(token, AutoSyncBubbleKind.Success, when {
                    retry != null -> Res.string.autosync_toast_retry_synced
                    chosenUrl != url -> Res.string.autosync_toast_synced_replaced
                    withinTolerance -> Res.string.autosync_toast_in_sync
                    else -> Res.string.autosync_toast_synced
                })
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (token != operationToken) return@launch
                showFailure(token, Res.string.autosync_toast_failed)
            } finally {
                if (token == operationToken && _retryState.value.busy) {
                    _retryState.value = _retryState.value.copy(busy = false, status = AutoSyncRetryStatus.FAILED)
                }
            }
        }
    }

    /**
     * Writes the subtitle at [subtitleUrl] with the AutoSync timeline applied, and returns its path,
     * or null when its format cannot be retimed. The name follows the content, so libmpv's `cached`
     * sub-add reuses the track when the same result is applied again.
     */
    private suspend fun writeRetimedSubtitle(
        subtitleUrl: String,
        resolved: AutoSyncResolvedTimeline,
    ): String? {
        val body = resolved.subtitleBody ?: AutomaticSubtitleSync.downloadSubtitleBody(
            url = subtitleUrl,
            headers = resolved.subtitleHeaders.ifEmpty { getSubtitleHeaders(subtitleUrl) },
        )
        val retimed = withContext(Dispatchers.Default) {
            AutoSyncSubtitleTextRetimer.retime(body, resolved.timeline)
        } ?: return null
        val prefix = "$LIBMPV_AUTOSYNC_FILE_PREFIX${subtitleUrl.hashCode().toUInt().toString(16)}_"
        val extension = when {
            retimed.trimStart().startsWith("WEBVTT") -> "vtt"
            retimed.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
        return platform.writeSubtitleFile("$prefix${retimed.hashCode().toUInt().toString(16)}.$extension", prefix, retimed)
    }
}

/**
 * Nuvio's libmpv controller with AutoSync layered on top. Every call is forwarded to [base]
 * unchanged; subtitle changes first stop any AutoSync run that still targets the old subtitle.
 */
internal open class AutoSyncMpvEngineController(
    private val base: PlayerEngineController,
    private val coordinator: AutoSyncMpvCoordinator,
    private val replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
) : PlayerEngineController by base, AutoSyncPlayerController {
    override val autoSyncRetryState = coordinator.retryState

    override fun retryWithAnotherReference() = coordinator.retryWithAnotherReference()

    override fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) =
        coordinator.setCandidates(candidates)

    override fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) = coordinator.setAppliedListener(listener)

    override fun setSubtitleUri(url: String) {
        coordinator.cancel()
        base.setSubtitleUri(url)
    }

    override fun setSubtitleUriWithAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.STARTUP_SEARCH, base, replaceExternalSubtitle)

    override fun setSubtitleUriWithSelectedAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_ONLY, base, replaceExternalSubtitle)

    override fun setSubtitleUriWithRestoredAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_THEN_SEARCH, base, replaceExternalSubtitle)

    override fun selectSubtitleTrack(index: Int) {
        coordinator.cancel()
        base.selectSubtitleTrack(index)
    }

    override fun clearExternalSubtitle() {
        coordinator.cancel()
        base.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        coordinator.cancel()
        base.clearExternalSubtitleAndSelect(trackIndex)
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        coordinator.onManualSubtitleDelayChanged(delayMs)
        base.setSubtitleDelayMs(delayMs)
    }
}
