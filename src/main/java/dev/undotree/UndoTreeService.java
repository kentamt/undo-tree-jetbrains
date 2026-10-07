// SPDX-License-Identifier: GPL-3.0-or-later
package dev.undotree;

import com.intellij.AppTopics;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import dev.undotree.core.History;
import dev.undotree.core.HistoryCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

@Service(Service.Level.PROJECT)
public final class UndoTreeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(UndoTreeService.class);
    private final Project project;
    private final Map<Document, History> histories = new IdentityHashMap<>();
    private final Map<Path, byte[]> pendingSnapshots = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService storage = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Undo Tree storage"); t.setDaemon(true); return t;
    });
    private Document active;
    private Pending pending;
    private record Pending(Document document, int id, String text) {}

    public UndoTreeService(Project project) {
        this.project = project;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void beforeDocumentChange(DocumentEvent event) {
                synchronized (UndoTreeService.this) {
                    Document document = event.getDocument();
                    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                    if (file != null && FileEditorManager.getInstance(project).isFileOpen(file)) track(document);
                }
            }
            @Override public void documentChanged(DocumentEvent event) { changed(event); }
        }, this);
        var bus = project.getMessageBus().connect(this);
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void fileOpened(FileEditorManager manager, VirtualFile file) {
                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document != null) { synchronized (UndoTreeService.this) { track(document); } }
                selectEditor();
            }
            @Override public void fileClosed(FileEditorManager manager, VirtualFile file) {
                synchronized (UndoTreeService.this) {
                    Document document = FileDocumentManager.getInstance().getCachedDocument(file);
                    if (document != null) { save(document); histories.remove(document); if (active == document) active = null; }
                }
                selectEditor();
            }
            @Override public void selectionChanged(FileEditorManagerEvent event) { selectEditor(); }
        });
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(AppTopics.FILE_DOCUMENT_SYNC, new FileDocumentManagerListener() {
                    @Override public void beforeDocumentSaving(Document document) {
                        synchronized (UndoTreeService.this) {
                            History history = histories.get(document);
                            if (history != null) { history.markSaved(); save(document); fire(); }
                        }
                    }
                });
    }
    public static UndoTreeService getInstance(Project project) { return project.getService(UndoTreeService.class); }
    public synchronized void initialize() {
        for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document != null) track(document);
        }
        selectEditor();
    }
    private synchronized void selectEditor() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        Document next = editor == null ? null : editor.getDocument();
        if (next != active) {
            History old = histories.get(active); if (old != null) old.seal();
            active = next;
        }
        if (active != null) track(active);
        fire();
    }
    private History track(Document document) {
        var settings = UndoTreeSettings.getInstance().getState();
        if (document.getTextLength() > settings.maxFileSize) return null;
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isValid() || file.getFileType().isBinary()) return null;
        History history = histories.get(document);
        if (history == null) {
            String text = document.getText();
            history = new History(text, settings.maxNodes, settings.groupDelay);
            if (settings.saveHistory) {
                try {
                    Path path = path(file);
                    byte[] bytes = pendingSnapshots.get(path);
                    if (bytes != null) history = HistoryCodec.decode(bytes, text, settings.maxNodes, settings.groupDelay);
                    else if (Files.exists(path) && Files.size(path) <= HistoryCodec.MAX_BYTES)
                        history = HistoryCodec.decode(Files.readAllBytes(path), text, settings.maxNodes, settings.groupDelay);
                } catch (IOException ex) { LOG.info("Undo Tree started fresh: " + ex.getMessage()); }
            }
            histories.put(document, history);
        }
        return history;
    }
    private synchronized void changed(DocumentEvent event) {
        Document document = event.getDocument(); History history = histories.get(document);
        if (history == null) return;
        if (document.getTextLength() > UndoTreeSettings.getInstance().getState().maxFileSize) {
            histories.remove(document); fire(); return;
        }
        String value = document.getText();
        if (pending != null && pending.document == document && pending.text.equals(value)) {
            int id = pending.id; pending = null; history.move(id);
        } else {
            UndoManager manager = UndoManager.getInstance(project);
            boolean undo = manager.isUndoInProgress(), redo = manager.isRedoInProgress();
            History.Node target = null;
            if (undo || redo) {
                for (History.Node node = undo ? history.undoTarget() : history.redoTarget(); node != null;
                        node = undo ? node.parent : node.preferred()) {
                    if (history.materialize(node.id).equals(value)) { target = node; break; }
                }
            }
            if (target != null) history.move(target.id);
            else {
                History.Change change = new History.Change(event.getOffset(), event.getOldFragment().toString(), event.getNewFragment().toString());
                // A reentrant listener can already have changed the document; preserve its actual text.
                try { history.record(value, undo || redo, List.of(change)); }
                catch (IllegalArgumentException ex) { history.record(value, true); }
            }
        }
        fire();
    }
    public synchronized Document activeDocument() { return active; }
    public synchronized History history(Document document) { return document == null ? null : track(document); }
    public synchronized void activate(Document document) { active = document; track(document); fire(); }
    public synchronized void navigate(Document document, int id) {
        History history = history(document);
        if (history == null) return;
        if (!document.getText().equals(history.text)) throw new IllegalStateException("Document changed; refresh the tree first");
        History.Plan plan = history.plan(id);
        if (plan.text().equals(history.text)) { history.move(id); fire(); return; }
        if (!FileDocumentManager.getInstance().requestWriting(document, project)) return;
        History.Change change = History.delta(history.text, plan.text());
        pending = new Pending(document, id, plan.text());
        try {
            WriteCommandAction.runWriteCommandAction(project, "Undo Tree: Restore State", null,
                    () -> document.replaceString(change.offset(), change.offset() + change.removed().length(), change.inserted()));
            if (pending != null) throw new IllegalStateException("Restore did not produce the expected document event");
            if (!history.text.equals(document.getText())) history.record(document.getText(), true);
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null && editor.getDocument() == document) {
                editor.getCaretModel().moveToOffset(Math.min(document.getTextLength(), change.offset() + change.inserted().length()));
                editor.getSelectionModel().removeSelection();
            }
        } finally { pending = null; fire(); }
    }
    public synchronized void step(Document document, boolean undo, boolean branchPoint) {
        History history = history(document); if (history == null) return;
        History.Node node = branchPoint ? history.branchPoint(undo) : undo ? history.undoTarget() : history.redoTarget();
        if (node != null) navigate(document, node.id);
    }
    public synchronized void cycle(Document document, int amount) {
        History history = history(document); if (history != null) { history.cycleBranch(amount); fire(); }
    }
    public synchronized void save(Document document) {
        History history = histories.get(document);
        if (history == null || !UndoTreeSettings.getInstance().getState().saveHistory || storage.isShutdown()) return;
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isInLocalFileSystem()) return;
        try {
            byte[] bytes = HistoryCodec.encode(history); Path target = path(file);
            pendingSnapshots.put(target, bytes);
            storage.submit(() -> {
                Path temporary = null;
                try {
                    Files.createDirectories(target.getParent());
                    temporary = Files.createTempFile(target.getParent(), "history-", ".tmp");
                    Files.write(temporary, bytes);
                    try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                    synchronized (UndoTreeService.this) { pendingSnapshots.remove(target, bytes); }
                } catch (IOException ex) { LOG.warn("Could not save Undo Tree history", ex); }
                finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
            });
        } catch (IOException ex) { LOG.warn("Could not encode Undo Tree history", ex); }
    }
    private Path path(VirtualFile file) {
        return Path.of(PathManager.getSystemPath(), "undo-tree", hash(project.getLocationHash()), hash(file.getUrl()) + ".utree");
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public void listen(Runnable listener, Disposable parent) {
        listeners.add(listener); Disposer.register(parent, () -> listeners.remove(listener));
    }
    public void refresh() { fire(); }
    private void fire() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) for (Runnable listener : listeners) listener.run();
        });
    }
    @Override public void dispose() {
        synchronized (this) {
            for (Document document : histories.keySet()) save(document);
            storage.shutdown(); histories.clear(); listeners.clear(); active = null;
        }
        try { storage.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
