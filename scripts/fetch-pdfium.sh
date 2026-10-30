#!/usr/bin/env bash
# Downloads the pinned prebuilt PDFium (bblanchon/pdfium-binaries) for each app ABI
# into third_party/pdfium/<abi>/ (headers + libpdfium.so). Not committed to git.
set -euo pipefail
PDFIUM_TAG="chromium/8100"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/pdfium"
declare -A ARCH=( [arm64-v8a]=arm64 [x86_64]=x64 )
for abi in "${!ARCH[@]}"; do
  out="$DEST/$abi"
  if [ -f "$out/lib/libpdfium.so" ] && grep -qx "$PDFIUM_TAG" "$out/TAG" 2>/dev/null; then continue; fi
  rm -rf "$out" && mkdir -p "$out"
  curl -fsSL "https://github.com/bblanchon/pdfium-binaries/releases/download/${PDFIUM_TAG}/pdfium-android-${ARCH[$abi]}.tgz" | tar -xz -C "$out"
  echo "$PDFIUM_TAG" > "$out/TAG"
done
echo "PDFium $PDFIUM_TAG ready in $DEST"
