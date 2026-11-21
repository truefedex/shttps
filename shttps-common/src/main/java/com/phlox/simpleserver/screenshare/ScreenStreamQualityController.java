package com.phlox.simpleserver.screenshare;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what one shared screen stream should carry, given everyone who is watching it.
 * <p>
 * Each viewer either names a level or leaves it to us ("auto"), and says how large its window is.
 * There is only one encoder, so the answers have to be folded into one:
 * <ul>
 *     <li>the <b>lowest</b> level anyone needs - a stream one viewer's link cannot carry is a
 *     broken stream for that viewer, while a soft picture is merely soft for the others;</li>
 *     <li>the <b>largest</b> window anyone has - nobody is short-changed, and nobody is sent
 *     pixels that no window is there to show.</li>
 * </ul>
 * For a viewer on "auto" the level follows how its connection is doing. It starts high on a local
 * network and at the old default everywhere else, steps down when the connection repeatedly fails
 * to keep up, and steps back up only after a quiet period that gets longer every time a step up
 * turned out to be a mistake - without that last part a link sitting between two levels would be
 * switched back and forth for as long as it stays open, restarting the encoder each time.
 * <p>
 * Pure bookkeeping: no threads, no clock of its own, nothing of the transport. Whoever owns it
 * passes in the time and carries out what {@link #profileFor} says. Thread safe.
 */
final class ScreenStreamQualityController {
    /** Falling behind this many times within {@link #FELL_BEHIND_WINDOW_MILLIS} costs a level. */
    static final int FELL_BEHIND_LIMIT = 2;
    static final long FELL_BEHIND_WINDOW_MILLIS = 10_000;
    /** How long a connection has to behave before it is trusted with the next level up. */
    static final long UPGRADE_DELAY_MIN_MILLIS = 30_000;
    static final long UPGRADE_DELAY_MAX_MILLIS = 5 * 60_000;

    /**
     * Windows are rounded up to one of these before they decide the size of the picture. Dragging
     * the edge of a browser window would otherwise restart the encoder for every pixel of it.
     */
    private static final int[] VIEWPORT_STEPS = {640, 960, 1280, 1600, 1920, 2560, 3840};

    /** One connection's say in the matter. Fields are guarded by the controller. */
    static final class Viewer {
        final ScreenCaptureSource source;
        final boolean localNetwork;
        /** The level this viewer asked for, or null for "auto". */
        @Nullable ScreenStreamQuality requested;
        /** Where "auto" currently stands for this viewer. */
        @NotNull ScreenStreamQuality autoLevel;
        /** Longer side of the viewer's window in device pixels, 0 when it did not say. */
        int viewport;
        /** Whether this viewer draws the mouse pointer itself from the positions it is sent. */
        boolean overlaysCursor;

        private int fellBehindCount;
        private long fellBehindWindowStart;
        /** When this viewer last fell behind or changed level; an upgrade counts from here. */
        private long quietSince;
        private long upgradeDelay = UPGRADE_DELAY_MIN_MILLIS;

        private Viewer(ScreenCaptureSource source, boolean localNetwork,
                       @Nullable ScreenStreamQuality requested, int viewport, long now) {
            this.source = source;
            this.localNetwork = localNetwork;
            this.requested = requested;
            this.viewport = Math.max(0, viewport);
            this.autoLevel = localNetwork ? ScreenStreamQuality.HIGH : ScreenStreamQuality.LOW;
            this.quietSince = now;
        }

        ScreenStreamQuality effective() {
            return requested != null ? requested : autoLevel;
        }
    }

    private final List<Viewer> viewers = new ArrayList<>();

    synchronized Viewer register(ScreenCaptureSource source, boolean localNetwork,
                                 @Nullable ScreenStreamQuality requested, int viewport, long now) {
        return register(source, localNetwork, requested, viewport, false, now);
    }

    synchronized Viewer register(ScreenCaptureSource source, boolean localNetwork,
                                 @Nullable ScreenStreamQuality requested, int viewport,
                                 boolean overlaysCursor, long now) {
        Viewer viewer = new Viewer(source, localNetwork, requested, viewport, now);
        viewer.overlaysCursor = overlaysCursor;
        viewers.add(viewer);
        return viewer;
    }

    synchronized void unregister(Viewer viewer) {
        viewers.remove(viewer);
    }

    /** The viewer changed its mind, or its window. */
    synchronized void request(Viewer viewer, @Nullable ScreenStreamQuality requested, int viewport,
                              long now) {
        if (requested == null && viewer.requested != null) {
            //back to auto: judged afresh, the fixed level it sat on says nothing about its link
            viewer.autoLevel = viewer.localNetwork ? ScreenStreamQuality.HIGH : ScreenStreamQuality.LOW;
            viewer.fellBehindCount = 0;
            viewer.quietSince = now;
            viewer.upgradeDelay = UPGRADE_DELAY_MIN_MILLIS;
        }
        viewer.requested = requested;
        if (viewport > 0) {
            viewer.viewport = viewport;
        }
    }

    /**
     * The viewer's send queue overflowed and its backlog was dropped.
     *
     * @return whether that changed the level "auto" stands at for it
     */
    synchronized boolean onFellBehind(Viewer viewer, long now) {
        viewer.quietSince = now;
        if (viewer.fellBehindCount == 0 || now - viewer.fellBehindWindowStart > FELL_BEHIND_WINDOW_MILLIS) {
            viewer.fellBehindWindowStart = now;
            viewer.fellBehindCount = 0;
        }
        viewer.fellBehindCount++;
        if (viewer.fellBehindCount < FELL_BEHIND_LIMIT) {
            return false;
        }
        viewer.fellBehindCount = 0;
        //whatever level it was at was too much, so the way back up is made longer - also for a
        //viewer on a fixed level, whose auto level is what it would return to
        viewer.upgradeDelay = Math.min(UPGRADE_DELAY_MAX_MILLIS, viewer.upgradeDelay * 2);
        ScreenStreamQuality lower = viewer.autoLevel.lower();
        if (lower == viewer.autoLevel) {
            return false;
        }
        viewer.autoLevel = lower;
        return viewer.requested == null;
    }

    /**
     * Called now and then while the viewer is being served.
     *
     * @return whether the viewer was just moved up a level
     */
    synchronized boolean onTick(Viewer viewer, long now) {
        if (viewer.requested != null || now - viewer.quietSince < viewer.upgradeDelay) {
            return false;
        }
        ScreenStreamQuality ceiling =
                viewer.localNetwork ? ScreenStreamQuality.HIGH : ScreenStreamQuality.MEDIUM;
        if (viewer.autoLevel.ordinal() >= ceiling.ordinal()) {
            return false;
        }
        viewer.autoLevel = viewer.autoLevel.higher();
        viewer.quietSince = now;
        return true;
    }

    /**
     * @param ceiling the most the person running the server allows, whatever viewers ask for
     * @return what the stream of that source should carry for everyone watching it, or null when
     * nobody is
     */
    synchronized @Nullable ScreenStreamProfile profileFor(ScreenCaptureSource source,
                                                          ScreenStreamQuality ceiling) {
        ScreenStreamQuality quality = null;
        int viewport = 0;
        boolean unknownViewport = false;
        //one viewer that cannot draw the pointer itself keeps it in the picture for all of them:
        //the others then see it a little late, that one would otherwise not see it at all
        boolean cursorInPicture = false;
        for (Viewer viewer : viewers) {
            if (viewer.source != source) {
                continue;
            }
            cursorInPicture |= !viewer.overlaysCursor;
            ScreenStreamQuality wanted = viewer.effective();
            quality = quality == null ? wanted : ScreenStreamQuality.min(quality, wanted);
            if (viewer.viewport <= 0) {
                unknownViewport = true;
            } else {
                viewport = Math.max(viewport, viewer.viewport);
            }
        }
        if (quality == null) {
            return null;
        }
        quality = ScreenStreamQuality.min(quality, ceiling);
        int dimension = quality.maxDimension();
        if (!unknownViewport && viewport > 0) {
            dimension = Math.min(dimension, viewportStep(viewport));
        }
        return new ScreenStreamProfile(quality, dimension, cursorInPicture);
    }

    synchronized List<Viewer> viewersOf(ScreenCaptureSource source) {
        List<Viewer> result = new ArrayList<>();
        for (Viewer viewer : viewers) {
            if (viewer.source == source) {
                result.add(viewer);
            }
        }
        return result;
    }

    synchronized @Nullable String requestedName(Viewer viewer) {
        return viewer.requested == null ? "auto" : viewer.requested.wireName();
    }

    static int viewportStep(int viewport) {
        for (int step : VIEWPORT_STEPS) {
            if (viewport <= step) {
                return step;
            }
        }
        return VIEWPORT_STEPS[VIEWPORT_STEPS.length - 1];
    }
}
