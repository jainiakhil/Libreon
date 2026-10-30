#!/usr/bin/env bash
# Provisions an Ubuntu 24.04 (WSL2) build host for the LibreOfficeKit engine.
# Run as root once:  wsl -d oo-build -u root -- bash /mnt/e/Coding/Office\ Clone/native/lok/provision.sh
set -euo pipefail

BUILD_USER=builder
SDK_ROOT=/opt/android-sdk
CMDLINE_TOOLS_ZIP=commandlinetools-linux-13114758_latest.zip

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  git ca-certificates curl wget unzip zip xz-utils \
  build-essential autoconf automake libtool pkg-config bison flex gperf nasm \
  gettext python3 python3-dev python3-setuptools libxml2-utils xsltproc \
  openjdk-17-jdk-headless ant ccache rsync file patch \
  libkrb5-dev libnss3-dev libfontconfig-dev libcups2-dev zlib1g-dev \
  libcap-dev libpam0g-dev libssl-dev \
  meson ninja-build cmake python3-lxml python3-polib

# The Collabora front-end needs Node >= 20 (Ubuntu 24.04 ships 18).
NODE_VERSION=v22.20.0
if ! /opt/node/bin/node --version 2>/dev/null | grep -qx "$NODE_VERSION"; then
  rm -rf /opt/node && mkdir -p /opt/node
  curl -fsSL "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-x64.tar.xz" \
    | tar -xJ -C /opt/node --strip-components=1
fi
ln -sf /opt/node/bin/node /opt/node/bin/npm /opt/node/bin/npx /usr/local/bin/

id "$BUILD_USER" >/dev/null 2>&1 || useradd -m -s /bin/bash "$BUILD_USER"

# Linux Android SDK + NDK (the Windows SDK cannot be used from the Linux build).
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK_ROOT/cmdline-tools"
  cd /tmp
  wget -q "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q -o "$CMDLINE_TOOLS_ZIP" -d "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi
chown -R "$BUILD_USER:$BUILD_USER" "$SDK_ROOT"

echo "provision: done"
