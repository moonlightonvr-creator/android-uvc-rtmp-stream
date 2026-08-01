package dev.alejandrorosas.streamlib.replay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File

/**
 * Lightweight bridge to expose replay buffer to the StreamService.
 */
class ReplayManager(private val baseDir: File) {
    private var buffer: ReplayBuffer? = null

    fun ensureInitialized(maxSeconds: Int = 60, segmentMs: Int = 1000) {
        if (buffer == null) {
            try {
                buffer = ReplayBuffer(baseDir, maxSeconds, segmentMs)
            } catch (e: Exception) {
                Log.e("ReplayManager", "Failed to init replay buffer", e)
            }
        }
    }

    fun writeFrame(h264Buffer: java.nio.ByteBuffer?, info: MediaCodec.BufferInfo?) {
        if (h264Buffer == null || info == null) return
        buffer?.writeFrame(h264Buffer, info)
    }

    fun saveLastNSeconds(seconds: Int, outFile: File, videoFormat: MediaFormat?): Boolean {
        if (buffer == null) return false
        return buffer!!.saveLastNSeconds(seconds, outFile.absolutePath, videoFormat)
    }

    fun setMaxSeconds(seconds: Int) {
        buffer?.setMaxSeconds(seconds)
    }

    fun shutdown() {
        buffer?.shutdown()
    }
}
