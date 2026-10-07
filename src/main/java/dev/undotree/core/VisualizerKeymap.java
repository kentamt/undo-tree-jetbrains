// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from undo-tree-visualizer-mode-map / selection-mode-map (undo-tree.el 1183-1283).
package dev.undotree.core;

import java.util.*;

/** Swing keystroke spellings; the host registers them as component-scoped IDE actions. */
public final class VisualizerKeymap {
    public enum Command { UP, DOWN, LEFT, RIGHT, PREVIOUS_POINT, NEXT_POINT, RESTORE,
        TIMESTAMPS, DIFF, SELECTION, STYLE, QUIT, ABORT, PAGE_UP, PAGE_DOWN, SCROLL_LEFT, SCROLL_RIGHT }
    private VisualizerKeymap() {}
    public static Map<String, Command> bindings(boolean emacs) {
        Map<String, Command> map = new LinkedHashMap<>();
        add(map, Command.UP, "UP"); add(map, Command.DOWN, "DOWN");
        add(map, Command.LEFT, "LEFT"); add(map, Command.RIGHT, "RIGHT");
        add(map, Command.PREVIOUS_POINT, "control UP"); add(map, Command.NEXT_POINT, "control DOWN");
        add(map, Command.RESTORE, "ENTER"); add(map, Command.TIMESTAMPS, "T");
        add(map, Command.DIFF, "D"); add(map, Command.SELECTION, "S"); add(map, Command.STYLE, "V");
        add(map, Command.QUIT, "Q", "ESCAPE");
        add(map, Command.PAGE_UP, "PAGE_UP"); add(map, Command.PAGE_DOWN, "PAGE_DOWN");
        add(map, Command.SCROLL_LEFT, "COMMA", "shift COMMA");
        add(map, Command.SCROLL_RIGHT, "PERIOD", "shift PERIOD");
        if (emacs) {
            add(map, Command.UP, "P", "control P"); add(map, Command.DOWN, "N", "control N");
            add(map, Command.LEFT, "B", "control B"); add(map, Command.RIGHT, "F", "control F");
            add(map, Command.PREVIOUS_POINT, "alt shift OPEN_BRACKET");
            add(map, Command.NEXT_POINT, "alt shift CLOSE_BRACKET");
            add(map, Command.ABORT, "control Q");
            add(map, Command.PAGE_DOWN, "control V"); add(map, Command.PAGE_UP, "alt V");
        }
        return Collections.unmodifiableMap(map);
    }
    private static void add(Map<String, Command> map, Command command, String... keys) {
        for (String key : keys) map.put(key, command);
    }
}
