# Runbook: every command to start and use the system

All paths are on the laptop. Phone serials: iQOO ·01GG = `10BFBJ0SQJ001GG`, iQOO ·00XP = `10BFAT1SA2000XP`.
Models live in `~/meshai-models` (laptop) and `/sdcard/Android/data/ai.maynards.mesh/files/models/` (phones).

## 0. Build (once, or after code changes)

```bash
cd ~/Documents/GitHub/maynards
scripts/build-llama.sh                       # llama.cpp for phones + laptop, same commit (RPC needs identical builds)
scripts/sync-natives.sh                      # copy the phone build into the app
cd android && ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
cd ../agent && cargo build --release         # target/release/mesh
```

## 1. Phones: install the app and keep it alive in the background

```bash
S=10BFBJ0SQJ001GG   # or 10BFAT1SA2000XP
adb -s $S install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb -s $S shell 'pkg=ai.maynards.mesh; dumpsys deviceidle whitelist +$pkg; cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow;
  settings put global settings_enable_monitor_phantom_procs false; settings put global stay_on_while_plugged_in 7'
# the model file (18.6 GB takes ~15 min over the cable)
adb -s $S shell 'mkdir -p /sdcard/Android/data/ai.maynards.mesh/files/models; chmod 777 /sdcard/Android/data/ai.maynards.mesh/files/models'
# (a folder made by adb is mode 770 and the app cannot list it: its log says "models in ...: null"; chmod 777 fixes it)
adb -s $S push ~/meshai-models/Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf /sdcard/Android/data/ai.maynards.mesh/files/models/
# by hand on a vivo/iQOO phone: Settings → Battery → Monster Mode on (no adb switch); the app's Speed card has an Open button
```

## 2. The cable link (USB tethering) on each phone

```bash
adb -s $S shell svc usb setFunctions rndis     # phone offers a network over USB; adb keeps working
ip -br addr | grep enx                          # the laptop now has one 10.x.x.x link per phone
ip route | grep default                         # must stay on the laptop's own Wi-Fi (the host keeps tether links non-default)
```

## 3a. Laptop as Host (the measured 30B setup)

```bash
cd ~/Documents/GitHub/maynards
MESH_CTX=16384 MESH_MODELS=$HOME/meshai-models setsid nohup agent/target/release/mesh host --port 8080 >> /tmp/mesh-host.log 2>&1 &
xdg-open http://localhost:8080/                 # dashboard (localhost needs no key)
# join a phone without scanning: fetch a fresh invite (each one works once) and hand it to the app
INV=$(curl -s localhost:8080/api/state | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['invite']))")
adb -s $S shell "am start -S -n ai.maynards.mesh/.MainActivity --es role HELPER --es join '$INV'"
# start a model from the shell (or press Run on the dashboard)
curl -s -X POST localhost:8080/api/run -H 'content-type: application/json' -d '{"model":"Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf"}'
curl -s localhost:8080/api/state | python3 -c "import sys,json; r=json.load(sys.stdin)['run']; print(r['status'], r['step'])"
# fixed split for repeatable runs: ~/.config/meshai/pinned.json (device ids as the dashboard shows them)
pkill -x mesh                                   # stop the host
```

Desktop app instead of the browser (starts the host itself if none runs; light/dark toggle in its top bar):
`python3 desktop/meshai.py`

## 3b. Phone as Host, laptop and other phone as helpers

