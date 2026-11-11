#!/bin/sh
# Sync this checkout to a named workspace on the dev server and run a command there.
#   scripts/remote.sh <workspace> <command...>
# e.g. scripts/remote.sh agent-a gradle :cpu-int:test
# Each workspace is an independent copy under ~/javapower/work/<workspace>, so parallel
# builds do not disturb each other. build/ and .gradle/ on the server are preserved.
# JP_HOST names the server; set it in the environment or in the untracked local.env at the
# repository root (see docs/development.md).
set -e
WS=$1; shift
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# The server is a local detail: JP_HOST, from the environment or the untracked local.env.
if [ -f "$ROOT/local.env" ]; then . "$ROOT/local.env"; fi
HOST=${JP_HOST:?set JP_HOST, or put JP_HOST=<build server> in local.env at the repository root}
rsync -az --delete --exclude .git --exclude build --exclude .gradle "$ROOT/" "$HOST:javapower/work/$WS/"
exec ssh -o BatchMode=yes "$HOST" "cd ~/javapower/work/$WS && $*"
