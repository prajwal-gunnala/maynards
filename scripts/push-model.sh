#!/usr/bin/env bash
# Copy model files into the MeshAI app's model folder on a phone.
#   scripts/push-model.sh [-s serial] model.gguf [more.gguf ...]
# adb writes files (and maybe the folder) as the shell user; the app must be able to list and read them.
set -euo pipefail
ADB=(adb)
if [ "${1:-}" = "-s" ]; then ADB=(adb -s "$2"); shift 2; fi
DIR=/sdcard/Android/data/ai.maynards.mesh/files/models
"${ADB[@]}" shell mkdir -p "$DIR"
"${ADB[@]}" shell chmod 777 "$DIR"   # if adb created the folder, the app could not list it
for f in "$@"; do
  "${ADB[@]}" push "$f" "$DIR/"
  "${ADB[@]}" shell chmod 666 "$DIR/$(basename "$f")"
done
"${ADB[@]}" shell ls -la "$DIR"
