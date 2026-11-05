@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleKind
import com.nuvio.app.features.autosync.bubble.showAutoSyncMessage
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerSubtitleUtils
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.audiosync.AudioSyncFallback
import com.nuvio.app.features.player.seekpreview.local.LocalPreviewSources
import com.nuvio.app.features.reshaped.livetv.LiveTvPlaybackRegistry
import androidx.media3.datasource.DataSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_analyzing
import nuvio.composeapp.generated.resources.autosync_toast_failed
import nuvio.composeapp.generated.resources.autosync_toast_failed_audio_fallback
import nuvio.composeapp.generated.resources.autosync_toast_failed_no_reference
import nuvio.composeapp.generated.resources.autosync_toast_failed_unsupported
import nuvio.composeapp.generated.resources.autosync_toast_in_sync
import nuvio.composeapp.generated.resources.autosync_toast_retry_failed
import nuvio.composeapp.generated.resources.autosync_toast_retry_none
import nuvio.composeapp.generated.resources.autosync_toast_retry_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced_replaced
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nuvio.app.features.player.addonSubtitleTrackId
import com.nuvio.app.features.player.getAssHandlerCompat
import com.nuvio.tv.libass.media.AssHandler
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.autosync_toast_no_fit
import kotlinx.coroutines.launch

private const val TAG = "NuvioAutoSyncPlayer"

