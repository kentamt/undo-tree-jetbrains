// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import dev.undotree.core.History;
import dev.undotree.core.TreeLayout;
import dev.undotree.core.VisualizerKeymap;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

public final class UndoTreePanel extends JPanel implements Disposable {
    private static final Color ACCENT = new JBColor(new Color(0x2866C7), new Color(0x73A7FF));
    private final Project project;
    private final ToolWindow window;
    private final UndoTreeService service;
    private final Canvas canvas = new Canvas();
    private final JTextArea status = new JTextArea(3, 30);
    private final JToggleButton selection = new JToggleButton("Select"), text = new JToggleButton("ASCII"), timestamps = new JToggleButton("Time"), emacs = new JToggleButton("Emacs keys");
    private final JToggleButton diffToggle = new JToggleButton("Diff");
    private final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private DiffRequestPanel diffPanel;
    private Disposable keyBindings;
    private String configuredStyle, configuredKeys;
    private boolean disposed;
    private Document document;
    private History history;
    private int selected, initial, nextId;
    private Map<Integer, TreeLayout.Position> graph = Map.of();
    private TreeLayout.TextTree ascii;
    private final JScrollPane scroll = new JScrollPane(canvas);

    public UndoTreePanel(Project project, ToolWindow window) {
        super(new BorderLayout()); this.project = project; this.window = window;
        service = UndoTreeService.getInstance(project);
        JPanel toolbar = new JPanel(new GridLayout(0, 3, 4, 4));
        button(toolbar, "Undo", () -> step(true, false)); button(toolbar, "Redo", () -> step(false, false));
        button(toolbar, "← Branch", () -> horizontal(-1)); button(toolbar, "Branch →", () -> horizontal(1));
        toolbar.add(selection); selection.addActionListener(e -> { if (history != null) selected = history.current.id; redraw(); requestTreeFocus(); });
        button(toolbar, "Restore", this::restore);
        toolbar.add(diffToggle); diffToggle.addActionListener(e -> { safe(this::updateDiff); requestTreeFocus(); });
        toolbar.add(text); text.addActionListener(e -> { redraw(); requestTreeFocus(); });
        toolbar.add(emacs); emacs.addActionListener(e -> { installKeybindings(); requestTreeFocus(); });
        toolbar.add(timestamps); timestamps.addActionListener(e -> { redraw(); requestTreeFocus(); });
        button(toolbar, "Register", () -> register(false)); button(toolbar, "Recall", () -> register(true));
        button(toolbar, "Save History", () -> { if (document != null) service.save(document); });
        button(toolbar, "Abort", this::abort);
        status.setEditable(false); status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setFont(UIManager.getFont("Label.font")); status.setOpaque(false);
        setPreferredSize(new Dimension(420, 600));
        split.setTopComponent(scroll); split.setResizeWeight(0.65); split.setDividerSize(0);
        add(toolbar, BorderLayout.NORTH); add(split, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        canvas.setFocusable(true); canvas.setToolTipText("Click a state to restore it. Select mode previews without editing. Enter restores.");
        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestTreeFocus();
                for (var entry : points().entrySet()) {
                    Point p = entry.getValue(); int radius = text.isSelected() ? 10 : 22;
                    Rectangle hit = new Rectangle(p.x - radius, p.y - radius, radius * 2, radius * 2);
                    if (ascii != null) {
                        var fm = canvas.getFontMetrics(canvas.getFont()); var position = ascii.positions().get(entry.getKey());
                        int cw = fm.charWidth('o'), half = timestamps.isSelected() ? 4 : 0;
                        hit = new Rectangle(24 + (position.column() - half) * cw, 24 + position.row() * fm.getHeight(),
                                (timestamps.isSelected() ? 9 : 1) * cw, fm.getHeight());
                    }
                    if (hit.contains(e.getPoint())) {
                        selected = entry.getKey(); if (!selection.isSelected()) safe(UndoTreePanel.this::restore); else redraw(); return;
                    }
                }
            }
        });
        service.listen(this::refresh, this); refresh();
    }
    private void button(JPanel toolbar, String label, Runnable action) {
        JButton button = new JButton(label); button.addActionListener(e -> { safe(action); requestTreeFocus(); }); toolbar.add(button);
    }
    private void installKeybindings() {
        if (keyBindings != null) Disposer.dispose(keyBindings);
        keyBindings = Disposer.newDisposable("Undo Tree visualizer keys"); Disposer.register(this, keyBindings);
        VisualizerKeymap.bindings(emacs.isSelected()).forEach((key, command) -> {
            KeyStroke stroke = KeyStroke.getKeyStroke(key);
            if (stroke == null) throw new IllegalArgumentException("Invalid visualizer key: " + key);
            new DumbAwareAction() {
                @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
                @Override public void update(AnActionEvent e) { e.getPresentation().setEnabled(canvas.isFocusOwner() && !disposed); }
                @Override public void actionPerformed(AnActionEvent e) { safe(() -> dispatch(command)); }
            }.registerCustomShortcutSet(new CustomShortcutSet(stroke), canvas, keyBindings);
        });
    }
    private void dispatch(VisualizerKeymap.Command command) {
        switch (command) {
            case UP -> step(true, false); case DOWN -> step(false, false);
            case LEFT -> horizontal(-1); case RIGHT -> horizontal(1);
            case PREVIOUS_POINT -> step(true, true); case NEXT_POINT -> step(false, true);
            case RESTORE -> restore(); case TIMESTAMPS -> timestamps.doClick();
            case DIFF -> diffToggle.doClick(); case SELECTION -> selection.doClick(); case STYLE -> text.doClick();
            case QUIT -> quit(); case ABORT -> abort();
            case PAGE_UP -> page(-1); case PAGE_DOWN -> page(1);
            case SCROLL_LEFT -> scrollHorizontal(-1); case SCROLL_RIGHT -> scrollHorizontal(1);
        }
        if (command != VisualizerKeymap.Command.QUIT && command != VisualizerKeymap.Command.ABORT) requestTreeFocus();
    }
    private void safe(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException ex) { Messages.showErrorDialog(project, ex.getMessage(), "Undo Tree"); }
    }
    private void refresh() {
        if (disposed) return;
        var settings = UndoTreeSettings.getInstance().getState();
        if (!Objects.equals(configuredStyle, settings.visualizerStyle)) {
            configuredStyle = settings.visualizerStyle; text.setSelected("text".equals(configuredStyle));
        }
        if (!Objects.equals(configuredKeys, settings.visualizerKeybindings)) {
            configuredKeys = settings.visualizerKeybindings; emacs.setSelected("emacs".equals(configuredKeys)); installKeybindings();
        }
        Document active = service.activeDocument(); History fresh = service.history(active);
        if (active != document || fresh != history) {
            document = active; history = fresh; selection.setSelected(false);
            if (history != null) { initial = selected = history.current.id; nextId = history.nextId; history.seal(); }
        } else if (history != null && nextId != history.nextId) {
            // Ordinary edits start a fresh visualizer session, as in Emacs.
            initial = selected = history.current.id; nextId = history.nextId; selection.setSelected(false);
        }
        if (history != null && (!selection.isSelected() || !history.nodes.containsKey(selected))) selected = history.current.id;
        redraw();
    }
    public void beginSession() {
        refresh();
        if (history != null) { initial = selected = history.current.id; history.seal(); }
        selection.setSelected(false); redraw(); requestTreeFocus();
    }
    public JComponent focusComponent() { return canvas; }
    public void requestTreeFocus() {
        var focus = IdeFocusManager.getInstance(project);
        focus.doWhenFocusSettlesDown(() -> {
            if (!disposed && !project.isDisposed() && window.isVisible() && canvas.isShowing()) focus.requestFocus(canvas, true);
        });
    }
    private void redraw() {
        if (history == null) {
            graph = Map.of(); ascii = null; status.setText("Open a text file within the size limit to begin recording history.");
        } else {
            graph = TreeLayout.graph(history); ascii = text.isSelected() ? TreeLayout.text(history, timestamps.isSelected(), true, System.currentTimeMillis()) : null;
            var file = FileDocumentManager.getInstance().getFile(document);
            var node = history.current;
            status.setText((file == null ? "" : file.getName() + " • ") + "Current #" + node.id + " • " + history.nodes.size()
                    + " states • Redo branch " + (node.children.isEmpty() ? "—" : (node.branch + 1) + "/" + node.children.size())
                    + (selection.isSelected() ? " • Selected #" + selected + " (Enter to restore)" : " • ↑↓ undo/redo; ←→ branch"));
        }
        canvas.revalidate(); canvas.repaint();
        Point point = points().get(selected);
        if (point != null) canvas.scrollRectToVisible(new Rectangle(point.x - 50, point.y - 50, 100, 100));
        if (diffToggle.isSelected()) updateDiff();
    }
    private void step(boolean undo, boolean branchPoint) {
        if (history == null) return;
        if (selection.isSelected()) {
            History.Node n = history.require(selected), target = undo ? n.parent : n.preferred();
            if (target != null) {
                n = target;
                if (branchPoint) n = history.significantPoint(history.require(selected), undo, true);
                selected = n.id; redraw();
            }
        } else {
            if (branchPoint) service.navigate(document, history.significantPoint(history.current, undo, true).id);
            else service.step(document, undo, false);
            selected = history.current.id; redraw();
        }
    }
    private void horizontal(int amount) {
        if (history == null) return;
        if (selection.isSelected()) {
            int depth = graph.get(selected).depth();
            List<Integer> row = graph.keySet().stream().filter(id -> graph.get(id).depth() == depth)
                    .sorted(Comparator.comparingDouble(id -> graph.get(id).x())).toList();
            int index = row.indexOf(selected) + amount;
            if (index >= 0 && index < row.size()) selected = row.get(index); redraw();
        } else { service.cycle(document, amount); redraw(); }
    }
    private void restore() {
        if (history == null) return;
        service.navigate(document, selected); selected = history.current.id; selection.setSelected(false); redraw(); requestTreeFocus();
    }
    private void abort() {
        if (history != null && history.nodes.containsKey(initial)) { service.navigate(document, initial); selected = history.current.id; redraw(); }
        quit();
    }
    private void quit() {
        window.hide(() -> {
            var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true);
        });
    }
    private void page(int direction) {
        if (selection.isSelected()) { for (int i = 0; i < 10; i++) step(direction < 0, false); }
        else scrollBy(0, direction * Math.max(1, scroll.getViewport().getHeight() - canvas.getFontMetrics(canvas.getFont()).getHeight()));
    }
    private void scrollHorizontal(int direction) {
        if (selection.isSelected()) { for (int i = 0; i < 10; i++) horizontal(direction); }
        else scrollBy(direction * 10 * canvas.getFontMetrics(canvas.getFont()).charWidth('o'), 0);
    }
    private void scrollBy(int dx, int dy) {
        var viewport = scroll.getViewport(); Point p = viewport.getViewPosition(); Dimension size = canvas.getPreferredSize();
        viewport.setViewPosition(new Point(Math.max(0, Math.min(Math.max(0, size.width - viewport.getWidth()), p.x + dx)),
                Math.max(0, Math.min(Math.max(0, size.height - viewport.getHeight()), p.y + dy))));
    }
    private void updateDiff() {
        if (!diffToggle.isSelected() || history == null) {
            split.setBottomComponent(null); split.setDividerSize(0); revalidate(); return;
        }
        if (diffPanel == null) diffPanel = DiffManager.getInstance().createRequestPanel(project, this, null);
        if (split.getBottomComponent() == null) {
            split.setBottomComponent(diffPanel.getComponent()); split.setDividerSize(5); split.setDividerLocation(0.65);
        }
        history.seal();
        int id = selection.isSelected() ? selected : history.current.parent == null ? history.current.id : history.current.parent.id;
        DiffContentFactory factory = DiffContentFactory.getInstance();
        var file = FileDocumentManager.getInstance().getFile(document);
        diffPanel.setRequest(new SimpleDiffRequest("Undo Tree: State #" + id,
                factory.create(project, history.materialize(id), file == null ? null : file.getFileType()),
                factory.create(project, history.text, file == null ? null : file.getFileType()), "State #" + id, "Current #" + history.current.id));
    }
    private void register(boolean recall) {
        if (history == null) return;
        String name = Messages.showInputDialog(project, "Register name (one character)", "Undo Tree", null);
        if (name == null || name.isEmpty()) return;
        if (name.length() != 1) throw new IllegalArgumentException("Use one UTF-16 character for a register");
        if (recall) {
            for (History.Node n : history.nodes.values()) if (Objects.equals(n.register, name.charAt(0))) {
                service.navigate(document, n.id); selected = n.id; redraw(); return;
            }
            throw new IllegalArgumentException("Register is empty or its state has expired in this file");
        }
        history.register(name.charAt(0)); redraw();
    }
    private Map<Integer, Point> points() {
        Map<Integer, Point> result = new LinkedHashMap<>();
        if (ascii != null) {
            FontMetrics fm = canvas.getFontMetrics(canvas.getFont()); int cw = fm.charWidth('o'), ch = fm.getHeight();
            ascii.positions().forEach((id, p) -> result.put(id, new Point(24 + p.column() * cw + cw / 2, 24 + p.row() * ch + ch / 2)));
        } else graph.forEach((id, p) -> result.put(id, new Point(40 + (int) (p.x() * 90), 40 + p.depth() * 80)));
        return result;
    }
    private final class Canvas extends JComponent {
        Canvas() { setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14)); setOpaque(true); }
        @Override public Dimension getPreferredSize() {
            int width = 300, height = 160;
            for (Point p : points().values()) { width = Math.max(width, p.x + 65); height = Math.max(height, p.y + 60); }
            if (ascii != null) {
                FontMetrics fm = getFontMetrics(getFont());
                for (String line : ascii.text().split("\n", -1)) width = Math.max(width, 48 + line.length() * fm.charWidth('o'));
            }
            return new Dimension(width, height);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(new JBColor(Color.WHITE, new Color(0x202124))); g.fillRect(0, 0, getWidth(), getHeight());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (history == null) return;
                Map<Integer, Point> points = points(); Set<Integer> active = new HashSet<>();
                String[] asciiLines = ascii == null ? null : ascii.text().split("\n", -1);
                for (History.Node n = history.root; n != null; n = n.preferred()) active.add(n.id);
                if (ascii != null) {
                    FontMetrics fm = g.getFontMetrics(); int row = 0, cw = fm.charWidth('o');
                    Font plain = canvas.getFont(), bold = plain.deriveFont(Font.BOLD);
                    for (String line : asciiLines) {
                        for (int column = 0; column < line.length(); column++) {
                            boolean activeCell = ascii.activeCells().contains(new TreeLayout.TextPosition(row, column));
                            g.setFont(activeCell ? bold : plain);
                            g.setColor(activeCell ? new JBColor(Color.BLACK, Color.WHITE) : JBColor.GRAY);
                            g.drawString(String.valueOf(line.charAt(column)), 24 + column * cw, 24 + row * fm.getHeight() + fm.getAscent());
                        }
                        row++;
                    }
                    g.setFont(plain);
                } else {
                    for (History.Node n : history.nodes.values()) for (History.Node child : n.children) {
                        Point p = points.get(n.id), c = points.get(child.id);
                        g.setColor(active.contains(n.id) && child == n.preferred() ? ACCENT : JBColor.GRAY);
                        g.setStroke(new BasicStroke(active.contains(n.id) && child == n.preferred() ? 2f : 1f));
                        g.drawLine(p.x, p.y, c.x, c.y);
                    }
                }
                for (History.Node n : history.nodes.values()) {
                    Point p = points.get(n.id); boolean current = n == history.current;
                    int radius = ascii == null ? 14 : 8;
                    if (ascii == null) {
                        g.setColor(current ? ACCENT : new JBColor(new Color(0xEEEEEE), new Color(0x36383B)));
                        g.fillOval(p.x - radius, p.y - radius, radius * 2, radius * 2);
                        g.setColor(active.contains(n.id) ? ACCENT : JBColor.GRAY); g.drawOval(p.x - radius, p.y - radius, radius * 2, radius * 2);
                        g.setColor(current ? new JBColor(Color.WHITE, Color.BLACK) : JBColor.foreground());
                        String label = n.register == null ? n.saved ? "s" : "" + n.id : "" + n.register;
                        g.drawString(label, p.x - g.getFontMetrics().stringWidth(label) / 2, p.y + 5);
                        g.setColor(JBColor.GRAY);
                        String time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(n.time));
                        g.drawString(time, p.x - 32, p.y + 30);
                    }
                    if (ascii != null) {
                        var position = ascii.positions().get(n.id); var fm = g.getFontMetrics(); int cw = fm.charWidth('o');
                        g.setFont(canvas.getFont().deriveFont(active.contains(n.id) ? Font.BOLD : Font.PLAIN));
                        g.setColor(current ? new JBColor(Color.RED, new Color(0xFF8585))
                                : n.saved ? new JBColor(new Color(0x008B8B), Color.CYAN)
                                : n.register != null ? new JBColor(new Color(0xB89100), Color.YELLOW)
                                : active.contains(n.id) ? new JBColor(Color.BLACK, Color.WHITE) : JBColor.GRAY);
                        if (!timestamps.isSelected()) {
                            char label = n.register != null ? n.register : n.saved ? 's' : current ? 'x' : 'o';
                            g.drawString(String.valueOf(label), 24 + position.column() * cw, 24 + position.row() * fm.getHeight() + fm.getAscent());
                        } else {
                            String line = asciiLines[position.row()];
                            int start = Math.max(0, position.column() - 4), end = Math.min(line.length(), position.column() + 5);
                            if (end > start) g.drawString(line.substring(start, end), 24 + start * cw, 24 + position.row() * fm.getHeight() + fm.getAscent());
                        }
                        g.setFont(canvas.getFont());
                        if (selection.isSelected() && n.id == selected) {
                            g.setColor(ACCENT); g.setStroke(new BasicStroke(1));
                            g.drawRect(24 + position.column() * cw, 24 + position.row() * fm.getHeight(), cw, fm.getHeight());
                        }
                    } else if (n.id == selected) {
                        g.setColor(ACCENT); g.setStroke(new BasicStroke(2));
                        g.drawRect(p.x - radius - 4, p.y - radius - 4, radius * 2 + 8, radius * 2 + 8);
                    }
                }
            } finally { g.dispose(); }
        }
    }
    @Override public void dispose() { disposed = true; }
}
