#!/bin/sh
# Regenerate golden vectors (needs QEMU and clang). Usage, here or through scripts/remote.sh:
#   scripts/remote.sh agent-a sh cpu-int/difftest/run.sh [group...]
# then run cpu-int/difftest/fetch.sh locally to copy them into src/test/resources/golden-int/.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SCRATCH=${SCRATCH:-$HOME/javapower/work/agent-a-scratch}
OUT=$SCRATCH/golden
mkdir -p "$OUT"
export TMPDIR=$SCRATCH
if [ $# -eq 0 ]; then
  set -- $(cd "$HERE" && python3 -c 'import groups; print(" ".join(groups.GROUPS))')
fi
for g in "$@"; do
  (python3 "$HERE/gen.py" "$g" "$OUT" && gzip -9nf "$OUT/$g.txt") &
done
wait
ls -la "$OUT"
