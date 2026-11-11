#!/bin/sh
# Copy regenerated golden vectors from the dev server into the test resources (run locally).
#   [SCRATCH=<dir on the server, relative to ~>] sh cpu-int/difftest/fetch.sh
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCRATCH=${SCRATCH:-javapower/work/agent-a-scratch}
mkdir -p "$ROOT/cpu-int/src/test/resources/golden-int"
rsync -a "devserver.nodes.bxta.net:$SCRATCH/golden/*.txt.gz" "$ROOT/cpu-int/src/test/resources/golden-int/"
