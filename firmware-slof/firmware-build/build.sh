#!/bin/sh
# Build the SLOF firmware image from source in a container (podman, rootless is fine).
#
#   build.sh [--install] [outdir]
#
# Writes slof.bin, slof.bin.gz and slof.properties (provenance: SLOF tag and commit, the patches
# applied, toolchain, base image, sizes and SHA-256 of both files) to outdir (default: ./out next to this script) and
# prints the SHA-256 of both images. With --install it also copies slof.bin.gz and
# slof.properties into the module's resources, replacing the committed image.
#
# Each run rebuilds from scratch (no layer cache), so two runs can be compared for
# reproducibility.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
INSTALL=
if [ "${1:-}" = --install ]; then
    INSTALL=1
    shift
fi
OUT=${1:-$HERE/out}
RES=$HERE/../src/main/resources/org/boxtheta/javapower/firmware/slof
TAG=javapower-slof-build:$$
BASE=$(sed -n 's/^FROM //p' "$HERE/Containerfile")

mkdir -p "$OUT"
podman build --no-cache --pull=missing -t "$TAG" -f "$HERE/Containerfile" "$HERE"
CID=$(podman create "$TAG")
trap 'podman rm -f "$CID" >/dev/null; podman rmi -f "$TAG" >/dev/null' EXIT
for f in slof.bin slof.bin.gz build.properties; do
    podman cp "$CID:/out/$f" "$OUT/$f"
done

sha() { sha256sum "$1" | cut -d' ' -f1; }
size() { wc -c < "$1" | tr -d ' '; }
{
    echo "# Provenance of slof.bin.gz, written by firmware-build/build.sh."
    cat "$OUT/build.properties"
    echo "build.base-image=$BASE"
    echo "image.size=$(size "$OUT/slof.bin")"
    echo "image.sha256=$(sha "$OUT/slof.bin")"
    echo "compressed.size=$(size "$OUT/slof.bin.gz")"
    echo "compressed.sha256=$(sha "$OUT/slof.bin.gz")"
} > "$OUT/slof.properties"
rm "$OUT/build.properties"

sha256sum "$OUT/slof.bin" "$OUT/slof.bin.gz"
if [ -n "$INSTALL" ]; then
    mkdir -p "$RES"
    cp "$OUT/slof.bin.gz" "$OUT/slof.properties" "$RES/"
    echo "installed into $RES"
fi
