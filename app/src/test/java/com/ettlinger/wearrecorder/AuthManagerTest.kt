package com.ettlinger.wearrecorder

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthManagerTest {
    private lateinit var server: HttpTestServer
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        server = HttpTestServer()
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("http_auth", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("refresh_token", "refresh&+= value").commit()
        setField("prefs", prefs)
        setField("CLIENT_ID", "client&+= value")
        setField("CLIENT_SECRET", "secret&+= value")
        AuthManager.oauthRoot = server.baseUrl
    }

    @After fun tearDown() {
        server.close()
        AuthManager.oauthRoot = "https://oauth2.googleapis.com"
        setField("prefs", null)
    }

    @Test fun refreshEncodesEachFormValue() = runTest {
        server.enqueue(200, "{\"access_token\":\"access\",\"expires_in\":3600}")
        assertEquals("access", AuthManager.getAccessToken())
        val request = server.takeRequest()
        val form = decodeForm(request.body.readUtf8())
        assertEquals("POST", request.method)
        assertEquals("/token", request.path)
        assertEquals("client&+= value", form["client_id"])
        assertEquals("secret&+= value", form["client_secret"])
        assertEquals("refresh&+= value", form["refresh_token"])
        assertEquals("refresh_token", form["grant_type"])
    }

    @Test fun configurationFailureDoesNotDeleteAccountCredentials() = runTest {
        server.enqueue(400, "{\"error\":\"invalid_client\"}")
        assertNull(AuthManager.getAccessToken())
        assertTrue(AuthManager.isAuthenticated())
    }

    @Test fun revokedRefreshTokenDisconnectsAccount() = runTest {
        server.enqueue(400, "{\"error\":\"invalid_grant\"}")
        assertNull(AuthManager.getAccessToken())
        assertFalse(AuthManager.isAuthenticated())
    }

    @Test fun signOutDuringRefreshCannotRestoreTokens() = runTest {
        val release = server.holdResponse("{\"access_token\":\"late-access\",\"expires_in\":3600}")
        val refresh = async(Dispatchers.IO) { AuthManager.getAccessToken() }
        try {
            server.takeRequest()
            AuthManager.clearAuth()
        } finally { release.countDown() }
        assertNull(refresh.await())
        assertFalse(AuthManager.isAuthenticated())
        assertNull(prefs.getString("access_token", null))
    }

    @Test fun signOutDuringAuthorizationCannotRelinkAccount() = runTest {
        val release = server.holdResponse("{\"access_token\":\"late-access\",\"refresh_token\":\"late-refresh\",\"expires_in\":3600}")
        val poll = async(Dispatchers.IO) { AuthManager.pollForAuthorization("code") }
        try {
            server.takeRequest()
            AuthManager.clearAuth()
        } finally { release.countDown() }
        assertTrue(poll.await() is AuthManager.PollResult.Error)
        assertFalse(AuthManager.isAuthenticated())
    }

    @Test fun disconnectClearsLocalAccountAndRevokesServerToken() = runTest {
        server.enqueue(200)
        val token = checkNotNull(AuthManager.disconnect())
        assertFalse(AuthManager.isAuthenticated())
        assertTrue(AuthManager.revokeAccess(token))
        val request = server.takeRequest()
        assertEquals("/revoke", request.path)
        assertEquals(token, decodeForm(request.body.readUtf8())["token"])
    }

    private fun setField(name: String, value: Any?) {
        AuthManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(AuthManager, value)
    }

    private fun decodeForm(body: String) = body.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }
}
