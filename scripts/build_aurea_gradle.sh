#!/usr/bin/env bash
set -euo pipefail

ROOT="$(pwd)"
WORK="$ROOT/build/aurea"
OUT="$ROOT/build/outputs/apk"
APKTOOL_VERSION="2.10.0"
APKTOOL="$WORK/apktool.jar"
ORIGINAL="$WORK/Aurea-0.4.7-original.apk"
DECODED="$WORK/decoded"
UNSIGNED="$WORK/Aurea-0.4.7-unsigned.apk"
ALIGNED="$WORK/Aurea-0.4.7-aligned.apk"
FINAL="$OUT/Aurea-0.4.7-GLES-patched.apk"

mkdir -p "$WORK" "$OUT"
curl --fail --location --retry 3 \
  "https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar" \
  --output "$APKTOOL"

gzip -t apks/Aurea-0.4.7-32bits.apk.gz
gzip -dc apks/Aurea-0.4.7-32bits.apk.gz > "$ORIGINAL"

# Decode and rebuild through Apktool; Gradle owns and executes this build task.
java -jar "$APKTOOL" d -f "$ORIGINAL" -o "$DECODED"

LIB="$DECODED/lib/armeabi-v7a/libaurea.so"
if [[ ! -f "$LIB" ]]; then
  echo "::error::Apktool output is missing lib/armeabi-v7a/libaurea.so"
  find "$DECODED/lib" -maxdepth 3 -type f -print || true
  exit 1
fi

python3 scripts/patch_aurea_shader_headers.py "$LIB"
python3 scripts/patch_aurea_device_defaults.py "$DECODED/smali/er1.smali"
python3 scripts/patch_aurea_font_fallback.py "$DECODED/smali/c01.smali"
sha256sum "$LIB" | tee "$OUT/patched-libaurea-sha256.txt"

java -jar "$APKTOOL" b "$DECODED" -o "$UNSIGNED"

# Keep native libraries uncompressed, then align before signing.
python3 - "$UNSIGNED" "$WORK/native-stored.apk" <<'PY'
import sys, zipfile, os
source, target = sys.argv[1:]
with zipfile.ZipFile(source, 'r') as zin, zipfile.ZipFile(target, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename.startswith('lib/') and item.filename.endswith('.so'):
            item.compress_type = zipfile.ZIP_STORED
        zout.writestr(item, data)
os.replace(target, source)
PY

zipalign -f -p 4 "$UNSIGNED" "$ALIGNED"

keytool -genkeypair -noprompt \
  -keystore "$WORK/aurea-gradle-test.jks" \
  -storepass android -keypass android \
  -alias aurea-patch -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Aurea GLES Compatibility Patch, OU=Android, O=Kineticv2, C=US"

apksigner sign \
  --ks "$WORK/aurea-gradle-test.jks" \
  --ks-key-alias aurea-patch \
  --ks-pass pass:android --key-pass pass:android \
  --out "$FINAL" "$ALIGNED"

apksigner verify --verbose "$FINAL"
zipalign -c -v 4 "$FINAL"
sha256sum "$FINAL" | tee "$OUT/apk-sha256.txt"
