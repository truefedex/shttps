package com.phlox.server.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

class RadixNode<T> {
    String prefix;
    T value;
    Map<Character, RadixNode<T>> children = new HashMap<>();

    RadixNode(String prefix) {
        this.prefix = prefix;
    }
}

public class RadixTree<T> {
    private RadixNode<T> root = new RadixNode<>("");

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

    /**
     * Looks up the value stored under exactly {@code key} — not the longest
     * matching prefix of it, and not keys that merely start with it. Returns
     * {@code null} if {@code key} was never {@link #put}, or was put with a
     * {@code null} value.
     */
    public T get(String key) {
        RadixNode<T> current = root;
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            RadixNode<T> child = current.children.get(c);
            if (child == null) return null;
            String childPrefix = child.prefix;
            int j = 0;
            while (j < childPrefix.length() && i < key.length() && key.charAt(i) == childPrefix.charAt(j)) {
                i++;
                j++;
            }
            if (j < childPrefix.length()) return null; // key ends mid-node, or mismatch
            current = child;
        }
        return current.value;
    }

    public void clear() {
        root = new RadixNode<T>("");
    }

    /**
     * Removes the value stored under exactly {@code key}, compacting the
     * tree the same way {@link #removeIf} does. Returns the previously
     * stored value, or {@code null} if {@code key} wasn't present.
     */
    public T remove(String key) {
        AtomicReference<T> removed = new AtomicReference<>();
        remove(root, key, 0, removed);
        return removed.get();
    }

    /**
     * @return true if {@code node} is now empty (no value, no children) and
     *         should be pruned from its parent's children map
     */
    private boolean remove(RadixNode<T> node, String key, int i, AtomicReference<T> removed) {
        if (i == key.length()) {
            removed.set(node.value);
            node.value = null;
        } else {
            char c = key.charAt(i);
            RadixNode<T> child = node.children.get(c);
            if (child != null) {
                String childPrefix = child.prefix;
                int j = 0, ci = i;
                while (j < childPrefix.length() && ci < key.length() && key.charAt(ci) == childPrefix.charAt(j)) {
                    ci++;
                    j++;
                }
                if (j == childPrefix.length()) {
                    if (remove(child, key, ci, removed)) {
                        node.children.remove(c);
                    } else if (child.value == null && child.children.size() == 1) {
                        RadixNode<T> grandchild = child.children.values().iterator().next();
                        grandchild.prefix = child.prefix + grandchild.prefix;
                        node.children.put(c, grandchild);
                    }
                } // else: key diverges from the tree here, nothing to remove
            }
        }
        return node != root && node.value == null && node.children.isEmpty();
    }

    /**
     * Removes every stored key/value pair for which {@code predicate} returns
     * {@code true} (analogous to {@link java.util.Map#values()}'s
     * {@code removeIf}, but keyed by the full reconstructed key). The tree is
     * compacted as it goes, so a branch node left with no value and a single
     * remaining child is merged back into that child, same as if the
     * intermediate key had never been inserted.
     *
     * @param predicate (key, value) -> true to remove this entry
     */
    public void removeIf(BiPredicate<String, T> predicate) {
        removeIf(root, "", predicate);
    }

    /**
     * @return true if {@code node} is now empty (no value, no children) and
     *         should be pruned from its parent's children map
     */
    private boolean removeIf(RadixNode<T> node, String path, BiPredicate<String, T> predicate) {
        Iterator<Map.Entry<Character, RadixNode<T>>> it = node.children.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Character, RadixNode<T>> entry = it.next();
            RadixNode<T> child = entry.getValue();
            String childPath = path + child.prefix;
            if (removeIf(child, childPath, predicate)) {
                it.remove();
            } else if (child.value == null && child.children.size() == 1) {
                RadixNode<T> grandchild = child.children.values().iterator().next();
                grandchild.prefix = child.prefix + grandchild.prefix;
                entry.setValue(grandchild);
            }
        }
        if (node.value != null && predicate.test(path, node.value)) {
            node.value = null;
        }
        return node != root && node.value == null && node.children.isEmpty();
    }

    public T findLongestPrefix(String key) {
        RadixNode<T> current = root;
        RadixNode<T> result = null;
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            RadixNode<T> child = current.children.get(c);
            if (child == null) break;
            String childPrefix = child.prefix;
            int j = 0;
            while (j < childPrefix.length() && i < key.length() && key.charAt(i) == childPrefix.charAt(j)) {
                i++;
                j++;
            }
            if (j == childPrefix.length()) {
                if (child.value != null) result = child;
                current = child;
            } else {
                break;
            }
        }
        return result != null ? result.value : null;
    }

    public List<T> findAllPrefixes(String key) {
        List<T> results = new ArrayList<>();
        visitPrefixes(key, value -> {
            results.add(value);
            return true; // continue
        });
        return results;
    }

    /**
     * Zero-allocation prefix visitor. Walks all stored keys that are prefixes
     * of {@code key} from shortest to longest, invoking {@code visitor} for
     * each matched value.
     *
     * The visitor is a {@link Predicate}<T>: return {@code true} to continue
     * walking, {@code false} to stop early (useful for "find first", rate
     * limiting, short-circuit logic, etc.).
     *
     * No collections, lambdas captures of mutable state, or boxing occur
     * inside this method itself — allocations are entirely the caller's
     * responsibility.
     *
     * @param key     the string to match prefixes against
     * @param visitor called for each matching value; return false to stop
     */
    public void visitPrefixes(String key, Predicate<T> visitor) {
        RadixNode<T> current = root;
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            RadixNode<T> child = current.children.get(c);
            if (child == null) break;
            String childPrefix = child.prefix;
            int j = 0;
            while (j < childPrefix.length() && i < key.length() && key.charAt(i) == childPrefix.charAt(j)) {
                i++;
                j++;
            }
            if (j == childPrefix.length()) {
                if (child.value != null && !visitor.test(child.value)) return;
                current = child;
            } else {
                break;
            }
        }
    }

    /**
     * Variant of {@link #visitPrefixes} that also exposes the matched prefix
     * string itself (the key segment accumulated so far) alongside the value.
     * Uses a {@link BiPredicate}<String, T> — return false to stop early.
     *
     * Note: the prefix string passed to the visitor IS a newly constructed
     * String per match (substring of key), which is a single small allocation
     * per matching node. If even that is unacceptable, use the index-based
     * overload below.
     *
     * @param key     the string to match prefixes against
     * @param visitor (matchedPrefix, value) -> continueWalking
     */
    public void visitPrefixes(String key, BiPredicate<String, T> visitor) {
        RadixNode<T> current = root;
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            RadixNode<T> child = current.children.get(c);
            if (child == null) break;
            String childPrefix = child.prefix;
            int j = 0;
            while (j < childPrefix.length() && i < key.length() && key.charAt(i) == childPrefix.charAt(j)) {
                i++;
                j++;
            }
            if (j == childPrefix.length()) {
                if (child.value != null && !visitor.test(key.substring(0, i), child.value)) return;
                current = child;
            } else {
                break;
            }
        }
    }

    /**
     * Visits every stored key that is equal to or longer than {@code prefix}
     * and begins with it — i.e. the reverse direction of {@link #visitPrefixes}.
     * Where {@code visitPrefixes} finds stored keys that are prefixes of the
     * input, this finds stored keys for which the input is itself a prefix.
     *
     * Visitation order is unspecified beyond: the node exactly matching
     * {@code prefix} (if any) is visited before its descendants, and a node
     * is always visited before its own children.
     *
     * @param prefix  the prefix that matched keys must start with
     * @param visitor (matchedKey, value) -> continueWalking; return false to stop early
     */
    public void visitPrefixesEqualOrLongerThan(String prefix, BiPredicate<String, T> visitor) {
        RadixNode<T> current = root;
        int i = 0;
        while (i < prefix.length()) {
            char c = prefix.charAt(i);
            RadixNode<T> child = current.children.get(c);
            if (child == null) return;
            String childPrefix = child.prefix;
            int j = 0;
            while (j < childPrefix.length() && i < prefix.length() && prefix.charAt(i) == childPrefix.charAt(j)) {
                i++;
                j++;
            }
            if (i == prefix.length()) {
                String nodePath = prefix.substring(0, i - j) + childPrefix;
                visitSubtree(child, nodePath, visitor);
                return;
            } else if (j == childPrefix.length()) {
                current = child;
            } else {
                return; // mismatch: no stored key starts with prefix
            }
        }
        visitSubtree(current, prefix, visitor);
    }

    private boolean visitSubtree(RadixNode<T> node, String path, BiPredicate<String, T> visitor) {
        if (node.value != null && !visitor.test(path, node.value)) return false;
        for (RadixNode<T> child : node.children.values()) {
            if (!visitSubtree(child, path + child.prefix, visitor)) return false;
        }
        return true;
    }

    /**
     * Fully zero-allocation variant. Instead of a String, exposes the matched
     * prefix as an end-index into the original {@code key}: the matched prefix
     * is always {@code key.substring(0, prefixEndIndex)}, but no substring is
     * constructed. The visitor receives the original key, the end index, and
     * the value — it can slice or inspect however it likes without triggering
     * any allocation inside this method.
     *
     * @param key     the string to match prefixes against
     * @param visitor (originalKey, prefixEndIndex, value) -> continueWalking
     */
    public void visitPrefixes(String key, PrefixVisitor<T> visitor) {
        RadixNode<T> current = root;
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            RadixNode<T> child = current.children.get(c);
            if (child == null) break;
            String childPrefix = child.prefix;
            int j = 0;
            while (j < childPrefix.length() && i < key.length() && key.charAt(i) == childPrefix.charAt(j)) {
                i++;
                j++;
            }
            if (j == childPrefix.length()) {
                if (child.value != null && !visitor.visit(key, i, child.value)) return;
                current = child;
            } else {
                break;
            }
        }
    }

    /**
     * Fully zero-allocation visitor interface. Implement this (e.g. as a
     * singleton or a reusable stateful object) to avoid any lambda capture
     * allocation on the call site too.
     */
    @FunctionalInterface
    public interface PrefixVisitor<T> {
        /**
         * @param key            the original lookup key
         * @param prefixEndIndex exclusive end index — matched prefix is key[0..prefixEndIndex)
         * @param value          the value stored at this prefix node
         * @return true to continue visiting, false to stop
         */
        boolean visit(String key, int prefixEndIndex, T value);
    }

    public static void main(String[] args) {
        RadixTree<Integer> tree = new RadixTree<>();
        tree.put("test", 1);
        tree.put("te", 2);
        tree.put("tester", 3);

        System.out.println("--- findLongestPrefix ---");
        System.out.println(tree.findLongestPrefix("tester_test"));   // 1 ("test" is longest)

        System.out.println("--- findAllPrefixes (allocating) ---");
        System.out.println(tree.findAllPrefixes("tester_test"));     // [2, 1, 3]

        System.out.println("--- visitPrefixes: value-only, stop early ---");
        tree.visitPrefixes("tester_test", value -> {
            System.out.println("  matched value: " + value);
            return value != 1; // stop after finding value==1
        });
        // matched value: 2
        // matched value: 1

        System.out.println("--- visitPrefixes: with prefix string ---");
        tree.visitPrefixes("tester_test", (prefix, value) -> {
            System.out.println("  prefix='" + prefix + "' value=" + value);
            return true;
        });
        // prefix='te'     value=2
        // prefix='test'   value=1
        // prefix='tester' value=3

        System.out.println("--- visitPrefixes: zero-alloc PrefixVisitor ---");
        tree.visitPrefixes("tester_test", (key, end, value) -> {
            // key.substring(0, end) only if you need it — or compare inline
            System.out.println("  prefix='" + key.substring(0, end) + "' value=" + value);
            return true;
        });

        System.out.println("--- visitPrefixesEqualOrLongerThan ---");
        tree.visitPrefixesEqualOrLongerThan("te", (key, value) -> {
            System.out.println("  key='" + key + "' value=" + value);
            return true;
        });
        // key='te'     value=2
        // key='test'   value=1
        // key='tester' value=3

        System.out.println("--- removeIf ---");
        tree.removeIf((key, value) -> value == 2); // removes "te"
        System.out.println(tree.findLongestPrefix("te"));      // null
        System.out.println(tree.findLongestPrefix("test"));    // 1
        System.out.println(tree.findLongestPrefix("tester"));  // 3

        System.out.println("--- get / remove (exact) ---");
        System.out.println(tree.get("test"));    // 1
        System.out.println(tree.get("tes"));     // null (not a stored key)
        System.out.println(tree.remove("test")); // 1
        System.out.println(tree.get("test"));    // null
        System.out.println(tree.get("tester"));  // 3 (unaffected)
    }
}