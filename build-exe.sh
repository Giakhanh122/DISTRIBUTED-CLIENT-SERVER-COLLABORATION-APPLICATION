#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

export MAVEN_OPTS="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
mvn -q package

jpackage_cmd="${JAVA_HOME:-}/bin/jpackage"
if [[ ! -x "$jpackage_cmd" ]]; then
    jpackage_cmd="jpackage"
fi

build_dir="target/package-input"
mkdir -p "$build_dir"
cp -f "target/collaboration-app-1.0-SNAPSHOT.jar" "$build_dir/app.jar"
rm -rf "dist/DLChat"

icon_args=()
if [[ -f "src/main/resources/assets/DL-Chat-icon-128.png" ]]; then
    icon_args=(--icon "src/main/resources/assets/DL-Chat-icon-128.png")
fi

"$jpackage_cmd" --type app-image --name DLChat --app-version 1.0 --vendor "DL-Chat" "${icon_args[@]}" --input "$build_dir" --main-jar app.jar --main-class ui.Launcher --dest dist

if [[ -d "dist/DLChat" ]]; then
    if [[ -d "config" ]]; then
        cp -rf "config" "dist/DLChat/config"
    elif [[ -f "server_identity/server-public.key" ]]; then
        mkdir -p "dist/DLChat/config"
        cp -f "server_identity/server-public.key" "dist/DLChat/config/server-public.key"
    fi
fi
