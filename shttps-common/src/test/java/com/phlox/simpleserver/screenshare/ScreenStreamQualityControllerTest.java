package com.phlox.simpleserver.screenshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What one shared screen stream ends up carrying for the people watching it: who gets to decide,
 * how a struggling connection is given less, and that it is not then switched back and forth.
 */
public class ScreenStreamQualityControllerTest {

    private static final ScreenStreamQuality NO_CEILING = ScreenStreamQuality.HIGH;

    private static final class Source implements ScreenCaptureSource {
        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public boolean addStreamListener(ScreenStreamListener listener) {
            return true;
        }

        @Override
        public void removeStreamListener(ScreenStreamListener listener) {}

        @Override
        public void requestKeyFrame() {}
    }

    private final ScreenStreamQualityController controller = new ScreenStreamQualityController();
    private final Source source = new Source();

    @Test
    public void nobodyWatchingMeansNothingToDecide() {
        assertNull(controller.profileFor(source, NO_CEILING));
    }

    @Test
    public void autoStartsHighOnALocalNetworkAndLowEverywhereElse() {
        ScreenStreamQualityController.Viewer local = controller.register(source, true, null, 0, 0);
        assertEquals(ScreenStreamQuality.HIGH, controller.profileFor(source, NO_CEILING).quality);
        controller.unregister(local);

        controller.register(source, false, null, 0, 0);
        assertEquals(ScreenStreamQuality.LOW, controller.profileFor(source, NO_CEILING).quality,
                "a link nothing is known about gets what it always got");
    }

    @Test
    public void theServersCeilingWinsOverWhatAViewerAsksFor() {
        controller.register(source, true, ScreenStreamQuality.HIGH, 0, 0);
        ScreenStreamProfile profile = controller.profileFor(source, ScreenStreamQuality.MEDIUM);
        assertEquals(ScreenStreamQuality.MEDIUM, profile.quality);
        assertEquals(ScreenStreamQuality.MEDIUM.maxDimension(), profile.maxDimension);
    }

    /** One encoder for everyone: a stream one of them cannot receive is worse than a soft one. */
    @Test
    public void severalViewersShareTheLowestLevelAnyOfThemNeeds() {
        controller.register(source, true, ScreenStreamQuality.HIGH, 0, 0);
        ScreenStreamQualityController.Viewer modest =
                controller.register(source, true, ScreenStreamQuality.LOW, 0, 0);
        assertEquals(ScreenStreamQuality.LOW, controller.profileFor(source, NO_CEILING).quality);

        controller.unregister(modest);
        assertEquals(ScreenStreamQuality.HIGH, controller.profileFor(source, NO_CEILING).quality,
                "with the modest one gone the other is no longer held back");
    }

    @Test
    public void viewersOfAnotherSourceHaveNoSay() {
        controller.register(new Source(), true, ScreenStreamQuality.LOW, 0, 0);
        controller.register(source, true, ScreenStreamQuality.HIGH, 0, 0);
        assertEquals(ScreenStreamQuality.HIGH, controller.profileFor(source, NO_CEILING).quality);
    }

    @Test
    public void thePictureIsNoLargerThanTheLargestWindow() {
        controller.register(source, true, ScreenStreamQuality.HIGH, 1300, 0);
        assertEquals(1600, controller.profileFor(source, NO_CEILING).maxDimension,
                "rounded up to a step, so that resizing a window does not restart the encoder");

        controller.register(source, true, ScreenStreamQuality.HIGH, 1900, 0);
        assertEquals(1920, controller.profileFor(source, NO_CEILING).maxDimension,
                "the larger window decides: nobody is short-changed");
    }

    @Test
    public void aViewerThatDoesNotSayHowLargeItIsGetsTheFullLevel() {
        controller.register(source, true, ScreenStreamQuality.HIGH, 800, 0);
        controller.register(source, true, ScreenStreamQuality.HIGH, 0, 0);
        assertEquals(ScreenStreamQuality.HIGH.maxDimension(),
                controller.profileFor(source, NO_CEILING).maxDimension);
    }

    @Test
    public void aWindowLargerThanTheLevelDoesNotRaiseIt() {
        controller.register(source, true, ScreenStreamQuality.LOW, 3840, 0);
        assertEquals(ScreenStreamQuality.LOW.maxDimension(),
                controller.profileFor(source, NO_CEILING).maxDimension);
    }

    @Test
    public void fallingBehindRepeatedlyCostsALevelButOnceDoesNot() {
        ScreenStreamQualityController.Viewer viewer = controller.register(source, true, null, 0, 0);

        assertFalse(controller.onFellBehind(viewer, 1_000), "one hiccup is not a slow link");
        assertEquals(ScreenStreamQuality.HIGH, controller.profileFor(source, NO_CEILING).quality);

        assertTrue(controller.onFellBehind(viewer, 3_000));
        assertEquals(ScreenStreamQuality.MEDIUM, controller.profileFor(source, NO_CEILING).quality);
    }

