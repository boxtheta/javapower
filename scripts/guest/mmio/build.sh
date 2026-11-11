#!/bin/sh
# Testing only. Build mmiotest.c for the big- and little-endian FreeBSD guests and pack both into
# an ISO for drive.py (--cdrom2), next to a RUN script. Sysroots come from the install discs'
# offline pkgbase repos, as in ../progs/build.sh.
#   sh scripts/guest/mmio/build.sh [outdir]      -> <outdir>/mmio.iso
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HOME/javapower/work/mmio-scratch/mmio}
IMAGES=${IMAGES:-$HOME/javapower/images}
REPO=usr/freebsd-packages/offline
mkdir -p "$OUT"
cd "$OUT"
rm -rf iso && mkdir -p iso
for e in be le; do
  if [ $e = be ]; then iso=freebsd-15.1-be-disc1.iso; tgt=powerpc64-unknown-freebsd15.1
  else iso=freebsd-15.1-disc1.iso; tgt=powerpc64le-unknown-freebsd15.1; fi
  if [ ! -d sys-$e ]; then
    mkdir -p pkgs-$e sys-$e
    for p in clibs clibs-dev libcompiler_rt-dev; do
      bsdtar -xf "$IMAGES/$iso" -C pkgs-$e "$REPO/FreeBSD-$p-15.1.pkg"
      bsdtar -xf "pkgs-$e/$REPO/FreeBSD-$p-15.1.pkg" -C sys-$e --exclude '+*' 2>/dev/null
    done
  fi
  clang --target=$tgt --sysroot="$OUT/sys-$e" -O2 -s -fuse-ld=lld -Wall -mcpu=power9 -static \
    -o iso/mmiotest-$e "$HERE/mmiotest.c"
done
cat > iso/RUN <<'EOS'
#!/bin/sh
# Run the build for this guest's byte order.
cd "$(dirname "$0")"
case "$(sysctl -n hw.machine_arch)" in
  powerpc64le) exec ./mmiotest-le "$@" ;;
  *) exec ./mmiotest-be "$@" ;;
esac
EOS
chmod +x iso/RUN
file iso/mmiotest-be iso/mmiotest-le
xorriso -as mkisofs -quiet -R -J -V JPMMIO -o mmio.iso iso
ls -la mmio.iso
