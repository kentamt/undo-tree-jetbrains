// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindowManager;

public final class UndoTreeActions {
    private UndoTreeActions() {}
    public abstract static class Base extends DumbAwareAction {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getProject() != null);
        }
        @Override public final void actionPerformed(AnActionEvent e) {
            if (e.getProject() == null) return;
            UndoTreeService service = UndoTreeService.getInstance(e.getProject());
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            Document document = editor == null ? service.activeDocument() : editor.getDocument();
            try { perform(e, service, document); }
            catch (IllegalArgumentException | IllegalStateException ex) { Messages.showErrorDialog(e.getProject(), ex.getMessage(), "Undo Tree"); }
        }
        protected abstract void perform(AnActionEvent e, UndoTreeService service, Document document);
    }
    public static final class Show extends Base {
        @Override protected void perform(AnActionEvent e, UndoTreeService service, Document document) {
            if (document != null) service.activate(document);
            var window = ToolWindowManager.getInstance(e.getProject()).getToolWindow("Undo Tree");
            if (window != null) window.activate(() -> {
                var content = window.getContentManager().getSelectedContent();
                if (content != null && content.getComponent() instanceof UndoTreePanel panel) panel.beginSession();
            }, true, true);
        }
    }
    public static final class Undo extends Base {
        @Override protected void perform(AnActionEvent e, UndoTreeService service, Document document) { service.step(document, true, false); }
    }
    public static final class Redo extends Base {
        @Override protected void perform(AnActionEvent e, UndoTreeService service, Document document) { service.step(document, false, false); }
    }
    public static final class PreviousBranch extends Base {
        @Override protected void perform(AnActionEvent e, UndoTreeService service, Document document) { service.cycle(document, -1); }
    }
    public static final class NextBranch extends Base {
        @Override protected void perform(AnActionEvent e, UndoTreeService service, Document document) { service.cycle(document, 1); }
    }
    public static final class UndoBranch extends Base {
        @Override protected void perform(AnActionEvent e, UndoTreeService service, Document document) { service.step(document, true, true); }
    }
    public static final class RedoBranch extends Base {
        @Override protected void perform(AnActionEvent e, UndoTreeService service, Document document) { service.step(document, false, true); }
    }
}
