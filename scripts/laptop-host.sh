#!/usr/bin/env bash
# The laptop as Host: it holds the model file and runs llama-server; both phones lend memory as Helpers,
# each over its own USB tethering link (turn on USB tethering on both phones first).
#
#   scripts/laptop-host.sh <phoneA-serial> <phoneB-serial> [model.gguf] [layers on each phone]
#
# 48-layer 30B with 16 per phone: laptop 0-15, phone A 16-31, phone B 32-47 (+ output head, pinned back to the laptop).
set -euo pipefail
A=$1 B=$2
MODEL=${3:-/mnt/storage/meshai/models/Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf}
PER_PHONE=${4:-16}
BIN=${BIN:-/mnt/storage/meshai/build/v3/host/bin}
PORT=${PORT:-8090}
PKG=ai.maynards.mesh

tether_ip() {  # the phone's address on its USB tethering link
  adb -s "$1" shell ip -4 -o addr show | awk '$2 ~ /^(rndis|usb|ncm)/ {split($4,a,"/"); print a[1]; exit}'
}

helper_port() {  # the app tries 50052 first, then a few fallbacks
  for p in 50052 50062 50070 50080 50100; do
    timeout 1 bash -c "</dev/tcp/$1/$p" 2>/dev/null && { echo "$p"; return; }
  done
  return 1
}

addrs=()
for s in "$A" "$B"; do
  ip=$(tether_ip "$s")
  [ -n "$ip" ] || { echo "$s: USB tethering is off (Settings → Personal hotspot → More → USB tethering)" >&2; exit 1; }
  adb -s "$s" shell am start -S -n $PKG/.MainActivity --es role HELPER --ez start true --es bind "$ip" >/dev/null
  port=""
  for _ in $(seq 20); do port=$(helper_port "$ip" || true); [ -n "$port" ] && break; sleep 1; done
  [ -n "$port" ] || { echo "$s: helper did not start on $ip" >&2; exit 1; }
  echo "$s helper on $ip:$port ($(ping -c 5 -i 0.2 -q "$ip" | tail -1 | cut -d= -f2))"
  addrs+=("$ip:$port")
done

layers=$(( PER_PHONE * 2 ))
# llama.cpp offloads the last N layers and counts the output head as one more; phone B (listed last) also gets the head,
# then the head is pinned back to the laptop's CPU.
split=$(python3 -c "n=$PER_PHONE; t=2*n+1; print(f'{n/t:.6f},{(n+1)/t:.6f}')")
pkill -f "llama-server.*--port $PORT" || true
echo "laptop: layers 0-$(( 47 - layers )), phone A next $PER_PHONE, phone B last $PER_PHONE"
nohup "$BIN/llama-server" -m "$MODEL" -c 4096 -t "$(( $(nproc) - 2 ))" --host 127.0.0.1 --port "$PORT" \
  --jinja --reasoning off -np 1 --fit off -ctk q8_0 -ctv q8_0 -fa on \
  --rpc "$(IFS=,; echo "${addrs[*]}")" -ngl $(( layers + 1 )) --tensor-split "$split" \
  --override-tensor '^(output|output_norm|token_embd)\.(weight|bias)$=CPU' > /tmp/mesh-laptop-host.log 2>&1 &

t=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' "localhost:$PORT/health")" = 200 ]; do
  sleep 5; t=$((t+5)); printf '.'
  if ! pgrep -f "llama-server.*--port $PORT" >/dev/null; then echo; tail -20 /tmp/mesh-laptop-host.log; exit 1; fi
done
echo " ready after ${t}s: http://localhost:$PORT/v1  (log: /tmp/mesh-laptop-host.log)"
