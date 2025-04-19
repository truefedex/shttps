package com.phlox.server.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

class RadixTreePerformanceTest {
    private static final int DATASET_SIZE = 100_000;
    private static final int SEARCH_ITERATIONS = 10_000;
    private static final int STRING_LENGTH = 20;
    
    private RadixTree<Integer> radixTree;
    private Map<String, Integer> hashMap;
    private String[] testData;
    private String[] searchQueries;
    
    @BeforeEach
    void setUp() {
        radixTree = new RadixTree<>();
        hashMap = new HashMap<>();
        generateTestData();
    }
    
    private void generateTestData() {
        Random random = new Random(42); // Fixed seed for reproducibility
        testData = new String[DATASET_SIZE];
        searchQueries = new String[SEARCH_ITERATIONS];
        
        // Generate test data
        for (int i = 0; i < DATASET_SIZE; i++) {
            testData[i] = generateRandomString(random, STRING_LENGTH);
            radixTree.put(testData[i], i);
            hashMap.put(testData[i], i);
        }
        
        // Generate search queries (mix of existing prefixes and random strings)
        for (int i = 0; i < SEARCH_ITERATIONS; i++) {
            if (i % 2 == 0) {
                // Use prefix of existing string
                String existingString = testData[random.nextInt(DATASET_SIZE)];
                int prefixLength = random.nextInt(existingString.length() + 1);
                searchQueries[i] = existingString.substring(0, prefixLength);
            } else {
                // Generate random string
                searchQueries[i] = generateRandomString(random, random.nextInt(STRING_LENGTH + 1));
            }
        }
    }
    
    private String generateRandomString(Random random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            // Generate strings with limited character set to increase prefix matches
            sb.append((char) ('a' + random.nextInt(8)));
        }
        return sb.toString();
    }
    
    private Integer findLongestPrefixWithHashMap(String query) {
        String longestPrefix = null;
        Integer result = null;
        
        for (Map.Entry<String, Integer> entry : hashMap.entrySet()) {
            String key = entry.getKey();
            if (query.startsWith(key)) {
                if (longestPrefix == null || key.length() > longestPrefix.length()) {
                    longestPrefix = key;
                    result = entry.getValue();
                }
            }
        }
        
        return result;
    }
    
    @Test
    void comparePerformance() {
        // Warm-up
        for (int i = 0; i < 1000; i++) {
            radixTree.findLongestPrefix(searchQueries[i % searchQueries.length]);
            findLongestPrefixWithHashMap(searchQueries[i % searchQueries.length]);
        }
        
        // Test RadixTree
        long startTime = System.nanoTime();
        for (String query : searchQueries) {
            radixTree.findLongestPrefix(query);
        }
        long radixTreeTime = System.nanoTime() - startTime;
        
        // Test HashMap
        startTime = System.nanoTime();
        for (String query : searchQueries) {
            findLongestPrefixWithHashMap(query);
        }
        long hashMapTime = System.nanoTime() - startTime;
        
        // Print results
        System.out.println("\nPerformance Test Results:");
        System.out.println("Dataset size: " + DATASET_SIZE + " entries");
        System.out.println("Search iterations: " + SEARCH_ITERATIONS);
        System.out.println("String length: " + STRING_LENGTH + " characters");
        System.out.println("\nRadixTree time: " + TimeUnit.NANOSECONDS.toMillis(radixTreeTime) + "ms");
        System.out.println("HashMap time: " + TimeUnit.NANOSECONDS.toMillis(hashMapTime) + "ms");
        System.out.println("RadixTree is " + String.format("%.2f", (double)hashMapTime / radixTreeTime) + "x faster");
        
        // Verify results match
        for (String query : searchQueries) {
            Integer radixResult = radixTree.findLongestPrefix(query);
            Integer hashMapResult = findLongestPrefixWithHashMap(query);
            assert Objects.equals(radixResult, hashMapResult) :
                String.format("Results don't match for query '%s': RadixTree=%s, HashMap=%s", 
                    query, radixResult, hashMapResult);
        }
    }
} 