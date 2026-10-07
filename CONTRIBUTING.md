# Contributing

The reference is **unmodified Emacs undo-tree 0.8.2**. Preserve its branching,
ASCII geometry and visualizer key behavior when changing the port. Read
[PORTING.md](docs/PORTING.md) and the [source provenance](upstream/README.md).

## Requirements and build

The supported build script needs Python 3.9+, JDK 21+ and an installed IntelliJ
IDEA or PyCharm. It reads the IDE's Platform jars without downloading dependencies:

```sh
python3 scripts/build.py --ide "/path/to/your/IDE" --test
```

The script defaults to `/Applications/IntelliJ IDEA.app`, uses its bundled
compiler if available, and otherwise tries `JAVA_HOME`. It emits Java 21
bytecode. Generated classes and ZIPs stay in the ignored `build/` directory.

A Gradle configuration is also provided. With JDK 21 and a compatible Gradle
installation, `gradle buildPlugin` builds against the configured 2025.1 SDK;
`gradle -PlocalIdePath="/path/to/your/IDE" buildPlugin` uses a local IDE, and
`gradle runIde` starts a development instance. Dependencies may be downloaded.
There is currently no Gradle wrapper, and this route has not been verified in
the current environment. `gradle check` runs the Java core tests; the Python
command below also runs the full Emacs conformance comparisons.

## Tests

Core tests require only Python and a JDK, without an IDE SDK:

```sh
python3 scripts/test.py --jdk "/path/to/jdk-21-or-newer"
```

This compiles the core into `build/test-classes`, compares the history/drawing/
keymap fixtures and runs corruption, Unicode and random-navigation checks.
If Emacs is available on `PATH`, it also executes the three oracles against the
vendored original source. Otherwise, committed fixtures still run.
If a package for the current version exists, the same command verifies its
structure, plugin entrypoints and license inclusion.

The GitHub Actions workflow runs these checks on JDK 21 and 25 with live Emacs.
It does not establish native IDE UI compatibility or build a release package.

For the real Platform integration harness:

```sh
python3 scripts/ide_smoke.py --ide "/path/to/your/IDE"
```

It creates independent IDE settings, a test plugin and a sample project below
`build/ide-smoke/`. It does not install into your normal IDE profile. Use
`--compile-only` to build the harness without launching the IDE. A headless run
can test registered actions and rendering, but cannot prove native focus behavior.
Complete the [manual checks](docs/MANUAL_TEST.md) on both target products.

## Changes and reports

For a bug report, include the IDE product/version, plugin version, OS/keymap,
the shortest edit/undo/branch sequence that reproduces it, and relevant logs.
Use synthetic sample text: saved histories and diff previews contain file contents.

Keep the original files in `upstream/undo-tree/` unchanged. If reference fixtures
need an intentional update, run the relevant Emacs oracle, document why the
expected behavior changed, and review the resulting fixture diff. Avoid changing
a fixture merely to make a failing port test pass.

Describe the resulting behavior and validation in a pull request. Update usage
documentation and the changelog when user behavior changes. Contributions to
this port are distributed under GPL-3.0-or-later; preserve existing notices.
