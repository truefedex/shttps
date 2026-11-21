package com.phlox.simpleserver.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.simpleserver.auth.basic.BasicAuthManager;
import com.phlox.simpleserver.auth.web.InMemorySessionManager;
import com.phlox.simpleserver.auth.web.WebAuthManager;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The guest account is re-read from the store once the cache's TTL has passed. A copy taken when
 * the auth manager was built kept serving a guest the administrator had removed, and never saw one
 * added later; a lookup on every request was a database query per anonymous request.
 */
public class GuestUserLookupTest {
    private static final long TTL_NANOS = GuestUserCache.DEFAULT_TTL_MS * 1_000_000L;

    @Test
    public void basicAuthSeesAGuestThatAppearsAfterTheManagerWasBuilt() {
        Map<String, User> users = new HashMap<>();
        AtomicLong clock = new AtomicLong();
        BasicAuthManager auth = new BasicAuthManager(store(users, new AtomicInteger()),
                new GuestUserCache(store(users, new AtomicInteger()), GuestUserCache.DEFAULT_TTL_MS, clock::get));

        assertNull(auth.authenticate(new RequestContext(null), new Request()));

        User guest = new User(User.GUEST_IDENTITY, "hash");
        users.put(User.GUEST_IDENTITY, guest);
        //still the cached "no guest" until the TTL is over
        assertNull(auth.authenticate(new RequestContext(null), new Request()));
        clock.addAndGet(TTL_NANOS);
        assertSame(guest, auth.authenticate(new RequestContext(null), new Request()));

        users.remove(User.GUEST_IDENTITY);
        clock.addAndGet(TTL_NANOS);
        assertNull(auth.authenticate(new RequestContext(null), new Request()));
    }

    @Test
    public void webAuthSeesAGuestThatAppearsAfterTheManagerWasBuilt() {
        Map<String, User> users = new HashMap<>();
        AtomicLong clock = new AtomicLong();
        UserStore store = store(users, new AtomicInteger());
        WebAuthManager auth = new WebAuthManager(store, new InMemorySessionManager(),
                new GuestUserCache(store, GuestUserCache.DEFAULT_TTL_MS, clock::get));

        assertNull(auth.authenticate(new RequestContext(null), new Request()));

        User guest = new User(User.GUEST_IDENTITY, "hash");
        users.put(User.GUEST_IDENTITY, guest);
        clock.addAndGet(TTL_NANOS);
        assertSame(guest, auth.authenticate(new RequestContext(null), new Request()));

        User replaced = new User(User.GUEST_IDENTITY, "other");
        users.put(User.GUEST_IDENTITY, replaced);
        clock.addAndGet(TTL_NANOS);
        assertSame(replaced, auth.authenticate(new RequestContext(null), new Request()));
    }

    @Test
    public void anonymousRequestsWithinTheTtlDoNotQueryTheStore() {
        Map<String, User> users = new HashMap<>();
        users.put(User.GUEST_IDENTITY, new User(User.GUEST_IDENTITY, "hash"));
        AtomicInteger lookups = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        GuestUserCache cache = new GuestUserCache(store(users, lookups), GuestUserCache.DEFAULT_TTL_MS, clock::get);

        for (int i = 0; i < 100; i++) {
            assertNotNull(cache.get());
        }
        assertEquals(1, lookups.get());

        clock.addAndGet(TTL_NANOS - 1);
        cache.get();
        assertEquals(1, lookups.get());
        clock.addAndGet(1);
        cache.get();
        assertEquals(2, lookups.get());
    }

    private static UserStore store(Map<String, User> users, AtomicInteger lookups) {
        return (UserStore) Proxy.newProxyInstance(UserStore.class.getClassLoader(),
                new Class<?>[]{UserStore.class}, (proxy, method, args) -> {
                    if ("find".equals(method.getName())) {
                        lookups.incrementAndGet();
                        return users.get(args[0]);
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
    }
}
