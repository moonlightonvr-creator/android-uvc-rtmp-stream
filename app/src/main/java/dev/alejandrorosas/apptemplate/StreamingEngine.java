package dev.alejandrorosas.apptemplate;

/**
 * Disabled fallback engine for legacy RTMP/streaming code.
 *
 * This class is intentionally kept as a commented-out migration path
 * so the repository can switch back to streaming later without losing
 * the old architecture.
 */
public class StreamingEngine {

    // NOTE: Streaming is disabled for this optimized local recorder build.
    // The legacy RTMP and YouTube server logic is left here as commented
    // placeholders for future reactivation.

    /*
    private String streamUrl;
    private boolean isStreaming;

    public void configureStream(String endpoint) {
        this.streamUrl = endpoint;
    }

    public void startStream() {
        if (streamUrl == null || streamUrl.isEmpty()) {
            return;
        }
        isStreaming = true;
        // legacy streaming initialization
    }

    public void stopStream() {
        isStreaming = false;
        // legacy streaming teardown
    }

    public boolean isStreaming() {
        return isStreaming;
    }
    */
}
