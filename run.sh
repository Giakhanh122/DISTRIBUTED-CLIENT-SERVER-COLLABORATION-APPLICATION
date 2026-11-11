#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_dir="$project_dir/src/main/java"
build_dir="$project_dir/out"

gson_jar="$(bash "$project_dir/scripts/java-deps.sh")"
mkdir -p "$build_dir"

javac --release 21 -encoding UTF-8 \
    -cp "$gson_jar" \
    -d "$build_dir" \
    -sourcepath "$source_dir" \
    "$source_dir/Main.java"

exec java -cp "$build_dir:$gson_jar" Main "$@"
