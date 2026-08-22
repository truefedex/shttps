package com.phlox.simpleserver.remotecontrol;

import com.phlox.server.utils.SHTTPSLoggerProxy;

import java.util.ServiceLoader;

/**
 * Finds the {@link RemoteInputProvider} this build ships, if it ships one.
 * <p>
 * A host asks {@link #get()} twice: once to decide whether to offer remote control in its UI at
 * all, and once per message while a client is connected, to get the target to carry the command
 * out. A build with no provider on its classpath gets null every time and never advertises the
 * feature.
 */
public final class RemoteInputProviders {
    private static final SHTTPSLoggerProxy.Logger logger =
            SHTTPSLoggerProxy.getLogger(RemoteInputProviders.class);

    //deliberately not the thread context classloader: the provider and the native libraries it
    //loads have to be found by one and the same loader, following ScreenCaptureProviders
    private static final ClassLoader CLASS_LOADER = RemoteInputProviders.class.getClassLoader();

    private static boolean resolved = false;
    private static RemoteInputProvider provider = null;

    private RemoteInputProviders() {}

    /**
     * @return the first provider that reports itself supported, or null if this build has none.
     * Resolved once and remembered - this is consulted per input message, and walking the service
     * loader for every mouse move is not something to do.
     */
    public static synchronized RemoteInputProvider get() {
        if (resolved) {
            return provider;
        }
        resolved = true;
        try {
            for (RemoteInputProvider candidate :
                    ServiceLoader.load(RemoteInputProvider.class, CLASS_LOADER)) {
                try {
                    if (candidate.isSupported()) {
                        logger.i("Remote input provider: " + candidate.getClass().getName());
                        provider = candidate;
                        return provider;
                    }
                    logger.d("Remote input provider " + candidate.getClass().getName() +
                            " reports it is not supported here");
                } catch (Throwable e) {
                    //a provider that blows up while answering is one we can not use, but it must
                    //not take the server down with it - or hide a second provider that would work
                    logger.stackTrace(e);
                }
            }
        } catch (Throwable e) {
            //a broken services file, or a provider whose class fails to link
            logger.stackTrace(e);
        }
        return provider;
    }

    /** Forgets the resolved provider. For tests. */
    static synchronized void reset() {
        resolved = false;
        provider = null;
    }
}
