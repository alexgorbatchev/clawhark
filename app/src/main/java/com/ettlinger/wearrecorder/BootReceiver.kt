package com.ettlinger.wearrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Prompts the user to resume after reboot without starting a background microphone.
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
            AppLog.i("Boot", "Reboot detected — user interaction required to resume recording")
            ResumeRecordingNotification.show(context)
        }
    }
}
