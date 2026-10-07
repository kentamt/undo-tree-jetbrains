// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Toby Cubitt's undo-tree 0.8.2 and its VS Code port; see upstream/README.md.
// Copyright (C) 2009-2021 Free Software Foundation, Inc.
package dev.undotree.core;

import java.util.*;
import java.util.function.LongSupplier;

/** Host-independent UTF-16 changeset tree. New futures precede older futures. */
public final class History {
    public record Change(int offset, String removed, String inserted) {
        public Change inverse() { return new Change(offset, inserted, removed); }
        public String apply(String text) {
            if (offset < 0 || offset > text.length() - removed.length()
                    || !text.startsWith(removed, offset))
                throw new IllegalArgumentException("Changeset does not match the document");
            return text.substring(0, offset) + inserted + text.substring(offset + removed.length());
        }
    }
    public static final class Node {
        public final int id;
        public Node parent;
        public final List<Node> children = new ArrayList<>();
        public final List<Change> redo = new ArrayList<>();
        public int branch;
        public long time;
        public boolean saved;
        public Character register;
        Node(int id, Node parent, List<Change> redo, long time) {
            this.id = id; this.parent = parent; this.redo.addAll(redo); this.time = time;
        }
        public Node preferred() { return children.isEmpty() ? null : children.get(branch); }
        public List<Change> undo() {
            List<Change> result = new ArrayList<>();
            for (int i = redo.size() - 1; i >= 0; i--) result.add(redo.get(i).inverse());
            return result;
        }
        @Override public String toString() {
            return "#" + id + (saved ? " [saved]" : "") + (register == null ? "" : " [" + register + "]");
        }
    }
    public record Plan(Node target, List<Node> up, List<Node> down, List<Change> changes, String text) {}
    public final Map<Integer, Node> nodes = new LinkedHashMap<>();
    public Node root, current;
    public String base, text;
    public int nextId;
    private boolean groupOpen;
    private final int maxNodes;
    private final long groupDelay;
    private final LongSupplier clock;

