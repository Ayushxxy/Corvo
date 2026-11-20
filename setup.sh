#!/bin/bash
# CARVO 2.0 toolchain setup (no gradle) — run once per sandbox session
set -e
ROOT=/var/tmp/carvo-sdk
mkdir -p $ROOT
cd $ROOT

# basics
for cmd in zip unzip curl; do
  command -v $cmd >/dev/null 2>&1 || sudo -n apt-get install -y $cmd >/dev/null 2>&1 || true
done
command -v zip >/dev/null || { sudo -n apt-get update -qq >/dev/null 2>&1; sudo -n apt-get install -y zip unzip curl >/dev/null 2>&1; }
command -v zip || echo "WARN: zip missing"

python3 -c "import PIL" 2>/dev/null || sudo -n apt-get install -y python3-pil >/dev/null 2>&1 || true

# JDK 17
if [ ! -x $ROOT/jdk17/bin/java ]; then
  echo "== downloading JDK 17 =="
  curl -sfL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk" -o jdk.tgz
  rm -rf jdk17 && mkdir -p jdk17
  tar -xzf jdk.tgz -C jdk17 --strip-components=1
fi
export JAVA_HOME=$ROOT/jdk17
export PATH=$JAVA_HOME/bin:$PATH
java -version 2>&1 | head -1

# Android cmdline-tools + SDK bits
if [ ! -d $ROOT/sdk/cmdline-tools/latest ]; then
  echo "== downloading cmdline-tools =="
  mkdir -p $ROOT/sdk/cmdline-tools
  rm -f ct.zip
  for u in commandlinetools-linux-11076708_latest.zip commandlinetools-linux-10406996_latest.zip commandlinetools-linux-10197708_latest.zip; do
    curl -sfL -o ct.zip "https://dl.google.com/android/repository/$u" && { echo "got $u"; break; } || rm -f ct.zip
  done
  [ -s ct.zip ] || { echo "FAILED to fetch cmdline-tools"; exit 1; }
  unzip -q ct.zip -d $ROOT/sdk/cmdline-tools
  rm -rf $ROOT/sdk/cmdline-tools/latest
  mv $ROOT/sdk/cmdline-tools/cmdline-tools $ROOT/sdk/cmdline-tools/latest
fi
SDKM=$ROOT/sdk/cmdline-tools/latest/bin/sdkmanager
export ANDROID_HOME=$ROOT/sdk
yes | $SDKM --licenses >/dev/null 2>&1 || true
$SDKM --install "platforms;android-33" "build-tools;33.0.2" >/dev/null
ls -d $ROOT/sdk/build-tools/* $ROOT/sdk/platforms/*
echo "== TOOLCHAIN READY =="
