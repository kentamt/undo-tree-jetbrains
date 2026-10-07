// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;

public final class UndoTreeToolWindow implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        UndoTreePanel panel = new UndoTreePanel(project, toolWindow);
        var content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setPreferredFocusableComponent(panel.focusComponent());
        content.setDisposer(panel); toolWindow.getContentManager().addContent(content);
    }
}
