package com.phlox.server.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadixTreeTest {
    private RadixTree<Integer> radixTree;

    @BeforeEach
    void setUp() {
        radixTree = new RadixTree<>();
    }

    @Test
    void testEmptyTree() {
        assertNull(radixTree.findLongestPrefix("test"));
    }

    @Test
    void testSingleInsertAndFind() {
        radixTree.put("test", 1);
        assertEquals(1, radixTree.findLongestPrefix("test"));
        assertNull(radixTree.findLongestPrefix("tes"));
    }

    @Test
    void testMultipleInsertAndFind() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("tester", 3);

        assertEquals(2, radixTree.findLongestPrefix("te"));
        assertEquals(1, radixTree.findLongestPrefix("test"));
        assertEquals(3, radixTree.findLongestPrefix("tester"));
        assertEquals(3, radixTree.findLongestPrefix("tester_extra"));
    }

    @Test
    void testOverwrite() {
        radixTree.put("test", 1);
        radixTree.put("test", 2);
        assertEquals(2, radixTree.findLongestPrefix("test"));
    }

    @Test
    void testPrefixMatching() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        
        assertEquals(2, radixTree.findLongestPrefix("te"));
        assertEquals(2, radixTree.findLongestPrefix("tes"));
        assertEquals(1, radixTree.findLongestPrefix("test"));
        assertEquals(1, radixTree.findLongestPrefix("testa"));
    }

    @Test
    void testNonExistentPaths() {
        radixTree.put("test", 1);
        radixTree.put("team", 2);

        assertNull(radixTree.findLongestPrefix("t"));
        assertNull(radixTree.findLongestPrefix("tea"));
        assertNull(radixTree.findLongestPrefix("different"));
    }

    @Test
    void testBranchingPaths() {
        radixTree.put("team", 1);
        radixTree.put("test", 2);
        radixTree.put("testing", 3);

        assertEquals(1, radixTree.findLongestPrefix("team"));
        assertEquals(1, radixTree.findLongestPrefix("teams"));
        assertEquals(2, radixTree.findLongestPrefix("test"));
        assertEquals(3, radixTree.findLongestPrefix("testing"));
        assertEquals(3, radixTree.findLongestPrefix("testings"));
    }

    @Test
    void testEmptyString() {
        radixTree.put("", 1);
        // Empty string is not considered a prefix
        assertNull(radixTree.findLongestPrefix(""));
        assertNull(radixTree.findLongestPrefix("anything"));
    }
} 