#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
gson_version=2.15.0
if [[ -n "${GSON_JAR:-}" && -f "$GSON_JAR" ]]; then
    printf '%s\n' "$GSON_JAR"
elif [[ -f "$HOME/.m2/repository/com/google/code/gson/gson/$gson_version/gson-$gson_version.jar" ]]; then
    printf '%s\n' "$HOME/.m2/repository/com/google/code/gson/gson/$gson_version/gson-$gson_version.jar"
else
    dependency_dir="$project_dir/.deps"
    mkdir -p "$dependency_dir"
    dependency_jar="$dependency_dir/gson-$gson_version.jar"
    if [[ ! -f "$dependency_jar" ]]; then
        curl --fail --silent --show-error --location \
            "https://repo.maven.apache.org/maven2/com/google/code/gson/gson/$gson_version/gson-$gson_version.jar" \
            -o "$dependency_jar.tmp"
        mv "$dependency_jar.tmp" "$dependency_jar"
    fi
    printf '%s\n' "$dependency_jar"
fi
