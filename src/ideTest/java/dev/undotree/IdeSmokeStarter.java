// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import dev.undotree.core.History;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;

/** Test-only starter, installed in an isolated sandbox by scripts/ide_smoke.py. */
public final class IdeSmokeStarter implements ApplicationStarter {
    public String getCommandName() { return "undo-tree-smoke"; }
    @Override public boolean isHeadless() { return true; }
    @Override public void main(List<String> args) {
        int exitCode = 0;
        try {
            Path directory = Path.of(System.getProperty("undo.tree.smoke.path")); Files.createDirectories(directory);
            Path filePath = directory.resolve("example.txt"); Files.writeString(filePath, "");
            Project project = ProjectManager.getInstance().createProject("UndoTreeSmoke", directory.toString());
            check(project != null, "Project created");
            ApplicationManager.getApplication().invokeAndWait(() -> {
                try { run(project, filePath); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            System.out.println("UNDO_TREE_IDE_SMOKE_PASSED");
        } catch (Throwable ex) { ex.printStackTrace(); exitCode = 1; }
        System.exit(exitCode);
    }
    private static void run(Project project, Path path) throws Exception {
        var defaults = new UndoTreeSettings.Values();
        check(defaults.visualizerStyle.equals("text") && defaults.visualizerKeybindings.equals("emacs"), "Emacs defaults");
        UndoTreeSettings.getInstance().getState().groupDelay = 0;
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        check(file != null, "Physical file found");
        Document document = FileDocumentManager.getInstance().getDocument(file);
        check(document != null, "Physical document loaded");
        UndoTreeService service = UndoTreeService.getInstance(project); service.activate(document);
        History h = service.history(document); int root = h.root.id;
        edit(project, document, "A"); int a = h.current.id;
        edit(project, document, "AB"); int b = h.current.id;
        service.step(document, true, false); check(document.getText().equals("A") && h.current.id == a, "IDE undo");
        edit(project, document, "AC"); int c = h.current.id;
        check(h.require(a).children.size() == 2, "Abandoned branch retained");
        service.navigate(document, a); service.cycle(document, 1);
        check(document.getText().equals("A"), "Branch selection leaves document intact");
        service.step(document, false, false); check(h.current.id == b && document.getText().equals("AB"), "Selected old branch redone");
        service.navigate(document, c); check(document.getText().equals("AC"), "Cross-branch restore");
        service.navigate(document, root); check(document.getText().isEmpty(), "Root restore");
        service.navigate(document, b); h.register('r');
        FileDocumentManager.getInstance().saveDocument(document); check(h.current.saved, "Save marker listener");
        for (String id : List.of("UndoTree.Show", "UndoTree.Undo", "UndoTree.Redo", "UndoTree.NextBranch"))
            check(ActionManager.getInstance().getAction(id) != null, "Action loaded: " + id);
        ToolWindow window = (ToolWindow) Proxy.newProxyInstance(ToolWindow.class.getClassLoader(), new Class<?>[]{ToolWindow.class},
                (proxy, method, values) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        UndoTreePanel panel = new UndoTreePanel(project, window); panel.beginSession(); panel.setSize(520, 720);
        check(panel.focusComponent().isFocusable(), "Tree is the focus target");
        JToggleButton ascii = toggle(panel, "ASCII"), keys = toggle(panel, "Emacs keys"), selection = toggle(panel, "Select");
        check(ascii.isSelected() && keys.isSelected(), "Default panel uses ASCII and Emacs keys");
        key(panel, "control P"); check(document.getText().equals("A"), "Control+P executes actual registered tree action");
        key(panel, "N"); check(document.getText().equals("AB"), "Plain n redoes");
        key(panel, "S"); key(panel, "control P"); check(document.getText().equals("AB"), "Selection motion does not edit");
        key(panel, "ENTER"); check(document.getText().equals("A") && !selection.isSelected(), "Enter restores and exits selection");
        key(panel, "control N"); check(document.getText().equals("AB"), "Redo after selection restore");
        keys.doClick(); check(action(panel, "control P") == null && action(panel, "P") == null, "Standard mode removes Emacs bindings");
        check(action(panel, "UP") != null, "Standard arrows remain available"); keys.doClick();
        ascii.doClick(); ascii.doClick();
        key(panel, "P"); key(panel, "control Q"); check(document.getText().equals("AB"), "Mode switches preserve Abort target");
        layout(panel);
        BufferedImage image = new BufferedImage(520, 720, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
        ImageIO.write(image, "png", path.getParent().resolve("tool-window.png").toFile());
        Disposer.dispose(panel);
        service.save(document);
        check(h.text.equals(document.getText()), "Document and model remain synchronized");
    }
    private static void edit(Project project, Document document, String text) {
        WriteCommandAction.runWriteCommandAction(project, () -> document.setText(text));
    }
    private static AnAction action(UndoTreePanel panel, String stroke) {
        var key = KeyStroke.getKeyStroke(stroke);
        for (AnAction action : ActionUtil.getActions(panel.focusComponent()))
            for (var shortcut : action.getShortcutSet().getShortcuts())
                if (shortcut instanceof KeyboardShortcut keyboard && keyboard.getFirstKeyStroke().equals(key)) return action;
        return null;
    }
    private static void key(UndoTreePanel panel, String stroke) {
        AnAction action = action(panel, stroke); check(action != null, "Key is registered: " + stroke);
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "UndoTreeSmoke", DataContext.EMPTY_CONTEXT));
    }
    private static JToggleButton toggle(java.awt.Container parent, String label) {
        for (var child : parent.getComponents()) {
            if (child instanceof JToggleButton button && label.equals(button.getText())) return button;
            if (child instanceof java.awt.Container nested) {
                JToggleButton button = toggle(nested, label); if (button != null) return button;
            }
        }
        return null;
    }
    private static void layout(java.awt.Container parent) {
        parent.doLayout(); for (java.awt.Component child : parent.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
