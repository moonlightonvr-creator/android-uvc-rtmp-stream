package dev.alejandrorosas.apptemplate

import android.Manifest.permission.CAMERA
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.RECORD_AUDIO
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.requestPermissions
import com.pedro.rtplibrary.view.OpenGlView
import dagger.hilt.android.AndroidEntryPoint
import dev.alejandrorosas.apptemplate.MainViewModel.ViewState
import dev.alejandrorosas.streamlib.StreamService

@AndroidEntryPoint
class MainActivity : AppCompatActivity(R.layout.activity_main), SurfaceHolder.Callback, ServiceConnection {

    private val viewModel by viewModels<MainViewModel>()
    private var mService: StreamService? = null
    private var isBound = false
    private var pendingServiceCallback: ((StreamService) -> Unit)? = null
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // provide the OpenGlView reference for the service to use as a default
        StreamService.openGlView = findViewById(R.id.openglview)
        // start the service (don't bind here) so it can register USBMonitor
        startService(Intent(this, StreamService::class.java))

        viewModel.serviceLiveEvent.observe(this) { callback ->
            if (mService != null) {
                try {
                    callback(mService!!)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error executing service callback", t)
                }
            } else {
                // keep the latest pending callback and run it once the service connects
                pendingServiceCallback = callback
            }
        }
        viewModel.getViewState().observe(this) { render(it) }

        findViewById<View>(R.id.settings_button).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<OpenGlView>(R.id.openglview).holder.addCallback(this)
        findViewById<Button>(R.id.start_stop_stream).setOnClickListener { viewModel.onStreamControlButtonClick() }

        requestPermissions(this, arrayOf(READ_EXTERNAL_STORAGE, RECORD_AUDIO, CAMERA, WRITE_EXTERNAL_STORAGE), 1)
    }

    private fun render(viewState: ViewState) {
        findViewById<Button>(R.id.start_stop_stream).setText(viewState.streamButtonText)
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
                // set view to null via context overload to indicate no preview target
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
        // unbind only if we bound the service
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

    override fun onStart() {
        super.onStart()
        // bind to the service if not already bound
        if (!isBound) {
            try {
                bindService(getServiceIntent(), this, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.w(TAG, "Exception while binding service in onStart", t)
            }
        }
    }

    override fun onDestroy() {
        try {
            if (isBound) {
                try {
                    unbindService(this)
                } catch (t: Throwable) {
                    Log.w(TAG, "Exception while unbinding service in onDestroy", t)
                } finally {
                    isBound = false
                }
            }
            // stop the service only if it's not streaming
            if (mService?.isStreaming == false) {
                try {
                    stopService(Intent(this, StreamService::class.java))
                } catch (t: Throwable) {
                    Log.w(TAG, "Exception while stopping service in onDestroy", t)
                }
            }
        } finally {
            super.onDestroy()
        }
    }

}
