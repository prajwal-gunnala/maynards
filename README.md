# MeshAI

Run AI models your phone can't run alone, by pooling the memory of the phones and laptop you already own.

Team Maynards · iQOO Hackathon 2026, Hyderabad · Developer Tools track.

## The idea

One app, installed on every phone. One phone is the **Host**; the other phones and the laptop are **Helpers**.

- The **Host** pairs devices, reads their specs, sorts models into **Doable / Tight / Not possible**, decides which
  device holds which layers, and runs the model. You use it from the Host: chat, voice, photos.
- **Helpers** hold part of the model and show what they are doing: layers held, memory, heat, battery.
- Everything stays on your devices. No cloud, no account.

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
agent/     `mesh`, the laptop helper and CLI (Rust)
scripts/   build-llama.sh · sync-natives.sh · two-phone-proof.sh
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

- [ ] Both phones charging, apps closed, MeshAI in front, screen on.
- [ ] Host hotspot on; Helper joined (Mesh tab shows 2 devices and the link in ms).
- [ ] Models tab: Qwen3-Coder-30B shows **Tight · Needs 2 devices**; Qwen3-8B shows **Runs on this phone alone**.
- [ ] Run the 30B; Helper screen shows its layers and GBs held.
- [ ] Ask a coding question on the Host; `mesh review file.py` from the laptop.
- [ ] Turn mobile data and Wi-Fi internet off: it keeps answering.
- [ ] Stats tab: tok/s per answer, one device vs split.

## Credits

- [llama.cpp](https://github.com/ggml-org/llama.cpp) and ggml, MIT licence: all inference and the RPC transport.
- Qwen3, Qwen3-Coder and Qwen2.5-VL model weights, Apache 2.0.
- [ZXing](https://github.com/zxing/zxing) and zxing-android-embedded, Apache 2.0: QR codes.
- AndroidX, Jetpack Compose, OkHttp, kotlinx coroutines: Apache 2.0. serde_json (Rust): MIT/Apache 2.0.
- vivo Office Kit: used as the phone-laptop bridge during the event; our code does not call it.

Everything under `docs/pre-event/` is research and design drafted before the event, as the rules allow. All code in
this repository was written during the event.
