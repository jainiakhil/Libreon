#!/usr/bin/env bash
# Reports size and exported PDFium symbols of a built engine library.
#   bash native/lok/inspect-engine.sh x86_64        # ENGINE_TAG=slim for ~/engine-x86_64-slim
set -euo pipefail
ABI="${1:?usage: inspect-engine.sh <abi>}"
BIN=/opt/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin
LIB="$HOME/engine-$ABI${ENGINE_TAG:+-$ENGINE_TAG}/android/jniLibs/$ABI/liblo-native-code.so"
echo "library: $(stat -c %s "$LIB") bytes"
echo "exported FPDF* symbols: $("$BIN/llvm-nm" -D --defined-only "$LIB" | grep -c ' FPDF' || true)"
echo "exported symbols total: $("$BIN/llvm-nm" -D --defined-only "$LIB" | wc -l)"
"$BIN/llvm-strip" -o /tmp/lo-stripped.so "$LIB"
echo "stripped: $(stat -c %s /tmp/lo-stripped.so) bytes, gzip: $(gzip -c /tmp/lo-stripped.so | wc -c) bytes"
