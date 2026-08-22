package com.phlox.simpleserver.remotecontrol;

import com.phlox.simpleserver.SHTTPSConfig;

/**
 * How a build that can inject input on its platform offers that to the app, found with
 * {@link java.util.ServiceLoader}. The open source build ships none, which is what makes remote
 * control simply not exist there rather than exist and fail.
 * <p>
 * Deliberately the twin of {@code ScreenCaptureProvider} rather than a member of it: watching a
 * screen and driving it are separate permissions, separate settings and, on Android, separate
 * system services. A host may well have one and not the other.
 */
public interface RemoteInputProvider {

    /**
     * Whether input can be injected on the machine we are actually running on.
     * <p>
     * Must be cheap and must not throw. This is called on every launch while the settings screen
     * composes, so it is an operating system check and nothing more - in particular it must not
     * load any native library, which is what the target itself does lazily on first use.
     */
    boolean isSupported();

    /**
     * @return the target to hand to the stream handler, or null if input can not be injected after
     * all. Called whenever remote control is switched on, so implementations are expected to
     * return the same instance rather than build a new one each time.
     */
    RemoteInputTarget getOrCreate(SHTTPSConfig config);

    /**
     * Releases anything currently held and stops whatever the target was running. Called when the
     * server stops and when the user switches remote control off, so that a button or a modifier
     * pressed at that moment is let go immediately.
     */
    void shutdown();
}
