#!/usr/bin/env python3
"""Run the real IntelliJ Platform in an isolated headless sandbox (installed IDE required)."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile
from build import ROOT, ide_home, java_home, plugin_version

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide", default=os.environ.get("IDE_HOME", "/Applications/IntelliJ IDEA.app"))
    parser.add_argument("--compile-only", action="store_true", help="Build the plugin and IDE test harness without launching the IDE.")
    args = parser.parse_args()
    ide = ide_home(args.ide)
    jdk = java_home(ide)
    subprocess.run([sys.executable, str(ROOT / "scripts/build.py"), "--ide", args.ide], check=True)
    sandbox = ROOT / "build/ide-smoke"
    plugins = sandbox / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(ROOT / f"build/distributions/undo-tree-jetbrains-{plugin_version()}.zip") as package:
        package.extractall(plugins)
    classes = sandbox / "test-classes"
    classes.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join([str(ROOT / "build/classes"), *map(str, (ide / "lib").glob("*.jar")), *map(str, (ide / "lib/modules").glob("*.jar"))])
    subprocess.run([str(jdk / "bin/javac"), "--release", "21", "-classpath", classpath, "-d", str(classes),
                    str(ROOT / "src/ideTest/java/dev/undotree/IdeSmokeStarter.java")], check=True)
    test_jar = plugins / "undo-tree-smoke/lib/smoke.jar"
    test_jar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(test_jar, "w") as jar:
        for path in classes.rglob("*.class"):
            jar.write(path, path.relative_to(classes))
        jar.writestr("META-INF/plugin.xml", '''<idea-plugin>
<id>dev.undotree.smoke</id><name>Undo Tree Smoke</name><version>1</version><vendor>Local tests</vendor>
<depends>dev.undotree.jetbrains</depends>
<extensions defaultExtensionNs="com.intellij"><appStarter id="undo-tree-smoke" implementation="dev.undotree.IdeSmokeStarter"/></extensions>
</idea-plugin>''')
    if args.compile_only:
        print("Plugin and IDE smoke harness compiled; IDE launch skipped.")
        return
    info_path = ide / "Resources/product-info.json"
    if not info_path.exists():
        info_path = ide / "product-info.json"
    launch = json.loads(info_path.read_text())["launch"][0]
    app_package = ide.parent if ide.name == "Contents" else ide
    options = [value.replace("$APP_PACKAGE", str(app_package)).replace("$IDE_HOME", str(ide))
               for value in launch.get("additionalJvmArguments", [])]
    cp = os.pathsep.join(str(ide / "lib" / name) for name in launch["bootClassPathJarNames"])
    for name in ["config", "system", "log", "project"]:
        (sandbox / name).mkdir(parents=True, exist_ok=True)
    command = [str(jdk / "bin/java"), "-Xmx2048m", *options,
               "-Djava.awt.headless=true", "-Didea.is.unit.test=false", "-Didea.initially.ask.config=false",
               "-Didea.config.path=" + str(sandbox / "config"), "-Didea.system.path=" + str(sandbox / "system"),
               "-Didea.log.path=" + str(sandbox / "log"), "-Didea.plugins.path=" + str(plugins),
               "-Dundo.tree.smoke.path=" + str(sandbox / "project"), "-Didea.home.path=" + str(ide),
               "-cp", cp, "com.intellij.idea.Main", "undo-tree-smoke"]
    log = sandbox / "console.log"
    print(f"Starting isolated IDE smoke test; log: {log}", flush=True)
    with log.open("w") as out:
        result = subprocess.run(command, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, timeout=180)
    output = log.read_text(errors="replace")
    if result.returncode or "UNDO_TREE_IDE_SMOKE_PASSED" not in output:
        print(output[-12000:])
        raise SystemExit(result.returncode or 1)
    print("Real IDE document listeners, branch navigation, save markers, action loading, and Swing rendering passed.")
    print("Preview: " + str(sandbox / "project/tool-window.png"))

if __name__ == "__main__":
    main()
