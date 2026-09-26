#!/usr/bin/env bash
# Prove a model split across two phones, with no app: everything over adb.
#   helper phone: ggml-rpc-server holds the last N layers
#   host phone:   llama-server loads the model and sends those layers to the helper
# Both phones must be on the same network (the host phone's hotspot).
#
#   scripts/two-phone-proof.sh <host-serial> <helper-serial> <model.gguf> [layers-on-helper]
set -euo pipefail

HOST=$1 HELPER=$2 MODEL=$3 NGL=${4:-0}
BIN=${BIN:-/mnt/storage/meshai/build/v3/android-arm64/bin}
DIR=/data/local/tmp/mesh
PORT=50052
CTX=${CTX:-4096}
THREADS=${THREADS:-6}
LOCAL_PORT=8090

on() { adb -s "$1" shell "$2"; }

push_bins() {
  on "$1" "mkdir -p $DIR/cache"
  adb -s "$1" push --sync "$BIN"/ggml-rpc-server "$BIN"/llama-server "$BIN"/llama-bench "$BIN"/*.so "$DIR/"
  on "$1" "chmod 755 $DIR/ggml-rpc-server $DIR/llama-server $DIR/llama-bench"
}

wifi_ip() {  # the phone's IPv4 on its Wi-Fi or hotspot interface
  on "$1" "ip -4 -o addr show" | awk '$2 ~ /^(wlan|ap|swlan)/ {split($4,a,"/"); print a[1]; exit}'
}

echo "== push engine to both phones"
push_bins "$HOST"; push_bins "$HELPER"

name=$(basename "$MODEL")
if [ "$(on "$HOST" "stat -c %s $DIR/$name 2>/dev/null" | tr -d '\r')" != "$(stat -c %s "$MODEL")" ]; then
  echo "== push $name to the host phone"
  adb -s "$HOST" push "$MODEL" "$DIR/$name"
fi

on "$HOST" "pkill -f llama-server; pkill -f ggml-rpc-server" || true
on "$HELPER" "pkill -f llama-server; pkill -f ggml-rpc-server" || true

ARGS="-m $DIR/$name -c $CTX -t $THREADS --host 127.0.0.1 --port 8080 --jinja"
help=$(on "$HOST" "cd $DIR && LD_LIBRARY_PATH=$DIR ./llama-server --help" 2>&1 || true)
grep -q -- '--fit' <<<"$help" && ARGS+=" --fit off"            # keep our layer plan as given
grep -q -- '--reasoning' <<<"$help" && ARGS+=" --reasoning off"

if [ "$NGL" -gt 0 ]; then
  HELPER_IP=$(wifi_ip "$HELPER")
  [ -n "$HELPER_IP" ] || { echo "helper phone has no Wi-Fi address" >&2; exit 1; }
  echo "== helper $HELPER_IP:$PORT"
  on "$HELPER" "cd $DIR; LD_LIBRARY_PATH=$DIR LLAMA_CACHE=$DIR/cache nohup ./ggml-rpc-server -H $HELPER_IP -p $PORT -t $THREADS -c > rpc.log 2>&1 < /dev/null &"
  sleep 2
  on "$HOST" "nc -z -w 3 $HELPER_IP $PORT" || { echo "host phone cannot reach the helper (hotspot isolation?)" >&2; exit 1; }
  # llama.cpp counts the output head as one more layer; offload it too, then pin head and embeddings back
  # to the host so only one small activation crosses the link per token.
  ARGS+=" --rpc $HELPER_IP:$PORT -ngl $((NGL + 1))"
  ARGS+=" --override-tensor '^(output|output_norm|token_embd)\\.(weight|bias)\$=CPU'"
  ARGS+=" -ctk q8_0 -ctv q8_0 -fa on"
else
  ARGS+=" -ngl 0"
fi

echo "== host: llama-server $ARGS"
on "$HOST" "cd $DIR; LD_LIBRARY_PATH=$DIR nohup ./llama-server $ARGS > server.log 2>&1 < /dev/null &"
adb -s "$HOST" forward tcp:$LOCAL_PORT tcp:8080 >/dev/null

t0=$(date +%s)
until [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:$LOCAL_PORT/health)" = 200 ]; do
  if ! on "$HOST" "pgrep -f llama-server" >/dev/null; then
    echo "llama-server exited:" >&2; on "$HOST" "tail -20 $DIR/server.log" >&2; exit 1
  fi
  sleep 2
done
echo "== ready in $(( $(date +%s) - t0 )) s"

curl -s localhost:$LOCAL_PORT/v1/chat/completions -H 'content-type: application/json' -d '{
  "messages":[{"role":"user","content":"Write a Python function that checks if a number is prime."}],
  "max_tokens":128}' |
  python3 -c 'import json,sys; r=json.load(sys.stdin); t=r["timings"]
print(r["choices"][0]["message"]["content"][:300]); print()
print("decode %.1f tok/s  prompt %.1f tok/s  tokens %d" % (t["predicted_per_second"], t["prompt_per_second"], t["predicted_n"]))'

echo "== memory held (RSS)"
on "$HOST" "ps -A -o RSS,NAME | grep llama-server" || true
[ "$NGL" -gt 0 ] && { on "$HELPER" "ps -A -o RSS,NAME | grep ggml-rpc-server" || true; }
echo "stop: adb -s $HOST shell pkill -f llama-server; adb -s $HELPER shell pkill -f ggml-rpc-server"
