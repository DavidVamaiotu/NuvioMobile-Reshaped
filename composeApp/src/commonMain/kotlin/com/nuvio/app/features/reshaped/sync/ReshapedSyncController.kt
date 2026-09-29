package com.nuvio.app.features.reshaped.sync

import kotlinx.coroutines.flow.StateFlow

enum class ReshapedSyncFailure { Network, SignedOut, NewerVersion }

data class ReshapedSyncStatus(
    val running: Boolean = false,
    val lastSyncedAtMs: Long = 0L,
    val failed: ReshapedSyncFailure? = null,
    /** What went wrong, in Google's words when it said. */
    val failedDetail: String = "",
)

/** Google's TV-style sign-in, done in the phone's browser: a code to paste at [verificationUrl]. */
sealed interface ReshapedSignInState {
    data object Starting : ReshapedSignInState
    data class Waiting(val userCode: String, val verificationUrl: String) : ReshapedSignInState
    /** [refused]: Google turned the request down (its reason in [detail]); else a network failure. */
    data class Failed(
        val declined: Boolean,
        val expired: Boolean,
        val refused: Boolean = false,
        val detail: String = "",
    ) : ReshapedSignInState
}

/**
 * Sync of Reshaped settings and Live TV through the viewer's Google account (one file in the
 * account's Google Drive; the TV app writes the same file). Android only: the
 * settings section shows only where [ReshapedSyncBridge.controller] is installed.
 */
interface ReshapedSyncController {
    /** Sign-in needs the build's Google client, set from the fork's CI secrets. */
    val isConfigured: Boolean
    val email: StateFlow<String?>
    val syncSettings: StateFlow<Boolean>
    val syncLiveTv: StateFlow<Boolean>
    val status: StateFlow<ReshapedSyncStatus>
    /** Null when no sign-in is in progress. */
    val signIn: StateFlow<ReshapedSignInState?>

    fun startSignIn()
    fun cancelSignIn()
    fun signOut()
    fun setSyncSettings(enabled: Boolean)
    fun setSyncLiveTv(enabled: Boolean)
    fun syncNow()
}

object ReshapedSyncBridge {
    var controller: ReshapedSyncController? = null
}
