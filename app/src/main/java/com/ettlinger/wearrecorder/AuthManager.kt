package com.ettlinger.wearrecorder

import android.content.Context
import android.content.SharedPreferences
import android.annotation.SuppressLint
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.UrlEncodedContent
import com.google.api.client.http.javanet.NetHttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Google device authorization, encrypted credentials, refresh, and revocation. */
object AuthManager {
    private const val TAG = "Auth"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val ENCRYPTED_PREFS_FILE = "clawhark_auth_enc"
    private var CLIENT_ID = ""
    private var CLIENT_SECRET = ""
    @Volatile private var prefs: SharedPreferences? = null
    private val tokenMutex = Mutex()
    private var generation = 0L
    internal var oauthRoot = "https://oauth2.googleapis.com"
    var configurationError: String? = null
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        try {
            val config = context.assets.open("oauth_config.json").bufferedReader().use { JSONObject(it.readText()) }
            CLIENT_ID = config.getString("client_id")
            CLIENT_SECRET = config.getString("client_secret")
            require(CLIENT_ID.isNotBlank() && CLIENT_SECRET.isNotBlank())
        } catch (_: Exception) {
            configurationError = "OAuth configuration is missing. Install a configured build."
            AppLog.e(TAG, "Missing or invalid OAuth asset")
        }
        try {
            prefs = EncryptedSharedPreferences.create(
                context.applicationContext, ENCRYPTED_PREFS_FILE,
                MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            configurationError = "Secure sign-in storage is unavailable. Existing audio is preserved."
            AppLog.e(TAG, "Cannot open encrypted credentials")
        }
    }

    @Synchronized fun isAuthenticated(): Boolean = prefs?.getString("refresh_token", null) != null

    // KTX edit discards commit()'s result; upload IDs require confirmed durable storage.
    @SuppressLint("UseKtx")
    @Synchronized fun authorizationSession(): String? {
        val storage = prefs ?: return null
        if (!isAuthenticated()) return null
        storage.getString("authorization_session", null)?.let { return it }
        val session = UUID.randomUUID().toString()
        return if (storage.edit().putString("authorization_session", session).commit()) session else null
    }

    @Synchronized fun clearAuth() {
        generation++
        prefs?.edit { clear() }
        AppLog.i(TAG, "Local account disconnected")
    }

    @Synchronized fun disconnect(): String? {
        val token = prefs?.getString("refresh_token", null)
        clearAuth()
        return token
    }

    @Synchronized fun invalidateAccessToken() {
        prefs?.edit { remove("access_token"); putLong("token_expiry", 0) }
    }

    data class DeviceCodeResponse(val deviceCode: String, val userCode: String,
        val verificationUrl: String, val expiresIn: Int, val interval: Int)
    sealed class PollResult {
        data class Success(val accessToken: String) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        object Expired : PollResult()
        data class Error(val message: String) : PollResult()
    }

    private data class Response(val status: Int, val json: JSONObject)

    private suspend fun post(path: String, form: Map<String, String>): Response = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val request = NetHttpTransport().createRequestFactory().buildPostRequest(
            GenericUrl("$oauthRoot$path"), UrlEncodedContent(form)
        ).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            numberOfRetries = 0
            isLoggingEnabled = false
            throwExceptionOnExecuteError = false
        }
        context.ensureActive()
        val response = request.execute()
        try {
            val body = response.parseAsString()
            context.ensureActive()
            Response(response.statusCode, if (body.isBlank()) JSONObject() else JSONObject(body))
        } finally {
            response.disconnect()
        }
    }

    suspend fun requestDeviceCode(): DeviceCodeResponse? {
        if (configurationError != null || prefs == null) return null
        return try {
            val result = post("/device/code", mapOf("client_id" to CLIENT_ID, "scope" to SCOPE))
            if (result.status != 200) return null
            result.json.let { DeviceCodeResponse(it.getString("device_code"), it.getString("user_code"),
                it.optString("verification_url", "https://www.google.com/device"),
                it.getInt("expires_in"), it.getInt("interval")) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AppLog.e(TAG, "Device authorization request failed")
            null
        }
    }

    // Report success only when commit() succeeds; the KTX edit API returns Unit.
    @SuppressLint("UseKtx")
    suspend fun pollForAuthorization(deviceCode: String): PollResult {
        val epoch = synchronized(this) { generation }
        return try {
            val result = post("/token", mapOf("client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET,
                "device_code" to deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            if (result.status == 200) {
                val json = result.json
                val stored = synchronized(this) {
                    epoch == generation && prefs?.edit()
                        ?.putString("refresh_token", json.getString("refresh_token"))
                        ?.putString("access_token", json.getString("access_token"))
                        ?.putString("authorization_session", UUID.randomUUID().toString())
                        ?.putLong("token_expiry", System.currentTimeMillis() + json.getLong("expires_in") * 1000)
                        ?.commit() == true
                }
                if (stored) PollResult.Success(json.getString("access_token")) else PollResult.Error("Sign-in was cancelled")
            } else when (result.json.optString("error")) {
                "authorization_pending" -> PollResult.Pending
                "slow_down" -> PollResult.SlowDown
                "expired_token" -> PollResult.Expired
                "access_denied" -> PollResult.Error("Access denied")
                else -> PollResult.Error("Sign-in failed (HTTP ${result.status})")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PollResult.Error("Unable to contact Google")
        }
    }

    // A failed token commit must not be reported as a successful refresh.
    @SuppressLint("UseKtx")
    suspend fun getAccessToken(): String? = tokenMutex.withLock {
        val snapshot = synchronized(this) {
            val storage = prefs ?: return@withLock null
            val refresh = storage.getString("refresh_token", null) ?: return@withLock null
            val cached = storage.getString("access_token", null)
            if (cached != null && System.currentTimeMillis() < storage.getLong("token_expiry", 0) - 120_000) {
                return@withLock cached
            }
            refresh to generation
        }
        try {
            val result = post("/token", mapOf("client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET,
                "refresh_token" to snapshot.first, "grant_type" to "refresh_token"))
            synchronized(this) {
                if (snapshot.second != generation) return@withLock null
                if (result.status == 200) {
                    val token = result.json.getString("access_token")
                    val stored = prefs?.edit()?.putString("access_token", token)
                        ?.putLong("token_expiry", System.currentTimeMillis() + result.json.getLong("expires_in") * 1000)
                        ?.commit() == true
                    if (stored) token else null
                } else {
                    if (result.json.optString("error") == "invalid_grant") clearAuth()
                    AppLog.e(TAG, "Token refresh failed HTTP ${result.status}")
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AppLog.e(TAG, "Token refresh unavailable")
            null
        }
    }

    suspend fun revokeAccess(token: String): Boolean = try {
        post("/revoke", mapOf("token" to token)).status == 200
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AppLog.e(TAG, "Google revocation unavailable; local account is disconnected")
        false
    }
}
