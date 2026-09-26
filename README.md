# MeshAI

Run AI models your phone can't run alone, by pooling the memory of the phones and laptop you already own.

Team Maynards · iQOO Hackathon 2026, Hyderabad · Developer Tools track.

## The idea

One app, installed on every phone. One device is the **Host**; the rest are **Helpers**.

- The **Host** pairs devices, reads their specs, sorts models into **Doable / Tight / Not possible**, decides which
  device holds which layers, and runs the model. You use it from the Host: chat, voice, photos.
- **Helpers** hold part of the model and show what they are doing: layers held, memory, heat, battery.
- Everything stays on your devices. No cloud, no account.

Either the phone or the laptop can be the Host, and the two paths are at different stages:

| Host | Where you drive it | Measured up to |
|---|---|---|
| **A phone** (the app's Host role) | the phone's screen | Qwen3-8B on the phone alone at 11.8 tok/s, and splits with a helper |
| **The laptop** (`mesh host`, a web panel) | `http://localhost:8080/` | **Qwen3-Coder-30B-A3B across the laptop and two phones at 6.6 tok/s**, the phones holding 9.6 GB of the 18.6 GB |

The 30B run is the headline and it is the laptop-as-Host path: the laptop coordinates and keeps the output head,
while most of the weights sit on the phones. Both numbers, with their conditions, are in `docs/measurements.md`.

Rules the Host follows: never split a model that fits on one device; skip devices that are too slow to reach
(> 60 ms), too hot or nearly flat; give each device one unbroken run of layers; keep embeddings and the output head
on the Host so only one small activation crosses the link per token.

```
Host phone                                Helper phone
 Mesh · Models · Use · Stats               "holding layers 25–47 · 8.9 GB"
 planner + llama-server --rpc  ◄── hotspot ──►  ggml-rpc-server
        ▲
        └── laptop: `mesh join` (third helper) · `mesh ask` / `mesh review` (uses the Host's /v1)
```

## Layout

```
android/   the app (Kotlin, Jetpack Compose): Host and Helper roles
  brain/   Gguf (read model headers) · Planner · EngineArgs · Runner · Chat · Stats · Catalog
  mesh/    Wire (JSON lines over TCP 7070) · MeshHost · MeshClient
  engine/  Engine (runs llama.cpp from the APK) · Specs · Net
  ui/      neobrutalist screens; the mesh is drawn as a spider web
agent/     `mesh`, the laptop's side (Rust): `mesh host` (web panel, the laptop as Host), `mesh join`
           (laptop as Helper), `mesh ask` / `mesh review` (CLI against any Host's /v1)
scripts/   build-llama.sh · sync-natives.sh · two-phone-proof.sh · demo-up.sh · bench.py · bench-report.py
results/   what each configuration scored on the same task set, and the chart
docs/      measurements.md (every number we took) · pre-event/ (notes drafted before the event)
```

## Build

```bash
scripts/build-llama.sh                 # llama.cpp for phones (arm64) + laptop, same commit (RPC needs identical builds)
scripts/sync-natives.sh                # copy the phone build into the app
cd android && ./gradlew assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest            # GGUF reader + planner tests
cd ../agent && cargo build --release   # target/release/mesh
```

## Run

1. **Phones:** install the APK on every phone. Developer options → turn off "child process restrictions",
   keep the screen on while charging.
2. **Link:** turn on the hotspot on the Host phone; join the Helper phone (and the laptop) to it.
   Venue Wi-Fi measured 266–483 ms: far too slow to split a model.
3. **Models:** put GGUF files in the Host's model folder:
   `adb push model.gguf /sdcard/Android/data/ai.maynards.mesh/files/models/`
4. **Host phone:** open MeshAI → Host. It shows a QR code.
5. **Helper phone:** open MeshAI → Helper → Scan to join.
6. **Laptop (optional):**
   `mesh join "$(adb shell cat /sdcard/Android/data/ai.maynards.mesh/files/invite.json)"`
   (set `MESH_RPC_SERVER` to the laptop's `ggml-rpc-server`).
7. **Host → Models:** pick a Doable or Tight model → Run. **Use:** chat, speak, or send a photo (vision models).
8. **From the laptop:** `mesh ask "question" --host <host ip>` or point any OpenAI-compatible tool at
   `http://<host ip>:8080/v1`.

Without touching the screen (handy with Office Kit mirroring or scripts):

```bash
adb shell am start -n ai.maynards.mesh/.MainActivity --es role HOST
adb shell am start -n ai.maynards.mesh/.MainActivity --es role HELPER --es join '<invite json>'
```

## Demo checklist

Both phones charging, other apps closed, MeshAI in front, screen on.

**Phone as Host, no laptop involved:**

- [ ] Models tab: Qwen3-8B shows **Runs on this phone alone**; run it and ask a coding question (11.8 tok/s).
- [ ] Add the second phone as a Helper: the Mesh tab shows 2 devices and the link in ms, the Helper screen
      shows the layers and GBs it holds.
- [ ] Turn mobile data and Wi-Fi internet off: it keeps answering.

**Laptop as Host, the 30B across three devices:**

- [ ] `mesh host`, both phones on USB tethering, panel shows the pool and the plan.
- [ ] Run Qwen3-Coder-30B-A3B: ready in about 70 s once each phone has stored its own layers, 6.6 tok/s.
- [ ] Measure this setup: the same coding problems against every configuration, scored by running the answers
      against their tests (`results/REPORT.md`).
- [ ] Ask from another tool: any OpenAI-compatible client against `http://<laptop>:8080/v1` with the key on the
      Use page; `mesh review file.py` from a shell.
- [ ] Stats tab: tok/s per answer, one device against the split.

Not tested yet, so do not promise it: a phone as Host running the 30B. The phone-as-Host path is proven to 8B.

## What it took to make the split usable

Each of these is a measured before-and-after, and the numbers are in `docs/measurements.md`:

- **`--load-mode none`: model load 517 s → 70 s.** With mmap the engine read the file in scattered pieces as it
  pushed layers to the phones. Reading it once, front to back, is the single largest win in the project.
- **Each phone stores its own layers.** A split it has never used before is ready in 70 s instead of 437 s,
  because the layers come off the phone's own storage rather than back over the cable. Layer names and bytes are
  checked against the engine's own file (`LayerStoreTest`).
- **One conversation slot (`-np 1`).** The engine's default divides the context between parallel slots, so a long
  chat kept falling out of the cache and being read again before it could answer. One slot means every message
  reuses the cached history.
- **Embeddings and the output head stay on the Host**, so only one small activation crosses the link per token.
  This is why splitting is viable at all: the link carries kilobytes, not weights.
- **Median of ten round trips, not the average**, so one Wi-Fi spike does not evict a healthy helper.
- **A sustained run, not a single shot**: 11.4 → 5.3 tok/s as the phone goes 34.6 → 47.5 °C, raw CSV in
  `docs/sustained-8b-iqoo.csv`. Phones throttle; a number taken in the first ten seconds is not a number.
- **Text and vision on two different phones at once**, routed by the request, with an `X-Mesh-Route` header
  naming the device that answered.
- **The planner refuses, with a reason you can read**: too slow to reach (> 60 ms), too hot, nearly flat, or the
  model fits one device already so splitting would only make it slower. Seven hermetic tests cover it.

## Credits

- [llama.cpp](https://github.com/ggml-org/llama.cpp) and ggml, MIT licence: all inference and the RPC transport.
- Qwen3, Qwen3-Coder and Qwen2.5-VL model weights, Apache 2.0.
- [ZXing](https://github.com/zxing/zxing) and zxing-android-embedded, Apache 2.0: QR codes.
- AndroidX, Jetpack Compose, OkHttp, kotlinx coroutines: Apache 2.0. serde_json (Rust): MIT/Apache 2.0.
- vivo Office Kit: used as the phone-laptop bridge during the event; our code does not call it.

Everything under `docs/pre-event/` is research and design drafted before the event, as the rules allow. All code in
this repository was written during the event.
