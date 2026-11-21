package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.simpleserver.database.TestDBEnvironment;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ChannelManager#closeAll} used to walk and clear the map with no lock, while the idle
 * sweep iterates it under the lock. Both have to take the map under that lock and close sessions
 * only afterwards.
 */
public class ChannelManagerCloseTest {

    @Test
    public void closeAllForgetsEveryChannel() {
        ChannelManager manager = new ChannelManager();
        TestDBEnvironment.Config config = enabledConfig();
        config.setPredefinedChannels(List.of(definition("room")));
        manager.applyPredefinedChannels(config);

        manager.closeAll(1001, "bye");

        assertNull(manager.get("room"));
        assertEquals(0, manager.countPredefined());
    }

    @Test
    public void closeAllAndTheIdleSweepCanRunTogether() throws Exception {
        ChannelManager manager = new ChannelManager();
        TestDBEnvironment.Config config = enabledConfig();
        config.setChannelIdleTimeoutMillis(1);
        config.setMaxDynamicChannels(1000);
        ChannelDefinition template = definition("ignored");
        AtomicBoolean failed = new AtomicBoolean();
        long end = System.currentTimeMillis() + 300;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            while (System.currentTimeMillis() < end && !failed.get()) {
                try {
                    manager.collectIdleChannels(config);
                } catch (Throwable t) {
                    failed.set(true);
                }
            }
        });
        pool.submit(() -> {
            int n = 0;
            while (System.currentTimeMillis() < end && !failed.get()) {
                try {
                    manager.createDynamic("d" + (n++), template, config);
                    manager.closeAll(1001, "bye");
                } catch (Throwable t) {
                    failed.set(true);
                }
            }
        });
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(failed.get());
        assertEquals(0, manager.countDynamic() + manager.countPredefined());
    }

    private static TestDBEnvironment.Config enabledConfig() {
        TestDBEnvironment.Config config = new TestDBEnvironment.Config();
        config.setChannelsEnabled(true);
        return config;
    }

    private static ChannelDefinition definition(String id) {
        return new ChannelDefinition(id, Channel.Mode.ECHO, null, null,
                false, false, true, null, null, false);
    }
}
