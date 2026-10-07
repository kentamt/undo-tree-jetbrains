// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree;

import com.intellij.openapi.options.Configurable;
import javax.swing.*;
import java.awt.*;

public final class UndoTreeConfigurable implements Configurable {
    private JSpinner delay, nodes, size;
    private JCheckBox save;
    private JComboBox<String> style, keys;
    @Override public String getDisplayName() { return "Undo Tree"; }
    @Override public JComponent createComponent() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));
        delay = new JSpinner(new SpinnerNumberModel(600, 0, 10000, 100));
        nodes = new JSpinner(new SpinnerNumberModel(200, 2, 2000, 10));
        size = new JSpinner(new SpinnerNumberModel(1_000_000, 1000, 10_000_000, 1000));
        save = new JCheckBox("Save history across IDE sessions");
        style = new JComboBox<>(new String[]{"Emacs ASCII", "Graphical"});
        keys = new JComboBox<>(new String[]{"Emacs", "Standard"});
        panel.add(new JLabel("Tree display")); panel.add(style);
        panel.add(new JLabel("Visualizer keybindings")); panel.add(keys);
        panel.add(new JLabel("Typing group delay (ms)")); panel.add(delay);
        panel.add(new JLabel("Maximum states per file (soft limit)")); panel.add(nodes);
        panel.add(new JLabel("Maximum file size (UTF-16 units)")); panel.add(size);
        panel.add(save); panel.add(new JLabel("History contains previous file contents."));
        panel.add(new JLabel("Grouping and state limits apply to newly tracked files.")); panel.add(new JLabel());
        JPanel outer = new JPanel(new BorderLayout()); outer.add(panel, BorderLayout.NORTH); reset(); return outer;
    }
    @Override public boolean isModified() {
        if (delay == null) return false;
        var s = UndoTreeSettings.getInstance().getState();
        return (int) delay.getValue() != s.groupDelay || (int) nodes.getValue() != s.maxNodes
                || (int) size.getValue() != s.maxFileSize || save.isSelected() != s.saveHistory
                || style.getSelectedIndex() != ("text".equals(s.visualizerStyle) ? 0 : 1)
                || keys.getSelectedIndex() != ("emacs".equals(s.visualizerKeybindings) ? 0 : 1);
    }
    @Override public void apply() {
        var s = new UndoTreeSettings.Values();
        s.groupDelay = (int) delay.getValue(); s.maxNodes = (int) nodes.getValue();
        s.maxFileSize = (int) size.getValue(); s.saveHistory = save.isSelected();
        s.visualizerStyle = style.getSelectedIndex() == 0 ? "text" : "graphical";
        s.visualizerKeybindings = keys.getSelectedIndex() == 0 ? "emacs" : "standard";
        UndoTreeSettings.getInstance().loadState(s);
        for (var project : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
            var service = project.getServiceIfCreated(UndoTreeService.class);
            if (service != null) service.refresh();
        }
    }
    @Override public void reset() {
        if (delay == null) return;
        var s = UndoTreeSettings.getInstance().getState();
        delay.setValue(s.groupDelay); nodes.setValue(s.maxNodes); size.setValue(s.maxFileSize); save.setSelected(s.saveHistory);
        style.setSelectedIndex("text".equals(s.visualizerStyle) ? 0 : 1);
        keys.setSelectedIndex("emacs".equals(s.visualizerKeybindings) ? 0 : 1);
    }
    @Override public void disposeUIResources() { delay = null; nodes = null; size = null; save = null; style = null; keys = null; }
}
