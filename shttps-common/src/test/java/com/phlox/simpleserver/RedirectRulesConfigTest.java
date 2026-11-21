package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware;
import com.phlox.simpleserver.database.TestDBEnvironment;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

/**
 * Redirect rules are compiled when a rule object is built. A pattern already sitting in config
 * must not be able to throw out of {@link SHTTPSConfig#getRedirectRules()}: that is what
 * {@code RedirectsActivity} calls from {@code onCreate}, and the same call starts the server.
 */
public class RedirectRulesConfigTest {

    @Test
    public void uncompilableStoredPatternDoesNotDropTheRuleOrItsNeighbours() {
        SHTTPSConfig config = new TestDBEnvironment.Config();
        config.setString(SHTTPSConfig.KEY_REDIRECT_RULES,
                "[{\"enabled\":true,\"code\":302,\"from\":\"(\",\"to\":\"/x\",\"comment\":\"bad\"},"
                        + "{\"enabled\":false,\"code\":301,\"from\":\"^/ok$\",\"to\":\"/y\",\"comment\":\"good\"}]");

        List<RedirectsMiddleware.RedirectRule> rules = config.getRedirectRules();

        assertNotNull(rules);
        assertEquals(2, rules.size());
        assertEquals("(", rules.get(0).from);
        assertEquals("/x", rules.get(0).to);
        assertEquals(302, rules.get(0).code);
        assertTrue(rules.get(0).enabled);
        assertEquals("bad", rules.get(0).comment);
        assertEquals("^/ok$", rules.get(1).from);
        assertEquals("/y", rules.get(1).to);
        assertEquals(301, rules.get(1).code);
        assertFalse(rules.get(1).enabled);
        assertEquals("good", rules.get(1).comment);
    }

    @Test
    public void uncompilableRuleRoundTripsThroughConfig() {
        SHTTPSConfig config = new TestDBEnvironment.Config();
        config.setRedirectRules(Collections.singletonList(
                RedirectsMiddleware.RedirectRule.restored("[", "/t", 307, true, "kept")));

        List<RedirectsMiddleware.RedirectRule> rules = config.getRedirectRules();

        assertNotNull(rules);
        assertEquals(1, rules.size());
        assertEquals("[", rules.get(0).from);
        assertEquals("/t", rules.get(0).to);
        assertEquals(307, rules.get(0).code);
        assertTrue(rules.get(0).enabled);
        assertEquals("kept", rules.get(0).comment);
    }
}
