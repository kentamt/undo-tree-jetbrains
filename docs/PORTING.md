# Emacs undo-tree → JetBrains

The Java implementation is based on Toby Cubitt's undo-tree 0.8.2 and the
[JavaScript port for VS Code](https://github.com/kentamt/undo-tree-vscode/tree/2c7aa3262560ee27f40da1351fee024f5c6e5c3c) at commit
`2c7aa3262560ee27f40da1351fee024f5c6e5c3c`.
The original GPL source is retained unmodified in `upstream/undo-tree/`.

| Source behavior | Java implementation |
| --- | --- |
| `undo-tree-make-node`, grow, transfer | `History.create/record`: new child first, old futures retained |
| Undo / redo | `History.plan/move`: ordered changes and inverse changes, reached-node timestamps |
| `undo-tree-switch-branch` | `switchBranch/cycleBranch`: choose route without editing; clamp horizontal navigation |
| `undo-tree-set` | `plan`: ascend to common ancestor and descend to target; select its ancestor route |
| Oldest leaf and discard | `oldestLeaf/discardNode/prune`: root/leaf pruning, current and last-undo protection |
| Left/center/right subtree widths | `widths`: iterative postorder, including even-child center gaps |
| Drawing | `TreeLayout.graph/text`: graph positions and literal two-row ASCII connectors |
| Timestamp strings | Relative unit thresholds and padded strings; absolute local clock |
| Selection mode | `UndoTreePanel`: selection without edits; horizontal movement includes cousins on the same row |
| Set, diff, quit, abort | Document restore; IDE diff window; keep current or restore session start |
| Registers | Per-document session registers, sealed editing boundaries; no persistence |
| Save/load history | Versioned changeset format, SHA-256 text guard, graph and changeset validation |

## IDE adapter

`UndoTreeService` is a project service initialized after startup. It subscribes to
physical document events and editor open/close/selection events. Before the first
edit it captures the original text. Histories are independent per document; no
language-specific plugin is required. Binary files and files above the configured
size limit are skipped. Crossing the size limit drops in-memory tracking; a later
edit in a smaller file can start a fresh history.

IDE `DocumentEvent` offsets use UTF-16 and describe one already-applied edit.
Changesets validate removed text. Consecutive edits may join one state according
to an idle interval; navigating, saving, switching files, opening the visualizer,
creating a register and showing a diff seal the group. Text-equal explicit
transactions retain separate states in the core.

Restoring a state computes the tree traversal, then applies a minimal contiguous
replacement in a `WriteCommandAction`. A matching synchronous document event
commits the tree move. Follow-up document events are recorded against the restored
state. The adapter refuses stale input and checks the resulting document. If a
reentrant listener changes the text before the expected event is seen, it retains
the observed edits and reports the incomplete restore. The selected editor's
caret is placed near the final changed span; exact Emacs point restoration is not
implemented. Read-only documents go through the IDE's writing permission check.

Native Undo/Redo uses its own manager and grouping. Matching text on the native
operation's ancestor/selected-descendant route moves the tree pointer; unmatched
edits become new states. Native commands cannot select an abandoned future from
this tree. Use the plugin actions for deterministic tree navigation. Existing
native keymaps are retained; users can assign normal Undo/Redo shortcuts to the
plugin actions through Keymap settings.

The tool window defaults to ASCII and Emacs keys. Both defaults are configurable;
session buttons switch display/key modes without changing selection or the Abort
target. ASCII geometry and timestamp strings match upstream fixtures, including
the original node glyph precedence and two connector rows. Preferred-route glyphs
and connectors use a bold face; current, saved and register states follow the
original red/cyan/yellow face roles, adjusted for IDE theme readability. Selection
mode shows a character-sized cursor. Graphical display is an optional mode.

`VisualizerKeymap` covers the original visualizer and selection keymaps: arrows,
p/n/b/f, Control+P/N/B/F, Control+Up/Down, Meta-{/}, t/d/s/q, Control+Q,
PageUp/PageDown and ,/./</>. Selection-mode scrolling keys select ten states.
Significant-point traversal stops at a fork, saved state or register. Control+V
and Meta-V are additional paging aliases, and V switches rendering. Emacs-specific
aliases are omitted in Standard mode. Component-scoped IntelliJ actions keep IDE
global shortcuts from taking these keys while the tree owns focus; outside the
canvas they remain inactive.

The tab registers its canvas as the preferred focus component. Opening the tree
activates its contents explicitly and requests focus through `IdeFocusManager`
after pending focus changes settle. Navigation and toolbar operations return focus
to the canvas. Closing returns focus to the document editor. Diff is an embedded
panel, toggled by d, and follows navigation/selection without opening a dialog.

## Storage

Persistence uses a bounded data stream rather than Java object deserialization.
Strings preserve exact UTF-16 units, including intermediate surrogate edits.
Graph identity, parents, ordered children, branches, and every changeset are
validated. Traversal keeps one working text instead of materializing all branches
at once. Input size, materialized text size and validation work are bounded.
Invalid, mismatched or excessively large histories start fresh, with a log entry.

Per-project/per-file SHA-256 names live below the IDE system directory. Writes run
on one worker in order and replace temporary files atomically where supported.
Pending snapshots are used if a file is reopened before the write completes.
Project disposal waits up to five seconds for queued writes. File rename/move
changes the storage key, and histories are not migrated between IDE installations.
Saved markers persist; registers do not. Past file contents remain on disk until
their history files are removed. Emacs Lisp and VS Code JSON histories are not
accepted.

## Validation and limits

`scripts/test.py` compares the Java trace after every operation with the fixtures
generated by the unmodified upstream Emacs implementation. If Emacs is available,
it reruns both original oracles. Tests cover ordered branches, equal-text states,
cross-branch traversal, Unicode, pruning, ASCII positions, timestamps, grouping,
multi-edit reversal, persistence corruption, surrogate roundtrips, and randomized
navigation. ZIP checks verify declared entrypoints and platform-only dependency.
The keymap oracle reads both maps directly from the unmodified Emacs package;
tests compare the normalized Java shortcuts against pinned and live Emacs maps.
Additional checks cover significant-point stops and preferred-route ASCII faces.

These model tests do not establish native IDE UI behavior. `scripts/ide_smoke.py`
provides a separate test-only plugin and isolated headless IDE launch to exercise
the real document service, action loading and offscreen Swing rendering. In the
same harness, registered IDE shortcut actions exercise Undo/Redo, selection,
Enter, mode changes and Abort. `--compile-only` validates compilation without an
IDE launch. Headless checks cannot establish actual native keyboard focus.
In the current restricted environment the IDE cannot create its startup lock socket, so
this test has not reached application initialization. Manual GUI testing on
IntelliJ IDEA and PyCharm 2025.1+ remains necessary.

The optional Emacs regional-undo fragment-splicing algorithms, text properties,
markers, prefix arguments, buffer faces, lazy drawing, compression and weak-reference
GC pools are not ported. There is no promise of importing history from before
plugin startup, preserving unsaved content across a crash, or reproducing the
IDE's multi-file undo groups. Undo Tree operates on one text document at a time.