internal class AutoSyncPlayerCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val sidecar: SidecarSubtitleController,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getUseLibass: () -> Boolean,
    private val getPreferredLanguage: () -> String?,
    private val getSecondaryLanguage: () -> String?,
    private val onMimeTypeSelected: (String) -> Unit,
    private val onSubtitleDelayChanged: (Int) -> Unit,
    sourceAudioUrl: String? = null,
    dataSourceFactory: DataSource.Factory? = null,
) {
    /**
     * Shows an AutoSync message in the app's language, resolving [message] off the call site: in
     * the glass bubble when it is turned on, else as a toast. [kind] tells the bubble how it ends.
     */
    private fun showToast(kind: AutoSyncBubbleKind, message: suspend () -> String) {
        scope.launch { showAutoSyncMessage(context, kind, message()) }
    }

    private var libassOperationToken = 0L
    private var job: Job? = null
    private var selectedBodyJob: Job? = null
    private var retryJob: Job? = null
    private var retryContext: RetryContext? = null
    private var retryOperationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    /** Syncs to the audio when AutoSync keeps a subtitle's original timing. */
    private val audioFallback = AudioSyncFallback(
        context = context,
        scope = scope,
        player = player,
        sidecar = sidecar,
        sourceUrl = sourceUrl,
        sourceAudioUrl = sourceAudioUrl,
        dataSourceFactory = dataSourceFactory,
        getSubtitleHeaders = getSubtitleHeaders,
        onSubtitleReplaced = { url ->
            onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
            onSubtitleDelayChanged(0)
            appliedListener?.invoke(url, 0)
        },
    )
    /** Lets on-device seek previews collect this stream's keyframes as playback demuxes them. */
    private val localPreviewSource = LocalPreviewSources.register(context, sourceUrl, player)
    private val _retryState = MutableStateFlow(AutoSyncRetryUiState())
    val retryState: StateFlow<AutoSyncRetryUiState> = _retryState.asStateFlow()

    init {
        // The coordinator is created when the stream opens. Start the embedded index download
        // now so it overlaps player startup instead of beginning when a subtitle is selected.
        AutoSyncPreferencesRepository.ensureLoaded()
        // Live TV channels have no embedded index, and a second connection can get the stream refused.
        if (AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value && !LiveTvPlaybackRegistry.isLiveTv(sourceUrl)) {
            EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
        }
    }

    fun wrap(controller: PlayerEngineController): PlayerEngineController =
        AutoSyncPlayerEngineController(base = controller, coordinator = this)

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
        audioFallback.setCandidates(candidates.map { Triple(it.url, it.language, it.name) })
    }

    fun setContent(type: String, videoId: String) = audioFallback.setContent(type, videoId)

    fun setAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) {
        appliedListener = listener
    }

    private fun invalidateRetryContext() {
        retryOperationToken++
        retryJob?.cancel()
        retryJob = null
        retryContext = null
        _retryState.value = AutoSyncRetryUiState()
    }

    fun cancel() {
        libassOperationToken++
        audioFallback.stop()
        job?.cancel()
        job = null
        selectedBodyJob?.cancel()
        selectedBodyJob = null
        invalidateRetryContext()
    }

    /**
     * Attaches [url] through Nuvio's own [attach] without AutoSync. If Nuvio rendered it with the
     * sidecar, records its MIME type; otherwise Nuvio reloads the media item for it, so any
     * previous sidecar subtitle is stopped to keep the two renderers from overlapping.
     */
    fun attachWithoutAutoSync(url: String, attach: (String) -> Unit) {
        cancel()
        forgetLibassCopies()
        val generationBefore = sidecar.currentGenerationFor(url)
        attach(url)
        val generationAfter = sidecar.currentGenerationFor(url)
        if (generationAfter != null && generationAfter != generationBefore) {
            onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        } else {
            sidecar.stopSidecarAddonSubtitle(clearView = true)
        }
    }

    fun onManualSubtitleDelayChanged() {
        if (retryJob?.isActive != true) return
        retryOperationToken++
        retryJob?.cancel()
        retryJob = null
        _retryState.value = _retryState.value.copy(
            busy = false,
            exhausted = false,
            status = AutoSyncRetryStatus.IDLE,
        )
    }

    fun forgetLibassCopies() {
        AutoSyncLibassSubtitles.clear(player.getAssHandlerCompat())
    }

    fun dispose() {
        forgetLibassCopies()
        cancel()
        audioFallback.release()
        LocalPreviewSources.unregister(localPreviewSource)
        appliedListener = null
    }

    fun retryWithAnotherReference() {
        val snapshot = retryContext ?: return
        if (retryJob?.isActive == true || _retryState.value.exhausted) return

        val currentGeneration = sidecar.currentGenerationFor(snapshot.subtitleUrl)
        if (
            sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
            currentGeneration != snapshot.expectedGeneration
        ) {
            invalidateRetryContext()
            return
        }

        val rejectedKeys = mergeRejectedReferenceKeys(
            previous = snapshot.rejectedReferenceKeys,
            referenceKey = snapshot.appliedReference.key,
            equivalentKeys = snapshot.appliedReference.equivalentKeys,
        )
        retryContext = snapshot.copy(rejectedReferenceKeys = rejectedKeys)

        val operationToken = ++retryOperationToken
        _retryState.value = AutoSyncRetryUiState(
            available = true,
            busy = true,
            status = AutoSyncRetryStatus.TRYING,
        )

        retryJob = scope.launch {
            var searchOutcome: AutoSyncReferenceSearchOutcome? = null
            try {
                val resolved = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = snapshot.subtitleUrl,
                    selectedSubtitleHeaders = snapshot.subtitleHeaders,
                    selectedSubtitleBodyDeferred = CompletableDeferred(snapshot.originalBody),
                    preferredLanguage = snapshot.preferredLanguage,
                    alternativeSubtitles = emptyList(),
                    alternativeSubtitlesProvider = null,
                    excludedReferenceKeys = rejectedKeys,
                    requiredReferenceSource = snapshot.appliedReference.source,
                    onReferenceSearchOutcome = { outcome -> searchOutcome = outcome },
                )

                currentCoroutineContext().ensureActive()
                val activeContext = retryContext ?: return@launch
                if (operationToken != retryOperationToken) return@launch
                if (
                    sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
                    sidecar.currentGenerationFor(snapshot.subtitleUrl) != snapshot.expectedGeneration
                ) {
                    invalidateRetryContext()
                    return@launch
                }

                if (resolved == null) {
                    val exhausted = searchOutcome == AutoSyncReferenceSearchOutcome.EXHAUSTED
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        busy = false,
                        exhausted = exhausted,
                        status = if (exhausted) {
                            AutoSyncRetryStatus.EXHAUSTED
                        } else {
                            AutoSyncRetryStatus.FAILED
                        },
                    )
                    val outcome = searchOutcome ?: AutoSyncReferenceSearchOutcome.UNAVAILABLE
                    AutoSyncDebugLog.info {
                        "RETRY operation=$operationToken outcome=$outcome"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY $outcome - current timing kept",
                        )
                    }
                    showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_retry_none) }
                    return@launch
                }

                if (
                    resolved.subtitleUrl != snapshot.subtitleUrl ||
                    resolved.reference.source != snapshot.appliedReference.source
                ) {
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        status = AutoSyncRetryStatus.FAILED,
                    )
                    AutoSyncDebugLog.warn {
                        "RETRY operation=$operationToken rejected unexpected external/reference source"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY unavailable - current timing kept",
                        )
                    }
                    showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_retry_none) }
                    return@launch
                }

                val applied = replaceAutoSyncSidecarSubtitle(
                    sidecar = sidecar,
                    expectedCurrentUrl = snapshot.subtitleUrl,
                    url = snapshot.subtitleUrl,
                    headers = snapshot.subtitleHeaders,
                    rawBody = snapshot.originalBody,
                    useLibass = getUseLibass(),
                    timeline = resolved.timeline,
                )
                currentCoroutineContext().ensureActive()
                if (operationToken != retryOperationToken) return@launch

                if (!applied) {
                    if (
                        sidecar.activeSidecarSubtitleKey != snapshot.subtitleUrl ||
                        sidecar.currentGenerationFor(snapshot.subtitleUrl) != snapshot.expectedGeneration
                    ) {
                        invalidateRetryContext()
                    } else {
                        _retryState.value = AutoSyncRetryUiState(
                            available = true,
                            status = AutoSyncRetryStatus.FAILED,
                        )
                    }
                    AutoSyncDebugLog.warn {
                        "RETRY operation=$operationToken apply=false"
                    }
                    if (AutoSyncDebugLog.ENABLED) {
                        AutoSyncDebugLog.finishAndCopy(
                            context = context,
                            decision = "REFERENCE RETRY apply failed - current timing kept",
                        )
                    }
                    showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_failed) }
                    return@launch
                }

                val committedGeneration =
                    sidecar.currentGenerationFor(snapshot.subtitleUrl)
                        ?: run {
                            invalidateRetryContext()
                            return@launch
                        }
                retryContext = activeContext.copy(
                    appliedReference = resolved.reference,
                    expectedGeneration = committedGeneration,
                )
                onSubtitleDelayChanged(0)
                appliedListener?.invoke(snapshot.subtitleUrl, 0)
                _retryState.value = AutoSyncRetryUiState(
                    available = true,
                    status = AutoSyncRetryStatus.UPDATED,
                )
                AutoSyncDebugLog.info {
                    "RETRY operation=$operationToken applied=true " +
                        "reference=${resolved.reference.key} originalBody=true"
                }
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REFERENCE RETRY applied reference=${resolved.reference.key}",
                    )
                }
                AutoSyncSyncedSubtitle.mark(snapshot.subtitleUrl)
                showToast(AutoSyncBubbleKind.Success) { getString(Res.string.autosync_toast_retry_synced) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (operationToken == retryOperationToken && retryContext != null) {
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        status = AutoSyncRetryStatus.FAILED,
                    )
                }
                AutoSyncDebugLog.error(error) {
                    "RETRY operation=$operationToken failed"
                }
                if (operationToken == retryOperationToken && AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REFERENCE RETRY error - current timing kept",
                    )
                }
                if (operationToken == retryOperationToken) {
                    showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_retry_failed) }
                }
            } finally {
                if (operationToken == retryOperationToken) {
                    retryJob = null
                    if (_retryState.value.busy) {
                        _retryState.value = _retryState.value.copy(busy = false)
                    }
                }
            }
        }
    }

    fun start(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        fallbackAttach: (String) -> Unit,
    ) {
        cancel()
        forgetLibassCopies()
        if (LiveTvPlaybackRegistry.isLiveTv(sourceUrl) ||
            !(sourceUrl.startsWith("https://", true) || sourceUrl.startsWith("http://", true))) {
            fallbackAttach(url)
            return
        }

        AutoSyncPreferencesRepository.ensureLoaded()
        when (
            decideAutoSyncStart(
                enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value,
            )
        ) {
            AutoSyncStartAction.RUN -> Unit
            AutoSyncStartAction.ATTACH_ORIGINAL -> {
                fallbackAttach(url)
                return
            }
        }

        showToast(AutoSyncBubbleKind.Working) { getString(Res.string.autosync_toast_analyzing) }

        val useLibass = getUseLibass()
        val subtitleHeaders = getSubtitleHeaders(url)
        val assHandler = player.getAssHandlerCompat()
        if (useLibass && assHandler != null && PlayerSubtitleUtils.mimeTypeFromUrl(url) == MimeTypes.TEXT_SSA) {
            val language = candidates.firstOrNull { it.url == url }?.language?.takeIf { it.isNotBlank() } ?: getPreferredLanguage()
            startLibass(libassOperationToken, url, candidateScope, subtitleHeaders, language, assHandler, fallbackAttach)
            return
        }
        if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
            fallbackAttach(url)
            showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_failed_unsupported) }
            return
        }

        // One download feeds both the sidecar renderer and the analysis. It completes with null
        // on failure or cancellation so neither side can wait on it forever.
        val selectedSubtitleBodyDeferred = CompletableDeferred<String?>()
        if (
            !sidecar.startSidecarAddonSubtitle(
                url = url,
                headers = subtitleHeaders,
                useLibass = useLibass,
                rawBodyLoader = {
                    selectedSubtitleBodyDeferred.await()
                        ?: throw IllegalStateException("Subtitle body unavailable")
                },
            )
        ) {
            fallbackAttach(url)
            showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_failed) }
            return
        }

        selectedBodyJob = scope.launch {
            val body = try {
                AutomaticSubtitleSync.downloadSubtitleBody(
                    url = url,
                    headers = subtitleHeaders,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "selected subtitle download failed url=$url: ${error.message}")
                null
            }
            selectedSubtitleBodyDeferred.complete(body)
        }.also { download ->
            download.invokeOnCompletion { selectedSubtitleBodyDeferred.complete(null) }
        }

        fun restoreOriginalSubtitleIfSidecarFailed() {
            if (
                shouldRestoreOriginalSubtitle(
                    activeSidecarSubtitleKey = sidecar.activeSidecarSubtitleKey,
                )
            ) {
                fallbackAttach(url)
            }
        }

        onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
        audioFallback.arm()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        job = scope.launch {
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            var searchLanguage = getPreferredLanguage()
            var resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrl,
                sourceHeaders = sourceHeaders,
                selectedSubtitleUrl = url,
                selectedSubtitleHeaders = subtitleHeaders,
                selectedSubtitleBodyDeferred = selectedSubtitleBodyDeferred,
                preferredLanguage = searchLanguage,
                alternativeSubtitles = candidateScope.alternativeCandidates(candidates),
                alternativeSubtitlesProvider = if (candidateScope.usesAlternativeProvider) {
                    { candidates }
                } else {
                    null
                },
                onReferenceReady = {},
                onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
            )
            AutoSyncDebugLog.info {
                "candidateScope=${candidateScope.name}"
            }

            // No match in the first language: search the secondary subtitle language before the
            // audio fallback. Only when a subtitle could still match (not without a reference),
            // and never over a subtitle the user picked themselves.
            val secondarySeed =
                if (
                    resolved == null &&
                    candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH &&
                    (
                        analysisOutcome == null ||
                            analysisOutcome == AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE ||
                            analysisOutcome == AutoSyncAnalysisOutcome.NO_FIT ||
                            analysisOutcome == AutoSyncAnalysisOutcome.INCONCLUSIVE
                        )
                ) {
                    secondaryLanguageSearchSeed(
                        candidates = candidates,
                        selectedUrl = url,
                        searchedLanguage =
                            candidates.firstOrNull { it.url == url }
                                ?.language
                                ?.takeIf { it.isNotBlank() }
                                ?: searchLanguage,
                        secondaryLanguage = getSecondaryLanguage(),
                    )
                } else {
                    null
                }
            if (secondarySeed != null) {
                searchLanguage = secondarySeed.language
                resolved = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = secondarySeed.url,
                    selectedSubtitleHeaders = getSubtitleHeaders(secondarySeed.url),
                    preferredLanguage = searchLanguage,
                    alternativeSubtitles = candidates,
                    alternativeSubtitlesProvider = { candidates },
                    continueDebugSession = true,
                )
                val matched = resolved != null
                val secondaryLanguage = searchLanguage
                AutoSyncDebugLog.info {
                    "secondaryLanguage=$secondaryLanguage matched=$matched"
                }
            }

            if (resolved == null) {
                restoreOriginalSubtitleIfSidecarFailed()
                // Original timing kept: sync it to the audio instead, when AutoSync found nothing
                // to align to or only a weak match (not when the subtitle itself failed to load).
                val audioTakesOver =
                    if (
                        analysisOutcome != AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE &&
                        sidecar.activeSidecarSubtitleKey == url
                    ) {
                        audioFallback.takeOver(url)
                    } else {
                        audioFallback.disarm()
                        false
                    }
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision = "REJECT V2 - original sidecar timing kept",
                    )
                }
                showToast(if (audioTakesOver) AutoSyncBubbleKind.Working else AutoSyncBubbleKind.Failure) {
                    if (audioTakesOver) {
                        getString(Res.string.autosync_toast_failed_audio_fallback)
                    } else {
                        buildAutoSyncFailureToast(analysisOutcome)
                    }
                }
                return@launch
            }

            val chosenUrl = resolved.subtitleUrl
            val timeline = resolved.timeline
            // A confident match whose whole-film correction is within the user's tolerance keeps
            // the selected subtitle's original timing instead of retiming it.
            val toleranceMs = AutoSyncPreferencesRepository.syncToleranceMs.value
            val withinToleranceMs = toleranceMs.takeIf {
                it > 0 && chosenUrl == url && timeline.maxAlignmentShiftMs() <= it
            }
            val applied = if (withinToleranceMs != null) {
                sidecar.activeSidecarSubtitleKey == url
            } else if (chosenUrl == url) {
                applyAutoSyncSidecarTimeline(
                    sidecar = sidecar,
                    url = url,
                    timeline = timeline,
                )
            } else if (
                sidecar.activeSidecarSubtitleKey == null &&
                sidecar.startSidecarAddonSubtitle(
                    url = chosenUrl,
                    headers = resolved.subtitleHeaders,
                    useLibass = useLibass,
                    rawBodyLoader = resolved.subtitleBody?.let { body ->
                        suspend { body }
                    },
                )
            ) {
                AutoSyncDebugLog.info {
                    "replacement sidecar attached after selected subtitle load failure"
                }
                applyAutoSyncSidecarTimeline(
                    sidecar = sidecar,
                    url = chosenUrl,
                    timeline = timeline,
                )
            } else {
                replaceAutoSyncSidecarSubtitle(
                    sidecar = sidecar,
                    expectedCurrentUrl = url,
                    url = chosenUrl,
                    headers = resolved.subtitleHeaders,
                    rawBody = resolved.subtitleBody,
                    useLibass = useLibass,
                    timeline = timeline,
                )
            }

            audioFallback.disarm()
            if (!applied) {
                restoreOriginalSubtitleIfSidecarFailed()
                if (AutoSyncDebugLog.ENABLED) {
                    AutoSyncDebugLog.finishAndCopy(
                        context = context,
                        decision =
                            if (chosenUrl == url) {
                                "REJECT V2 - sidecar changed or was unavailable before apply"
                            } else {
                                "REJECT V2 replacement - original sidecar preserved"
                            },
                    )
                }
                showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_failed) }
                return@launch
            }

            if (chosenUrl != url) {
                onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(chosenUrl))
            }
            val originalBody = resolved.subtitleBody
            val referenceGeneration = sidecar.currentGenerationFor(chosenUrl)
            val pendingRetryContext =
                if (originalBody != null && referenceGeneration != null) {
                    RetryContext(
                        subtitleUrl = chosenUrl,
                        subtitleHeaders = resolved.subtitleHeaders,
                        originalBody = originalBody,
                        appliedReference = resolved.reference,
                        rejectedReferenceKeys = emptySet(),
                        expectedGeneration = referenceGeneration,
                        preferredLanguage = searchLanguage,
                    )
                } else {
                    null
                }

            onSubtitleDelayChanged(0)
            appliedListener?.invoke(chosenUrl, 0)
            AutoSyncSyncedSubtitle.mark(chosenUrl)

            AutoSyncDebugLog.info {
                "AUTO APPLY V2 sidecar=true bufferPreserved=true " +
                    "externalChanged=${chosenUrl != url} groups=${timeline.groups.size} " +
                    "alignment=${timeline.alignmentSource} " +
                    "targetCoverage=${"%.4f".format(timeline.targetCoverage)} " +
                    "referenceCoverage=${"%.4f".format(timeline.referenceCoverage)} finalDelay=0ms " +
                    "maxShift=${"%.1f".format(timeline.maxAlignmentShiftMs())}ms " +
                    "withinTolerance=${withinToleranceMs != null} toleranceMs=$toleranceMs"
            }
            if (AutoSyncDebugLog.ENABLED) {
                AutoSyncDebugLog.finishAndCopy(
                    context = context,
                    decision =
                        if (withinToleranceMs != null) {
                            "WITHIN TOLERANCE ${withinToleranceMs}ms - original timing kept " +
                                "url=$chosenUrl alignment=${timeline.alignmentSource}"
                        } else {
                            "APPLIED V2 sidecar timeline bufferPreserved=true " +
                                "externalChanged=${chosenUrl != url} url=$chosenUrl " +
                                "alignment=${timeline.alignmentSource}"
                        },
                )
            }

            if (pendingRetryContext != null) {
                retryContext = pendingRetryContext
                _retryState.value = AutoSyncRetryUiState(available = true)
            } else {
                invalidateRetryContext()
            }

            showToast(AutoSyncBubbleKind.Success) {
                getString(
                    when {
                        chosenUrl != url -> Res.string.autosync_toast_synced_replaced
                        withinToleranceMs != null -> Res.string.autosync_toast_in_sync
                        else -> Res.string.autosync_toast_synced
                    },
                )
            }
            Log.i(
                TAG,
                "applied selected=$url chosen=$chosenUrl alignment=${timeline.alignmentSource}",
            )
        }
    }

    private fun startLibass(
        token: Long,
        url: String,
        candidateScope: AutoSyncCandidateScope,
        subtitleHeaders: Map<String, String>,
        subtitleLanguage: String?,
        assHandler: AssHandler,
        attach: (String) -> Unit,
    ) {
        attach(url)
        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
        fun isAss(candidateUrl: String) =
            PlayerSubtitleUtils.mimeTypeFromUrl(candidateUrl) == MimeTypes.TEXT_SSA

        job = scope.launch {
            try {
                var outcome: AutoSyncAnalysisOutcome? = null
                suspend fun search(ass: Boolean): AutoSyncResolvedTimeline? {
                    val pool = { candidates.filter { isAss(it.url) == ass } }
                    return AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = sourceUrl,
                        sourceHeaders = sourceHeaders,
                        selectedSubtitleUrl = url,
                        selectedSubtitleHeaders = subtitleHeaders,
                        preferredLanguage = subtitleLanguage,
                        alternativeSubtitles = candidateScope.alternativeCandidates(pool()),
                        alternativeSubtitlesProvider = if (candidateScope.usesAlternativeProvider) pool else null,
                        onAnalysisOutcome = { outcome = it },
                    )
                }
                var resolved = search(ass = true)
                // No ASS/SSA fits: try the other formats, unless the video has nothing to compare with.
                if (
                    resolved == null && candidateScope.usesAlternativeProvider &&
                    token == libassOperationToken &&
                    outcome != AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS &&
                    outcome != AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE &&
                    candidates.any { !isAss(it.url) }
                ) {
                    resolved = search(ass = false)
                }
                currentCoroutineContext().ensureActive()
                if (token != libassOperationToken) return@launch
                if (resolved == null) {
                    showToast(AutoSyncBubbleKind.Failure) { buildAutoSyncFailureToast(outcome) }
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                if (!isAss(chosenUrl)) {
                    // Nuvio's own switch shows the SRT/VTT through the sidecar; retime it there.
                    attach(chosenUrl)
                    if (!applyAutoSyncSidecarTimeline(sidecar, chosenUrl, resolved.timeline)) {
                        if (token == libassOperationToken) {
                            attach(url)
                            showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_failed) }
                        }
                        return@launch
                    }
                    onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(chosenUrl))
                    onSubtitleDelayChanged(0)
                    appliedListener?.invoke(chosenUrl, 0)
                    AutoSyncSyncedSubtitle.mark(chosenUrl)
                    showToast(AutoSyncBubbleKind.Success) { getString(Res.string.autosync_toast_synced_replaced) }
                    return@launch
                }
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val withinTolerance = tolerance > 0 && chosenUrl == url &&
                    resolved.timeline.maxAlignmentShiftMs() <= tolerance
                if (!withinTolerance) {
                    val body = resolved.subtitleBody ?: AutomaticSubtitleSync.downloadSubtitleBody(
                        url = chosenUrl,
                        headers = resolved.subtitleHeaders.ifEmpty { getSubtitleHeaders(chosenUrl) },
                        languageHint = subtitleLanguage,
                    )
                    val retimed = withContext(autoSyncCpuDispatcher) {
                        AutoSyncSubtitleTextRetimer.retime(body, resolved.timeline)
                    } ?: run {
                        showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_failed) }
                        return@launch
                    }
                    currentCoroutineContext().ensureActive()
                    if (token != libassOperationToken) return@launch
                    val trackId = addonSubtitleTrackId(chosenUrl)
                    if (chosenUrl == url) {
                        AutoSyncLibassSubtitles.apply(assHandler, trackId, retimed)
                    } else {
                        // Nuvio's own switch loads the chosen subtitle; libass gets the synced copy.
                        AutoSyncLibassSubtitles.remember(assHandler, trackId, retimed)
                        attach(chosenUrl)
                    }
                }
                onSubtitleDelayChanged(0)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(chosenUrl)
                showToast(AutoSyncBubbleKind.Success) { getString(Res.string.autosync_toast_synced) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (token == libassOperationToken) showToast(AutoSyncBubbleKind.Failure) { getString(Res.string.autosync_toast_failed) }
            }
        }
    }

    private data class RetryContext(
        val subtitleUrl: String,
        val subtitleHeaders: Map<String, String>,
        val originalBody: String,
        val appliedReference: AutoSyncReferenceIdentity,
        val rejectedReferenceKeys: Set<String>,
        val expectedGeneration: Long,
        val preferredLanguage: String?,
    )
}

/** Why AutoSync kept the original timing, in the fewest words that still help the viewer. */
private suspend fun buildAutoSyncFailureToast(analysisOutcome: AutoSyncAnalysisOutcome?): String =
    getString(
        when (analysisOutcome) {
            AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
            AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
            -> Res.string.autosync_toast_failed_no_reference
            AutoSyncAnalysisOutcome.NO_FIT -> Res.string.autosync_toast_no_fit
            AutoSyncAnalysisOutcome.INCONCLUSIVE,
            AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE,
            null,
            -> Res.string.autosync_toast_failed
        },
    )
