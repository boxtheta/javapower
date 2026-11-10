#!/bin/sh
# Device tree diff against QEMU. Run on the dev server from the checkout root, e.g.
#   scripts/remote.sh agent-c platform/scripts/dt-diff.sh
# Runs the platform DeviceTreeTest (which writes our blobs to platform/build/dt/), decompiles them
# with dtc and diffs them against the trees QEMU produced for the same configuration. The only
# expected differences are rng-seed (random) and the order of the ibm,drc-* arrays (QEMU emits them
# in GLib hash-table order, we emit them sorted by DRC index).
set -e
R=platform/src/test/resources/javapower/papr
gradle -q :platform:test --tests javapower.papr.DeviceTreeTest
for t in reset:pseries-min cas-linux:cas-linux cas-freebsd:cas-freebsd; do
    ours=${t%%:*}; ref=${t##*:}
    dtc -q -I dtb -O dts -o platform/build/dt/$ours.dts platform/build/dt/$ours.dtb
    dtc -q -I dtb -O dts -o platform/build/dt/$ref.ref.dts $R/$ref.dtb
    echo "=== $ours vs QEMU $ref"
    diff platform/build/dt/$ref.ref.dts platform/build/dt/$ours.dts || true
done
