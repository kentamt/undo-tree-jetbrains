# Publishing to GitHub

The repository is prepared for source publication and ZIP distribution.
Do not commit generated `build/` contents; attach the plugin ZIP to a GitHub
release alongside the source tag.

## Prepare the repository

1. Check the source, [LICENSE](../LICENSE), [NOTICE](../NOTICE), provenance and
   documentation. Keep the original Emacs files and conformance fixtures.
2. Commit the source and documentation on `main`, then publish to the intended
   GitHub repository. Configure the remote for that repository; no destination
   URL is assumed by this project.
3. Let the `Core conformance` workflow run. It checks the Java core and live Emacs
   comparisons; it does not test the native IDE UI.

## Prepare a release

1. Keep the version in `src/main/resources/META-INF/plugin.xml` and
   `build.gradle.kts` consistent, and update [CHANGELOG.md](../CHANGELOG.md).
   Update version-specific install examples in both READMEs.
2. Build and test from the source intended for the release:

   ```sh
   python3 scripts/build.py --ide "/path/to/your/IDE" --test
   ```

3. Run the real IDE harness and [manual checks](MANUAL_TEST.md) on IntelliJ IDEA
   and PyCharm 2025.1+. Record tested product/build numbers, OS and focus/keymap
   results. Document any unchecked combinations in the release notes.
4. Create a tag matching the version, such as `v0.2.0`, at the tested source commit.
   Create a GitHub release for that tag and attach
   `build/distributions/undo-tree-jetbrains-0.2.0.zip`.
5. Mark builds with outstanding GUI checks as pre-releases. Include features,
   installation instructions, validation results and known limitations in the
   release description. Installing the ZIP requires no Emacs runtime.
6. Once a release exists, add its actual download link to both READMEs. The
   source can be published before a binary release is available; retain the
   local build instructions in that case.

The current 0.2.0 package and IDE harness compile against IntelliJ IDEA 2026.2.3.
The core, ASCII and keymap comparisons pass locally. The restricted development
environment blocks the IDE's startup lock socket, so the integration harness has
not reached application initialization and native GUI checks remain outstanding.

## License and distribution

The ZIP contains GPL-3.0-or-later license and attribution notices in its JAR.
Keep corresponding source available at the release tag, including scripts,
fixtures and the vendored original files. This project does not currently publish
to JetBrains Marketplace, sign packages, or automatically publish GitHub releases.
