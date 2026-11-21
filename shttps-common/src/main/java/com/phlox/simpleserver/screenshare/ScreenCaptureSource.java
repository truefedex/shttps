package com.phlox.simpleserver.screenshare;

/**
 * A live capture of the screen, encoded as fragmented MP4 and handed to subscribers.
 * <p>
 * This is the seam between {@link ScreenStreamWebSocketHandler}, which is the same on every
 * platform, and the capture pipeline, which is not: Android mirrors the display through
 * {@code MediaProjection} into a {@code MediaCodec} surface, while a desktop build grabs the
 * framebuffer and encodes it itself. Both end up producing the same segments through
 * {@link FragmentedMp4Muxer}, so everything above this interface can be shared.
 * <p>
 * Implementations decide for themselves when capture actually runs. A platform whose capture needs
 * the user's consent may well keep a session alive with no subscribers, while one that has nothing
 * to ask permission for is free to start on the first subscriber and stop on the last. All that is
 * promised here is that {@link #isActive()} says whether {@link #addStreamListener} can succeed.
 */
public interface ScreenCaptureSource {
    /**
     * @return whether a capture session exists at all. A false answer is why a client is turned
     * away with {@code POLICY_VIOLATION} rather than left waiting for a picture.
     */
    boolean isActive();

    /**
     * Subscribes to the stream. The listener receives an initialization segment first, then media
     * segments starting at a key frame.
     * <p>
     * <b>{@link ScreenStreamListener#onInitSegment} must have been called before this returns.</b>
     * Callers queue their own messages to the client straight afterwards, and those have to land
     * behind the init segment: a client is told what it may do once it has a picture, not before.
     * An implementation that starts its encoder on demand therefore waits for it here rather than
     * returning and delivering later.
     *
     * @return false if the capture is not active or could not be started, in which case nothing
     * was subscribed
     */
    boolean addStreamListener(ScreenStreamListener listener);

    /** Unsubscribes a listener. Harmless if it was never subscribed or has already been dropped. */
    void removeStreamListener(ScreenStreamListener listener);

    /**
     * Asks the encoder for a key frame as soon as it can manage one. Used when a client asks for a
     * fresh decoding start point, and when a client that fell behind had its backlog dropped and
     * needs somewhere to resume.
     * <p>
     * Best effort: a platform that cannot force one is free to do nothing, at the cost of that
     * client waiting for the next scheduled key frame.
     */
    void requestKeyFrame();

    /**
     * What kind of machine is being shared, told to the browser in the {@code init} frame so its UI
     * can offer the right controls - a phone has a Back button, a PC has a right mouse button.
     *
     * @return {@code "android"}, {@code "desktop"}, or null to leave the client guessing
     */
    default String getPlatform() {
        return null;
    }
}
