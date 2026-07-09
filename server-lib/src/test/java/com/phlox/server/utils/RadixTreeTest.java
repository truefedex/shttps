package com.phlox.server.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    private List<String> collectEqualOrLongerKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        radixTree.visitPrefixesEqualOrLongerThan(prefix, (key, value) -> {
            keys.add(key);
            return true;
        });
        return keys;
    }

    @Test
    void testVisitPrefixesEqualOrLongerThanOnEmptyTree() {
        assertTrue(collectEqualOrLongerKeys("test").isEmpty());
        assertTrue(collectEqualOrLongerKeys("").isEmpty());
    }

    @Test
    void testVisitPrefixesEqualOrLongerThanExactAndLonger() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("tester", 3);
        radixTree.put("team", 4);

        List<String> keys = collectEqualOrLongerKeys("te");
        assertEquals(4, keys.size());
        assertTrue(keys.containsAll(List.of("te", "test", "tester", "team")));
    }

    @Test
    void testVisitPrefixesEqualOrLongerThanMidNode() {
        radixTree.put("test", 1);
        radixTree.put("tester", 2);
        radixTree.put("team", 3);

        // "tes" ends partway through the "test"/"tester" shared node
        List<String> keys = collectEqualOrLongerKeys("tes");
        assertEquals(2, keys.size());
        assertTrue(keys.containsAll(List.of("test", "tester")));
        assertFalse(keys.contains("team"));
    }

    @Test
    void testVisitPrefixesEqualOrLongerThanExactMatchOnly() {
        radixTree.put("test", 1);

        List<String> keys = collectEqualOrLongerKeys("test");
        assertEquals(List.of("test"), keys);
    }

    @Test
    void testVisitPrefixesEqualOrLongerThanNoMatch() {
        radixTree.put("test", 1);
        radixTree.put("team", 2);

        assertTrue(collectEqualOrLongerKeys("tea_extra").isEmpty());
        assertTrue(collectEqualOrLongerKeys("different").isEmpty());
        assertTrue(collectEqualOrLongerKeys("testing").isEmpty());
    }

    @Test
    void testVisitPrefixesEqualOrLongerThanEmptyPrefixVisitsAll() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("team", 3);

        List<String> keys = collectEqualOrLongerKeys("");
        assertEquals(3, keys.size());
        assertTrue(keys.containsAll(List.of("test", "te", "team")));
    }

    @Test
    void testVisitPrefixesEqualOrLongerThanStopsEarly() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("tester", 3);

        List<Integer> visited = new ArrayList<>();
        radixTree.visitPrefixesEqualOrLongerThan("te", (key, value) -> {
            visited.add(value);
            return false; // stop after first
        });
        assertEquals(1, visited.size());
    }

    @Test
    void testRemoveIfOnEmptyTree() {
        radixTree.removeIf((key, value) -> true);
        assertNull(radixTree.findLongestPrefix("anything"));
    }

    @Test
    void testRemoveIfNoMatch() {
        radixTree.put("test", 1);
        radixTree.put("team", 2);

        radixTree.removeIf((key, value) -> value == 99);

        assertEquals(1, radixTree.findLongestPrefix("test"));
        assertEquals(2, radixTree.findLongestPrefix("team"));
    }

    @Test
    void testRemoveIfSingleLeaf() {
        radixTree.put("test", 1);

        radixTree.removeIf((key, value) -> key.equals("test"));

        assertNull(radixTree.findLongestPrefix("test"));
    }

    @Test
    void testRemoveIfLeavesSiblingIntactAndCompacts() {
        radixTree.put("test", 1);
        radixTree.put("team", 2);

        radixTree.removeIf((key, value) -> key.equals("team"));

        assertNull(radixTree.findLongestPrefix("team"));
        assertEquals(1, radixTree.findLongestPrefix("test"));
        // structure should have compacted "te" branch node away
        assertEquals(1, radixTree.findLongestPrefix("test_extra"));
    }

    @Test
    void testRemoveIfKeepsNodeWithRemainingChildren() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("tester", 3);

        // remove "test" only; "te" and "tester" must survive, and "test"
        // must no longer resolve even though "te" is still a valid prefix of it
        radixTree.removeIf((key, value) -> key.equals("test"));

        assertEquals(2, radixTree.findLongestPrefix("te"));
        assertEquals(2, radixTree.findLongestPrefix("test"));
        assertEquals(3, radixTree.findLongestPrefix("tester"));
    }

    @Test
    void testRemoveIfByPredicateOnValue() {
        radixTree.put("a", 1);
        radixTree.put("b", 2);
        radixTree.put("c", 3);

        radixTree.removeIf((key, value) -> value % 2 == 0);

        assertEquals(1, radixTree.findLongestPrefix("a"));
        assertNull(radixTree.findLongestPrefix("b"));
        assertEquals(3, radixTree.findLongestPrefix("c"));
    }

    @Test
    void testRemoveIfRemovesEverything() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("tester", 3);
        radixTree.put("team", 4);

        radixTree.removeIf((key, value) -> true);

        assertNull(radixTree.findLongestPrefix("test"));
        assertNull(radixTree.findLongestPrefix("te"));
        assertNull(radixTree.findLongestPrefix("tester"));
        assertNull(radixTree.findLongestPrefix("team"));
        assertTrue(radixTree.findAllPrefixes("testerteam").isEmpty());
    }

    @Test
    void testRemoveIfThenReinsert() {
        radixTree.put("test", 1);
        radixTree.removeIf((key, value) -> true);
        radixTree.put("test", 2);

        assertEquals(2, radixTree.findLongestPrefix("test"));
    }

    @Test
    void testRemoveIfEmptyKeyValue() {
        radixTree.put("", 1);
        radixTree.put("test", 2);

        radixTree.removeIf((key, value) -> key.isEmpty());

        // root's own value ("") should be gone, "test" untouched
        List<String> remainingKeys = collectEqualOrLongerKeys("");
        assertEquals(List.of("test"), remainingKeys);
        assertEquals(2, radixTree.findLongestPrefix("test"));
    }

    @Test
    void testGetOnEmptyTree() {
        assertNull(radixTree.get("test"));
        assertNull(radixTree.get(""));
    }

    @Test
    void testGetExactMatch() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("tester", 3);

        assertEquals(1, radixTree.get("test"));
        assertEquals(2, radixTree.get("te"));
        assertEquals(3, radixTree.get("tester"));
    }

    @Test
    void testGetDoesNotMatchPartialOrLongerKeys() {
        radixTree.put("test", 1);

        assertNull(radixTree.get("tes"));       // shorter than stored key
        assertNull(radixTree.get("testing"));   // longer than stored key
        assertNull(radixTree.get("team"));      // diverging key
    }

    @Test
    void testGetOnBranchNodeWithoutValue() {
        radixTree.put("test", 1);
        radixTree.put("team", 2);

        // "te" is a branch node in the tree but was never explicitly put
        assertNull(radixTree.get("te"));
    }

    @Test
    void testGetEmptyKey() {
        radixTree.put("", 1);
        radixTree.put("test", 2);

        assertEquals(1, radixTree.get(""));
    }

    @Test
    void testRemoveNonExistentKeyReturnsNull() {
        radixTree.put("test", 1);

        assertNull(radixTree.remove("team"));
        assertNull(radixTree.remove("tes"));
        assertEquals(1, radixTree.get("test"));
    }

    @Test
    void testRemoveExactSingleLeaf() {
        radixTree.put("test", 1);

        assertEquals(1, radixTree.remove("test"));
        assertNull(radixTree.get("test"));
        assertNull(radixTree.findLongestPrefix("test"));
    }

    @Test
    void testRemoveExactCompactsSibling() {
        radixTree.put("test", 1);
        radixTree.put("team", 2);

        assertEquals(2, radixTree.remove("team"));
        assertNull(radixTree.get("team"));
        assertEquals(1, radixTree.get("test"));
        assertEquals(1, radixTree.findLongestPrefix("test_extra"));
    }

    @Test
    void testRemoveExactKeepsNodeWithRemainingChildren() {
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("tester", 3);

        assertEquals(1, radixTree.remove("test"));

        assertNull(radixTree.get("test"));
        assertEquals(2, radixTree.get("te"));
        assertEquals(3, radixTree.get("tester"));
    }

    @Test
    void testRemoveExactThenReinsert() {
        radixTree.put("test", 1);
        assertEquals(1, radixTree.remove("test"));

        radixTree.put("test", 2);
        assertEquals(2, radixTree.get("test"));
    }

    @Test
    void testRemoveEmptyKey() {
        radixTree.put("", 1);
        radixTree.put("test", 2);

        assertEquals(1, radixTree.remove(""));
        assertNull(radixTree.get(""));
        assertEquals(2, radixTree.get("test"));
    }
} 