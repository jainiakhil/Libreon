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

WORK="$HOME/engine-$ABI"
if [ ! -d "$WORK" ]; then
  rsync -a --exclude .git "$SRC/engine/" "$WORK/"
fi
cd "$WORK"

# Later options override the distro config:
#  - no PDF import / PDFium: we render PDFs with our own PDFium, and two copies would clash
#  - en-US only for now (more UI languages later, each adds size)
#  - size: drop features the app does not offer (Base/database, macros, Java, media,
#    solver, barcodes, galleries/templates, bundled dictionaries) and build with -Os.
#    The first engine build without these was 204 MB stripped (x86_64).
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
--with-extra-cc-flags=-Os
--with-extra-cxx-flags=-Os
--with-parallelism=$(nproc)
EOF

./autogen.sh
make
echo "engine build for $ABI finished in $WORK"
