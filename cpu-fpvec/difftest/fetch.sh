#!/bin/sh
# Copy regenerated golden vectors from the dev server into the test resources (run locally).
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
# The server is a local detail: JP_HOST, from the environment or the untracked local.env.
if [ -f "$ROOT/local.env" ]; then . "$ROOT/local.env"; fi
HOST=${JP_HOST:?set JP_HOST, or put JP_HOST=<build server> in local.env at the repository root}
mkdir -p "$ROOT/cpu-fpvec/src/test/resources/golden"
rsync -a "$HOST:javapower/work/agent-b-scratch/golden/*.txt.gz" "$ROOT/cpu-fpvec/src/test/resources/golden/"
