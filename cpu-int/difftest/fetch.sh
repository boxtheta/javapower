#!/bin/sh
# Copy regenerated golden vectors from the dev server into the test resources (run locally).
#   [SCRATCH=<dir on the server, relative to ~>] sh cpu-int/difftest/fetch.sh
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
# The server is a local detail: JP_HOST, from the environment or the untracked local.env.
if [ -f "$ROOT/local.env" ]; then . "$ROOT/local.env"; fi
HOST=${JP_HOST:?set JP_HOST, or put JP_HOST=<build server> in local.env at the repository root}
SCRATCH=${SCRATCH:-javapower/work/agent-a-scratch}
mkdir -p "$ROOT/cpu-int/src/test/resources/golden-int"
rsync -a "$HOST:$SCRATCH/golden/*.txt.gz" "$ROOT/cpu-int/src/test/resources/golden-int/"
