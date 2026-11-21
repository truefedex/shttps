package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.simpleserver.channels.Channel;
import com.phlox.simpleserver.channels.ChannelDefinition;
import com.phlox.simpleserver.database.TestDBEnvironment;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

/**
 * Channels are reachable only from a settings dialog, and settings only reachable from a dialog are
 * exactly the ones that go missing from {@code serializeAll()} unnoticed - two of the keys added
 * before these ({@code webdav_support}, {@code screen_share}) did. So the export/import round trip
 * is pinned here, along with the defaults, which are what an untouched server actually runs with.
 */
public class ChannelsConfigTest {

    @Test
    public void defaultsAreTheDocumentedOnes() {
        SHTTPSConfig config = new TestDBEnvironment.Config();

        assertFalse(config.isChannelsEnabled(), "channels must be off until they are switched on");
        assertFalse(config.getAllowDynamicChannelCreation());
        assertEquals(20, config.getMaxDynamicChannels());
        assertEquals(50, config.getMaxParticipantsPerChannel());
        assertEquals(600_000, config.getChannelIdleTimeoutMillis());
        assertEquals(20, config.getChannelMessageRateLimitPerSecond());
        assertNull(config.getPredefinedChannels(), "nothing is predefined until something is");
    }

    @Test
    public void aPredefinedChannelSurvivesAStoreAndLoad() {
        SHTTPSConfig config = new TestDBEnvironment.Config();
        config.setPredefinedChannels(Collections.singletonList(new ChannelDefinition(
                "lobby", Channel.Mode.STATE, new JSONObject().put("round", 1), 8, true,
                false, false, ChannelDefinition.hashChannelPassword("pin"))));

        List<ChannelDefinition> read = config.getPredefinedChannels();
        assertNotNull(read);
        assertEquals(1, read.size());
        ChannelDefinition definition = read.get(0);
        assertEquals("lobby", definition.id);
        assertEquals(Channel.Mode.STATE, definition.mode);
        assertNotNull(definition.initialState, "the state a STATE channel starts from is a setting");
        assertEquals(1, definition.initialState.getInt("round"));
        assertEquals(Integer.valueOf(8), definition.maxParticipants);
        assertTrue(definition.deletable);
        assertFalse(definition.notifyPresence);
        assertFalse(definition.guestsAllowed);
        assertEquals(ChannelDefinition.hashChannelPassword("pin"), definition.passwordHash);
    }

    /**
     * The per-channel message limit has three states, not two, and the difference between two of
     * them is invisible unless it is asserted: absent means "whatever the server says", and an
     * explicit zero means "do not throttle this one". A round trip that turned the zero back into an
     * absence would silently hand the channel to the server-wide default.
     */
    @Test
    public void aPerChannelRateLimitOfZeroSurvivesAStoreAndLoadAsZero() {
        SHTTPSConfig config = new TestDBEnvironment.Config();
        config.setPredefinedChannels(List.of(
                new ChannelDefinition("unthrottled", Channel.Mode.ECHO, null, null, false,
                        true, true, null, 0),
                new ChannelDefinition("slow", Channel.Mode.ECHO, null, null, false,
                        true, true, null, 3),
                new ChannelDefinition("ordinary")));

        List<ChannelDefinition> read = config.getPredefinedChannels();
        assertNotNull(read);
        assertEquals(Integer.valueOf(0), read.get(0).messageRateLimitPerSecond,
                "an explicit zero is a setting of its own, not an absent one");
        assertEquals(Integer.valueOf(3), read.get(1).messageRateLimitPerSecond);
        assertNull(read.get(2).messageRateLimitPerSecond,
                "a channel that says nothing follows the server-wide setting");
    }

    @Test
    public void aChannelPasswordIsNeverStoredInPlainText() {
        SHTTPSConfig config = new TestDBEnvironment.Config();
        config.setPredefinedChannels(Collections.singletonList(new ChannelDefinition(
                "lobby", Channel.Mode.ECHO, null, null, false, true, true,
                ChannelDefinition.hashChannelPassword("pin"))));

        //the predefined channel list travels through configuration export like any other setting
        String exported = config.serializeAll().toString();
        assertFalse(exported.contains("pin"), "the plain-text channel password reached the export");
    }

    @Test
    public void everyChannelSettingSurvivesExportAndImport() {
        SHTTPSConfig source = new TestDBEnvironment.Config();
        source.setChannelsEnabled(true);
        source.setAllowDynamicChannelCreation(true);
        source.setMaxDynamicChannels(3);
        source.setMaxParticipantsPerChannel(4);
        source.setChannelIdleTimeoutMillis(5000);
        source.setChannelMessageRateLimitPerSecond(7);
        source.setPredefinedChannels(Collections.singletonList(new ChannelDefinition("lobby")));

        JSONObject exported = source.serializeAll();
        SHTTPSConfig restored = new TestDBEnvironment.Config();
        restored.applyAll(exported);

        assertTrue(restored.isChannelsEnabled());
        assertTrue(restored.getAllowDynamicChannelCreation());
        assertEquals(3, restored.getMaxDynamicChannels());
        assertEquals(4, restored.getMaxParticipantsPerChannel());
        assertEquals(5000, restored.getChannelIdleTimeoutMillis());
        assertEquals(7, restored.getChannelMessageRateLimitPerSecond());
        List<ChannelDefinition> channels = restored.getPredefinedChannels();
        assertNotNull(channels, "the predefined channels did not survive the round trip");
        assertEquals(1, channels.size());
        assertEquals("lobby", channels.get(0).id);
    }

    /**
     * This list is read while the server starts, from a file a human may have edited and from an
     * imported blob that {@code applyAll()} plants raw. An entry that does not parse has to cost
     * that entry and nothing else - throwing here would mean the server does not come up at all.
     */
    @Test
    public void anUnusablePredefinedChannelIsSkippedRatherThanFatal() {
        SHTTPSConfig config = new TestDBEnvironment.Config();
        JSONArray stored = new JSONArray();
        stored.put(new JSONObject().put("mode", "echo"));//no id
        stored.put(new JSONObject().put("id", "future").put("mode", "broadcast"));//mode from later version
        stored.put(new JSONObject().put("id", "lobby").put("mode", "echo"));
        config.setJSONArray(SHTTPSConfig.KEY_PREDEFINED_CHANNELS, stored);

        List<ChannelDefinition> channels = config.getPredefinedChannels();

        assertNotNull(channels);
        assertEquals(1, channels.size(), "only the entry that parses should survive");
        assertEquals("lobby", channels.get(0).id);
    }

    @Test
    public void migrationStampsTheNewConfigVersion() {
        SHTTPSConfig config = new TestDBEnvironment.Config();
        config.setConfigVersion(5);

        config.runMigrations();

        assertEquals(SHTTPSConfig.CONFIG_VERSION, config.getConfigVersion());
        //nothing to convert: a configuration written before channels existed simply has none
        assertFalse(config.isChannelsEnabled());
        assertNull(config.getPredefinedChannels());
    }
}
