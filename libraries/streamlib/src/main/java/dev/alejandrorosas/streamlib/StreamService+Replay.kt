package dev.alejandrorosas.streamlib

import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.alejandrorosas.streamlib.replay.ReplayManager
import dev.alejandrorosas.streamlib.replay.ReplayHolder

fun StreamService.ensureReplayInitialized() {
    try {
        val clipsDir = File(getExternalFilesDir(null), "Clips")
        if (!clipsDir.exists()) clipsDir.mkdirs()
        if (ReplayHolder.replayManager == null) {
            val mgr = ReplayManager(File(clipsDir.parentFile, "replay_segments"))
            mgr.ensureInitialized(120, 1000)
            ReplayHolder.replayManager = mgr
        }
    } catch (t: Throwable) {
        Log.e("StreamService", "Failed to init replay manager", t)
    }
}

fun StreamService.saveLastNSeconds(seconds: Int): Pair<Boolean, String?> {
    ensureReplayInitialized()
    val clipsDir = File(getExternalFilesDir(null), "Clips")
    if (!clipsDir.exists()) clipsDir.mkdirs()
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val outFile = File(clipsDir, "clip_${'$'}{timestamp}_${'$'}{seconds}s.mp4")
    try {
        // Use public getter on StreamService rather than accessing private members
        val success = ReplayHolder.replayManager?.saveLastNSeconds(seconds, outFile, getRtmpVideoFormat()) ?: false
        return Pair(success, if (success) outFile.absolutePath else null)
    } catch (t: Throwable) {
        Log.e("StreamService", "Failed to save clip", t)
        return Pair(false, null)
    }
}
