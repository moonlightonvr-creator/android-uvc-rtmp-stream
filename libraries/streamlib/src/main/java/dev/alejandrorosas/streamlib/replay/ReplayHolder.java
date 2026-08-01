package dev.alejandrorosas.streamlib.replay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.io.File;

public class ReplayHolder {
    private static final String TAG = "ReplayHolder";
    public static volatile ReplayManager replayManager = null;

    public static void writeFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        try {
            if (replayManager != null) {
                replayManager.writeFrame(buffer, info);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write frame to replay manager", e);
        }
    }

    public static boolean saveLastNSeconds(int seconds, File outFile, MediaFormat videoFormat) {
        try {
            if (replayManager == null) return false;
            return replayManager.saveLastNSeconds(seconds, outFile, videoFormat);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save last N seconds", e);
            return false;
        }
    }
}
