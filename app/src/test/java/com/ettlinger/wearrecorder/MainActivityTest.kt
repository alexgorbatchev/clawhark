package com.ettlinger.wearrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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
    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        val auth = app.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        auth.edit().putString("refresh_token", "test-only-token").commit()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, auth)
        app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("battery_exemption_asked", true).commit()
    }

    @After
    fun tearDown() {
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
    fun signOutDisablesRecordingBeforeServiceBindingCompletes() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        MainActivity::class.java.getDeclaredMethod("signOut").apply { isAccessible = true }.invoke(controller.get())
        assertFalse(app.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(RecordingService.PREF_SHOULD_RECORD, true))
        assertFalse(AuthManager.isAuthenticated())
        controller.destroy()
    }
}
