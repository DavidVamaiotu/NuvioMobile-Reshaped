package com.nuvio.app.features.autosync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private const val AUTO_SYNC_MAX_PARALLELISM = 2

/**
 * Shared CPU dispatcher for AutoSync work. Capping parallelism keeps cue parsing and
 * matching from saturating Dispatchers.Default and causing playback microstutters.
 */
internal val autoSyncDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(AUTO_SYNC_MAX_PARALLELISM)
