#!/bin/bash
# Corvo Open-Source Build (aapt2 + javac + d8 + zipalign + apksigner, zero Gradle)
set -e
ROOT=/var/tmp/carvo-sdk
export JAVA_HOME=$ROOT/jdk17
export PATH=$JAVA_HOME/bin:$PATH
SDK=$ROOT/sdk
BT=$SDK/build-tools/33.0.2
PLAT=$SDK/platforms/android-33/android.jar

cd "$(dirname "$0")/app"
rm -rf build
mkdir -p build/gen/com/carvo/app build/classes
printf 'package com.carvo.app;\npublic final class Flavor {\n    public static final boolean DOST = true;\n}\n' > build/gen/com/carvo/app/Flavor.java

echo "== aapt2 compile =="
$BT/aapt2 compile --dir res -o build/res.zip

echo "== aapt2 link =="
$BT/aapt2 link -o build/corvo.unsigned.apk -I $PLAT \
  --manifest AndroidManifest.xml \
  --java build/gen --auto-add-overlay \
  --min-sdk-version 24 --target-sdk-version 33 \
  build/res.zip

echo "== javac =="
javac -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -bootclasspath $PLAT \
  -d build/classes \
  build/gen/com/carvo/app/R.java build/gen/com/carvo/app/Flavor.java src/com/carvo/app/*.java 2>/dev/null || \
javac -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath $PLAT \
  -d build/classes \
  build/gen/com/carvo/app/R.java build/gen/com/carvo/app/Flavor.java src/com/carvo/app/*.java

echo "== d8 =="
$BT/d8 --release --min-api 24 --lib $PLAT --output build $(find build/classes -name '*.class')

echo "== package dex =="
cd build
zip -q -j corvo.unsigned.apk classes.dex
cd ..

echo "== zipalign =="
$BT/zipalign -f 4 build/corvo.unsigned.apk build/corvo.aligned.apk

echo "== sign (local debug keystore) =="
KS=build/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias corvo -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass corvo123 -keypass corvo123 -dname "CN=CorvoDebug" >/dev/null 2>&1
fi
OUT_APK=${1:-build/Corvo-debug.apk}
$BT/apksigner sign --ks "$KS" --ks-pass pass:corvo123 --key-pass pass:corvo123 \
  --out "$OUT_APK" build/corvo.aligned.apk

$BT/apksigner verify --print-certs "$OUT_APK" | head -3
echo "== DONE: $(ls -la "$OUT_APK" | awk '{print $5}') bytes -> $OUT_APK =="
