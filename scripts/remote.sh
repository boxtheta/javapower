#!/bin/sh
# Sync this checkout to a named workspace on the dev server and run a command there.
#   scripts/remote.sh <workspace> <command...>
# e.g. scripts/remote.sh agent-a gradle :cpu-int:test
# Each workspace is an independent copy under ~/javapower/work/<workspace>, so parallel
# builds do not disturb each other. build/ and .gradle/ on the server are preserved.
# JP_HOST picks the server (default devserver.nodes.bxta.net; unix01.nodes.bxta.net is the
# FreeBSD sister machine).
set -e
HOST=${JP_HOST:-devserver.nodes.bxta.net}
WS=$1; shift
ROOT=$(cd "$(dirname "$0")/.." && pwd)
rsync -az --delete --exclude .git --exclude build --exclude .gradle "$ROOT/" "$HOST:javapower/work/$WS/"
exec ssh -o BatchMode=yes "$HOST" "cd ~/javapower/work/$WS && $*"
