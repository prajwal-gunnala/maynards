# Measurements

Every number here was run. Engine: llama.cpp 66fba63, CPU backend, built by `scripts/build-llama.sh`.

| When | Device(s) | Model | Layers | Link | Context | Decode tok/s | Prompt tok/s | Memory held | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 26 Sep 13:25 | iQOO 15 #1 alone (SM8850, 16 GB) | Qwen3-0.6B Q8_0 | all 28 on the phone | none | 4096 | **78.1** | 768.7 | 1.77 GB RSS | 6 threads, 128 tokens, USB-powered, 95% battery, run over adb (`scripts/two-phone-proof.sh`) |
