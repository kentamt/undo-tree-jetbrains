// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small stdin protocol keeps the tests independent of IDE and JSON libraries. */
public final class OracleRunner {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("keys")) {
            Map<String, Object> bindings = new LinkedHashMap<>();
            VisualizerKeymap.bindings(Boolean.parseBoolean(args[1])).forEach((key, command) -> {
                if (javax.swing.KeyStroke.getKeyStroke(key) == null) throw new AssertionError("Unusable key " + key);
                bindings.put(key, command.name());
            });
            System.out.println(json(bindings)); return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (args[0].equals("text")) { text(reader); return; }
        long[] clock = {1000};
        History history = new History(decode(reader.readLine()), 2000, 0, () -> ++clock[0]);
        Map<String, History.Node> labels = new HashMap<>(); Map<History.Node, String> names = new IdentityHashMap<>();
        labels.put("root", history.root); names.put(history.root, "root"); trace(history, names);
        for (String line; (line = reader.readLine()) != null;) {
            String[] parts = line.split("\t", -1);
            switch (parts[0]) {
                case "edit" -> {
                    String value = decode(parts[2]);
                    History.Node node = history.record(value, false, List.of(new History.Change(0, history.text, value)));
                    labels.put(parts[1], node); names.put(node, parts[1]);
                }
                case "undo" -> history.move(history.undoTarget().id);
                case "redo" -> history.move(history.redoTarget().id);
                case "switch" -> history.switchBranch(Integer.parseInt(parts[1]));
                case "set" -> history.move(labels.get(parts[1]).id);
                case "discard" -> history.discardNode(labels.get(parts[1]));
                default -> throw new IllegalArgumentException("Unknown operation " + line);
            }
            trace(history, names);
            History restored = HistoryCodec.decode(HistoryCodec.encode(history), history.text, 2000, 0);
            if (!restored.materialize(restored.current.id).equals(history.text)) throw new AssertionError("Roundtrip");
        }
    }
    private static void trace(History h, Map<History.Node, String> names) {
        Map<Integer, int[]> widths = h.widths(); List<Object> nodes = new ArrayList<>();
        List<History.Node> sorted = new ArrayList<>(h.nodes.values()); sorted.sort(Comparator.comparing(names::get));
        for (History.Node n : sorted) {
            int[] w = widths.get(n.id);
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("name", names.get(n)); raw.put("parent", names.get(n.parent));
            raw.put("children", n.children.stream().map(names::get).toList()); raw.put("branch", n.branch);
            raw.put("widths", List.of(w[0], w[1], w[2])); nodes.add(raw);
        }
        System.out.println(json(Map.of("text", h.text, "root", names.get(h.root), "current", names.get(h.current), "nodes", nodes)));
    }
    private static void text(BufferedReader reader) throws Exception {
        String[] header = reader.readLine().split("\t");
        History h = new History("", 2000, 0); h.nodes.clear(); Map<Integer, int[]> childIds = new HashMap<>();
        for (String line; (line = reader.readLine()) != null;) {
            String[] p = line.split("\t", -1); int id = Integer.parseInt(p[0]);
            History.Node n = new History.Node(id, null, List.of(), Long.parseLong(p[2]));
            n.branch = Integer.parseInt(p[1]); n.saved = Boolean.parseBoolean(p[3]); n.register = p[4].isEmpty() ? null : p[4].charAt(0);
            h.nodes.put(id, n); childIds.put(id, p[5].isEmpty() ? new int[0] : Arrays.stream(p[5].split(",")).mapToInt(Integer::parseInt).toArray());
        }
        for (History.Node n : h.nodes.values()) for (int id : childIds.get(n.id)) {
            History.Node child = h.nodes.get(id); child.parent = n; n.children.add(child);
        }
        h.root = h.require(Integer.parseInt(header[0])); h.current = h.require(Integer.parseInt(header[1]));
        TreeLayout.TextTree tree = TreeLayout.text(h, Boolean.parseBoolean(header[2]), Boolean.parseBoolean(header[3]), Long.parseLong(header[4]));
        List<Object> positions = new ArrayList<>();
        tree.positions().keySet().stream().sorted().forEach(id -> {
            var p = tree.positions().get(id); positions.add(Map.of("id", id, "row", p.row(), "column", p.column()));
        });
        System.out.println(json(Map.of("text", tree.text(), "positions", positions)));
    }
    private static String decode(String base64) { return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8); }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : string.toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32 || c > 126) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream().map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(OracleRunner::json).toList()) + "]";
        return value.toString();
    }
}
