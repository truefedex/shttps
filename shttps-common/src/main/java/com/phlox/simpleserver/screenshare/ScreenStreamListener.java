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

    /**
     * Where the mouse pointer is and what it looks like, for a stream whose profile keeps it out of
     * the picture ({@link ScreenStreamProfile#cursorInPicture} false). Called when either changes,
     * and now and then regardless, so that a listener that joined late learns it too.
     * <p>
     * {@code default} because most streams never call it: Android has no pointer, and a pipeline
     * that draws the pointer into its frames has nothing to report.
     *
     * @param x       across the captured picture, 0 at the left edge and 1 at the right; outside
     *                that range while the pointer is on another display
     * @param y       down the captured picture, 0 at the top and 1 at the bottom
     * @param shape   a CSS cursor keyword - {@code default}, {@code text}, {@code pointer}... -
     *                since a browser is what ends up drawing it
     * @param visible false while the machine itself is hiding its pointer
     */
    default void onCursor(float x, float y, String shape, boolean visible) {
    }
}
