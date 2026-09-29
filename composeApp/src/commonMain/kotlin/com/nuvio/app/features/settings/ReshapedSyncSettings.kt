package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.reshaped.sync.ReshapedSignInState
import com.nuvio.app.features.reshaped.sync.ReshapedSyncBridge
import com.nuvio.app.features.reshaped.sync.ReshapedSyncController
import com.nuvio.app.features.reshaped.sync.ReshapedSyncFailure
import kotlin.time.Clock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.reshaped_sync_account_title
import nuvio.composeapp.generated.resources.reshaped_sync_failed_network
import nuvio.composeapp.generated.resources.reshaped_sync_failed_newer
import nuvio.composeapp.generated.resources.reshaped_sync_failed_signed_out
import nuvio.composeapp.generated.resources.reshaped_sync_just_now
import nuvio.composeapp.generated.resources.reshaped_sync_last_minutes
import nuvio.composeapp.generated.resources.reshaped_sync_live_tv_description
import nuvio.composeapp.generated.resources.reshaped_sync_live_tv_title
import nuvio.composeapp.generated.resources.reshaped_sync_never
import nuvio.composeapp.generated.resources.reshaped_sync_now_title
import nuvio.composeapp.generated.resources.reshaped_sync_phone_button
import nuvio.composeapp.generated.resources.reshaped_sync_phone_instruction
import nuvio.composeapp.generated.resources.reshaped_sync_running
import nuvio.composeapp.generated.resources.reshaped_sync_section
import nuvio.composeapp.generated.resources.reshaped_sync_settings_description
import nuvio.composeapp.generated.resources.reshaped_sync_settings_title
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_declined
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_description
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_expired
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_detail
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_failed
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_refused
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_starting
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_title
import nuvio.composeapp.generated.resources.reshaped_sync_sign_in_waiting
import nuvio.composeapp.generated.resources.reshaped_sync_sign_out
import nuvio.composeapp.generated.resources.reshaped_sync_sign_out_description
import nuvio.composeapp.generated.resources.reshaped_sync_signed_in
import nuvio.composeapp.generated.resources.reshaped_sync_signed_in_as
import nuvio.composeapp.generated.resources.reshaped_sync_not_configured
import nuvio.composeapp.generated.resources.reshaped_sync_try_again
import org.jetbrains.compose.resources.stringResource

