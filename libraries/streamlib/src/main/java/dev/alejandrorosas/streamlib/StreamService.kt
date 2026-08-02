package dev.alejandrorosas.streamlib

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.media.MediaFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.view.OpenGlView
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

class StreamService : Service() {
    companion object {
        private const val TAG = "RtpService"
        private const val channelId = "rtpStreamChannel"
        private const val notifyId = 123456

        var openGlView: OpenGlView? = null
    }

    val isStreaming: Boolean get() = endpoint != null
    var cameraWidth = 1280
    var cameraHeight = 960

    private var endpoint: String? = null
    private var rtmpUSB: RtmpUSB? = null
    private var uvcCamera: UVCCamera? = null
    private var usbMonitor: USBMonitor? = null
    private var verticalCropEnabled = false
    private var streamBitrateKbps = 4000
    private var recordingBitrateKbps = 12000
    private var streamWidth = 1280
    private var streamHeight = 720
    private var recordingWidth = 1280
    private var recordingHeight = 720
    private val notificationManager: NotificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "RTP service create")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        keepAliveTrick()
    }

    private fun keepAliveTrick() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            val notification = NotificationCompat.Builder(this, channelId).setOngoing(true).setContentTitle("").setContentText("").build()
            startForeground(1, notification)
        } else {
            startForeground(1, Notification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "RTP service started")
        usbMonitor = USBMonitor(this, onDeviceConnectListener).apply {
            register()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "RTP service destroy")
        stopStream()
        stopLocalRecording()
        usbMonitor?.unregister()
        uvcCamera?.destroy()
    }

    private fun prepareStreamRtp() {
        stopStream()
        stopPreview()

        rtmpUSB = if (openGlView == null) {
            RtmpUSB(this, connectCheckerRtmp)
        } else {
            RtmpUSB(openGlView, connectCheckerRtmp)
        }
    }

    fun startStreamRtp(endpoint: String): Boolean {
        if (rtmpUSB?.isStreaming == false) {
            this.endpoint = endpoint
            val width = if (verticalCropEnabled) 720 else streamWidth
            val height = if (verticalCropEnabled) 1280 else streamHeight
            val fps = if (verticalCropEnabled) 60 else 30
            val bitrate = streamBitrateKbps * 1024
            if (rtmpUSB!!.prepareVideo(width, height, fps, bitrate, 0, uvcCamera) && rtmpUSB!!.prepareAudio()) {
                rtmpUSB!!.startStream(uvcCamera, endpoint)
                return true
            }
        }
        return false
    }

    fun startLocalRecording(outputPath: String): Boolean {
        if (rtmpUSB == null || uvcCamera == null || outputPath.isBlank()) return false
        val bitrate = recordingBitrateKbps * 1024
        return try {
            if (rtmpUSB!!.prepareVideo(recordingWidth, recordingHeight, 60, bitrate, 0, uvcCamera) && rtmpUSB!!.prepareAudio()) {
                rtmpUSB!!.startRecord(uvcCamera, outputPath)
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start local recording", t)
            false
        }
    }

    fun stopLocalRecording() {
        if (rtmpUSB != null) {
            try {
                rtmpUSB!!.stopRecord(uvcCamera)
            } catch (t: Throwable) {
                Log.w(TAG, "Ignoring stopRecord error", t)
            }
        }
    }

    fun setVerticalCropEnabled(enabled: Boolean) {
        verticalCropEnabled = enabled
    }

    fun setView(view: OpenGlView) {
        openGlView = view
        rtmpUSB?.replaceView(openGlView, uvcCamera)
    }

    fun setView(context: Context) {
        openGlView = null
        rtmpUSB?.replaceView(context, uvcCamera)
    }

    fun startPreview() {
        rtmpUSB?.startPreview(uvcCamera, cameraWidth, cameraHeight)
    }

    fun stopStream(force: Boolean = false) {
        if (force) endpoint = null
        if (rtmpUSB?.isStreaming == true) rtmpUSB!!.stopStream(uvcCamera)
    }

    fun stopPreview() {
        if (rtmpUSB?.isOnPreview == true) rtmpUSB!!.stopPreview(uvcCamera)
    }

    /**
     * Expose the current encoder video format so callers (e.g., StreamService+Replay) can mux replay clips.
     */
    fun getRtmpVideoFormat(): MediaFormat? {
        return rtmpUSB?.getVideoFormat()
    }

    private val connectCheckerRtmp = object : ConnectCheckerRtmp {
        override fun onConnectionSuccessRtmp() {
            showNotification("Stream started")
            Log.e(TAG, "RTP connection success")
        }

        override fun onConnectionFailedRtmp(reason: String) {
            showNotification("Stream connection failed")
            Log.e(TAG, "RTP service destroy")
        }

        override fun onConnectionStartedRtmp(rtmpUrl: String) {
            showNotification("On connection started")
            Log.e(TAG, "RTP On connection started")
        }

        override fun onNewBitrateRtmp(bitrate: Long) {
//            TODO("Not yet implemented")
        }

        override fun onDisconnectRtmp() {
            showNotification("Stream stopped")
        }

        override fun onAuthErrorRtmp() {
            showNotification("Stream auth error")
        }

        override fun onAuthSuccessRtmp() {
            showNotification("Stream auth success")
        }
    }

    private fun showNotification(text: String) {
        val notification =
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.mipmap.sym_def_app_icon)
                .setContentTitle("RTP Stream")
                .setContentText(text).build()
        notificationManager.notify(notifyId, notification)
    }

    private val onDeviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            usbMonitor!!.requestPermission(device)
        }

        override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
            val camera = UVCCamera()
            camera.open(ctrlBlock)
            try {
                val maxSupportedSize = camera.supportedSizeList.maxBy { it.width * it.height }
                cameraWidth = maxSupportedSize.width
                cameraHeight = maxSupportedSize.height
                streamWidth = cameraWidth
                streamHeight = cameraHeight
                recordingWidth = cameraWidth
                recordingHeight = cameraHeight
                camera.setPreviewSize(cameraWidth, cameraHeight, UVCCamera.FRAME_FORMAT_MJPEG)
            } catch (e: IllegalArgumentException) {
                camera.destroy()
                try {
                    camera.setPreviewSize(cameraWidth, cameraHeight, UVCCamera.DEFAULT_PREVIEW_MODE)
                } catch (e1: IllegalArgumentException) {
                    return
                }
            }
            uvcCamera = camera
            prepareStreamRtp()
            rtmpUSB!!.startPreview(uvcCamera, cameraWidth, cameraHeight)
            endpoint?.let { startStreamRtp(it) }
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            stopStream(false)
        }

        override fun onCancel(device: UsbDevice?) {
        }

        override fun onDettach(device: UsbDevice?) {
            stopStream(false)
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
