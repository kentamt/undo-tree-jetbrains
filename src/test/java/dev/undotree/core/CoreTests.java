// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree.core;

import java.io.IOException;
import java.util.*;

public final class CoreTests {
    public static void main(String[] args) throws Exception {
        grouping(); changesets(); pruning(); persistence(); randomNavigation(); significantPoints(); activeBranch();
        System.out.println("Core tests passed: grouping, transactions, pruning, persistence, 5,000 random operations.");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void significantPoints() {
        History h = new History("", 200, 0);
        h.record("a", true); int a = h.current.id; h.markSaved();
        h.record("b", true); int b = h.current.id; h.register('r');
        h.record("c", true); int c = h.current.id;
        check(h.significantPoint(h.current, true, true).id == b, "Emacs backward-paragraph stops at register");
        h.move(b); check(h.significantPoint(h.current, true, true).id == a, "Emacs backward-paragraph stops at saved state");
        h.move(h.root.id); check(h.significantPoint(h.current, false, true).id == a, "Forward stop at saved");
        h.move(a); check(h.significantPoint(h.current, false, true).id == b, "Forward stop at register");
        check(h.branchPoint(false).id == c, "Explicit branch command skips saved/register nodes");
        check(h.significantPoint(h.require(c), true, true).id == b && h.current.id == a, "Selection traversal leaves buffer untouched");
    }
    private static void activeBranch() {
        History h = new History("", 200, 0);
        h.record("A", true); int a = h.current.id; h.record("AB", true); int b = h.current.id;
        h.move(a); h.record("AC", true); int c = h.current.id; h.move(a);
        var tree = TreeLayout.text(h); var cp = tree.positions().get(c); var bp = tree.positions().get(b);
        check(tree.activeCells().contains(cp) && !tree.activeCells().contains(bp), "Highlight preferred future only");
        h.switchBranch(1); var other = TreeLayout.text(h);
        check(other.text().equals(tree.text()) && h.text.equals("A"), "Branch switch changes neither ASCII glyphs nor buffer");
        check(other.activeCells().contains(bp) && !other.activeCells().contains(cp), "Highlight follows branch switch");
        check(!other.activeCells().equals(tree.activeCells()), "Branch connectors update");
    }
    private static void grouping() {
        long[] clock = {1000}; History h = new History("", 200, 600, () -> clock[0]);
        h.record("a", false); clock[0] += 100; h.record("ab", false);
        check(h.nodes.size() == 2, "Typing must group");
        h.move(h.root.id); check(h.text.isEmpty(), "Grouped undo");
        h.record("other", false); check(h.root.children.size() == 2, "Keep old future");
        h.move(h.root.id); h.switchBranch(1); check(h.text.isEmpty(), "Switch must not edit");
        h.cycleBranch(100); check(h.current.branch == 1, "Clamp right");
        h.cycleBranch(-100); check(h.current.branch == 0, "Clamp left");
        h.move(h.redoTarget().id); h.markSaved(); h.record("other!", false);
        check(h.current.parent.saved, "Saving seals group");
        h.register('r'); h.record("other!!", false); check(h.current.parent.register == 'r', "Register seals group");
        h.seal(); h.record("final", false); clock[0] += 601; h.record("later", false);
        check(h.current.parent != h.root, "Idle boundary");
    }
    private static void changesets() {
        History h = new History("日本語🙂\nabcdef", 200, 0);
        String before = h.text;
        List<History.Change> changes = List.of(new History.Change(10, "ef", "EF"), new History.Change(6, "ab", "AB"));
        String after = History.apply(before, changes); h.record(after, true, changes); h.move(h.root.id);
        check(h.text.equals(before), "Reverse transaction order"); h.move(h.redoTarget().id); check(h.text.equals(after), "Redo transaction");
        try { h.record("wrong", true, List.of(new History.Change(0, "not present", "x"))); throw new AssertionError("Mismatch accepted"); }
        catch (IllegalArgumentException expected) {}
        check(h.text.equals(after), "Failed record must be atomic");
        h.record(after, true, List.of(new History.Change(0, after, after)));
        check(h.current.parent != h.root, "Explicit equal-text states retained");
        String lone = "\ud83d";
        check(History.delta(lone, "🙂").apply(lone).equals("🙂"), "UTF-16 offsets");
    }
    private static void pruning() {
        History h = new History("", 2, 0);
        for (int i = 0; i < 500; i++) h.record("text" + i, true);
        check(h.nodes.size() == 2 && h.current.parent == h.root, "Keep last undo step");
        String previous = h.materialize(h.root.id); h.move(h.root.id); check(h.text.equals(previous), "Rebased root");
        History fork = new History("", 3, 0);
        fork.record("a", true); int a = fork.current.id; fork.record("b", true); fork.move(a); fork.record("c", true);
        check(fork.nodes.containsKey(fork.current.id), "Current protected");
        for (History.Node n : fork.nodes.values()) check(fork.materialize(n.id) != null, "Pruned links valid");
    }
    private static void persistence() throws IOException {
        History h = new History("\ud83d", 200, 0); h.record("🙂", true); int emoji = h.current.id;
        h.move(h.root.id); h.record("日本語", true); h.markSaved(); h.register('a');
        byte[] encoded = HistoryCodec.encode(h); History loaded = HistoryCodec.decode(encoded, h.text, 200, 0);
        check(loaded.current.saved && loaded.current.register == null, "Saved persists; registers do not");
        loaded.move(emoji); check(loaded.text.equals("🙂"), "Other branch restored"); loaded.move(loaded.root.id);
        check(loaded.text.equals("\ud83d"), "Lone surrogate roundtrip");
        reject(encoded, "mismatch"); reject(Arrays.copyOf(encoded, encoded.length - 1), h.text);
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1); reject(trailing, h.text);
        byte[] badHeader = encoded.clone(); badHeader[0] ^= 1; reject(badHeader, h.text);
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            byte[] corrupt = encoded.clone(); corrupt[random.nextInt(corrupt.length)] ^= (byte) (1 << random.nextInt(8));
            try { HistoryCodec.decode(corrupt, h.text, 200, 0); } catch (IOException expected) {}
        }
    }
    private static void reject(byte[] data, String text) throws IOException {
        try { HistoryCodec.decode(data, text, 200, 0); throw new AssertionError("Invalid history accepted"); }
        catch (IOException expected) {}
    }
    private static void randomNavigation() throws IOException {
        Random random = new Random(231); History h = new History("", 70, 0);
        Map<Integer, String> expected = new HashMap<>(); expected.put(h.root.id, "");
        for (int i = 0; i < 5000; i++) {
            if (random.nextInt(3) == 0) {
                List<Integer> ids = new ArrayList<>(h.nodes.keySet()); int id = ids.get(random.nextInt(ids.size()));
                h.move(id); check(h.text.equals(expected.get(id)), "Cross-branch navigation " + i);
            } else {
                String value = "state " + i + " 日本語🌳"; h.record(value, true); expected.put(h.current.id, value);
            }
            for (History.Node n : h.nodes.values()) check(h.materialize(n.id).equals(expected.get(n.id)), "State reconstruction " + i);
            if (i % 100 == 0) {
                History copy = HistoryCodec.decode(HistoryCodec.encode(h), h.text, 70, 0);
                check(copy.nodes.size() <= h.nodes.size(), "Loading may prune to the soft limit");
                for (History.Node n : copy.nodes.values()) check(copy.materialize(n.id).equals(expected.get(n.id)), "Roundtrip contents");
            }
        }
    }
}
