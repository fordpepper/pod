#!/bin/bash
# Builds pod.apk with the bare SDK tools (no Gradle).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
KS=$HOME/.android/debug.keystore

rm -rf build && mkdir -p build/res build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$JAR" --java build/gen build/res/*.flat
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" \
    -classpath "$BT/core-lambda-stubs.jar" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && "$BT/aapt" add ../base.apk classes.dex >/dev/null)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out pod.apk build/aligned.apk
ls -la pod.apk
