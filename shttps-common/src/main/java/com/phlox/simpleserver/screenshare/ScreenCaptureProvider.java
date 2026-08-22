package com.phlox.simpleserver.screenshare;

import com.phlox.simpleserver.SHTTPSConfig;

/**
 * Supplies a {@link ScreenCaptureSource} for the machine the server is running on.
 * <p>
 * Found with {@link java.util.ServiceLoader}, so a host application can offer screen sharing
 * without compiling against any capture code, and a build that ships none simply never advertises
 * the feature. This is how the closed PLUS desktop build adds Windows screen capture to an open
 * source application that knows nothing about it.
 * <p>
 * To provide one, put the implementation on the classpath together with a
 * {@code META-INF/services/com.phlox.simpleserver.screenshare.ScreenCaptureProvider} file naming
 * it. See {@link ScreenCaptureProviders} for how the host picks one up.
 * <p>
 * Platforms that own their capture pipeline outright - Android builds it inside the foreground
 * service that holds the user's consent - are free to ignore this and register the handler
 * directly from {@code SHTTPSApp.Callback.onRouterPrepared}.
 */
public interface ScreenCaptureProvider {
    /**
     * @return whether this provider can capture on the machine it finds itself on. Answering false
     * hides screen sharing from the UI entirely, so that no build advertises an endpoint it cannot
     * serve. Must not throw: a provider whose native library is missing reports false.
     */
    boolean isSupported();

    /**
     * The capture source, created on first use and reused afterwards. Called when the server starts
     * and screen sharing is switched on.
     *
     * @return the source, or null if capture could not be set up after all
     */
    ScreenCaptureSource getOrCreate(SHTTPSConfig config);

    /**
     * Releases everything the provider holds. Called when the server stops, so that a stopped
     * server is not still holding the framebuffer open.
     */
    void shutdown();
}
