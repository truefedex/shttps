package com.phlox.simpleserver.screenshare;

import com.phlox.server.utils.SHTTPSLoggerProxy;

import java.util.ServiceLoader;

/**
 * Finds the {@link ScreenCaptureProvider} this build ships, if it ships one.
 * <p>
 * A host asks {@link #get()} twice: once to decide whether to offer screen sharing in its UI at
 * all, and once when the server starts, to get the source to hand to
 * {@link ScreenStreamWebSocketHandler}. A build with no provider on its classpath gets null both
 * times and never advertises the feature.
 */
public final class ScreenCaptureProviders {
    private static final SHTTPSLoggerProxy.Logger logger =
            SHTTPSLoggerProxy.getLogger(ScreenCaptureProviders.class);

    //deliberately not the thread context classloader: the provider and the native libraries it
    //loads have to be found by one and the same loader, following DesktopExtensions
    private static final ClassLoader CLASS_LOADER = ScreenCaptureProviders.class.getClassLoader();

    private static boolean resolved = false;
    private static ScreenCaptureProvider provider = null;

    private ScreenCaptureProviders() {}

    /**
     * @return the first provider that reports itself supported, or null if this build has none.
     * Resolved once and remembered - loading a provider means loading native libraries, which is
     * not something to do per request.
     */
    public static synchronized ScreenCaptureProvider get() {
        if (resolved) {
            return provider;
        }
        resolved = true;
        try {
            for (ScreenCaptureProvider candidate :
                    ServiceLoader.load(ScreenCaptureProvider.class, CLASS_LOADER)) {
                try {
                    if (candidate.isSupported()) {
                        logger.i("Screen capture provider: " + candidate.getClass().getName());
                        provider = candidate;
                        return provider;
                    }
                    logger.d("Screen capture provider " + candidate.getClass().getName() +
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
