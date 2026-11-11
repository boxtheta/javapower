#!/bin/sh
# Build the big-endian FreeBSD test programs and pack them into an ISO for drive.py's `progs`
# scenario (runs on the dev server).
#   sh scripts/guest/progs/build.sh [outdir]      -> <outdir>/progs.iso
#
# Sysroots come from the BE install disc's offline pkgbase repo: headers and 64-bit static
# and shared libraries from FreeBSD-clibs(-dev), 32-bit ones from FreeBSD-clibs(-dev)-lib32
# (also copied to usr/lib so clang's FreeBSD driver finds them for --target=powerpc-unknown-freebsd).
# Each program is built four ways: 32- and 64-bit, static and dynamic. The 32-bit ones target the
# 7450 (G4, with AltiVec), so the compiler may vectorise them. Dynamic 32-bit programs need the lib32 runtime
# (FreeBSD-clibs-lib32) installed in the guest; drive.py does that.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HOME/javapower/work/be-scratch/progs}
ISO=${ISO:-$HOME/javapower/images/freebsd-15.1-be-disc1.iso}
REPO=usr/freebsd-packages/offline
mkdir -p "$OUT"
cd "$OUT"
if [ ! -d pkgs ]; then
  mkdir -p pkgs
  for p in clibs clibs-lib32 clibs-dev clibs-dev-lib32 libcompiler_rt-dev libcompiler_rt-dev-lib32; do
    bsdtar -xf "$ISO" -C pkgs "$REPO/FreeBSD-$p-15.1.pkg"
  done
fi
x() { bsdtar -xf "pkgs/$REPO/FreeBSD-$2-15.1.pkg" -C "$1" --exclude '+*' 2>/dev/null; }
if [ ! -d sys64 ]; then
  mkdir -p sys64 sys32
  x sys64 clibs
  x sys64 clibs-dev
  x sys64 libcompiler_rt-dev
  x sys32 clibs-lib32
  x sys32 clibs-dev-lib32
  x sys32 libcompiler_rt-dev-lib32
  cp -a sys64/usr/include sys32/usr/include
  # Linker scripts in lib32 name /usr/lib32/...: keep it, and give the driver a usr/lib copy.
  mkdir -p sys32/usr/lib
  cp -a sys32/usr/lib32/. sys32/usr/lib/
fi
rm -rf iso && mkdir -p iso/s32 iso/d32 iso/s64 iso/d64
CFLAGS="-O2 -s -fuse-ld=lld -Wall -Wno-unused-result -Wno-atomic-alignment"
for prog in intops fpops strops sigs thr proc hash; do
  libs="-lm -pthread"
  for w in 32 64; do
    if [ $w = 32 ]; then tgt=powerpc-unknown-freebsd15.1; cpu=-mcpu=7450; else tgt=powerpc64-unknown-freebsd15.1; cpu=-mcpu=power9; fi
    clang --target=$tgt --sysroot="$OUT/sys$w" $CFLAGS $cpu -static -o iso/s$w/$prog "$HERE/$prog.c" $libs
    clang --target=$tgt --sysroot="$OUT/sys$w" $CFLAGS $cpu -o iso/d$w/$prog "$HERE/$prog.c" $libs
  done
done
cat > iso/RUN <<'EOF'
#!/bin/sh
# Run every test program; each section starts with "=== <variant>/<program>".
cd "$(dirname "$0")"
for v in s32 d32 s64 d64; do
  for p in intops fpops strops sigs thr proc hash; do
    echo "=== $v/$p"
    ./$v/$p 2>&1
    echo "exit $?"
  done
done
EOF
chmod +x iso/RUN
file iso/s32/intops iso/d32/intops iso/s64/intops iso/d64/intops
xorriso -as mkisofs -quiet -R -J -V JPPROGS -o progs.iso iso
ls -la progs.iso
