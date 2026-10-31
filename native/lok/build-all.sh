#!/usr/bin/env bash
# Builds the engine, the front-end/Android layer, and packages both into third_party/lok
# for one ABI. Run as the build user inside the oo-build distro:
#   ENGINE_TAG=slim bash native/lok/build-all.sh arm64-v8a
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
bash "$HERE/build-engine.sh" "$1"
bash "$HERE/build-online.sh" "$1"
bash "$HERE/package-lok.sh" "$1"
