#!/usr/bin/env bash
# Builds the Collabora Online mobile layer (browser front-end + Android native glue)
# against an engine tree produced by build-engine.sh, then the upstream Android
# library/app with Gradle. Run as the build user inside the oo-build distro:
#   bash native/lok/build-online.sh x86_64        # or arm64-v8a
set -euo pipefail

export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
unset MSYSTEM

ABI="${1:?usage: build-online.sh <arm64-v8a|x86_64>}"
SRC="${CO_SRC:-$HOME/co}"
ENGINE="$HOME/engine-$ABI${ENGINE_TAG:+-$ENGINE_TAG}"
WORK="$HOME/online-$ABI${ENGINE_TAG:+-$ENGINE_TAG}"
export ANDROID_HOME=/opt/android-sdk

[ -f "$ENGINE/instdir/program/soffice" ] || [ -d "$ENGINE/workdir" ] || {
  echo "engine for $ABI not built: run build-engine.sh $ABI first" >&2; exit 1; }

if [ ! -d "$WORK" ]; then
  rsync -a --exclude .git --exclude /engine "$SRC/" "$WORK/"
fi
# The browser build reads engine sources (e.g. Accelerators.xcu for keyboard shortcuts)
# from ./engine, so point it at this ABI's engine tree.
ln -sfn "$ENGINE" "$WORK/engine"
cd "$WORK"

if [ ! -f config.status ]; then
  ./autogen.sh
  # --disable-branding: no Collabora trademarks in anything we build.
  ./configure --enable-androidapp \
              --with-lo-builddir="$ENGINE" \
              --with-android-abi="$ABI" \
              --disable-branding
fi
make -j"$(nproc)"

cd android
./gradlew --no-daemon assembleRelease
echo "online/android build for $ABI finished in $WORK"
