// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree.core;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned, bounded data format; no Java object deserialization. */
public final class HistoryCodec {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAGIC = 0x55545245;
    private HistoryCodec() {}

    public static byte[] encode(History history) throws IOException {
        history.seal();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(1); out.write(digest(history.text));
            string(out, history.base);
            out.writeInt(history.root.id); out.writeInt(history.current.id); out.writeInt(history.nextId);
            out.writeInt(history.nodes.size());
            for (History.Node n : history.nodes.values()) {
                out.writeInt(n.id); out.writeInt(n.parent == null ? -1 : n.parent.id);
                out.writeInt(n.branch); out.writeLong(n.time); out.writeBoolean(n.saved);
                out.writeInt(n.children.size());
                for (History.Node child : n.children) out.writeInt(child.id);
                out.writeInt(n.redo.size());
                for (History.Change change : n.redo) {
                    out.writeInt(change.offset()); string(out, change.removed()); string(out, change.inserted());
                }
                if (bytes.size() > MAX_BYTES) throw new IOException("History exceeds storage limit");
            }
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("History exceeds storage limit");
        return bytes.toByteArray();
    }
    public static History decode(byte[] bytes, String text, int maxNodes, long groupDelay) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("History exceeds storage limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Unsupported history format");
            byte[] hash = in.readNBytes(32);
            if (!MessageDigest.isEqual(hash, digest(text))) throw new IOException("Document changed since history was saved");
            History history = new History(string(in), maxNodes, groupDelay); history.nodes.clear();
            int rootId = in.readInt(), currentId = in.readInt(), nextId = in.readInt();
            int count = bounded(in.readInt(), 100_000);
            Map<Integer, Integer> parents = new HashMap<>();
            Map<Integer, int[]> children = new HashMap<>();
            int largestId = -1;
            for (int i = 0; i < count; i++) {
                int id = in.readInt(), parent = in.readInt(), branch = in.readInt();
                long time = in.readLong(); boolean saved = in.readBoolean();
                if (id < 0 || history.nodes.containsKey(id)) throw new IOException("Invalid node id");
                int[] childIds = new int[bounded(in.readInt(), count)];
                for (int j = 0; j < childIds.length; j++) childIds[j] = in.readInt();
                if (branch < 0 || branch >= Math.max(1, childIds.length)) throw new IOException("Invalid branch");
                List<History.Change> redo = new ArrayList<>();
                int edits = bounded(in.readInt(), MAX_BYTES / 12);
                for (int j = 0; j < edits; j++) {
                    int offset = in.readInt();
                    if (offset < 0) throw new IOException("Invalid changeset offset");
                    redo.add(new History.Change(offset, string(in), string(in)));
                }
                History.Node node = new History.Node(id, null, redo, time); node.branch = branch; node.saved = saved;
                history.nodes.put(id, node); parents.put(id, parent); children.put(id, childIds);
                largestId = Math.max(largestId, id);
            }
            if (in.available() != 0 || nextId <= largestId || count == 0) throw new IOException("Invalid history header");
            history.root = history.nodes.get(rootId); history.current = history.nodes.get(currentId); history.nextId = nextId;
            if (history.root == null || history.current == null) throw new IOException("Missing root or current state");
            for (History.Node n : history.nodes.values()) {
                int parentId = parents.get(n.id);
                n.parent = history.nodes.get(parentId);
                if ((n == history.root && (parentId != -1 || !n.redo.isEmpty()))
                        || (n != history.root && n.parent == null)) throw new IOException("Invalid parent");
                for (int id : children.get(n.id)) {
                    History.Node child = history.nodes.get(id);
                    if (child == null || parents.get(id) != n.id) throw new IOException("Invalid child");
                    n.children.add(child);
                }
            }
            final class Frame {
                final History.Node node; int nextChild;
                Frame(History.Node node) { this.node = node; }
            }
            // Traverse with forward/inverse changes instead of retaining a full text per branch.
            Deque<Frame> stack = new ArrayDeque<>(); Set<Integer> seen = new HashSet<>();
            stack.push(new Frame(history.root)); seen.add(history.root.id);
            String value = history.base, actual = history.current == history.root ? value : null;
            long[] budget = {512_000_000};
            if (value.length() > 10_000_000) throw new IOException("History text exceeds size limit");
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (frame.nextChild < frame.node.children.size()) {
                    History.Node child = frame.node.children.get(frame.nextChild++);
                    if (!seen.add(child.id)) throw new IOException("Cyclic or duplicate history links");
                    value = checkedApply(value, child.redo, budget);
                    if (child == history.current) actual = value;
                    stack.push(new Frame(child));
                } else {
                    stack.pop();
                    if (!stack.isEmpty()) value = checkedApply(value, frame.node.undo(), budget);
                }
            }
            if (seen.size() != count || !text.equals(actual)) throw new IOException("History does not match document");
            history.text = text; history.seal(); history.prune(); return history;
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
            throw new IOException("Invalid history changeset", ex);
        }
    }
    private static int bounded(int value, int max) throws IOException {
        if (value < 0 || value > max) throw new IOException("Invalid history length");
        return value;
    }
    private static String checkedApply(String value, List<History.Change> changes, long[] budget) throws IOException {
        for (History.Change change : changes) {
            long length = (long) value.length() - change.removed().length() + change.inserted().length();
            if (length > 10_000_000 || (budget[0] -= Math.max(value.length(), length)) < 0)
                throw new IOException("History exceeds validation limits");
            value = change.apply(value);
        }
        return value;
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        if (value.length() > MAX_BYTES / 2) throw new IOException("Text exceeds storage limit");
        out.writeInt(value.length());
        // Preserve UTF-16 code units, including intermediate edits of surrogate pairs.
        for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }
    private static String string(DataInputStream in) throws IOException {
        int length = bounded(in.readInt(), in.available() / 2);
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) chars[i] = in.readChar();
        return new String(chars);
    }
    private static byte[] digest(String value) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < value.length(); i++) {
                hash.update((byte) (value.charAt(i) >>> 8)); hash.update((byte) value.charAt(i));
            }
            return hash.digest();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
