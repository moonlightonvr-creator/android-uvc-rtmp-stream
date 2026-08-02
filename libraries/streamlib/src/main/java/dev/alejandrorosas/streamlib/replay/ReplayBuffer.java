package dev.alejandrorosas.streamlib.replay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Simple segment-based replay buffer for encoded H264 frames.
 *
 * Segment file format (per frame append):
 * [long ptsUs][int flags][int size][byte[] data]
 *
 * This implementation prioritizes simplicity and robustness over performance.
 */
public class ReplayBuffer {
    private static final String TAG = "ReplayBuffer";
    private final File dir;
    private final int segmentDurationMs;
    private int maxSeconds = 120; // default

    private DataOutputStream currentOut;
    private long currentSegmentStartUs = -1;
    private File currentSegmentFile;
    private long lastFramePtsUs = -1;
    private int frameCount = 0;

    public ReplayBuffer(File baseDir, int maxSeconds, int segmentDurationMs) throws IOException {
        this.maxSeconds = Math.max(1, maxSeconds);
        this.segmentDurationMs = Math.max(200, segmentDurationMs);
        this.dir = new File(baseDir, "replay_segments");
        if (!this.dir.exists() && !this.dir.mkdirs()) {
            throw new IOException("Could not create replay buffer directory: " + this.dir.getAbsolutePath());
        }
        cleanupOldSegments();
    }

    public synchronized void setMaxSeconds(int seconds) {
        this.maxSeconds = Math.max(1, seconds);
        cleanupOldSegments();
    }

    private void cleanupOldSegments() {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long keepMs = (long) maxSeconds * 1000L;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > keepMs) {
                f.delete();
            }
        }
    }

    private String segmentNameForStart(long startUs) {
        return String.format("segment_%d.seg", startUs);
    }

    private void rotateSegmentIfNeeded(long ptsUs) {
        long segDurUs = (long) segmentDurationMs * 1000L;
        if (currentOut == null || currentSegmentStartUs < 0 || ptsUs - currentSegmentStartUs >= segDurUs) {
            // close old
            closeCurrentSegment();
            // open new
            currentSegmentStartUs = (ptsUs / segDurUs) * segDurUs;
            currentSegmentFile = new File(dir, segmentNameForStart(currentSegmentStartUs));
            try {
                currentOut = new DataOutputStream(new FileOutputStream(currentSegmentFile, true));
            } catch (IOException e) {
                Log.e(TAG, "Failed to open segment file", e);
                currentOut = null;
                currentSegmentFile = null;
            }
            cleanupOldSegments();
        }
    }

    private void closeCurrentSegment() {
        if (currentOut != null) {
            try {
                currentOut.flush();
                currentOut.close();
            } catch (IOException ignore) {
            }
            currentOut = null;
            currentSegmentFile = null;
            currentSegmentStartUs = -1;
        }
    }

    public synchronized void writeFrame(ByteBuffer h264Buffer, MediaCodec.BufferInfo info) {
        if (h264Buffer == null || info == null) return;
        try {
            long ptsUs = info.presentationTimeUs;
            if (lastFramePtsUs >= 0 && ptsUs > lastFramePtsUs) {
                frameCount++;
            }
            rotateSegmentIfNeeded(ptsUs);
            if (currentOut == null) return;
            int size = info.size;
            byte[] data = new byte[size];
            int oldPos = h264Buffer.position();
            h264Buffer.get(data, 0, size);
            h264Buffer.position(oldPos);
            currentOut.writeLong(ptsUs);
            currentOut.writeInt(info.flags);
            currentOut.writeInt(size);
            currentOut.write(data);
            lastFramePtsUs = ptsUs;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write frame to segment", e);
        }
    }

    public synchronized boolean saveLastNSeconds(int seconds, String outPath, MediaFormat videoFormat) {
        if (seconds <= 0) return false;
        if (videoFormat == null) {
            Log.e(TAG, "VideoFormat is null, cannot mux");
            return false;
        }
        closeCurrentSegment(); // ensure all data flushed
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            Log.w(TAG, "No segments to save");
            return false;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long nowMs = System.currentTimeMillis();
        long keepMs = (long) seconds * 1000L;
        ArrayList<File> toUse = new ArrayList<>();
        for (File f : files) {
            if (nowMs - f.lastModified() <= keepMs) {
                toUse.add(f);
            }
        }
        if (toUse.isEmpty()) {
            // fallback: pick the last N seconds by file count (approx)
            int want = Math.max(1, seconds / Math.max(1, segmentDurationMs / 1000));
            for (int i = Math.max(0, files.length - want); i < files.length; i++) {
                toUse.add(files[i]);
            }
        }
        if (toUse.isEmpty()) {
            Log.w(TAG, "Still no segments to save");
            return false;
        }

        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat);
            muxer.start();
            for (File seg : toUse) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(seg)));
                try {
                    while (in.available() > 0) {
                        long ptsUs = in.readLong();
                        int flags = in.readInt();
                        int size = in.readInt();
                        byte[] data = new byte[size];
                        int read = in.read(data);
                        if (read != size) {
                            Log.w(TAG, "Segment read size mismatch");
                            break;
                        }
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        info.presentationTimeUs = ptsUs;
                        info.flags = flags;
                        info.size = size;
                        ByteBuffer wrap = ByteBuffer.wrap(data);
                        muxer.writeSampleData(videoTrack, wrap, info);
                    }
                } finally {
                    try { in.close(); } catch (IOException ignored) {}
                }
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to mux replay buffer", e);
            return false;
        } finally {
            if (muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    public synchronized void shutdown() {
        closeCurrentSegment();
        lastFramePtsUs = -1;
        frameCount = 0;
    }
}
