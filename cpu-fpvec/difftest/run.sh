#!/bin/sh
# Regenerate golden vectors (needs QEMU and clang). Usage, here or through scripts/remote.sh:
#   scripts/remote.sh agent-b sh cpu-fpvec/difftest/run.sh <group...>
# then copy ~/javapower/work/agent-b-scratch/golden/*.txt.gz into
# cpu-fpvec/src/test/resources/golden/ (see fetch.sh).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${GOLDEN_OUT:-$HOME/javapower/work/agent-b-scratch/golden}
mkdir -p "$OUT"
if [ $# -eq 0 ]; then
  set -- $(cd "$HERE" && python3 -c 'import groups; print(" ".join(k for k in groups.GROUPS if k != "selftest"))')
fi
for g in "$@"; do
  (python3 "$HERE/gen.py" "$g" "$OUT" && gzip -9nf "$OUT/$g.txt") &
done
wait
ls -la "$OUT"
