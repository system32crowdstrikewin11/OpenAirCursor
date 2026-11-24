#!/usr/bin/env sh
set -eu

MODEL_URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
MODEL_SHA256="fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
MODEL_DEST="app/src/main/assets/hand_landmarker.task"

verify_sha256() {
    echo "$MODEL_SHA256  $1" | sha256sum -c - >/dev/null 2>&1
}

if [ -f "$MODEL_DEST" ] && verify_sha256 "$MODEL_DEST"; then
    echo "hand_landmarker.task already present and verified."
    exit 0
fi

mkdir -p "$(dirname "$MODEL_DEST")"
TMP_FILE="${MODEL_DEST}.tmp"
echo "Downloading hand_landmarker.task..."
curl -fL --retry 3 -o "$TMP_FILE" "$MODEL_URL"

if ! verify_sha256 "$TMP_FILE"; then
    rm -f "$TMP_FILE"
    echo "ERROR: SHA-256 mismatch for hand_landmarker.task!" >&2
    exit 1
fi

mv "$TMP_FILE" "$MODEL_DEST"
echo "Verified hand_landmarker.task ($MODEL_SHA256)."
