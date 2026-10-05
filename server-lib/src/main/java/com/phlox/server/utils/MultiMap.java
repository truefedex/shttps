package com.phlox.server.utils;

import java.util.*;

public class MultiMap<K, V> {

    private TreeMap<K, List<V>> treeMap;
    private int size;

    public MultiMap() {
        treeMap = new TreeMap<>();
        size = 0;
    }

    /** Keys are compared with {@code comparator}; for keys it holds equal, the first spelling is kept. */
    public MultiMap(Comparator<? super K> comparator) {
        treeMap = new TreeMap<>(comparator);
        size = 0;
    }

    /** A map of names that are compared case-insensitively, as HTTP header names are. */
    public static <V> MultiMap<String, V> caseInsensitive() {
        return new MultiMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    public void add(K key, V value) {
        List<V> list = treeMap.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(value);
        ++size;
    }

    /**
     * The same as add but replacing all values for that key
     */
    public void put(K key, V value) {
        List<V> list = treeMap.computeIfAbsent(key, k -> new ArrayList<>());
        int lSize = list.size();
        if (lSize != 0) {
            list.clear();
            size -= lSize;
        }
        list.add(value);
        ++size;
    }

    public void addAll(K key, List<V> values) {
        for (V value : values) {
            add(key, value);
        }
    }

    public void addAll(MultiMap<K, V> multiMap) {
        for (K key : multiMap.keys()) {
            addAll(key, multiMap.getAll(key));
        }
    }

    /** All values of {@code key} in insertion order, as a read-only view (empty if there are none). */
    public List<V> getAll(K key) {
        List<V> list = treeMap.get(key);
        return list != null ? Collections.unmodifiableList(list) : Collections.<V>emptyList();
    }

    /**
     * Returns first value if any (or null)
     **/
    public V get(K key) {
        List<V> list = treeMap.get(key);
        return list != null ? list.get(0) : null;
    }

    public void removeAll(K key) {
        if (this.containsKey(key)) {
            size -= treeMap.get(key).size();
            treeMap.remove(key);
        }
    }

    public boolean remove(K key, V value) {
        boolean isKeyPresent = this.containsKey(key);
        if (!isKeyPresent) {
            return false;
        }

        List<V> list = treeMap.get(key);
        if (list.contains(value)) {
            list.remove(value);
            if (list.isEmpty()) {
                treeMap.remove(key);
            }
            --size;
            return true;
        }

        return false;
    }

    public int size() {
        return this.size;
    }

    public boolean containsKey(K key) {
        return treeMap.containsKey(key);
    }

    /** The keys in sort order, as a read-only view. */
    public Set<K> keys() {
        return Collections.unmodifiableSet(treeMap.keySet());
    }

    @Override
    public String toString() {
        StringBuilder printMultiMap = new StringBuilder("{\n");

        for (K key : treeMap.keySet()) {
            printMultiMap.append(key).append(" = ").append(treeMap.get(key)).append("\n");
        }

        printMultiMap.append("}");

        return printMultiMap.toString();
    }

    public boolean isEmpty() {
        return treeMap.isEmpty();
    }

    public void forEach(BiConsumer<K, List<V>> consumer) {
        for (Map.Entry<K, List<V>> entry : treeMap.entrySet()) {
            consumer.accept(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
    }

     public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }
}
