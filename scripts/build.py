#!/usr/bin/env python3
"""Build against an installed IDE without Gradle downloads or third-party Python packages."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def plugin_version():
    return ET.parse(ROOT / "src/main/resources/META-INF/plugin.xml").getroot().findtext("version")

def ide_home(path):
    path = Path(path).expanduser().resolve()
    return path / "Contents" if (path / "Contents").is_dir() else path

def java_home(ide):
    candidates = [ide / "jbr/Contents/Home", ide / "jbr", Path(os.environ.get("JAVA_HOME", "/nonexistent"))]
    for home in candidates:
        if (home / "bin/javac").is_file():
            return home
    raise SystemExit("Set JAVA_HOME to JDK 21+ or use an IDE containing a JBR with javac.")

def run(args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT)
    if result.returncode:
        raise SystemExit(result.returncode)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide", default=os.environ.get("IDE_HOME", "/Applications/IntelliJ IDEA.app"))
    parser.add_argument("--test", action="store_true")
    args = parser.parse_args()
    ide = ide_home(args.ide)
    if not (ide / "lib").is_dir():
        raise SystemExit("IDE not found. Pass --ide /path/to/idea-or-pycharm.")
    jdk = java_home(ide)
    # Platform API moved from aggregate jars to separate jars in recent IDEs.
    jars = sorted((ide / "lib").glob("*.jar")) + sorted((ide / "lib/modules").glob("*.jar"))
    classpath = os.pathsep.join(str(path) for path in jars)
    classes = ROOT / "build/classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    run([jdk / "bin/javac", "--release", "21", "-encoding", "UTF-8", "-classpath", classpath,
         "-d", classes, *sorted((ROOT / "src/main/java").rglob("*.java"))])
    resources = ROOT / "src/main/resources"
    shutil.copytree(resources, classes, dirs_exist_ok=True)
    dist = ROOT / "build/distributions"
    dist.mkdir(parents=True, exist_ok=True)
    archive = dist / f"undo-tree-jetbrains-{plugin_version()}.zip"
    plugin_jar = ROOT / "build/undo-tree-jetbrains.jar"
    with zipfile.ZipFile(plugin_jar, "w", zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(classes.rglob("*")):
            if path.is_file():
                jar.write(path, path.relative_to(classes))
        for name in ["LICENSE", "NOTICE"]:
            jar.write(ROOT / name, "META-INF/" + name)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as package:
        package.write(plugin_jar, "undo-tree-jetbrains/lib/undo-tree-jetbrains.jar")
    print(f"Built {archive}", flush=True)
    if args.test:
        run([sys.executable, ROOT / "scripts/test.py", "--jdk", jdk])

if __name__ == "__main__":
    main()
