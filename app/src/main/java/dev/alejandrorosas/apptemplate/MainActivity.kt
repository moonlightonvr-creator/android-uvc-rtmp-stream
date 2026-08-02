package dev.alejandrorosas.apptemplate

import android.Manifest.permission.CAMERA
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.RECORD_AUDIO
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.requestPermissions
import com.google.android.material.snackbar.Snackbar
import com.pedro.rtplibrary.view.OpenGlView
import dagger.hilt.android.AndroidEntryPoint
import dev.alejandrorosas.apptemplate.MainViewModel.ViewState
import dev.alejandrorosas.streamlib.StreamService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class MainActivity : AppCompatActivity(R.layout.activity_main), SurfaceHolder.Callback, ServiceConnection {

    private val viewModel by viewModels<MainViewModel>()
    private var mService: StreamService? = null
    private var isBound = false
    private var pendingServiceCallback: ((StreamService) -> Unit)? = null
    private var streamActive = false
    private var recordingActive = false
    private val TAG = "MainActivity"
    private val prefs by lazy { getSharedPreferences("obs_like_ui_prefs", Context.MODE_PRIVATE) }

    private lateinit var streamButton: Button
    private lateinit var recordButton: Button
    private lateinit var streamStatusText: TextView
    private lateinit var sceneStatusText: TextView
    private lateinit var sceneNameInput: EditText
    private lateinit var overlayTextInput: EditText
    private lateinit var browserUrlInput: EditText
    private lateinit var privateChatInput: EditText
    private lateinit var privateChatSendButton: Button
    private lateinit var privateChatHistory: TextView
    private lateinit var audienceCountText: TextView
    private lateinit var audienceMinusButton: Button
    private lateinit var audiencePlusButton: Button
    private lateinit var audienceResetButton: Button
    private lateinit var subscriberCountText: TextView
    private lateinit var subscriberMinusButton: Button
    private lateinit var subscriberPlusButton: Button
    private lateinit var subscriberResetButton: Button
    private lateinit var verticalCropSwitch: Switch
    private lateinit var webViewWidget: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        streamButton = findViewById(R.id.start_stop_stream)
        recordButton = findViewById(R.id.start_stop_record)
        streamStatusText = findViewById(R.id.stream_status_text)
        sceneStatusText = findViewById(R.id.scene_status_text)
        sceneNameInput = findViewById(R.id.scene_name_input)
        overlayTextInput = findViewById(R.id.overlay_text_input)
        browserUrlInput = findViewById(R.id.browser_url_input)
        privateChatInput = findViewById(R.id.private_chat_input)
        privateChatSendButton = findViewById(R.id.private_chat_send_button)
        privateChatHistory = findViewById(R.id.private_chat_history)
        audienceCountText = findViewById(R.id.audience_count_text)
        audienceMinusButton = findViewById(R.id.audience_minus_button)
        audiencePlusButton = findViewById(R.id.audience_plus_button)
        audienceResetButton = findViewById(R.id.audience_reset_button)
        subscriberCountText = findViewById(R.id.subscriber_count_text)
        subscriberMinusButton = findViewById(R.id.subscriber_minus_button)
        subscriberPlusButton = findViewById(R.id.subscriber_plus_button)
        subscriberResetButton = findViewById(R.id.subscriber_reset_button)
        verticalCropSwitch = findViewById(R.id.vertical_crop_switch)
        webViewWidget = findViewById(R.id.webview_widget)

        StreamService.openGlView = findViewById(R.id.openglview)
        startService(Intent(this, StreamService::class.java))
        bindService(getServiceIntent(), this, Context.BIND_AUTO_CREATE)

        viewModel.serviceLiveEvent.observe(this) { callback ->
            if (mService != null) {
                try {
                    callback(mService!!)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error executing service callback", t)
                }
            } else {
                pendingServiceCallback = callback
            }
        }
        viewModel.getViewState().observe(this) { render(it) }

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<OpenGlView>(R.id.openglview).holder.addCallback(this)

        streamButton.setOnClickListener { toggleStream() }
        recordButton.setOnClickListener { toggleRecording() }

        findViewById<Button>(R.id.save_30s).setOnClickListener { saveReplayClip(30) }
        findViewById<Button>(R.id.save_60s).setOnClickListener { saveReplayClip(60) }
        findViewById<Button>(R.id.save_90s).setOnClickListener { saveReplayClip(90) }
        findViewById<Button>(R.id.save_120s).setOnClickListener { saveReplayClip(120) }
        findViewById<Button>(R.id.save_scene_button).setOnClickListener { saveScenePreset() }
        privateChatSendButton.setOnClickListener { sendPrivateReply() }
        audienceMinusButton.setOnClickListener { updateAudience(-1) }
        audiencePlusButton.setOnClickListener { updateAudience(1) }
        audienceResetButton.setOnClickListener { resetAudience() }
        subscriberMinusButton.setOnClickListener { updateSubscribers(-1) }
        subscriberPlusButton.setOnClickListener { updateSubscribers(1) }
        subscriberResetButton.setOnClickListener { resetSubscribers() }
        privateChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendPrivateReply()
                true
            } else {
                false
            }
        }

        verticalCropSwitch.setOnCheckedChangeListener { _, isChecked ->
            mService?.setVerticalCropEnabled(isChecked) ?: run {
                pendingServiceCallback = { svc -> svc.setVerticalCropEnabled(isChecked) }
            }
            streamStatusText.text = if (isChecked) "Vertical 9:16 stream ready" else "Landscape stream ready"
        }

        setupWebWidget()
        restoreSceneState()
        restorePrivateChat()
        restoreAudienceCount()
        restoreSubscriberCount()
        requestPermissions(this, arrayOf(READ_EXTERNAL_STORAGE, RECORD_AUDIO, CAMERA, WRITE_EXTERNAL_STORAGE), 1)
    }

    private fun setupWebWidget() {
        val settings: WebSettings = webViewWidget.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowUniversalAccessFromFileURLs = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        webViewWidget.setBackgroundColor(Color.TRANSPARENT)
        webViewWidget.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webViewWidget.webViewClient = android.webkit.WebViewClient()
    }

    private fun restoreSceneState() {
        val lastSceneName = prefs.getString("scene_name", "Prime Black")
        val lastOverlay = prefs.getString("overlay_text", "Live with OBS Studio")
        val lastBrowserUrl = prefs.getString("browser_url", "https://www.youtube.com")
        sceneNameInput.setText(lastSceneName)
        overlayTextInput.setText(lastOverlay)
        browserUrlInput.setText(lastBrowserUrl)
        verticalCropSwitch.isChecked = prefs.getBoolean("vertical_crop", false)
        streamStatusText.text = if (verticalCropSwitch.isChecked) "Vertical 9:16 stream ready" else "Landscape stream ready"
        sceneStatusText.text = "Scene preset • ${lastSceneName ?: "Prime Black"}"
        if (!lastBrowserUrl.isNullOrBlank()) {
            try {
                webViewWidget.loadUrl(lastBrowserUrl)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to restore browser widget URL", t)
            }
        }
    }

    private fun saveScenePreset() {
        val sceneName = sceneNameInput.text.toString().ifBlank { "Scene ${SimpleDateFormat("HHmmss", Locale.US).format(Date())}" }
        val overlayText = overlayTextInput.text.toString().ifBlank { "Streaming with Prism Live" }
        val browserUrl = browserUrlInput.text.toString().ifBlank { "https://www.youtube.com" }
        prefs.edit()
            .putString("scene_name", sceneName)
            .putString("overlay_text", overlayText)
            .putString("browser_url", browserUrl)
            .putBoolean("vertical_crop", verticalCropSwitch.isChecked)
            .apply()
        sceneStatusText.text = "Scene saved • $sceneName"
        if (browserUrl.isNotBlank()) {
            try {
                webViewWidget.loadUrl(browserUrl)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load browser widget URL", t)
            }
        }
        Snackbar.make(findViewById(R.id.control_panel), "Preset saved: $sceneName", Snackbar.LENGTH_LONG).show()
    }

    private fun sendPrivateReply() {
        val raw = privateChatInput.text.toString().trim()
        if (raw.isBlank()) {
            Snackbar.make(findViewById(R.id.control_panel), "Type a private reply first", Snackbar.LENGTH_SHORT).show()
            return
        }

        val safeText = raw.replace("\n", " ").take(160)
        val timestamp = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val messages = loadPrivateMessages().toMutableList()
        messages.add("$timestamp • $safeText")
        while (messages.size > 12) {
            messages.removeAt(0)
        }
        prefs.edit().putString("private_chat_messages", messages.joinToString("\n")).apply()
        privateChatHistory.text = messages.joinToString("\n")
        privateChatInput.setText("")
        Snackbar.make(findViewById(R.id.control_panel), "Private reply saved locally", Snackbar.LENGTH_SHORT).show()
    }

    private fun restorePrivateChat() {
        privateChatHistory.text = loadPrivateMessages().joinToString("\n").ifBlank { "No private replies yet" }
    }

    private fun restoreAudienceCount() {
        val count = prefs.getInt("audience_count", 0)
        audienceCountText.text = "Audience: $count"
    }

    private fun updateAudience(delta: Int) {
        val current = prefs.getInt("audience_count", 0).coerceAtLeast(0) + delta
        val safeValue = current.coerceAtLeast(0)
        prefs.edit().putInt("audience_count", safeValue).apply()
        audienceCountText.text = "Audience: $safeValue"
    }

    private fun resetAudience() {
        prefs.edit().putInt("audience_count", 0).apply()
        audienceCountText.text = "Audience: 0"
    }

    private fun restoreSubscriberCount() {
        val count = prefs.getInt("subscriber_count", 0)
        subscriberCountText.text = "Subscribers: $count"
    }

    private fun updateSubscribers(delta: Int) {
        val current = prefs.getInt("subscriber_count", 0).coerceAtLeast(0) + delta
        val safeValue = current.coerceAtLeast(0)
        prefs.edit().putInt("subscriber_count", safeValue).apply()
        subscriberCountText.text = "Subscribers: $safeValue"
    }

    private fun resetSubscribers() {
        prefs.edit().putInt("subscriber_count", 0).apply()
        subscriberCountText.text = "Subscribers: 0"
    }

    private fun loadPrivateMessages(): List<String> {
        return (prefs.getString("private_chat_messages", "") ?: "")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun toggleStream() {
        if (mService != null) {
            if (streamActive) {
                mService!!.stopStream(true)
                streamActive = false
                streamButton.setText(R.string.button_start_stream)
                streamStatusText.text = "Stream stopped"
            } else {
                val endpoint = prefs.getString("endpoint", null)
                if (endpoint.isNullOrBlank()) {
                    Snackbar.make(findViewById(R.id.control_panel), "Set an RTMP endpoint first", Snackbar.LENGTH_LONG).show()
                    return
                }
                val started = mService!!.startStreamRtp(endpoint)
                if (started) {
                    streamActive = true
                    streamButton.setText(R.string.button_stop_stream)
                    streamStatusText.text = if (verticalCropSwitch.isChecked) "Vertical 9:16 stream active" else "Landscape stream active"
                } else {
                    Snackbar.make(findViewById(R.id.control_panel), "Stream start failed", Snackbar.LENGTH_LONG).show()
                }
            }
        } else {
            pendingServiceCallback = { svc ->
                val endpoint = prefs.getString("endpoint", null)
                if (!endpoint.isNullOrBlank()) {
                    val started = svc.startStreamRtp(endpoint)
                    if (started) {
                        streamActive = true
                        streamButton.setText(R.string.button_stop_stream)
                    }
                }
            }
            Snackbar.make(findViewById(R.id.control_panel), "Waiting for camera service…", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun toggleRecording() {
        if (mService != null) {
            val outputFile = getExternalFilesDir(null)?.resolve("Recordings")?.resolve("session_${System.currentTimeMillis()}.mp4")
            if (recordingActive) {
                mService!!.stopLocalRecording()
                recordingActive = false
                recordButton.text = "Record"
                streamStatusText.text = "Local recording stopped"
            } else {
                val dir = outputFile?.parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                val started = mService!!.startLocalRecording(outputFile?.absolutePath ?: "")
                if (started) {
                    recordingActive = true
                    recordButton.text = "Stop record"
                    streamStatusText.text = "High-bitrate local recording"
                } else {
                    Snackbar.make(findViewById(R.id.control_panel), "Recording start failed", Snackbar.LENGTH_LONG).show()
                }
            }
        } else {
            pendingServiceCallback = { svc ->
                val outputFile = this.getExternalFilesDir(null)?.resolve("Recordings")?.resolve("session_${System.currentTimeMillis()}.mp4")
                val dir = outputFile?.parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                val started = svc.startLocalRecording(outputFile?.absolutePath ?: "")
                if (started) {
                    recordingActive = true
                    recordButton.text = "Stop record"
                }
            }
            Snackbar.make(findViewById(R.id.control_panel), "Waiting for camera service…", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun saveReplayClip(seconds: Int) {
        val action = {
            svc: StreamService ->
                try {
                    val (ok, path) = svc.saveLastNSeconds(seconds)
                    val msg = if (ok) "Saved $seconds s clip: $path" else "Replay export failed"
                    Snackbar.make(findViewById(R.id.control_panel), msg, Snackbar.LENGTH_LONG).show()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error saving replay clip", t)
                    Snackbar.make(findViewById(R.id.control_panel), "Replay export failed", Snackbar.LENGTH_LONG).show()
                }
        }
        if (mService != null) {
            action(mService!!)
        } else {
            pendingServiceCallback = action
        }
    }

    private fun render(viewState: ViewState) {
        streamButton.setText(viewState.streamButtonText)
    }

    private fun getServiceIntent(): Intent {
        return Intent(this, StreamService::class.java)
    }

    private fun stopStreamService() {
        try {
            if (isBound) {
                unbindService(this)
                isBound = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Exception while unbinding service", t)
        }
        try {
            stopService(Intent(this, StreamService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "Exception while stopping service", t)
        }
        mService = null
    }

    override fun surfaceChanged(holder: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        try {
            mService?.let {
                it.setView(findViewById<OpenGlView>(R.id.openglview))
                it.startPreview()
            } ?: run {
                Log.d(TAG, "surfaceChanged: service not bound yet")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error while starting preview", t)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            mService?.let {
                it.setView(applicationContext)
                it.stopPreview()
            } ?: run {
                Log.d(TAG, "surfaceDestroyed: service not bound")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error while stopping preview", t)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
    }

    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        mService = (service as StreamService.LocalBinder).getService()
        isBound = true
        Log.d(TAG, "Service connected")
        mService?.setVerticalCropEnabled(verticalCropSwitch.isChecked)
        pendingServiceCallback?.let { cb ->
            try {
                cb(mService!!)
            } catch (t: Throwable) {
                Log.e(TAG, "Error executing pending service callback", t)
            } finally {
                pendingServiceCallback = null
            }
        }
    }

    override fun onServiceDisconnected(arg0: ComponentName) {
        mService = null
        isBound = false
        Log.d(TAG, "Service disconnected")
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            try {
                unbindService(this)
            } catch (t: Throwable) {
                Log.w(TAG, "Exception while unbinding service in onStop", t)
            } finally {
                isBound = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreamService()
    }
}
