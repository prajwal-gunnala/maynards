#!/usr/bin/env bash
# Laptop side of the two-model demo: each phone runs its own model (Host → Models → Run),
# the laptop gives them one address and a chat page. Photos go to the phone running a vision model.
#   scripts/two-models.sh            (both phones on USB with debugging on)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
text="" vision="" port=18081

for s in $(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /emulator/ {print $1}'); do
  adb -s "$s" forward "tcp:$port" tcp:8080 >/dev/null
  model=$(curl -s -m 3 "localhost:$port/v1/models" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"][0]["id"].rsplit("/",1)[-1])' 2>/dev/null || true)
  if [ -z "$model" ]; then
    echo "$s: no model running (on the phone: MeshAI → Host → Models → Run)"
    adb -s "$s" forward --remove "tcp:$port"
  elif grep -qiE 'vl|vision' <<<"$model"; then
    echo "$s: $model  → photos"; vision="127.0.0.1:$port"
  else
    echo "$s: $model  → text"; text="127.0.0.1:$port"
  fi
  port=$((port + 1))
done

[ -n "$text$vision" ] || { echo "no phone is running a model yet" >&2; exit 1; }
pkill -x mesh 2>/dev/null || true
args=()
[ -n "$text" ] && args+=(--text "$text")
[ -n "$vision" ] && args+=(--vision "$vision")
nohup "$ROOT/agent/target/release/mesh" route "${args[@]}" --port 8080 > /tmp/mesh-route.log 2>&1 &
sleep 1
echo "chat page: http://localhost:8080/"
xdg-open http://localhost:8080/ >/dev/null 2>&1 || true
