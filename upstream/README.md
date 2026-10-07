# Reference sources

This port references Emacs undo-tree **0.8.2**, via the
[VS Code JavaScript port](https://github.com/kentamt/undo-tree-vscode/tree/2c7aa3262560ee27f40da1351fee024f5c6e5c3c)
at commit `2c7aa3262560ee27f40da1351fee024f5c6e5c3c`.
Sources and fixtures were copied on 2026-10-07. The original provenance note from
that project is retained verbatim in [REFERENCE.md](REFERENCE.md).

| File | Version | SHA-256 |
| --- | --- | --- |
| `undo-tree/undo-tree.el` | 0.8.2 | `68be39d82047ecb71d763d21b04a6e6aef1bfa068958458e829e040079d613f3` |
| `undo-tree/queue.el` | 0.2 | `6be60aa5f429e0e3e2c000563356855e3edb7f5378ebf8499ed35aac1141a233` |

Both are unmodified GPL-3.0-or-later sources. Copyright notices and the complete
license are retained. Upstream: [GNU ELPA](https://elpa.gnu.org/packages/undo-tree.html)
and [Toby Cubitt](https://www.dr-qubit.org/undo-tree.html).

`test/oracle.el`, `test/text-oracle.el` and `test/keymap-oracle.el` execute these
copies in Emacs.
`test/fixtures/` contains the original scenarios and committed Emacs output from
the reference project. `python3 scripts/test.py` compares Java against the
fixtures and, when Emacs is installed, against a fresh live run.
