package com.ettlinger.wearrecorder

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        // Exercise the receiver with native SharedPreferences without requiring a hardware keystore.
        val prefs = application.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        prefs.edit().putString("refresh_token", "test-only-token").commit()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, prefs)
        setRecordingRequested(true)
    }

    @After
    fun tearDown() {
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, null)
    }

    @Test
    fun rebootStartsRecordingWhenPreviouslyEnabled() {
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(RecordingService::class.java.name, shadowOf(application).nextStartedService?.component?.className)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    fun explicitStopDoesNotRestartRecording() {
        setRecordingRequested(false)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedService)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    fun unrelatedBroadcastDoesNothing() {
        BootReceiver().onReceive(application, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        assertNull(shadowOf(application).nextStartedService)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    fun signedOutDoesNotRestartRecording() {
        application.getSharedPreferences("test_auth", Context.MODE_PRIVATE).edit().clear().commit()
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedService)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    private fun setRecordingRequested(enabled: Boolean) {
        application.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, enabled).commit()
    }
}
