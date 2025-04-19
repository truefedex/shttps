package com.phlox.server.utils;

import java.util.HashMap;
import java.util.Map;

class RadixNode<T> {
    String prefix;
    T value;
    Map<Character, RadixNode<T>> children = new HashMap<>();

    RadixNode(String prefix) {
        this.prefix = prefix;
    }
}

public class RadixTree<T> {
    private final RadixNode<T> root = new RadixNode<>("");

    public void put(String key, T value) {
        RadixNode<T> current = root;
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            RadixNode<T> child = current.children.get(c);
            if (child == null) {
                RadixNode<T> newNode = new RadixNode<>(key.substring(i));
                newNode.value = value;
                current.children.put(c, newNode);
                return;
            }
            String childPrefix = child.prefix;
            int j = 0;
            while (j < childPrefix.length() && i < key.length() && key.charAt(i) == childPrefix.charAt(j)) {
                i++;
                j++;
            }
            if (j == childPrefix.length()) {
                current = child;
            } else {
                RadixNode<T> newNode = new RadixNode<>(childPrefix.substring(0, j));
                newNode.children.put(childPrefix.charAt(j), child);
                child.prefix = childPrefix.substring(j);
                current.children.put(c, newNode);
                if (i == key.length()) {
                    newNode.value = value;
                    return;
                } else {
                    RadixNode<T> newChild = new RadixNode<>(key.substring(i));
                    newChild.value = value;
                    newNode.children.put(key.charAt(i), newChild);
                    return;
                }
            }
        }
        current.value = value;
    }

    public T findLongestPrefix(String key) {
        RadixNode<T> current = root;
        RadixNode<T> result = null;
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            RadixNode<T> child = current.children.get(c);
            if (child == null) {
                break;
            }
            String childPrefix = child.prefix;
            int j = 0;
            while (j < childPrefix.length() && i < key.length() && key.charAt(i) == childPrefix.charAt(j)) {
                i++;
                j++;
            }
            if (j == childPrefix.length()) {
                if (child.value != null) {
                    result = child;
                }
                current = child;
            } else {
                break;
            }
        }
        return result != null ? result.value : null;
    }

    public static void main(String[] args) {
        RadixTree<Integer> radixTree = new RadixTree<>();
        radixTree.put("test", 1);
        radixTree.put("te", 2);
        radixTree.put("tester", 3);

        System.out.println(radixTree.findLongestPrefix("t"));
        System.out.println(radixTree.findLongestPrefix("test"));
        System.out.println(radixTree.findLongestPrefix("tester_test"));
        System.out.println(radixTree.findLongestPrefix("tes"));
    }
}