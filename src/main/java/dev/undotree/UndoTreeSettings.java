// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;

@State(name = "UndoTreeSettings", storages = @Storage("undo-tree.xml"))
@Service(Service.Level.APP)
public final class UndoTreeSettings implements PersistentStateComponent<UndoTreeSettings.Values> {
    public static final class Values {
        public int groupDelay = 600;
        public int maxNodes = 200;
        public int maxFileSize = 1_000_000;
        public boolean saveHistory = true;
        public String visualizerStyle = "text";
        public String visualizerKeybindings = "emacs";
    }
    private Values values = new Values();
    public static UndoTreeSettings getInstance() { return ApplicationManager.getApplication().getService(UndoTreeSettings.class); }
    @Override public Values getState() { return values; }
    @Override public void loadState(Values state) {
        state.groupDelay = Math.max(0, Math.min(10000, state.groupDelay));
        state.maxNodes = Math.max(2, Math.min(2000, state.maxNodes));
        state.maxFileSize = Math.max(1000, Math.min(10_000_000, state.maxFileSize));
        if (!"graphical".equals(state.visualizerStyle)) state.visualizerStyle = "text";
        if (!"standard".equals(state.visualizerKeybindings)) state.visualizerKeybindings = "emacs";
        values = state;
    }
}
