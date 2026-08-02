package dev.alejandrorosas.apptemplate;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private static final int REQUEST_CAMERA_PERMISSION = 101;
    private static final int VIDEO_WIDTH = 720;
    private static final int VIDEO_HEIGHT = 1280;
    private static final int FRAME_RATE = 60;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int MIN_BITRATE_KBPS = 1000;
    private static final int MAX_BITRATE_KBPS = 12000;
    private static final long MAX_REPLAY_US = 120_000_000L;

    private TextureView captureSurface;
    private SeekBar bitrateSeekBar;
    private TextView bitrateValue;
    private TextView statusText;
    private Button buttonStartRecording;
    private Button buttonStopRecording;
    private Button buttonRecordLast30s;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface previewSurface;
    private MediaCodec videoEncoder;
    private Surface encoderSurface;
    private MediaMuxer mediaMuxer;
    private MediaFormat outputFormat;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread encoderThread;
    private Handler encoderHandler;

    private final AtomicBoolean recordingActive = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<EncodedFrame> replayQueue = new ConcurrentLinkedQueue<>();
    private long lastFrameTimeUs = 0;
    private int currentBitrateKbps = 4000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        captureSurface = findViewById(R.id.captureSurface);
        bitrateSeekBar = findViewById(R.id.bitrateSeekBar);
        bitrateValue = findViewById(R.id.bitrateValue);
        statusText = findViewById(R.id.statusText);
        buttonStartRecording = findViewById(R.id.buttonStartRecording);
        buttonStopRecording = findViewById(R.id.buttonStopRecording);
        buttonRecordLast30s = findViewById(R.id.buttonRecordLast30s);

        captureSurface.setSurfaceTextureListener(this);

        bitrateSeekBar.setMax(11);
        bitrateSeekBar.setProgress(3);
        bitrateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentBitrateKbps = (progress + 1) * 1000;
                updateBitrateLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (recordingActive.get()) {
                    updateStatus("Bitrate will apply on next recording start");
                }
            }
        });

        buttonStartRecording.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRecording();
            }
        });

        buttonStopRecording.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopRecording();
            }
        });

        buttonRecordLast30s.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveReplayClip(30);
            }
        });

        updateBitrateLabel();
        configureButtonStates();
        updateStatus("Ready to capture vertical shorts");
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThreads();
        if (captureSurface.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThreads();
        super.onPause();
    }

    private void startBackgroundThreads() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        encoderThread = new HandlerThread("EncoderThread");
        encoderThread.start();
        encoderHandler = new Handler(encoderThread.getLooper());
    }

    private void stopBackgroundThreads() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException ignored) {
            }
            cameraThread = null;
            cameraHandler = null;
        }
        if (encoderThread != null) {
            encoderThread.quitSafely();
            try {
                encoderThread.join();
            } catch (InterruptedException ignored) {
            }
            encoderThread = null;
            encoderHandler = null;
        }
    }

    private void updateBitrateLabel() {
        bitrateValue.setText(currentBitrateKbps + " Kbps");
    }

    private void updateStatus(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(message);
            }
        });
    }

    private void configureButtonStates() {
        buttonStartRecording.setEnabled(!recordingActive.get());
        buttonStopRecording.setEnabled(recordingActive.get());
        buttonRecordLast30s.setEnabled(!replayQueue.isEmpty());
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "Camera permission is required for recording.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openCamera() {
        if (!hasCameraPermission()) {
            requestCameraPermission();
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            updateStatus("Camera manager unavailable");
            return;
        }

        try {
            String cameraId = chooseBackFacingCamera(manager);
            if (cameraId == null) {
                updateStatus("No suitable camera found");
                return;
            }

            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            updateStatus("Unable to access camera");
        }
    }

    private String chooseBackFacingCamera(CameraManager manager) throws CameraAccessException {
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        return null;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (captureSurface.isAvailable()) {
                startPreviewSession(false);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            updateStatus("Camera error " + error);
        }
    };

    private void startPreviewSession(boolean useEncoderSurface) {
        if (cameraDevice == null || previewSurface == null) {
            return;
        }

        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }

        try {
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            if (useEncoderSurface && encoderSurface != null) {
                surfaces.add(encoderSurface);
            }

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        requestBuilder.addTarget(previewSurface);
                        if (useEncoderSurface && encoderSurface != null) {
                            requestBuilder.addTarget(encoderSurface);
                        }
                        requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(FRAME_RATE, FRAME_RATE));
                        captureSession.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                        updateStatus(useEncoderSurface ? "Recording preview active" : "Preview active");
                    } catch (CameraAccessException e) {
                        updateStatus("Capture session failed");
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    updateStatus("Capture session failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            updateStatus("Unable to start preview");
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        stopRecording();
    }

    private void prepareEncoder() {
        if (videoEncoder != null) {
            return;
        }

        try {
            String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
            videoEncoder = MediaCodec.createEncoderByType(mimeType);
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, VIDEO_WIDTH, VIDEO_HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, currentBitrateKbps * 1000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = videoEncoder.createInputSurface();
            videoEncoder.setCallback(encoderCallback, encoderHandler);
            videoEncoder.start();
        } catch (IOException e) {
            updateStatus("Encoder creation failed");
            videoEncoder = null;
            encoderSurface = null;
        }
    }

    private void prepareMuxer() {
        if (mediaMuxer != null || outputFormat == null) {
            return;
        }

        try {
            File videosDir = new File(getExternalFilesDir(null), "videos");
            if (!videosDir.exists()) {
                videosDir.mkdirs();
            }
            String outputPath = new File(videosDir, "recording_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
            mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            videoTrackIndex = mediaMuxer.addTrack(outputFormat);
            mediaMuxer.start();
            muxerStarted = true;
            updateStatus("Recording to " + outputPath);
        } catch (IOException e) {
            updateStatus("Muxer initialization failed");
        }
    }

    private void startRecording() {
        if (!hasCameraPermission()) {
            requestCameraPermission();
            return;
        }
        if (cameraDevice == null) {
            updateStatus("Camera not ready");
            return;
        }
        if (recordingActive.get()) {
            return;
        }

        recordingActive.set(true);
        prepareEncoder();
        if (outputFormat != null) {
            prepareMuxer();
        }
        startPreviewSession(true);
        configureButtonStates();
        updateStatus("Recording live");
    }

    private void stopRecording() {
        if (!recordingActive.get()) {
            return;
        }

        recordingActive.set(false);
        configureButtonStates();
        startPreviewSession(false);

        if (mediaMuxer != null) {
            try {
                if (muxerStarted) {
                    mediaMuxer.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            mediaMuxer.release();
            mediaMuxer = null;
        }
        muxerStarted = false;
        videoTrackIndex = -1;
        releaseEncoder();
        updateStatus("Recording stopped");
    }

    private void releaseEncoder() {
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
            } catch (IllegalStateException ignored) {
            }
            videoEncoder.release();
            videoEncoder = null;
        }
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        outputFormat = null;
    }

    private void saveReplayClip(final int seconds) {
        if (outputFormat == null) {
            Toast.makeText(this, "Replay buffer not ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        final long cutoffUs = lastFrameTimeUs - seconds * 1_000_000L;
        final List<EncodedFrame> frames = new ArrayList<>();
        for (EncodedFrame frame : replayQueue) {
            if (frame.presentationTimeUs >= cutoffUs) {
                frames.add(frame);
            }
        }

        if (frames.isEmpty()) {
            Toast.makeText(this, "No recent clip available yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                File videosDir = new File(getExternalFilesDir(null), "videos");
                if (!videosDir.exists()) {
                    videosDir.mkdirs();
                }
                String clipPath = new File(videosDir, "clip_" + seconds + "s_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
                MediaMuxer replayMuxer = null;
                try {
                    replayMuxer = new MediaMuxer(clipPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    int trackIndex = replayMuxer.addTrack(outputFormat);
                    replayMuxer.start();
                    long baseTime = frames.get(0).presentationTimeUs;
                    for (EncodedFrame frame : frames) {
                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        bufferInfo.set(0, frame.data.length, frame.presentationTimeUs - baseTime, frame.flags);
                        ByteBuffer buffer = ByteBuffer.wrap(frame.data);
                        replayMuxer.writeSampleData(trackIndex, buffer, bufferInfo);
                    }
                    final String message = "Saved last " + seconds + "s clip";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateStatus(message);
                            configureButtonStates();
                        }
                    });
                } catch (IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateStatus("Replay save failed");
                        }
                    });
                } finally {
                    if (replayMuxer != null) {
                        try {
                            replayMuxer.stop();
                        } catch (IllegalStateException ignored) {
                        }
                        replayMuxer.release();
                    }
                }
            }
        }).start();
    }

    private void storeReplayFrame(byte[] bytes, long presentationTimeUs, int flags) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        replayQueue.add(new EncodedFrame(bytes, presentationTimeUs, flags));
        lastFrameTimeUs = presentationTimeUs;
        while (!replayQueue.isEmpty() && lastFrameTimeUs - replayQueue.peek().presentationTimeUs > MAX_REPLAY_US) {
            replayQueue.poll();
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                configureButtonStates();
            }
        });
    }

    private final MediaCodec.Callback encoderCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            // Not used for surface input
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codec.releaseOutputBuffer(index, false);
                return;
            }

            ByteBuffer outputBuffer = codec.getOutputBuffer(index);
            if (outputBuffer != null && info.size > 0) {
                ByteBuffer copyBuffer = outputBuffer.duplicate();
                copyBuffer.position(info.offset);
                copyBuffer.limit(info.offset + info.size);
                byte[] data = new byte[info.size];
                copyBuffer.get(data);
                storeReplayFrame(data, info.presentationTimeUs, info.flags);

                if (recordingActive.get() && muxerStarted && mediaMuxer != null) {
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, info);
                }
            }

            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            updateStatus("Encoder error");
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            outputFormat = format;
            if (recordingActive.get()) {
                prepareMuxer();
            }
        }
    };

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        surfaceTexture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT);
        previewSurface = new Surface(surfaceTexture);
        configurePreviewTransform();
        openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        configurePreviewTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
    }

    private void configurePreviewTransform() {
        if (!captureSurface.isAvailable()) {
            return;
        }
        float viewWidth = captureSurface.getWidth();
        float viewHeight = captureSurface.getHeight();
        if (viewWidth == 0 || viewHeight == 0) {
            return;
        }
        Matrix matrix = new Matrix();
        float scale = Math.max(viewWidth / VIDEO_WIDTH, viewHeight / VIDEO_HEIGHT);
        float dx = (viewWidth - VIDEO_WIDTH * scale) / 2f;
        float dy = (viewHeight - VIDEO_HEIGHT * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        captureSurface.setTransform(matrix);
    }

    private static class EncodedFrame {
        final byte[] data;
        final long presentationTimeUs;
        final int flags;

        EncodedFrame(byte[] data, long presentationTimeUs, int flags) {
            this.data = Arrays.copyOf(data, data.length);
            this.presentationTimeUs = presentationTimeUs;
            this.flags = flags;
        }
    }
}
