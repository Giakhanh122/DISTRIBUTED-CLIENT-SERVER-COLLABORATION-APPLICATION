#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

mode="${1:-ui}"
if [[ $# -gt 0 ]]; then shift; fi
case "$mode" in
    server|s) entry_point=server.Server ;;
    cli) entry_point=Main ;;
    ui) entry_point=ui.Launcher ;;
    *) printf 'Usage: %s [ui|server|cli]\n' "$0" >&2; exit 1 ;;
esac
mvn -q package
exec java -cp "target/classes:target/deps/*" "$entry_point" "$@"
