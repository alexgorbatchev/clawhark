package com.ettlinger.wearrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {
    private lateinit var server: HttpTestServer
    @Before
    fun setUp() {
        server = HttpTestServer().apply { enqueue(200) }
        AuthManager.oauthRoot = server.baseUrl
        val app = RuntimeEnvironment.getApplication()
        val auth = app.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        auth.edit().putString("refresh_token", "test-only-token").commit()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, auth)
        app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("battery_exemption_asked", true).commit()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        server.close()
        AuthManager.oauthRoot = "https://oauth2.googleapis.com"
        androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, null)
    }

    @Test
    fun missingMicrophonePermissionDoesNotStartServiceOnOpen() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertNull(shadowOf(app).nextStartedService)
        controller.pause().stop().destroy()
    }

    @Test
    fun startPersistsRecordingPreferenceAndStartsWatchService() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val prefs = app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        MainActivity::class.java.getDeclaredMethod("toggle").apply { isAccessible = true }.invoke(controller.get())
        org.junit.Assert.assertTrue(prefs.getBoolean(RecordingService.PREF_SHOULD_RECORD, false))
        assertEquals(RecordingService::class.java.name, shadowOf(app).nextStartedService.component?.className)
        controller.destroy()
    }

    @Test
    fun permissionCallbackPreservesExplicitStop() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        controller.get().onRequestPermissionsResult(1, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertNull(shadowOf(app).nextStartedService)
        controller.pause().stop().destroy()
    }

    @Test
    fun lostDriveAuthorizationLeavesRecordingStopControlAccessible() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val job = kotlinx.coroutines.Job()
        RecordingService::class.java.getDeclaredField("recordJob").apply { isAccessible = true }.set(service, job)
        MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }.set(activity, service)
        AuthManager.clearAuth()
        MainActivity::class.java.getDeclaredMethod("updateUI").apply { isAccessible = true }.invoke(activity)
        assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.recordGroup).visibility)
        assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.authGroup).visibility)
        assertEquals(activity.getString(R.string.stop), activity.findViewById<android.widget.Button>(R.id.toggleBtn).text.toString())
        job.cancel()
        service.onDestroy()
        controller.destroy()
    }

    @Test
    fun authorizationCodeRemainsVisibleAfterActivityResumes() {
        AuthManager.clearAuth()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        val pending = kotlinx.coroutines.Job()
        MainActivity::class.java.getDeclaredField("authPollingJob").apply { isAccessible = true }.set(activity, pending)
        val code = activity.findViewById<android.widget.TextView>(R.id.authCode)
        code.text = "TEST-CODE"
        code.visibility = android.view.View.VISIBLE
        controller.pause().resume()
        assertEquals(android.view.View.VISIBLE, code.visibility)
        assertEquals("TEST-CODE", code.text.toString())
        pending.cancel()
        controller.pause().stop().destroy()
    }

    @Test
    fun signOutDisablesRecordingBeforeServiceBindingCompletes() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.get().findViewById<android.widget.Button>(R.id.authBtn).isEnabled = false
        MainActivity::class.java.getDeclaredMethod("signOut").apply { isAccessible = true }.invoke(controller.get())
        assertFalse(app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(RecordingService.PREF_SHOULD_RECORD, true))
        assertFalse(AuthManager.isAuthenticated())
        org.junit.Assert.assertTrue(controller.get().findViewById<android.widget.Button>(R.id.authBtn).isEnabled)
        controller.destroy()
    }
}
