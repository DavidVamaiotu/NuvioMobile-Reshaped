package com.nuvio.app.features.reshaped.livetv

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Keeps the Reshaped settings entry independent of Nuvio's tab model. */
object LiveTvNavigationRequests {
    private val requests = Channel<Unit>(Channel.BUFFERED)
    val events = requests.receiveAsFlow()

    fun open() {
        requests.trySend(Unit)
    }
}
