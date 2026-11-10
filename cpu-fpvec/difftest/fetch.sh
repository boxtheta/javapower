#!/bin/sh
# Copy regenerated golden vectors from the dev server into the test resources (run locally).
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p "$ROOT/cpu-fpvec/src/test/resources/golden"
rsync -a "devserver.nodes.bxta.net:javapower/work/agent-b-scratch/golden/*.txt.gz" "$ROOT/cpu-fpvec/src/test/resources/golden/"
