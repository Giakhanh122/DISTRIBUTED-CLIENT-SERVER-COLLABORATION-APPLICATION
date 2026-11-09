#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_dir="$project_dir/src/main/java"
build_dir="$project_dir/out"

mkdir -p "$build_dir"

javac --release 21 -encoding UTF-8 \
    -d "$build_dir" \
    -sourcepath "$source_dir" \
    "$source_dir/Main.java"

exec java -cp "$build_dir" Main "$@"
