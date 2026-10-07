// SPDX-License-Identifier: GPL-3.0-or-later
// Port of undo-tree drawing widths and ASCII geometry via the VS Code port; see upstream/README.md.
package dev.undotree.core;

import java.util.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TreeLayout {
    public record Position(double x, int depth) {}
    public record TextPosition(int row, int column) {}
    public record TextTree(String text, Map<Integer, TextPosition> positions, Set<TextPosition> activeCells) {}
    private TreeLayout() {}

    public static Map<Integer, Position> graph(History history) {
        Map<Integer, int[]> widths = history.widths();
        Map<Integer, Position> positions = new LinkedHashMap<>();
        record Item(History.Node node, double start, int depth) {}
        Deque<Item> stack = new ArrayDeque<>(); stack.push(new Item(history.root, 0, 0));
        while (!stack.isEmpty()) {
            Item item = stack.pop(); int[] w = widths.get(item.node.id);
            positions.put(item.node.id, new Position(item.start + w[0] + w[1] / 2.0, item.depth));
            double start = item.start;
            for (History.Node child : item.node.children) {
                stack.push(new Item(child, start, item.depth + 1)); start += History.total(widths.get(child.id));
            }
        }
        return positions;
    }
    public static TextTree text(History history) {
        return text(history, false, true, System.currentTimeMillis());
    }
    public static TextTree text(History history, boolean timestamps, boolean relative, long now) {
        int spacing = timestamps ? relative ? 9 : 13 : 3, half = spacing / 2;
        Map<Integer, int[]> widths = history.widths();
        Map<Integer, TextPosition> positions = new LinkedHashMap<>();
        List<StringBuilder> grid = new ArrayList<>();
        record Item(History.Node node, int row, int column) {}
        Deque<Item> stack = new ArrayDeque<>(); stack.push(new Item(history.root, 0, left(history.root, widths, spacing) + (timestamps ? half : 0) + 2));
        while (!stack.isEmpty()) {
            Item item = stack.pop(); History.Node node = item.node;
            String label = timestamps ? timestamp(node, node == history.current, relative, now)
                    : String.valueOf(node.register != null ? node.register : node.saved ? 's' : node == history.current ? 'x' : 'o');
            put(grid, item.row, item.column - (timestamps ? half : 0), label);
            positions.put(node.id, new TextPosition(item.row, item.column));
            int childColumn = node.children.size() == 1 ? item.column : item.column - left(node, widths, spacing)
                    + (node.children.isEmpty() ? 0 : left(node.children.get(0), widths, spacing));
            for (int i = 0; i < node.children.size(); i++) {
                History.Node child = node.children.get(i);
                put(grid, item.row + 1, item.column, "|");
                if (node.children.size() == 1) put(grid, item.row + 2, item.column, "|");
                else if (i < node.children.size() / 2) {
                    put(grid, item.row + 1, childColumn + 2, "_".repeat(Math.max(0, item.column - childColumn - 2)));
                    put(grid, item.row + 2, childColumn + 1, "/");
                } else if (node.children.size() % 2 == 1 && i == node.children.size() / 2)
                    put(grid, item.row + 2, childColumn, "|");
                else {
                    put(grid, item.row + 1, item.column + 1, "_".repeat(Math.max(0, childColumn - item.column - 2)));
                    put(grid, item.row + 2, childColumn - 1, "\\");
                }
                stack.push(new Item(child, item.row + 3, childColumn));
                if (i + 1 < node.children.size()) childColumn += right(child, widths, spacing) + left(node.children.get(i + 1), widths, spacing) + spacing + 1;
            }
        }
        int margin = Integer.MAX_VALUE;
        for (StringBuilder row : grid) for (int i = 0; i < row.length(); i++) if (row.charAt(i) != ' ') { margin = Math.min(margin, i); break; }
        if (margin == Integer.MAX_VALUE) margin = 0;
        List<String> rows = new ArrayList<>();
        for (StringBuilder row : grid) {
            int end = row.length(); while (end > margin && row.charAt(end - 1) == ' ') end--;
            rows.add(end <= margin ? "" : row.substring(margin, end));
        }
        int offset = margin;
        Set<TextPosition> active = new HashSet<>();
        for (History.Node n = history.root; n != null; n = n.preferred()) {
            TextPosition p = positions.get(n.id);
            String label = timestamps ? timestamp(n, n == history.current, relative, now) : "o";
            mark(active, p.row, p.column - (timestamps ? half : 0), label.length(), offset);
            History.Node child = n.preferred();
            if (child == null) continue;
            TextPosition c = positions.get(child.id);
            mark(active, p.row + 1, p.column, 1, offset);
            if (n.children.size() == 1) mark(active, p.row + 2, p.column, 1, offset);
            else if (n.branch < n.children.size() / 2) {
                mark(active, p.row + 1, c.column + 2, Math.max(0, p.column - c.column - 2), offset);
                mark(active, p.row + 2, c.column + 1, 1, offset);
            } else if (n.children.size() % 2 == 1 && n.branch == n.children.size() / 2)
                mark(active, p.row + 2, c.column, 1, offset);
            else {
                mark(active, p.row + 1, p.column + 1, Math.max(0, c.column - p.column - 2), offset);
                mark(active, p.row + 2, c.column - 1, 1, offset);
            }
        }
        positions.replaceAll((id, p) -> new TextPosition(p.row, p.column - offset));
        return new TextTree(String.join("\n", rows), positions, Collections.unmodifiableSet(active));
    }
    private static void mark(Set<TextPosition> cells, int row, int column, int length, int margin) {
        for (int i = 0; i < length; i++) if (column + i >= margin) cells.add(new TextPosition(row, column + i - margin));
    }
    private static int left(History.Node n, Map<Integer, int[]> widths, int spacing) {
        int[] w = widths.get(n.id); return n.children.isEmpty() ? 0 : (spacing + 1) * w[0] - (w[1] == 0 ? spacing / 2 + 1 : 0);
    }
    private static int right(History.Node n, Map<Integer, int[]> widths, int spacing) {
        int[] w = widths.get(n.id); return n.children.isEmpty() ? 0 : (spacing + 1) * w[2] - (w[1] == 0 ? spacing / 2 + 1 : 0);
    }
    private static String timestamp(History.Node n, boolean current, boolean relative, long now) {
        String suffix = n.register == null ? "   " : "[" + n.register + "]";
        if (!relative) return (current ? " *" : "  ") + DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(n.time)) + suffix;
        long seconds = Math.max(0, (now - n.time) / 1000), years = seconds / 315360000;
        String age = "-0s";
        if (years > 0) age = years > 999 ? "-ages" : "-" + years + "y";
        else {
            long[] divisors = {86400, 3600, 60, 1}; String[] units = {"d", "h", "m", "s"};
            for (int i = 0; i < divisors.length; i++) {
                long count = seconds / divisors[i];
                if (count > 0 || i == 3) { age = "-" + count + units[i]; break; }
                seconds %= divisors[i];
            }
        }
        String value = (current ? "*" : " ") + age + suffix;
        return " ".repeat(Math.max(0, 9 - value.length())) + value;
    }
    private static void put(List<StringBuilder> grid, int row, int column, String text) {
        while (grid.size() <= row) grid.add(new StringBuilder());
        StringBuilder line = grid.get(row);
        while (line.length() < column + text.length()) line.append(' ');
        for (int i = 0; i < text.length(); i++) line.setCharAt(column + i, text.charAt(i));
    }
}
