# Changelog

## Unreleased

- Prepare English and Japanese READMEs, detailed usage, contributor guidance,
  release instructions and a bug report template for GitHub publication.
- Add a GitHub Actions workflow for core and live Emacs conformance on JDK 21 and 25.

## 0.2.0 — 2026-10-07

- Default to Emacs ASCII rendering and Emacs visualizer keys; add persistent
  ASCII / Graphical and Emacs / Standard options.
- Add p/n/b/f, Control aliases, Meta-{/}, timestamps and scrolling keys from the
  original keymaps, including ten-state selection movement.
- Highlight the preferred ASCII route and distinguish current, saved and
  register states using the original face roles.
- Register the tree as the tab's preferred focus target and request focus after
  IDE focus transitions settle.
- Embed a toggled diff panel that follows navigation and selection.
- Stop significant-point movement at saved states and registers as well as forks.
- Compare visualizer and selection keymaps with pinned and live Emacs output.

## 0.1.0 — 2026-10-07

- Initial Java port of branching history, changeset traversal and pruning.
- Add graphical and ASCII views, selection, diff, registers and history persistence.
- Add an IntelliJ Platform adapter targeting IntelliJ IDEA and PyCharm 2025.1+.
- Retain original GPL source, attribution and Emacs conformance fixtures.

These versions are local development builds. GitHub release publication and
native GUI validation are separate from these version entries.
