package com.ettlinger.wearrecorder

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Attempts automatic restart and prompts the user if Android rejects background capture.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        AppLog.init(context)
        AuthManager.init(context)

        if (!AuthManager.isAuthenticated()) return

        val shouldRecord = context.getSharedPreferences(
            RecordingService.PREF_FILE, Context.MODE_PRIVATE
        ).getBoolean(RecordingService.PREF_SHOULD_RECORD, true)

        if (shouldRecord) {
            AppLog.i("Boot", "Reboot detected — restarting recording")
            // alexgorbatchev: Automatic microphone capture after reboot needs testing on our Pixel Watch 3
            // running Wear OS 7; Android documents restrictions and exceptions for this behavior:
            // https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
            val serviceIntent = Intent(context, RecordingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        context.startForegroundService(serviceIntent)
                    } catch (error: ForegroundServiceStartNotAllowedException) {
                        AppLog.w("Boot", "Background service start rejected — open ClawHark to resume")
                        ResumeRecordingNotification.show(context)
                    }
                } else {
                    context.startForegroundService(serviceIntent)
                }
            } catch (error: SecurityException) {
                AppLog.w("Boot", "Background microphone permission rejected — open ClawHark to resume")
                ResumeRecordingNotification.show(context)
            }
        }
    }
}