```bash
S=10BFBJ0SQJ001GG
adb -s $S shell am start -S -n ai.maynards.mesh/.MainActivity --es role HOST
INV=$(adb -s $S shell cat /sdcard/Android/data/ai.maynards.mesh/files/invite.json | tr -d '\r')
# laptop joins (offers ~/meshai-models and its memory; keep this running)
MESH_MODELS=$HOME/meshai-models MESH_RPC_SERVER=/mnt/storage/meshai/build/v3/host/bin/ggml-rpc-server \
  LD_LIBRARY_PATH=/mnt/storage/meshai/build/v3/host/bin agent/target/release/mesh join "$INV"
# second phone joins (read the invite again: the token rotates after each join)
INV=$(adb -s $S shell cat /sdcard/Android/data/ai.maynards.mesh/files/invite.json | tr -d '\r')
adb -s 10BFAT1SA2000XP shell "am start -S -n ai.maynards.mesh/.MainActivity --es role HELPER --es join '$INV'"
# start a model on the phone host from the shell
adb -s $S shell am start -n ai.maynards.mesh/.MainActivity --es role HOST --es run Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf
# ask it from the laptop (the phone's cable address; key is shown on the phone's Use tab)
agent/target/release/mesh ask "hello" --host 10.155.241.73
```

Note: two phones talk to each other over Wi-Fi in this mode (their cables both end at the laptop). The
planner refuses a device over 60 ms, and the hotspot measured 27–145 ms between the phones; with the
laptop as Host both phones are on 2–3 ms cables, which is why the 30B runs were done that way.

## 4. Using it

```bash
agent/target/release/mesh ask "question" [--host IP]
agent/target/release/mesh review FILE...          # private code review
agent/target/release/mesh tests FILE --out PATH
agent/target/release/mesh hook                    # git hook
# any OpenAI-compatible client: http://<host>:8080/v1 with the key from ~/.config/meshai/api-key (laptop host)
```

## 5. Operations: Relay, Jobs, Metering & Speed Planning

```bash
# 5a. Start the transparent WAN TCP relay (for helpers behind NAT / mobile data):
python3 scripts/relay.py --bind 0.0.0.0 --port 7071

# 5b. Start host with relay advertise:
MESH_RELAY=public-relay-ip:7071 agent/target/release/mesh host --port 8080

# 5c. Submit and manage background jobs:
curl -s -X POST localhost:8080/api/jobs -H 'content-type: application/json' \
  -d '{"prompt": "Explain distributed pipelined inference.", "model": "Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf"}'
curl -s localhost:8080/api/jobs                 # list all jobs and progress
curl -s -X POST localhost:8080/api/jobs/cancel -H 'content-type: application/json' -d '{"id": "<job_id>"}'

# 5d. Query warm RAM residency & cloud arbitrage earnings:
curl -s localhost:8080/api/meter | jq .

# 5e. Inspect compute-aware speed plan and pipeline bottleneck:
curl -s localhost:8080/api/plan | jq .pipeline

# 5f. Run the automated integration test harness across all subsystems:
for f in scripts/test_*_wire.py; do python3 "$f"; done
```

## 6. Mesh Compiler (tuning, separate repo ~/Documents/GitHub/mesh-compiler)

```bash
cd ~/Documents/GitHub/mesh-compiler; S=10BFBJ0SQJ001GG; M=Qwen3-4B-Instruct-2507-Q4_K_M.gguf
scripts/build-opencl.sh                                        # GPU build of llama.cpp, once
python3 -m tuner baseline --serial $S --model $M               # MeshAI's settings today
.venv/bin/python -m tuner optimize --serial $S --trials 24 --cool 40
.venv/bin/python -m tuner part --part gate --serial $S --cool 40     # quality gate (15 coding problems)
python3 -m tuner reuse --serial $S --model $M --set engine=opencl ngl=99 ctk=f16 ctv=f16
.venv/bin/python -m tuner summary && .venv/bin/python -m tuner report   # docs/03-results.md, reports/index.html
```

## 7. Phone as laptop touchpad/keyboard (not part of MeshAI)

```bash
sudo setfacl -m u:$USER:rw /dev/uinput     # once per boot
~/phone-remote/start.sh                    # opens localhost:8765 on the iQOO; Ctrl+C stops it
```

## 8. Stopping everything

```bash
pkill -x mesh                                                # laptop host or helper
adb -s $S shell 'am force-stop ai.maynards.mesh'             # the phone app and its engine
adb -s $S shell svc usb setFunctions mtp                     # tethering off
```
