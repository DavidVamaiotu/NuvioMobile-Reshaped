package com.nuvio.app.features.reshaped.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A Google sign-in in progress: the code the viewer types at [verificationUrl] on a phone. */
internal data class GoogleDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMs: Long,
    val intervalMs: Long,
)

/**
 * The Google account Reshaped syncs to, signed in with Google's sign-in for TVs, the same
 * client as the TV app (a code approved in the browser, no Play Services needed). Only Drive access to the app's own files is asked for: the app
 * sees only the file it made, never the viewer's other files.
 */
internal object GoogleAccount {
    private const val PREFS = "nuvio_reshaped_sync_account"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EMAIL = "email"
    // Google's TV sign-in allows only a few scopes: drive.file (files this app made), not the
    // hidden app folder (drive.appdata is refused with invalid_scope).
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val SCOPES = "openid email $DRIVE_SCOPE"
    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    /** Sign-in needs the build's Google client (set from the fork's CI secrets). */
    val isConfigured: Boolean
        get() = ReshapedGoogleConfig.CLIENT_ID.isNotBlank() &&
            ReshapedGoogleConfig.CLIENT_SECRET.isNotBlank()

    private val _email = MutableStateFlow<String?>(null)
    /** The signed-in account's address, or null when signed out. */
    val email: StateFlow<String?> = _email.asStateFlow()

    @Volatile private var loaded = false
    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAtMs = 0L
    private val tokenLock = Mutex()

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            _email.value = prefs.getString(KEY_EMAIL, null)?.takeIf { prefs.contains(KEY_REFRESH) }
            loaded = true
        }
    }

    fun isSignedIn(context: Context): Boolean {
        ensureLoaded(context)
        return prefs(context).getString(KEY_REFRESH, null) != null
    }

    /** Starts a sign-in: show the returned code, then [awaitSignIn]. */
    suspend fun requestDeviceCode(): GoogleDeviceCode {
        val body = FormBody.Builder()
            .add("client_id", ReshapedGoogleConfig.CLIENT_ID)
            .add("scope", SCOPES)
            .build()
        val json = post(DEVICE_CODE_URL, body)
        if (json.has("error")) throw GoogleAuthException(json.optString("error"), json.optString("error_description"))
        return GoogleDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url").ifBlank { json.optString("verification_uri", "https://www.google.com/device") },
            expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 1800L) * 1000,
            intervalMs = json.optLong("interval", 5L).coerceAtLeast(1L) * 1000,
        )
    }

    /**
     * Waits until the viewer approves [code] on their phone. Returns the account's address;
     * throws [GoogleAuthException] when it was declined or the code ran out. Cancel to stop waiting.
     */
    suspend fun awaitSignIn(context: Context, code: GoogleDeviceCode): String {
        var interval = code.intervalMs
        while (System.currentTimeMillis() < code.expiresAtMs) {
            delay(interval)
            val body = FormBody.Builder()
                .add("client_id", ReshapedGoogleConfig.CLIENT_ID)
                .add("client_secret", ReshapedGoogleConfig.CLIENT_SECRET)
                .add("device_code", code.deviceCode)
                .add("grant_type", DEVICE_GRANT)
                .build()
            val json = try {
                post(TOKEN_URL, body)
            } catch (io: IOException) {
                continue // A dropped connection while waiting: ask again next round.
            }
            when (json.optString("error")) {
                "" -> {
                    val refresh = json.optString("refresh_token").ifBlank { throw GoogleAuthException("no_refresh_token") }
                    val scopes = json.optString("scope")
                    if (scopes.isNotBlank() && DRIVE_SCOPE !in scopes.split(' ')) {
                        // The viewer unticked Drive on Google's consent page: sync could never work.
                        runCatching { post(REVOKE_URL, FormBody.Builder().add("token", refresh).build()) }
                        throw GoogleAuthException("access_denied")
                    }
                    val email = emailFromIdToken(json.optString("id_token")).orEmpty()
                    prefs(context).edit().putString(KEY_REFRESH, refresh).putString(KEY_EMAIL, email).apply()
                    accessToken = json.optString("access_token").ifBlank { null }
                    accessExpiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 0L) * 1000
                    loaded = true
                    _email.value = email
                    return email
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5_000
                else -> throw GoogleAuthException(json.optString("error"), json.optString("error_description"))
            }
        }
        throw GoogleAuthException("expired_token")
    }

    /**
     * A valid access token, refreshed when needed; null when signed out or the account withdrew
     * access (then it is signed out). Network failures throw.
     */
    suspend fun accessToken(context: Context, forceRefresh: Boolean = false): String? = tokenLock.withLock {
        val refresh = prefs(context).getString(KEY_REFRESH, null) ?: return@withLock null
        val current = accessToken
        if (!forceRefresh && current != null && System.currentTimeMillis() < accessExpiresAtMs - 60_000) {
            return@withLock current
        }
        val body = FormBody.Builder()
            .add("client_id", ReshapedGoogleConfig.CLIENT_ID)
            .add("client_secret", ReshapedGoogleConfig.CLIENT_SECRET)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val json = post(TOKEN_URL, body)
        when (json.optString("error")) {
            "" -> Unit
            "invalid_grant" -> {
                // Access was withdrawn (or the sign-in expired): sign out, keep the local data.
                clearLocal(context)
                return@withLock null
            }
            else -> throw IOException("Google token refresh failed: ${json.optString("error")}")
        }
        accessToken = json.getString("access_token")
        accessExpiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000
        accessToken
    }

    /** Signs out on this device and withdraws the app's access; the synced file stays in the account. */
    suspend fun signOut(context: Context) {
        val refresh = prefs(context).getString(KEY_REFRESH, null)
        clearLocal(context)
        if (refresh != null) {
            runCatching { post(REVOKE_URL, FormBody.Builder().add("token", refresh).build()) }
        }
    }

    private fun clearLocal(context: Context) {
        prefs(context).edit().remove(KEY_REFRESH).remove(KEY_EMAIL).apply()
        accessToken = null
        accessExpiresAtMs = 0L
        _email.value = null
    }

    private suspend fun post(url: String, body: FormBody): JSONObject = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            when {
                json != null && (response.isSuccessful || json.has("error")) -> json
                response.isSuccessful -> JSONObject()
                else -> throw IOException("HTTP ${response.code}")
            }
        }
    }

    /** The "email" claim of Google's ID token (already verified by the TLS exchange it came from). */
    private fun emailFromIdToken(idToken: String): String? = runCatching {
        val payload = idToken.split('.')[1]
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(decoded).optString("email").ifBlank { null }
    }.getOrNull()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal class GoogleAuthException(val code: String, val description: String = "") :
    Exception("Google sign-in failed: $code ${description}".trim()) {
    /** Google's own words, for the viewer to act on (a wrong client type, a scope not allowed). */
    val detail: String get() = if (description.isBlank()) code else "$code: $description"
}
