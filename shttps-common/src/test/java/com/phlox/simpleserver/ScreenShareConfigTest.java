package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.simpleserver.database.TestDBEnvironment;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Screen sharing is reached from a settings section rather than the main screen, which is the same
 * shape of setting that {@code ChannelsConfigTest} was written about: {@code screen_share} was in
 * fact one of the two keys that went missing from {@code serializeAll()} unnoticed. It is pinned
 * here so that it cannot go missing again, together with the monitor selection added alongside it.
 */
public class ScreenShareConfigTest {

    @Test
    public void defaultsAreTheDocumentedOnes() {
        SHTTPSConfig config = new TestDBEnvironment.Config();

        assertFalse(config.isScreenShareEnabled(),
                "sharing the screen must never be on until someone turns it on");
        assertEquals(SHTTPSConfig.SCREEN_MONITOR_PRIMARY, config.getScreenMonitor(),
                "with nothing chosen, the primary display is what gets shared");
        assertFalse(config.isRemoteControlEnabled(),
                "handing over control of the machine must never be on until someone turns it on");
    }

    @Test
    public void screenSettingsSurviveAnExportAndImport() {
        SHTTPSConfig source = new TestDBEnvironment.Config();
        source.setScreenShareEnabled(true);
        source.setScreenMonitor(1);
        source.setRemoteControlEnabled(true);

        JSONObject exported = source.serializeAll();
        SHTTPSConfig restored = new TestDBEnvironment.Config();
        restored.applyAll(exported);

        assertTrue(restored.isScreenShareEnabled(),
                "screen sharing was switched off by a backup and restore");
        assertEquals(1, restored.getScreenMonitor(),
                "the chosen monitor was lost by a backup and restore");
        assertTrue(restored.isRemoteControlEnabled(),
                "remote control was switched off by a backup and restore");
    }

    /**
     * Importing a configuration written before these keys existed must leave them alone rather than
     * stamp defaults over whatever the machine is already set to - {@code applyAll} only touches
     * keys the blob actually carries.
     */
    @Test
    public void anOlderConfigDoesNotResetTheseSettings() {
        SHTTPSConfig config = new TestDBEnvironment.Config();
        config.setScreenShareEnabled(true);
        config.setScreenMonitor(2);
        config.setRemoteControlEnabled(true);

        config.applyAll(new JSONObject().put(SHTTPSConfig.KEY_PORT, 9090));

        assertTrue(config.isScreenShareEnabled());
        assertEquals(2, config.getScreenMonitor());
        assertTrue(config.isRemoteControlEnabled());
    }
}
