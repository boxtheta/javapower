#!/bin/sh
# Copy regenerated golden vectors from the machine named by JP_HOST into the test resources.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if [ -f "$ROOT/local.env" ]; then . "$ROOT/local.env"; fi
HOST=${JP_HOST:?set JP_HOST, or put JP_HOST=<host> in local.env at the repository root}
mkdir -p "$ROOT/cpu-fpvec/src/test/resources/golden"
rsync -a "$HOST:javapower/work/agent-b-scratch/golden/*.txt.gz" "$ROOT/cpu-fpvec/src/test/resources/golden/"
