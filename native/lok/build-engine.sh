#!/usr/bin/env bash
# Builds the Collabora/LibreOffice engine (LibreOfficeKit) for one Android ABI.
# Run as the build user inside the oo-build WSL distro:
#   bash native/lok/build-engine.sh x86_64        # or arm64-v8a
# Each ABI gets its own worktree of engine/, because the engine builds in-tree.
set -euo pipefail

# A Windows PATH leaking in via WSL interop (e.g. Git Bash's mingw64) makes configure
# think this is a Windows build using WSL only as a helper.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
unset MSYSTEM

ABI="${1:?usage: build-engine.sh <arm64-v8a|x86_64>}"
SRC="${CO_SRC:-$HOME/co}"
SDK=/opt/android-sdk
NDK_VERSION=28.2.13676358

case "$ABI" in
  arm64-v8a) DISTRO=CPAndroidAarch64 ;;
  x86_64)    DISTRO=CPAndroidX86_64 ;;
  *) echo "unsupported ABI $ABI" >&2; exit 2 ;;
esac

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "ndk;$NDK_VERSION" "platforms;android-36" "build-tools;36.0.0" "platform-tools" >/dev/null

# ENGINE_TAG builds into a separate tree (e.g. ENGINE_TAG=slim -> ~/engine-x86_64-slim),
# so an experiment never clobbers a working engine.
WORK="$HOME/engine-$ABI${ENGINE_TAG:+-$ENGINE_TAG}"
if [ ! -d "$WORK" ]; then
  rsync -a --exclude .git "$SRC/engine/" "$WORK/"
fi
cd "$WORK"

# Later options override the distro config:
#  - no PDF import / PDFium: we render PDFs with our own PDFium, and two copies would clash
#  - en-US only for now (more UI languages later, each adds size)
#  - size: drop features the app does not offer (Base/database, macros, Java, media,
#    solver, barcodes, galleries/templates, bundled dictionaries). 204 MB -> 192 MB stripped.
#  - API level 31 (our minSdk) so the linker may emit compact RELR relocations; .rela.dyn
#    alone was 21 MB.
#  - -Os: --with-extra-c*-flags lands early on the command line and gbuild's hard-coded
#    gb_COMPILEROPTFLAGS (-O2) wins, so -Os is passed to make instead (clean builds only:
#    gbuild does not rebuild objects when flags change).
#    Not --enable-lto: for Android it adds GCC's -fuse-linker-plugin, which NDK clang rejects.
cat > autogen.input <<EOF
--with-android-ndk=$SDK/ndk/$NDK_VERSION
--with-android-sdk=$SDK
--with-distro=$DISTRO
--disable-pdfimport
--disable-pdfium
--with-lang=en-US
--disable-database-connectivity
--disable-scripting
--without-java
--disable-avmedia
--disable-coinmp
--disable-zxing
--without-galleries
--without-templates
--without-myspell-dicts
--with-android-api-level=31
LDFLAGS=-Wl,-z,pack-relative-relocs
--with-parallelism=$(nproc)
EOF

./autogen.sh
# The final liblo-native-code.so link line ignores LDFLAGS, so add RELR there too
# (in this build tree only; idempotent).
grep -q pack-relative-relocs android/Bootstrap/Makefile.shared ||
  sed -i 's|\$(CXX) -Wl,--build-id=sha1|$(CXX) -Wl,-z,pack-relative-relocs -Wl,--build-id=sha1|' \
    android/Bootstrap/Makefile.shared
make gb_COMPILEROPTFLAGS=-Os
echo "engine build for $ABI finished in $WORK"
