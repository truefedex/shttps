package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The prefix visitors, all three of which share one walk. These are the examples that used to
 * live in RadixTree.main().
 */
public class RadixTreeVisitTest {
    private RadixTree<Integer> tree;

    @BeforeEach
    public void setUp() {
        tree = new RadixTree<>();
        tree.put("test", 1);
        tree.put("te", 2);
        tree.put("tester", 3);
    }

    @Test
    public void longestAndAllPrefixes() {
        assertEquals(Integer.valueOf(3), tree.findLongestPrefix("tester_test"));
        assertEquals(Integer.valueOf(1), tree.findLongestPrefix("testing"));
        assertEquals(Arrays.asList(2, 1, 3), tree.findAllPrefixes("tester_test"));
        assertEquals(Collections.emptyList(), tree.findAllPrefixes("x"));
    }

    @Test
    public void valueVisitorStopsEarly() {
        List<Integer> seen = new ArrayList<>();
        tree.visitPrefixes("tester_test", value -> {
            seen.add(value);
            return value != 1;
        });
        assertEquals(Arrays.asList(2, 1), seen);
    }

    @Test
    public void prefixStringVisitor() {
        List<String> seen = new ArrayList<>();
        tree.visitPrefixes("tester_test", (prefix, value) -> {
            seen.add(prefix + "=" + value);
            return true;
        });
        assertEquals(Arrays.asList("te=2", "test=1", "tester=3"), seen);
    }

    @Test
    public void indexVisitor() {
        List<String> seen = new ArrayList<>();
        tree.visitPrefixes("tester_test", (RadixTree.PrefixVisitor<Integer>) (key, end, value) -> {
            seen.add(key.substring(0, end) + "=" + value);
            return end < 4;
        });
        assertEquals(Arrays.asList("te=2", "test=1"), seen);
    }

    @Test
    public void keysEqualOrLongerThanAPrefix() {
        List<String> seen = new ArrayList<>();
        tree.visitPrefixesEqualOrLongerThan("te", (key, value) -> {
            seen.add(key + "=" + value);
            return true;
        });
        Collections.sort(seen);
        assertEquals(Arrays.asList("te=2", "test=1", "tester=3"), seen);
    }

    @Test
    public void removalKeepsTheRestReachable() {
        tree.removeIf((key, value) -> value == 2);
        assertEquals(null, tree.findLongestPrefix("te"));
        assertEquals(Integer.valueOf(1), tree.findLongestPrefix("test"));
        assertEquals(Integer.valueOf(3), tree.findLongestPrefix("tester"));
        assertEquals(Integer.valueOf(1), tree.get("test"));
        assertEquals(null, tree.get("tes"));
        assertEquals(Integer.valueOf(1), tree.remove("test"));
        assertEquals(null, tree.get("test"));
        assertEquals(Integer.valueOf(3), tree.get("tester"));
    }
}
