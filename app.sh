#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

mode="u"
if [[ $# -gt 0 ]]; then
    if [[ "$1" == "-m" && $# -gt 1 ]]; then
        mode="$2"
        shift 2
    else
        mode="$1"
        shift
    fi
fi

case "$mode" in
    s|server) entry_point=server.Server ;;
    c|cli) entry_point=app.Main ;;
    u|ui) entry_point=ui.Launcher ;;
    *) printf 'Usage: %s [-m u|s|c]\n' "$0" >&2; exit 1 ;;
esac
export MAVEN_OPTS="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
mvn -q -DskipTests package
exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp "target/classes:target/deps/*" "$entry_point" "$@"
