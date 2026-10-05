package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MultiMapTest {

    @Test
    public void addKeepsAllValuesInOrder() {
        MultiMap<String, String> map = new MultiMap<>();
        map.add("a", "1");
        map.add("a", "2");
        map.add("b", "3");
        assertEquals(Arrays.asList("1", "2"), map.getAll("a"));
        assertEquals("1", map.get("a"));
        assertEquals(3, map.size());
        assertFalse(map.isEmpty());
    }

    @Test
    public void putReplacesAllValues() {
        MultiMap<String, String> map = new MultiMap<>();
        map.add("a", "1");
        map.add("a", "2");
        map.put("a", "3");
        assertEquals(Collections.singletonList("3"), map.getAll("a"));
        assertEquals(1, map.size());
    }

    @Test
    public void missingKey() {
        MultiMap<String, String> map = new MultiMap<>();
        assertNull(map.get("x"));
        assertTrue(map.getAll("x").isEmpty());
        assertFalse(map.containsKey("x"));
        assertTrue(map.isEmpty());
    }

    @Test
    public void removeSingleValueAndKey() {
        MultiMap<String, String> map = new MultiMap<>();
        map.add("a", "1");
        map.add("a", "2");
        assertTrue(map.remove("a", "1"));
        assertFalse(map.remove("a", "nope"));
        assertFalse(map.remove("missing", "1"));
        assertEquals(Collections.singletonList("2"), map.getAll("a"));
        assertTrue(map.remove("a", "2"));
        assertFalse(map.containsKey("a"));
        assertEquals(0, map.size());

        map.add("b", "1");
        map.add("b", "2");
        map.removeAll("b");
        assertFalse(map.containsKey("b"));
        assertEquals(0, map.size());
    }

    @Test
    public void addAllCopiesEveryValue() {
        MultiMap<String, String> source = new MultiMap<>();
        source.add("a", "1");
        source.add("a", "2");
        source.add("b", "3");
        MultiMap<String, String> target = new MultiMap<>();
        target.add("a", "0");
        target.addAll(source);
        assertEquals(Arrays.asList("0", "1", "2"), target.getAll("a"));
        assertEquals(4, target.size());
    }

    @Test
    public void keysAreSortedAndForEachSeesEveryEntry() {
        MultiMap<String, String> map = new MultiMap<>();
        map.add("b", "2");
        map.add("a", "1");
        assertEquals(Arrays.asList("a", "b"), new ArrayList<>(map.keys()));
        List<String> seen = new ArrayList<>();
        map.forEach((key, values) -> seen.add(key + "=" + values));
        assertEquals(Arrays.asList("a=[1]", "b=[2]"), seen);
    }

    @Test
    public void viewsAreReadOnly() {
        //a caller clearing a value list would leave a key with no values, and get() would throw
        MultiMap<String, String> map = new MultiMap<>();
        map.add("a", "1");
        assertThrows(UnsupportedOperationException.class, () -> map.getAll("a").clear());
        assertThrows(UnsupportedOperationException.class, () -> map.getAll("missing").add("x"));
        assertThrows(UnsupportedOperationException.class, () -> map.keys().remove("a"));
        map.forEach((key, values) ->
                assertThrows(UnsupportedOperationException.class, values::clear));
        assertEquals("1", map.get("a"));
    }

    @Test
    public void caseInsensitiveMapMatchesAnyCase() {
        MultiMap<String, String> headers = MultiMap.caseInsensitive();
        headers.add("Content-Length", "5");
        assertTrue(headers.containsKey("content-length"));
        assertEquals("5", headers.get("CONTENT-LENGTH"));
        headers.put("content-length", "6");
        assertEquals(Collections.singletonList("6"), headers.getAll("Content-Length"));
        //the spelling of the first insertion is kept for writing the key out
        assertEquals(Collections.singletonList("Content-Length"), new ArrayList<>(headers.keys()));
        headers.removeAll("CONTENT-length");
        assertTrue(headers.isEmpty());
    }

    @Test
    public void toStringListsEntries() {
        MultiMap<String, String> map = new MultiMap<>();
        map.add("a", "1");
        assertEquals("{\na = [1]\n}", map.toString());
    }
}
