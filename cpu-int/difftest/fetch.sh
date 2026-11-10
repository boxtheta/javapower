#!/bin/sh
# Copy regenerated golden vectors from the dev server into the test resources (run locally).
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p "$ROOT/cpu-int/src/test/resources/golden-int"
rsync -a "devserver.nodes.bxta.net:javapower/work/agent-a-scratch/golden/*.txt.gz" "$ROOT/cpu-int/src/test/resources/golden-int/"
