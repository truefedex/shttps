package com.phlox.server.utils;

import java.util.*;

public class MultiMap<K, V> {

    private TreeMap<K, List<V>> treeMap;
    private int size;

    public MultiMap() {
        treeMap = new TreeMap<>();
        size = 0;
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

    public List<V> getAll(K key) {
        return this.containsKey(key) ? treeMap.get(key) : new ArrayList<>();
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

    public Set<K> keys() {
        return treeMap.keySet();
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
            consumer.accept(entry.getKey(), entry.getValue());
        }
    }

     public interface BiConsumer<K, V> {
        void accept(K key, V value);
    }
}