    @Test
    public void hiccupsFarApartAreNotCountedTogether() {
        ScreenStreamQualityController.Viewer viewer = controller.register(source, true, null, 0, 0);
        assertFalse(controller.onFellBehind(viewer, 1_000));
        assertFalse(controller.onFellBehind(viewer,
                1_000 + ScreenStreamQualityController.FELL_BEHIND_WINDOW_MILLIS + 1));
        assertEquals(ScreenStreamQuality.HIGH, controller.profileFor(source, NO_CEILING).quality);
    }

    @Test
    public void aFixedLevelIsNotLoweredBehindTheViewersBack() {
        ScreenStreamQualityController.Viewer viewer =
                controller.register(source, true, ScreenStreamQuality.HIGH, 0, 0);
        controller.onFellBehind(viewer, 1_000);
        assertFalse(controller.onFellBehind(viewer, 2_000), "they asked for this level by name");
        assertEquals(ScreenStreamQuality.HIGH, controller.profileFor(source, NO_CEILING).quality);
    }

    /**
     * The part that keeps a link sitting between two levels from being switched back and forth for
     * as long as it stays open: every step up that had to be taken back makes the next one wait
     * twice as long.
     */
    @Test
    public void theWayBackUpGetsLongerEveryTimeItFails() {
        ScreenStreamQualityController.Viewer viewer = controller.register(source, true, null, 0, 0);
        long now = 0;
        controller.onFellBehind(viewer, now += 1_000);
        assertTrue(controller.onFellBehind(viewer, now += 1_000));
        long firstDelay = ScreenStreamQualityController.UPGRADE_DELAY_MIN_MILLIS * 2;

        assertFalse(controller.onTick(viewer, now + firstDelay - 1), "not trusted yet");
        assertTrue(controller.onTick(viewer, now += firstDelay), "quiet for long enough");
        assertEquals(ScreenStreamQuality.HIGH, controller.profileFor(source, NO_CEILING).quality);

        //and it was a mistake again
        controller.onFellBehind(viewer, now += 1_000);
        assertTrue(controller.onFellBehind(viewer, now += 1_000));
        assertFalse(controller.onTick(viewer, now + firstDelay), "the same wait is no longer enough");
        assertTrue(controller.onTick(viewer, now + firstDelay * 2));
    }

    @Test
    public void autoNeverGoesBelowTheLowestLevelOrAboveWhatTheNetworkWarrants() {
        ScreenStreamQualityController.Viewer remote = controller.register(source, false, null, 0, 0);
        controller.onFellBehind(remote, 1_000);
        assertFalse(controller.onFellBehind(remote, 2_000), "there is nothing below low");

        long later = 2_000 + ScreenStreamQualityController.UPGRADE_DELAY_MAX_MILLIS;
        assertTrue(controller.onTick(remote, later));
        assertEquals(ScreenStreamQuality.MEDIUM, controller.profileFor(source, NO_CEILING).quality);
        assertFalse(controller.onTick(remote, later + ScreenStreamQualityController.UPGRADE_DELAY_MAX_MILLIS),
                "the best level is only tried unasked on a local network");
    }

    @Test
    public void goingBackToAutoStartsTheJudgementOver() {
        ScreenStreamQualityController.Viewer viewer =
                controller.register(source, true, ScreenStreamQuality.LOW, 0, 0);
        controller.request(viewer, null, 0, 5_000);
        assertEquals(ScreenStreamQuality.HIGH, controller.profileFor(source, NO_CEILING).quality);
        assertEquals("auto", controller.requestedName(viewer));

        controller.request(viewer, ScreenStreamQuality.MEDIUM, 1000, 6_000);
        ScreenStreamProfile profile = controller.profileFor(source, NO_CEILING);
        assertEquals(ScreenStreamQuality.MEDIUM, profile.quality);
        assertEquals(1280, profile.maxDimension);
    }

    @Test
    public void addressesOnThisMachineOrItsNetworkCountAsLocal() {
        assertTrue(ScreenStreamWebSocketHandler.isLocalNetwork("127.0.0.1"));
        assertTrue(ScreenStreamWebSocketHandler.isLocalNetwork("192.168.1.20"));
        assertTrue(ScreenStreamWebSocketHandler.isLocalNetwork("10.0.0.7"));
        assertTrue(ScreenStreamWebSocketHandler.isLocalNetwork("::1"));
        assertTrue(ScreenStreamWebSocketHandler.isLocalNetwork("fe80::1"));
        assertTrue(ScreenStreamWebSocketHandler.isLocalNetwork("fd12:3456::1"));
        assertFalse(ScreenStreamWebSocketHandler.isLocalNetwork("8.8.8.8"));
        assertFalse(ScreenStreamWebSocketHandler.isLocalNetwork("2001:4860:4860::8888"));
        //never handed to a resolver: this runs on a connection's own thread
        assertFalse(ScreenStreamWebSocketHandler.isLocalNetwork("localhost"));
        assertFalse(ScreenStreamWebSocketHandler.isLocalNetwork(null));
        assertFalse(ScreenStreamWebSocketHandler.isLocalNetwork("test"));
    }
}
