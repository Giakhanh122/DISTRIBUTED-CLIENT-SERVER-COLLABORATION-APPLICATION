#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if [ "$1" == "server" ] || [ "$1" == "s" ]; then
    mkdir -p out server_storage
    javac -d out $(find src/main/java/server src/main/java/protocols src/main/java/transfer src/main/java/logic -name "*.java")
    exec java -cp out server.Server
fi

M2_JFX="$HOME/.m2/repository/org/openjfx"
JFX_MODULES=$(find "$M2_JFX" -name "*.jar" 2>/dev/null | tr '\n' ':')

mkdir -p out
cp -r src/main/resources/* out/ 2>/dev/null || true

javac --release 21 --module-path "$JFX_MODULES" --add-modules javafx.controls,javafx.fxml -d out $(find src/main/java -name "*.java")
exec java --module-path "$JFX_MODULES" --add-modules javafx.controls,javafx.fxml -cp out ui.MainApp
