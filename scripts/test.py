#!/usr/bin/env python3
"""Compare the Java port to pinned Emacs traces and, if available, live Emacs."""
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME", "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"))
    args = parser.parse_args()
    jdk = Path(args.jdk)
    classes = ROOT / "build/test-classes"
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "src/main/java/dev/undotree/core").glob("*.java")) + sorted((ROOT / "src/test/java").rglob("*.java"))
    subprocess.run([str(jdk / "bin/javac"), "--release", "21", "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)], check=True)
    command = [str(jdk / "bin/java"), "-Duser.timezone=UTC", "-cp", str(classes)]
    subprocess.run([*command, "dev.undotree.core.CoreTests"], check=True)
    runner = [*command, "dev.undotree.core.OracleRunner"]
    keys = json.loads(subprocess.run([*runner, "keys", "true"], text=True, capture_output=True, check=True).stdout)
    standard_keys = json.loads(subprocess.run([*runner, "keys", "false"], text=True, capture_output=True, check=True).stdout)
    source_keys = json.loads((ROOT / "test/fixtures/emacs-keymaps-0.8.2.json").read_text())
    source_commands = {
        "undo-tree-visualize-undo": "UP", "undo-tree-visualize-redo": "DOWN",
        "undo-tree-visualize-switch-branch-left": "LEFT", "undo-tree-visualize-switch-branch-right": "RIGHT",
        "undo-tree-visualizer-select-previous": "UP", "undo-tree-visualizer-select-next": "DOWN",
        "undo-tree-visualizer-select-left": "LEFT", "undo-tree-visualizer-select-right": "RIGHT",
        "undo-tree-visualize-undo-to-x": "PREVIOUS_POINT", "undo-tree-visualize-redo-to-x": "NEXT_POINT",
        "undo-tree-visualizer-toggle-timestamps": "TIMESTAMPS", "undo-tree-visualizer-toggle-diff": "DIFF",
        "undo-tree-visualizer-selection-toggle-diff": "DIFF", "undo-tree-visualizer-selection-mode": "SELECTION",
        "undo-tree-visualizer-scroll-left": "SCROLL_LEFT", "undo-tree-visualizer-scroll-right": "SCROLL_RIGHT",
        "undo-tree-visualizer-scroll-up": "PAGE_DOWN", "undo-tree-visualizer-scroll-down": "PAGE_UP",
        "undo-tree-visualizer-quit": "QUIT", "undo-tree-visualizer-abort": "ABORT", "undo-tree-visualizer-set": "RESTORE",
    }
    for mode, bindings in source_keys.items():
        for stroke, source_command in bindings.items():
            if source_command.startswith("("):
                # Source selection-mode lambdas use the same keys with a count of ten.
                expected = {"PAGE_DOWN": "PAGE_DOWN", "PAGE_UP": "PAGE_UP", "COMMA": "SCROLL_LEFT",
                            "shift COMMA": "SCROLL_LEFT", "PERIOD": "SCROLL_RIGHT", "shift PERIOD": "SCROLL_RIGHT"}[stroke]
                assert "10" in source_command
            else:
                expected = source_commands[source_command]
            assert keys[stroke] == expected, (mode, stroke, source_command, keys.get(stroke))
    for stroke in ["P", "N", "B", "F", "control P", "control N", "control B", "control F", "control Q",
                   "alt shift OPEN_BRACKET", "alt shift CLOSE_BRACKET", "control V", "alt V"]:
        assert stroke not in standard_keys, f"Emacs-only key leaked into Standard mode: {stroke}"
    print("Visualizer shortcuts matched both original Emacs keymaps; Standard mode excludes Emacs aliases.", flush=True)
    encode = lambda text: base64.b64encode(text.encode()).decode()
    fixtures = ROOT / "test/fixtures"
    scenarios = json.loads((fixtures / "scenarios.json").read_text())
    actual = []
    for scenario in scenarios:
        lines = [encode(scenario["initial"])]
        for op in scenario["ops"]:
            if op["type"] == "edit":
                lines.append("\t".join(["edit", op["name"], encode(op["text"])]))
            elif op["type"] in ["set", "discard"]:
                lines.append(op["type"] + "\t" + op["name"])
            elif op["type"] == "switch":
                lines.append("switch\t" + str(op["branch"]))
            else:
                lines.append(op["type"])
        result = subprocess.run([*runner, "history"], input="\n".join(lines) + "\n", text=True, capture_output=True, check=True)
        actual.append({"name": scenario["name"], "traces": [json.loads(line) for line in result.stdout.splitlines()]})
    assert actual == json.loads((fixtures / "emacs-0.8.2.json").read_text()), "Emacs history trace mismatch"
    print(f"Matched {sum(len(s['traces']) for s in actual)} history traces from Emacs undo-tree 0.8.2.", flush=True)
    text_actual = []
    for scenario in json.loads((fixtures / "text-scenarios.json").read_text()):
        header = [scenario["root"], scenario["current"], str(scenario["timestamps"]).lower(), str(scenario["relativeTimestamps"]).lower(), scenario["now"]]
        lines = ["\t".join(map(str, header))]
        for n in scenario["nodes"]:
            lines.append("\t".join(map(str, [n["id"], n["branch"], n["time"], str(n["saved"]).lower(), n["register"] or "", ",".join(map(str, n["children"]))])))
        result = subprocess.run([*runner, "text"], input="\n".join(lines) + "\n", text=True, capture_output=True, check=True)
        text_actual.append({"name": scenario["name"], **json.loads(result.stdout)})
    assert text_actual == json.loads((fixtures / "emacs-text-0.8.2.json").read_text()), "Emacs ASCII tree mismatch"
    print(f"Matched {len(text_actual)} ASCII tree fixtures, including timestamps and registers.", flush=True)
    emacs = shutil.which("emacs")
    if emacs:
        for oracle, data, expected in [("oracle.el", "scenarios.json", actual), ("text-oracle.el", "text-scenarios.json", text_actual)]:
            result = subprocess.run([emacs, "--batch", "-Q", "-L", str(ROOT / "upstream/undo-tree"), "--load", str(ROOT / "test" / oracle), str(fixtures / data)], env={**os.environ, "TZ": "UTC"}, capture_output=True, text=True, check=True, timeout=30)
            assert json.loads(result.stdout) == expected, f"Live Emacs mismatch: {oracle}"
        print("Matched live Emacs history and drawing oracles.", flush=True)
        result = subprocess.run([emacs, "--batch", "-Q", "-L", str(ROOT / "upstream/undo-tree"), "--load", str(ROOT / "test/keymap-oracle.el")], capture_output=True, text=True, check=True, timeout=30)
        assert json.loads(result.stdout) == source_keys, "Live Emacs keymap mismatch"
        print("Matched live Emacs visualizer and selection keymaps.", flush=True)
    else:
        print("Emacs unavailable: live oracle skipped; pinned fixtures passed.", flush=True)
    version = ET.parse(ROOT / "src/main/resources/META-INF/plugin.xml").getroot().findtext("version")
    archive = ROOT / f"build/distributions/undo-tree-jetbrains-{version}.zip"
    if archive.exists():
        import io
        with zipfile.ZipFile(archive) as package:
            with zipfile.ZipFile(io.BytesIO(package.read("undo-tree-jetbrains/lib/undo-tree-jetbrains.jar"))) as jar:
                plugin = ET.fromstring(jar.read("META-INF/plugin.xml"))
                assert plugin.find("idea-version").attrib["since-build"] == "251"
                assert [d.text for d in plugin.findall("depends")] == ["com.intellij.modules.platform"]
                for node in plugin.iter():
                    for attr in ["class", "factoryClass", "implementation", "instance"]:
                        if attr in node.attrib:
                            assert node.attrib[attr].replace(".", "/") + ".class" in jar.namelist(), node.attrib[attr]
                assert "META-INF/LICENSE" in jar.namelist()
        print("Plugin ZIP structure, entrypoints, platform dependency, and license passed.", flush=True)

if __name__ == "__main__":
    main()
