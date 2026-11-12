#!/usr/bin/env python3
"""Compile and test both registries and real client/server sockets with Java 21+."""

from pathlib import Path
import subprocess
import tempfile


def main():
    project = Path(__file__).resolve().parent.parent
    sources = sorted((project / "src/main/java").rglob("*.java"))
    sources.append(project / "src/test/java/SessionLifecycleTest.java")
    with tempfile.TemporaryDirectory(prefix="collaboration-sessions-") as directory:
        subprocess.run(
            ["javac", "--release", "21", "-encoding", "UTF-8", "-d", directory,
             *map(str, sources)],
            check=True,
        )
        subprocess.run(
            ["java", "-ea", "-cp", directory, "SessionLifecycleTest"],
            cwd=directory,
            check=True,
            timeout=45,
        )


if __name__ == "__main__":
    main()
