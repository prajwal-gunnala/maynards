# Measurements

Every number here was run. Engine: llama.cpp 66fba63, CPU backend, built by `scripts/build-llama.sh`.

| When | Device(s) | Model | Layers | Link | Context | Decode tok/s | Prompt tok/s | Memory held | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 26 Sep 13:25 | iQOO 15 #1 alone (SM8850, 16 GB) | Qwen3-0.6B Q8_0 | all 28 on the phone | none | 4096 | **78.1** | 768.7 | 1.77 GB RSS | 6 threads, 128 tokens, USB-powered, 95% battery, run over adb (`scripts/two-phone-proof.sh`) |
| 26 Sep 13:45 | laptop host + iQOO 15 #1 helper (in-app rpc-server) | Qwen3-0.6B Q8_0 | 15 of 28 on the phone | venue Wi-Fi, ping 266–483 ms (avg 373) | 2048 | — | — | 9 MB | Helper accepted the connection, but the model had not finished loading after 60 s. Venue Wi-Fi is unusable for splitting; use our own hotspot. |
| 26 Sep 14:09 | POCO F5 alone, in the app as Host (SD 7+ Gen 2, 8 GB) | Qwen3-0.6B Q8_0 | all 28 on the phone | none | 4096, KV q8_0 | **23.1** | — | — | App end to end: Models → Run → Chat. First word 0.3 s, 12 tokens. Phone had 1.9 GB free (apps open). |
| 26 Sep 14:11 | POCO F5 Host + laptop joined with `mesh join` | — | — | USB tethering (rndis0 10.250.110.200 ↔ enx 10.250.110.231) | — | — | — | — | Pairing over the private link works: Host shows 2 devices, 8.9 GB pooled. |
| 26 Sep 14:49 | emulator Host (x86_64, 4 cores) + laptop helper (`mesh join`) | Qwen3-0.6B Q8_0 | Host 0–8, laptop 9–27 (Host capped at 0.7 GB to force a split) | emulator NAT (10.0.2.2) | 4096, KV q8_0 | 11.9 | — | laptop helper 475 MB RSS | App end to end over a real split: plan → helper ready → reach → llama-server --rpc → correct answer ("Paris"). Killing the helper mid-run: Host shows "A helper left", helper's engine exits with it. |
| 26 Sep 14:58 | emulator Host alone | Qwen2.5-VL-3B Q4_K_M + mmproj f16 | all 36 on the device | none | 4096 | — | — | — | Camera → photo → "What is in this photo?" → correct description (pixel-art house). Planner counted the 1.3 GB projector on the Host (Tight). Slow on the emulator's CPU (~1–2 min per photo); to measure on the iQOO. |
| 26 Sep 15:04 | iQOO 15 #1 alone, in the app as Host | Qwen3-8B Q4_K_M | all 36 on the phone | laptop → phone over venue Wi-Fi (HTTP only) | 4096, KV q8_0 | **11.8** | — | — | Models page: Tight, "Runs on this phone alone"; ready ~8 s after Run. Asked from the laptop with `mesh ask --host 10.2.37.21`: correct code answer, 98 tokens. |
