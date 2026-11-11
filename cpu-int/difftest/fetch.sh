#!/bin/sh
# Copy regenerated golden vectors from the machine named by JP_HOST into the test resources.
#   [SCRATCH=<dir on the server, relative to ~>] sh cpu-int/difftest/fetch.sh
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if [ -f "$ROOT/local.env" ]; then . "$ROOT/local.env"; fi
HOST=${JP_HOST:?set JP_HOST, or put JP_HOST=<host> in local.env at the repository root}
SCRATCH=${SCRATCH:-javapower/work/agent-a-scratch}
mkdir -p "$ROOT/cpu-int/src/test/resources/golden-int"
rsync -a "$HOST:$SCRATCH/golden/*.txt.gz" "$ROOT/cpu-int/src/test/resources/golden-int/"
