#!/usr/bin/env bash
# Copies the Office engine's native libraries and assets out of the upstream Android
# library archive (built by build-online.sh) into third_party/lok/ in this repo, where
# the :engine-office module picks them up. Only the .so files and assets are taken;
# upstream's Java classes and Android resources are not used.
# Run as the build user inside the oo-build distro:
#   bash native/lok/package-lok.sh x86_64        # ENGINE_TAG=slim for the slim trees
set -euo pipefail

ABI="${1:?usage: package-lok.sh <arm64-v8a|x86_64>}"
AAR="$HOME/online-$ABI${ENGINE_TAG:+-$ENGINE_TAG}/android/build/lib/outputs/aar/lib-release.aar"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$REPO/third_party/lok"
[ -f "$AAR" ] || { echo "missing $AAR; run build-online.sh first" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$AAR" "jni/$ABI/*" "assets/*" -d "$TMP"

# Native libraries are per ABI; assets are the same for every ABI.
rm -rf "$OUT/jniLibs/$ABI" "$OUT/assets"
mkdir -p "$OUT/jniLibs"
mv "$TMP/jni/$ABI" "$OUT/jniLibs/$ABI"
mv "$TMP/assets" "$OUT/assets"

# Upstream bug: a few dark-theme icons (the slideshow button among them) keep the light
# theme's dark grey, so on a dark toolbar they look disabled. Give them the dark theme's ink.
grep -l 'fill="#3a3a38"' "$OUT/assets/dist/images/dark/"*.svg 2>/dev/null |
  xargs -r sed -i 's/fill="#3a3a38"/fill="#fafafa"/g'

# The page links branding.css for host-specific styles; upstream ships none, so ours goes there.
#  - Message dialogs (e.g. "Font Disallows Editing") show their text as an inline label: the
#    label's top border is drawn on every wrapped line, its margin only on the first, and the
#    message's own line breaks are lost. Lay it out as a block that keeps the breaks.
cat > "$OUT/assets/dist/branding.css" <<'CSS'
.mobile-wizard-content > .mobile-wizard.static-label.ui-text {
  display: block !important;
  white-space: pre-line !important;
  line-height: 1.45 !important;
  margin: 0 !important;
  padding: 16px 4% !important;
  border: none !important;
}
.mobile-wizard-content > .mobile-wizard.static-label.ui-text:empty { display: none !important; }
CSS

echo "packaged $AAR"
du -sh "$OUT/jniLibs/$ABI" "$OUT/assets"