    public History(String text, int maxNodes, long groupDelay) {
        this(text, maxNodes, groupDelay, System::currentTimeMillis);
    }
    public History(String text, int maxNodes, long groupDelay, LongSupplier clock) {
        this.base = text; this.text = text; this.maxNodes = Math.max(2, maxNodes);
        this.groupDelay = Math.max(0, groupDelay); this.clock = clock;
        root = create(null, List.of()); current = root;
    }
    private Node create(Node parent, List<Change> changes) {
        Node node = new Node(nextId++, parent, changes, clock.getAsLong());
        nodes.put(node.id, node);
        if (parent != null) { parent.children.add(0, node); parent.branch = 0; }
        return node;
    }
    public static Change delta(String before, String after) {
        int start = 0, end = 0;
        while (start < Math.min(before.length(), after.length()) && before.charAt(start) == after.charAt(start)) start++;
        while (end < before.length() - start && end < after.length() - start
                && before.charAt(before.length() - end - 1) == after.charAt(after.length() - end - 1)) end++;
        return new Change(start, before.substring(start, before.length() - end), after.substring(start, after.length() - end));
    }
    public Node record(String value, boolean boundary) {
        if (value.equals(text)) return current;
        return record(value, boundary, List.of(delta(text, value)));
    }
    public Node record(String value, boolean boundary, List<Change> changes) {
        if (changes.isEmpty()) return current;
        if (!apply(text, changes).equals(value)) throw new IllegalArgumentException("Edit event does not match the document");
        long now = clock.getAsLong();
        if (!boundary && groupOpen && groupDelay > 0 && current != root && current.children.isEmpty()
                && !current.saved && current.register == null && now - current.time < groupDelay) {
            current.redo.addAll(changes); current.time = now;
        } else current = create(current, changes);
        text = value; groupOpen = !boundary; prune(); return current;
    }
    public void seal() { groupOpen = false; }
    public Node undoTarget() { return current.parent; }
    public Node redoTarget() { return current.preferred(); }
    public void switchBranch(int branch) {
        if (current.children.size() < 2 || branch < 0 || branch >= current.children.size())
            throw new IllegalArgumentException("Invalid branch number");
        current.branch = branch; seal();
    }
    public void cycleBranch(int amount) {
        if (!current.children.isEmpty()) current.branch = Math.max(0, Math.min(current.children.size() - 1, current.branch + amount));
        seal();
    }
    public Plan plan(int id) {
        Node target = require(id);
        Set<Node> path = new HashSet<>();
        for (Node n = target; n != null; n = n.parent) path.add(n);
        List<Node> up = new ArrayList<>(), down = new ArrayList<>();
        Node intersection = current;
        while (!path.contains(intersection)) { up.add(intersection); intersection = intersection.parent; }
        for (Node n = target; n != intersection; n = n.parent) down.add(0, n);
        List<Change> changes = new ArrayList<>();
        for (Node n : up) changes.addAll(n.undo());
        for (Node n : down) changes.addAll(n.redo);
        return new Plan(target, up, down, changes, apply(text, changes));
    }
    public Node move(int id) {
        Plan plan = plan(id);
        for (Node child = plan.target; child.parent != null; child = child.parent)
            child.parent.branch = child.parent.children.indexOf(child);
        for (Node n : plan.up) n.parent.time = clock.getAsLong();
        for (Node n : plan.down) n.time = clock.getAsLong();
        current = plan.target; text = plan.text; seal(); return current;
    }
    public Node require(int id) {
        Node node = nodes.get(id);
        if (node == null) throw new IllegalArgumentException("History state no longer exists");
        return node;
    }
    public String materialize(int id) {
        Node node = require(id);
        if (node == current) return text;
        Deque<Node> path = new ArrayDeque<>();
        for (Node n = node; n.parent != null; n = n.parent) path.addFirst(n);
        String value = base;
        for (Node n : path) value = apply(value, n.redo);
        return value;
    }
    public Node branchPoint(boolean undo) {
        return significantPoint(current, undo, false);
    }
    public Node significantPoint(Node start, boolean undo, boolean savedOrRegister) {
        Node node = start, next;
        while ((next = undo ? node.parent : node.preferred()) != null) {
            node = next;
            if (node.children.size() > 1 || (savedOrRegister && (node.saved || node.register != null))) break;
        }
        return node;
    }
    public void markSaved() { for (Node n : nodes.values()) n.saved = false; current.saved = true; seal(); }
    public void register(char name) {
        for (Node n : nodes.values()) if (Objects.equals(n.register, name)) n.register = null;
        current.register = name; seal();
    }
    public Node oldestLeaf(Node node) {
        while (!node.children.isEmpty()) node = Collections.min(node.children, Comparator.comparingLong(n -> n.time));
        return node;
    }
    public Node discardNode(Node node) {
        if (node == current) return null;
        if (node == root) {
            if (node.children.size() > 1) throw new IllegalArgumentException("Cannot discard a branching root");
            Node child = node.preferred();
            if (child == null || child == current) return null;
            base = apply(base, child.redo); root = child; child.redo.clear(); child.parent = null; nodes.remove(node.id);
            return child.children.size() > 1 || child.preferred() == current ? oldestLeaf(child) : child;
        }
        if (!node.children.isEmpty()) throw new IllegalArgumentException("Only roots and leaves can be discarded");
        Node parent = node.parent, active = parent.preferred();
        parent.children.remove(node); parent.branch = Math.max(0, parent.children.indexOf(active)); nodes.remove(node.id);
        return parent == current || (!parent.children.isEmpty() && (parent != root || parent.children.size() > 1))
                ? oldestLeaf(parent) : parent;
    }
    public void prune() {
        Node node = root.children.size() > 1 ? oldestLeaf(root) : root;
        while (node != null && nodes.size() > maxNodes) node = discardNode(node);
    }
    public Map<Integer, int[]> widths() {
        Map<Integer, int[]> result = new HashMap<>();
        List<Node> order = new ArrayList<>(); Deque<Node> stack = new ArrayDeque<>(); stack.push(root);
        while (!stack.isEmpty()) { Node n = stack.pop(); order.add(n); for (Node child : n.children) stack.push(child); }
        Collections.reverse(order);
        for (Node n : order) {
            int size = n.children.size(), middle = size / 2, left = 0, center = 0, right = 0;
            if (size == 0) center = 1;
            else {
                for (int i = 0; i < middle; i++) left += total(result.get(n.children.get(i).id));
                for (int i = middle + size % 2; i < size; i++) right += total(result.get(n.children.get(i).id));
                if (size % 2 == 1) {
                    int[] w = result.get(n.children.get(middle).id); left += w[0]; center = w[1]; right += w[2];
                }
            }
            result.put(n.id, new int[]{left, center, right});
        }
        return result;
    }
    public static int total(int[] width) { return width[0] + width[1] + width[2]; }
    public static String apply(String value, List<Change> changes) {
        for (Change change : changes) value = change.apply(value);
        return value;
    }
}
