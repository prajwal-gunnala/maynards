#!/usr/bin/env bash
# Copy model files into the MeshAI app's model folder on a phone.
#   scripts/push-model.sh [-s serial] model.gguf [more.gguf ...]
# adb writes files as the shell user; they must be readable by the app, hence the chmod.
set -euo pipefail
ADB=(adb)
if [ "${1:-}" = "-s" ]; then ADB=(adb -s "$2"); shift 2; fi
DIR=/sdcard/Android/data/ai.maynards.mesh/files/models
"${ADB[@]}" shell mkdir -p "$DIR"
for f in "$@"; do
  "${ADB[@]}" push "$f" "$DIR/"
  "${ADB[@]}" shell chmod 666 "$DIR/$(basename "$f")"
done
"${ADB[@]}" shell ls -la "$DIR"
