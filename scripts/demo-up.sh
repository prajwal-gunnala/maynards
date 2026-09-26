#!/usr/bin/env bash
# Bring the whole mesh up from the laptop, both phones on USB:
#   scripts/demo-up.sh <host-serial> <helper-serial> [model name part] [helpers to wait for]
# 1. phone settings (child-process limits off, stay awake, background apps closed)
# 2. latest APK on both phones
# 3. the model on the Host only (the Helper gets its layers over the link)
# 4. Helper joins the Host's hotspot, the laptop joins over USB tethering
# 5. Host starts, both join, the Host plans and runs the model
# The Host's hotspot must be on (Settings → Personal hotspot), and USB tethering if the laptop should help.
set -euo pipefail
HOST=$1 HELPER=$2 MODEL=${3:-Qwen3-Coder-30B} WAIT=${4:-2}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK=$ROOT/android/app/build/outputs/apk/debug/app-debug.apk
MODELS=${MESH_MODELS:-/mnt/storage/meshai/models}
SSID=${MESH_SSID:-iQOO 15} PASS=${MESH_PASS:-}
# the hotspot password is never stored here: export MESH_PASS before running, or pass it on the line
if [[ -z $PASS ]]; then echo "set MESH_PASS to the Host phone's hotspot password (export MESH_PASS=...)" >&2; exit 1; fi
PKG=ai.maynards.mesh
DIR=/sdcard/Android/data/$PKG/files/models

say() { printf '\n== %s\n' "$*"; }

say "phones"
for s in "$HOST" "$HELPER"; do
  adb -s "$s" shell "settings put global settings_enable_monitor_phantom_procs false; \
    settings put global stay_on_while_plugged_in 7; am kill-all" >/dev/null
  adb -s "$s" install -r "$APK" | tail -1
  for p in POST_NOTIFICATIONS RECORD_AUDIO CAMERA; do adb -s "$s" shell pm grant $PKG android.permission.$p || true; done
  echo "$s: $(adb -s "$s" shell grep MemAvailable /proc/meminfo | awk '{printf "%.1f GB free", $2/1e6}')"
done

say "model on the Host"
file=$(ls "$MODELS" | grep -i -- "$MODEL" | grep '\.gguf$' | head -1)
[ -n "$file" ] || { echo "no model matching $MODEL in $MODELS" >&2; exit 1; }
have=$(adb -s "$HOST" shell "stat -c %s $DIR/$file 2>/dev/null" | tr -d '\r' || true)
if [ "$have" != "$(stat -c %s "$MODELS/$file")" ]; then
  "$ROOT/scripts/push-model.sh" -s "$HOST" "$MODELS/$file" | tail -1
fi
echo "$file ready on the Host"

say "link"
adb -s "$HELPER" shell cmd wifi connect-network "'\"$SSID\"'" wpa2 "$PASS" >/dev/null || true
sleep 8
hotspot_ip=$(adb -s "$HOST" shell ip -4 -o addr show | awk '$2 ~ /^(wlan[1-9]|ap|swlan)/ {split($4,a,"/"); print a[1]; exit}')
[ -n "$hotspot_ip" ] || { echo "turn on the Host's hotspot first" >&2; exit 1; }
adb -s "$HOST" shell ping -c 5 -q "$(adb -s "$HELPER" shell ip -4 -o addr show wlan0 | awk '{split($4,a,"/"); print a[1]}')" | tail -1

say "mesh"
adb -s "$HOST" shell am start -S -n $PKG/.MainActivity --es role HOST --es run "$MODEL" --ei helpers "$WAIT" >/dev/null
sleep 5
invite=$(adb -s "$HOST" shell cat /sdcard/Android/data/$PKG/files/invite.json)
echo "$invite"
adb -s "$HELPER" shell am start -S -n $PKG/.MainActivity --es role HELPER --es join "'$invite'" >/dev/null
if [ "$WAIT" -ge 2 ]; then
  pkill -x mesh || true
  MESH_MODELS=$MODELS MESH_RPC_SERVER=${MESH_RPC_SERVER:-/mnt/storage/meshai/build/v3/host/bin/ggml-rpc-server} \
    nohup "$ROOT/agent/target/release/mesh" join "$invite" > /tmp/mesh-agent.log 2>&1 &
  echo "laptop joined as a helper (log: /tmp/mesh-agent.log)"
fi

say "waiting for the model"
host_ip=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["hosts"][0])' "$invite")
t=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' "http://$host_ip:8080/health")" = 200 ]; do
  sleep 5; t=$((t+5)); printf '.'
  [ $t -ge 900 ] && { echo " not ready after 15 min" >&2; exit 1; }
done
echo " ready after ${t}s: http://$host_ip:8080/v1"
