# MeshAI

Run AI models your phone can't run alone, by pooling the memory of the phones and laptop you already own.

Team Maynards · iQOO Hackathon 2026, Hyderabad · Developer Tools track.

## The idea

One app, installed on every phone. One phone is the **Host**; the other phones and the laptop are **Helpers**.

- The **Host** pairs devices, reads their specs, sorts models into **Doable / Tight / Not possible**, decides which
  device holds which layers, and runs the model. You use it from the Host: chat, image, voice.
- **Helpers** hold part of the model and show what they are doing: layers held, memory, heat, battery.
- Everything stays on your devices. No cloud, no account.

Rules the Host follows: never split a model that fits on one device; skip devices that are too slow to reach, too
hot or nearly flat; keep each device's layers in one unbroken run.

## Layout

```
android/   the app (Kotlin, Jetpack Compose): Host and Helper roles
agent/     `mesh`, the laptop helper and CLI (Rust)
scripts/   llama.cpp build, two-phone proof
docs/      measurements.md (every number we took); pre-event/ (notes drafted before the event)
```

## Credits

- [llama.cpp](https://github.com/ggml-org/llama.cpp) and ggml, MIT licence: all inference and the RPC transport.
- Qwen3 and Qwen3-Coder model weights, Apache 2.0.
- [ZXing](https://github.com/zxing/zxing) and zxing-android-embedded, Apache 2.0: QR codes.
- AndroidX, Jetpack Compose, OkHttp, kotlinx: Apache 2.0.
- vivo Office Kit: used as the phone-laptop bridge during the event; our code does not call it.

Everything under `docs/pre-event/` is research and design drafted before the event, as the rules allow. All code in
this repository was written during the event.
