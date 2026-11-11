#!/usr/bin/env python3
"""Compile and test both registries and real client/server sockets with Java 21+."""

from pathlib import Path
import subprocess
import tempfile


def main():
    project = Path(__file__).resolve().parent.parent
    sources = sorted(p for p in (project / "src/main/java").rglob("*.java")
                     if "ui" not in p.relative_to(project / "src/main/java").parts)
    gson = subprocess.check_output(["bash", str(project / "scripts/java-deps.sh")], text=True).strip()
    sources.extend([project / "src/test/java/SessionLifecycleTest.java",
                    project / "src/test/java/JsonProtocolTest.java"])
    with tempfile.TemporaryDirectory(prefix="collaboration-sessions-") as directory:
        subprocess.run(
            ["javac", "--release", "21", "-encoding", "UTF-8", "-cp", gson, "-d", directory,
             *map(str, sources)],
            check=True,
        )
        subprocess.run(["java", "-ea", "-cp", directory + ":" + gson, "JsonProtocolTest"],
                       cwd=directory, check=True, timeout=30)
        subprocess.run(
            ["java", "-ea", "-cp", directory + ":" + gson, "SessionLifecycleTest"],
            cwd=directory,
            check=True,
            timeout=45,
        )


if __name__ == "__main__":
    main()