/** "Sync with Google": the same Reshaped settings and Live TV on the viewer's phone and TVs. */
@Composable
internal fun ReshapedSyncSettingsSection(isTablet: Boolean) {
    val controller = ReshapedSyncBridge.controller ?: return
    val email by controller.email.collectAsStateWithLifecycle()
    val syncSettings by controller.syncSettings.collectAsStateWithLifecycle()
    val syncLiveTv by controller.syncLiveTv.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val signIn by controller.signIn.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    SettingsSection(title = stringResource(Res.string.reshaped_sync_section), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            val signedIn = email
            if (signedIn == null) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.reshaped_sync_sign_in_title),
                    description = stringResource(
                        if (controller.isConfigured) Res.string.reshaped_sync_sign_in_description else Res.string.reshaped_sync_not_configured
                    ),
                    enabled = controller.isConfigured,
                    isTablet = isTablet,
                    onClick = controller::startSignIn,
                )
            } else {
                SettingsNavigationRow(
                    title = stringResource(Res.string.reshaped_sync_account_title),
                    description = if (signedIn.isNotBlank()) stringResource(Res.string.reshaped_sync_signed_in_as, signedIn)
                    else stringResource(Res.string.reshaped_sync_signed_in),
                    isTablet = isTablet,
                    onClick = { confirmSignOut = true },
                )
                SettingsSwitchRow(
                    title = stringResource(Res.string.reshaped_sync_settings_title),
                    description = stringResource(Res.string.reshaped_sync_settings_description),
                    checked = syncSettings,
                    isTablet = isTablet,
                    onCheckedChange = controller::setSyncSettings,
                )
                SettingsSwitchRow(
                    title = stringResource(Res.string.reshaped_sync_live_tv_title),
                    description = stringResource(Res.string.reshaped_sync_live_tv_description),
                    checked = syncLiveTv,
                    isTablet = isTablet,
                    onCheckedChange = controller::setSyncLiveTv,
                )
                if (syncSettings || syncLiveTv) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.reshaped_sync_now_title),
                        description = when {
                            status.running -> stringResource(Res.string.reshaped_sync_running)
                            status.failed == ReshapedSyncFailure.Network -> stringResource(Res.string.reshaped_sync_failed_network) +
                                status.failedDetail.takeIf(String::isNotBlank)?.let { "\n" + it }.orEmpty()
                            status.failed == ReshapedSyncFailure.SignedOut -> stringResource(Res.string.reshaped_sync_failed_signed_out)
                            status.failed == ReshapedSyncFailure.NewerVersion -> stringResource(Res.string.reshaped_sync_failed_newer)
                            status.lastSyncedAtMs == 0L -> stringResource(Res.string.reshaped_sync_never)
                            else -> {
                                val minutes = (Clock.System.now().toEpochMilliseconds() - status.lastSyncedAtMs) / 60_000
                                if (minutes < 1) stringResource(Res.string.reshaped_sync_just_now)
                                else stringResource(Res.string.reshaped_sync_last_minutes, minutes.toInt())
                            }
                        },
                        isTablet = isTablet,
                        onClick = { if (!status.running) controller.syncNow() },
                    )
                }
            }
        }
    }

    signIn?.let { state -> GoogleSignInDialog(controller, state) }
    if (confirmSignOut) {
        SignOutDialog(
            onSignOut = {
                confirmSignOut = false
                controller.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun GoogleSignInDialog(controller: ReshapedSyncController, state: ReshapedSignInState) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    BasicAlertDialog(onDismissRequest = controller::cancelSignIn) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.reshaped_sync_sign_in_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                when (state) {
                    ReshapedSignInState.Starting -> DialogText(stringResource(Res.string.reshaped_sync_sign_in_starting))
                    is ReshapedSignInState.Waiting -> {
                        DialogText(stringResource(Res.string.reshaped_sync_phone_instruction))
                        Text(
                            text = state.userCode,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 4.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(state.userCode))
                                runCatching { uriHandler.openUri("${state.verificationUrl}?user_code=${state.userCode}") }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(Res.string.reshaped_sync_phone_button))
                        }
                        DialogText(stringResource(Res.string.reshaped_sync_sign_in_waiting))
                    }
                    is ReshapedSignInState.Failed -> {
                        DialogText(
                            stringResource(
                                when {
                                    state.declined -> Res.string.reshaped_sync_sign_in_declined
                                    state.expired -> Res.string.reshaped_sync_sign_in_expired
                                    state.refused -> Res.string.reshaped_sync_sign_in_refused
                                    else -> Res.string.reshaped_sync_sign_in_failed
                                }
                            )
                        )
                        if (state.detail.isNotBlank() && !state.declined && !state.expired) {
                            Text(
                                text = stringResource(Res.string.reshaped_sync_sign_in_detail, state.detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = controller::cancelSignIn) { Text(stringResource(Res.string.action_cancel)) }
                    if (state is ReshapedSignInState.Failed) {
                        TextButton(onClick = controller::startSignIn) { Text(stringResource(Res.string.reshaped_sync_try_again)) }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SignOutDialog(onSignOut: () -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.reshaped_sync_sign_out),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                DialogText(stringResource(Res.string.reshaped_sync_sign_out_description))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                    TextButton(onClick = onSignOut) { Text(stringResource(Res.string.reshaped_sync_sign_out)) }
                }
            }
        }
    }
}

@Composable
private fun DialogText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
