package com.phlox.simpleserver.screenshare;

/**
 * Receives the fragmented MP4 stream produced by a {@link ScreenCaptureSource}.
 * <p>
 * The segments are meant to be pushed to a browser as-is and appended to a Media Source Extensions
 * {@code SourceBuffer}: first the initialization segment, then media segments in the order they
 * arrive.
 * <p>
 * All methods are called on the encoder's background thread, one at a time. Implementations must
 * not block - hand the data over to a send queue and return.
 */
public interface ScreenStreamListener {
    /**
     * fMP4 initialization segment ({@code ftyp} + {@code moov}). Delivered once right after
     * subscribing (or as soon as the encoder is ready), and before any media segment. Also
     * re-delivered if the stream is reinitialized.
     *
     * @param codec MSE codec string of the stream, e.g. {@code avc1.42c01f}
     */
    void onInitSegment(byte[] initSegment, String codec, int width, int height);

    /**
     * One fMP4 media segment ({@code moof} + {@code mdat}) holding exactly one video frame. The
     * first segment a listener receives is always a key frame.
     * <p>
     * The array is shared between all listeners and must not be modified.
     */
    void onMediaSegment(byte[] segment, boolean keyFrame, long presentationTimeUs);

    /**
     * The capture session ended (the user revoked it, the server is stopping, or the encoder
     * failed). No further callbacks will follow and the listener is already unsubscribed.
     */
    void onStreamStopped();
}
