package com.ettlinger.wearrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat

import kotlinx.coroutines.*

class MainActivity : Activity() {

    private var service: RecordingService? = null
    private var bindRequested = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Recording views
    private lateinit var recordGroup: LinearLayout
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView

    // Auth views
    private lateinit var authGroup: LinearLayout
    private lateinit var authTitle: TextView
    private lateinit var authStatus: TextView
    private lateinit var authCode: TextView
    private lateinit var authBtn: Button

    private var authPollingJob: Job? = null
    private var activityResumed = false
    private var uiJob: Job? = null
    private var storageBytes = 0L
    private var permissionRequestInFlight = false

    // Double-tap protection
    private var lastToggleTime = 0L
    private var lastAuthTapTime = 0L

    // Stop confirmation (two-tap)
    private var confirmPending = false
    private var confirmResetJob: Job? = null

    // Auth polling animation
    private var dotCount = 0

    private companion object {
        const val DEBOUNCE_MS = 600L
        const val CONFIRM_TIMEOUT_MS = 3000L
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RecordingService.LocalBinder).getService()
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLog.init(this)
        AuthManager.init(this)

        // Recording views
        recordGroup = findViewById(R.id.recordGroup)
        toggleBtn = findViewById(R.id.toggleBtn)
        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)

        // Auth views
        authGroup = findViewById(R.id.authGroup)
        authTitle = findViewById(R.id.authTitle)
        authStatus = findViewById(R.id.authStatus)
        authCode = findViewById(R.id.authCode)
        authBtn = findViewById(R.id.authBtn)

        toggleBtn.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastToggleTime < DEBOUNCE_MS) return@setOnClickListener
            lastToggleTime = now
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (service?.isSessionActive() == true) {
                toggle()
            } else if (checkPermissions()) {
                toggle()
            } else {
                getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
                    .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).apply()
            }
        }

        // Long press on toggle to sign out
        toggleBtn.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            signOut()
            true
        }

        authBtn.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAuthTapTime < DEBOUNCE_MS) return@setOnClickListener
            lastAuthTapTime = now
            startDeviceCodeFlow()
        }

        checkPermissions()
        showCorrectScreen()

    }

    override fun onStart() {
        super.onStart()
        requestBatteryExemption()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        showCorrectScreen()
        resumeRecordingIfPermitted()
        uiJob?.cancel()
        uiJob = scope.launch {
            var nextStorageRefresh = 0L
            while (isActive) {
                if (SystemClock.elapsedRealtime() >= nextStorageRefresh) {
                    val connected = service
                    storageBytes = withContext(Dispatchers.IO) { connected?.getStorageUsed() ?: 0L }
                    nextStorageRefresh = SystemClock.elapsedRealtime() + 10_000
                }
                updateUI()
                delay(1000)
            }
        }
    }

    override fun onPause() {
        activityResumed = false
        uiJob?.cancel()
        super.onPause()
    }

    private fun resumeRecordingIfPermitted() {
        if (!activityResumed ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) return
        if (AuthManager.isAuthenticated()) {
            val shouldRecord = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
                .getBoolean(RecordingService.PREF_SHOULD_RECORD, true)
            if (shouldRecord) {
                val intent = Intent(this, RecordingService::class.java)
                startForegroundService(intent)
                doBind(intent)
            }
        } else {
            // Attach to an already-running local recording without starting a new service.
            doBind(Intent(this, RecordingService::class.java), 0)
        }
    }

    override fun onStop() {
        super.onStop()
        doUnbind()
    }

    override fun onDestroy() {
        authPollingJob?.cancel()
        confirmResetJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun doBind(intent: Intent, flags: Int = Context.BIND_AUTO_CREATE) {
        if (!bindRequested) {
            bindRequested = bindService(intent, connection, flags)
        }
    }

    private fun doUnbind() {
        if (bindRequested) {
            unbindService(connection)
            bindRequested = false
            service = null
        }
    }

    private fun showCorrectScreen() {
        if (AuthManager.isAuthenticated() || service?.isSessionActive() == true) {
            authGroup.visibility = View.GONE
            recordGroup.visibility = View.VISIBLE
        } else {
            recordGroup.visibility = View.GONE
            authGroup.visibility = View.VISIBLE
            if (authPollingJob?.isActive != true) {
                authTitle.text = getString(R.string.link_title)
                authBtn.isEnabled = true
                authBtn.text = getString(R.string.link)
                authStatus.text = AuthManager.configurationError ?: getString(R.string.connect_drive)
                authCode.visibility = View.GONE
            }
        }
    }

    // ─── Sign Out ─────────────────────────────────────────────────────────

    private fun signOut() {
        authPollingJob?.cancel()
        getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
            .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false).apply()
        stopService(Intent(this, RecordingService::class.java))
        doUnbind()
        val token = AuthManager.disconnect()
        androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag(UploadWorker.WORK_TAG)
        Toast.makeText(this, getString(R.string.signed_out), Toast.LENGTH_SHORT).show()
        showCorrectScreen()
        if (token != null) scope.launch {
            if (!AuthManager.revokeAccess(token)) {
                Toast.makeText(this@MainActivity, getString(R.string.revocation_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ─── Device Code Auth Flow ───────────────────────────────────────────

    private fun startDeviceCodeFlow() {
        authBtn.isEnabled = false
        authBtn.text = "..."
        authStatus.text = getString(R.string.requesting_code)
        authCode.visibility = View.GONE

        authPollingJob?.cancel()
        authPollingJob = scope.launch pollLoop@{
            val response = AuthManager.requestDeviceCode()
            if (response == null) {
                authBtn.isEnabled = true
                authBtn.text = getString(R.string.retry)
                authStatus.text = AuthManager.configurationError ?: getString(R.string.connection_failed)
                return@pollLoop
            }

            // Show the user code prominently
            authTitle.text = getString(R.string.enter_code)
            authStatus.text = response.verificationUrl.removePrefix("https://")
            authCode.text = response.userCode
            authCode.visibility = View.VISIBLE
            authBtn.text = getString(R.string.waiting)
            dotCount = 0

            // Poll for authorization
            var interval = maxOf(response.interval, 5) * 1000L
            val deadline = SystemClock.elapsedRealtime() + response.expiresIn * 1000L
            while (isActive) {
                delay(interval)
                // Animate dots while polling
                dotCount = (dotCount + 1) % 4
                authBtn.text = getString(R.string.waiting_dots, ".".repeat(dotCount))

                val result = if (SystemClock.elapsedRealtime() >= deadline) AuthManager.PollResult.Expired
                    else AuthManager.pollForAuthorization(response.deviceCode)
                when (result) {
                    is AuthManager.PollResult.Success -> {
                        getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
                            .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).apply()
                        authTitle.text = getString(R.string.connected)
                        authStatus.text = ""
                        authCode.visibility = View.GONE
                        authBtn.text = getString(R.string.ok)
                        delay(1500)
                        showCorrectScreen()
                        resumeRecordingIfPermitted()
                        return@pollLoop
                    }
                    is AuthManager.PollResult.Pending -> {
                        // Keep waiting — dots already animated above
                    }
                    is AuthManager.PollResult.SlowDown -> {
                        interval += 5000
                    }
                    is AuthManager.PollResult.Expired -> {
                        authTitle.text = getString(R.string.link_title)
                        authStatus.text = getString(R.string.code_expired)
                        authCode.visibility = View.GONE
                        authBtn.isEnabled = true
                        authBtn.text = getString(R.string.retry)
                        return@pollLoop
                    }
                    is AuthManager.PollResult.Error -> {
                        authTitle.text = getString(R.string.link_title)
                        authStatus.text = getString(R.string.auth_error, result.message)
                        authCode.visibility = View.GONE
                        authBtn.isEnabled = true
                        authBtn.text = getString(R.string.retry)
                        return@pollLoop
                    }
                }
            }
        }
    }

    // ─── Recording Controls ──────────────────────────────────────────────

    private fun toggle() {
        val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
        val svc = service
        if (svc == null || !svc.isSessionActive()) {
            // Start recording — clear any pending stop confirmation
            confirmPending = false
            confirmResetJob?.cancel()
            prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).apply()
            val intent = Intent(this, RecordingService::class.java)
            startForegroundService(intent)
            doBind(intent)
        } else {
            // Stop recording — require two taps for confirmation
            if (!confirmPending) {
                confirmPending = true
                toggleBtn.text = getString(R.string.sure)
                toggleBtn.contentDescription = getString(R.string.confirm_stop)
                statusText.text = getString(R.string.tap_stop)
                toggleBtn.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                confirmResetJob = scope.launch {
                    delay(CONFIRM_TIMEOUT_MS)
                    confirmPending = false
                    updateUI()
                }
                return
            }
            // Second tap — actually stop
            confirmPending = false
            confirmResetJob?.cancel()
            prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false).apply()
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            startService(intent)
        }
        updateUI()
    }

    private fun updateUI() {
        val svc = service
        if (!AuthManager.isAuthenticated() && svc?.isSessionActive() != true) {
            if (recordGroup.visibility == View.VISIBLE) showCorrectScreen()
            return
        }
        recordGroup.visibility = View.VISIBLE
        authGroup.visibility = View.GONE
        if (svc != null && svc.isSessionActive()) {
            // Recording state — red button
            toggleBtn.setBackgroundResource(R.drawable.circle_button_recording)
            if (!confirmPending) {
                statusText.text = when (svc.recordingState) {
                    RecordingService.RecordingState.RECORDING -> getString(R.string.recording)
                    RecordingService.RecordingState.RECOVERING -> getString(R.string.recovering)
                    RecordingService.RecordingState.STORAGE_FULL -> getString(R.string.storage_full)
                    RecordingService.RecordingState.PERMISSION_REQUIRED -> getString(R.string.mic_permission)
                    RecordingService.RecordingState.STOPPED -> getString(R.string.starting)
                }
                statusText.setTextColor(if (svc.isCurrentlyRecording()) 0xFFCC3333.toInt() else 0xFFFFAA33.toInt())
                toggleBtn.text = getString(R.string.stop)
            }

            val elapsed = if (svc.recordingStartTime == 0L) 0L else maxOf(0L, System.currentTimeMillis() - svc.recordingStartTime)
            val mins = (elapsed / 60000).toInt()
            val hrs = mins / 60
            val m = mins % 60
            val chunks = svc.totalChunks
            val mb = String.format("%.1f", storageBytes / 1024.0 / 1024.0)

            infoText.text = when (svc.recordingState) {
                RecordingService.RecordingState.STORAGE_FULL -> getString(R.string.pending_storage, mb)
                RecordingService.RecordingState.RECOVERING -> getString(R.string.microphone_retry)
                else -> getString(R.string.recording_info, hrs, m, resources.getQuantityString(R.plurals.chunk_count, chunks, chunks), mb)
            }
            if (!AuthManager.isAuthenticated()) infoText.text = getString(R.string.drive_disconnected)
        } else {
            // Stopped state — default button
            toggleBtn.setBackgroundResource(R.drawable.circle_button)
            confirmPending = false
            statusText.text = if (svc?.recordingState == RecordingService.RecordingState.PERMISSION_REQUIRED) getString(R.string.mic_permission) else getString(R.string.stopped)
            statusText.setTextColor(0xFF888888.toInt())
            toggleBtn.text = getString(R.string.start)
            infoText.text = getString(R.string.record_help)
        }
        toggleBtn.contentDescription = if (confirmPending) getString(R.string.confirm_stop)
            else if (svc?.isSessionActive() == true) getString(R.string.stop_recording) else getString(R.string.start_recording)
    }

    // ─── Battery Exemption ─────────────────────────────────────────────

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        // Only prompt once — don't nag on every app open
        val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
        if (prefs.getBoolean("battery_exemption_asked", false)) return
        prefs.edit().putBoolean("battery_exemption_asked", true).apply()

        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            Toast.makeText(this, R.string.battery_settings, Toast.LENGTH_LONG).show()
            startActivity(intent)
        } catch (_: Exception) {
            // Some watches may not support this intent
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────────

    private fun checkPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val needed = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val micGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (needed.isNotEmpty() && !permissionRequestInFlight) {
            permissionRequestInFlight = true
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
        return micGranted
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != 1) return
        permissionRequestInFlight = false
        if (results.isEmpty()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (AuthManager.isAuthenticated()) {
                resumeRecordingIfPermitted()
            }
        } else {
            val denied = perms.zip(results.toList()).filter { it.second != PackageManager.PERMISSION_GRANTED }.map { it.first }
            val permanentlyDenied = denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
            if (permanentlyDenied) {
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
            }
        }
    }
}
