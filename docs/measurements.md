# Measurements

Every number here was run. Engine: llama.cpp 66fba63, CPU backend, built by `scripts/build-llama.sh`.

| When | Device(s) | Model | Layers | Link | Context | Decode tok/s | Prompt tok/s | Memory held | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 26 Sep 13:25 | iQOO 15 #1 alone (SM8850, 16 GB) | Qwen3-0.6B Q8_0 | all 28 on the phone | none | 4096 | **78.1** | 768.7 | 1.77 GB RSS | 6 threads, 128 tokens, USB-powered, 95% battery, run over adb (`scripts/two-phone-proof.sh`) |
| 26 Sep 13:45 | laptop host + iQOO 15 #1 helper (in-app rpc-server) | Qwen3-0.6B Q8_0 | 15 of 28 on the phone | venue Wi-Fi, ping 266–483 ms (avg 373) | 2048 | — | — | 9 MB | Helper accepted the connection, but the model had not finished loading after 60 s. Venue Wi-Fi is unusable for splitting; use our own hotspot. |
| 26 Sep 14:09 | POCO F5 alone, in the app as Host (SD 7+ Gen 2, 8 GB) | Qwen3-0.6B Q8_0 | all 28 on the phone | none | 4096, KV q8_0 | **23.1** | — | — | App end to end: Models → Run → Chat. First word 0.3 s, 12 tokens. Phone had 1.9 GB free (apps open). |
| 26 Sep 14:11 | POCO F5 Host + laptop joined with `mesh join` | — | — | USB tethering (rndis0 10.250.110.200 ↔ enx 10.250.110.231) | — | — | — | — | Pairing over the private link works: Host shows 2 devices, 8.9 GB pooled. |
