# Findings: what we learned before and while building

Labels: **measured** = run by us on our devices; **source** = read in llama.cpp's code or docs at the
commit we pin (66fba63, 23 Sep 2026); **reported** = someone else's numbers; **estimate** = our reasoning,
not yet measured.

## 1. The idea in one paragraph

MeshAI pools phones and a laptop so a model too big for one device can run. Every phone runs the same
engine with the same settings. The Mesh Compiler measures each device for each kind of task and picks
the settings that make it fastest without lowering quality, then hands them to MeshAI as a per-device
manifest. The one combination not found in published work (maynards `docs/UNDERSTANDING.md` §7) is a
cross-device plan whose output is a tuned setup per device, rebuilt when conditions change.

## 2. Where a phone GPU helps, and where it cannot (source + measured)

- The phone's CPU and Adreno GPU share one memory (LPDDR5X). The GPU is much faster at maths but
  cannot read memory faster than the CPU.
- **Prompt reading** (many tokens at once, matrix × matrix) is limited by maths, so the GPU should help a
  lot. **Word writing** (one token, matrix × vector) is limited by memory speed, plus a CPU↔GPU hand-off
  every step, so the GPU can be the same or slower.
- Measured on our iQOO with Qwen3-4B: GPU prompt reading **3.2× faster**, writing **21% slower**
  (see `03-results.md`). Exactly the predicted shape.
- **Consequence:** the best setting depends on the task. A coding-agent step is ~70% prompt reading; a chat
  answer is ~80% writing. So the tuner scores **task time**, not raw tokens per second.

## 3. Facts from llama.cpp's own code (source)

| Fact | Where | Why it matters |
|---|---|---|
| The OpenCL backend's `offload_op` hook is `NULL` | `ggml-opencl.cpp:12885` | the GPU cannot take *only* prompt reading automatically; the real knob is **how many layers live on the GPU** (`-ngl`), and those layers use it for both reading and writing |
| GPU device name is `GPUOpenCL` | `ggml-opencl.cpp:12772` | used in `-dev GPUOpenCL` and `ggml-rpc-server -d GPUOpenCL` |
| CPU "repack" fast paths on ARM with i8mm: **Q4_0 (4x8), Q4_K (8x8), Q5_K, Q6_K, IQ4_NL** | `ggml-cpu/repack.cpp:4925` | Q4_0 is **not** automatically faster than Q4_K_M on the CPU; it has to be measured (corrects an earlier assumption) |
| Adreno-specific kernels exist for Q4_0, Q4_K, Q5_K, Q6_K, Q8_0, IQ4_NL **and Mixture-of-Experts layers** (`gemm_moe_q4_k_*`) | `ggml-opencl/kernels/` | the 30B (MoE) can use the GPU too |
| OpenCL docs: "Flash attention does not always improve performance" | `docs/backend/OPENCL.md` | flash attention is a knob, not a default |
| `ggml-rpc-server` defaults to 4 threads | `--help` | MeshAI already passes cores − 2 = 6 (checked in `MeshClient.kt:144`) |
| Speculative decoding in llama-server: `-md`, `--spec-draft-n-max`, `--spec-type ngram-*` (copy from prompt, no draft model) | `--help` | two ways to get several words per pass |
| Our iQOO's engine sees `GPUOpenCL: QUALCOMM Adreno(TM) 840 (7609 MiB, 6585 MiB free)` | measured, `--list-devices` | the real GPU, not a software fallback |

## 4. Existing tools and research we build on

| Work | What it does | What we take |
|---|---|---|
| [llama-optimus](https://github.com/BrunoArsioli/llama-optimus) (MIT) | Optuna (Bayesian) search over llama-bench flags; prints best commands | **the search method** (Optuna TPE driving llama-bench) |
| [llama-bench-tuner](https://github.com/kennel-org/llama-bench-tuner) | grid/Optuna sweeps, results in SQLite, reports | store every trial; report at the end |
| llama.cpp `--fit` / `llama-fit-params` ([discussion #18049](https://github.com/ggml-org/llama.cpp/discussions/18049)) | picks GPU layers so the model fits memory | memory fit is solved upstream; we tune for speed |
| [Pooled](https://github.com/Nehanth/pooled) `engine/autotune.js` | times 5 GPU kernel shapes for ~1 s at load; keeps the default unless another wins by > 3% | **the noise guard** (> 3% and > run-to-run spread) |
| [Phone study, arXiv 2410.03613](https://arxiv.org/html/2410.03613v2) | threads = number of big cores is usually best on phones | thread range 4–8 |
| [Mobile NPU paper, arXiv 2607.05475](https://arxiv.org/pdf/2607.05475) | core pinning on GPU decode: +10.8% speed, −36% energy (reported) | a knob to add later (`-C` core mask) |
| [llama.cpp discussion #23736](https://github.com/ggml-org/llama.cpp/discussions/23736) | Adreno 830: 1.5B 24× faster prompt reading; 7B Q4_K_M 3.7× faster prompt, ~17% slower writing; 6 threads best, 8 threads −55% writing (reported) | expectations, and why we test threads |
| [Qualcomm IWOCL 2025 slides](https://www.iwocl.org/wp-content/uploads/iwocl-2025-hongqiang-wang-lamacpp-backend-update.pdf) | prompt reading is compute-bound, writing memory-bound | same model as §2 |
| [adreno-llm field report](https://github.com/Sophia-Thickums/adreno-llm) | a software OpenCL driver can silently replace the GPU | we check the device name before trusting GPU numbers |

**What none of them do (our part):** drive a phone over the cable; score by task time (chat vs agent) in one
multi-objective search; cool down before every trial; gate on quality; screenshot every result on the phone.

## 4b. Repeated tasks: existing work we build on (searched 10 Oct 2026)

| Work | What it shows | Use |
|---|---|---|
| llama.cpp prompt cache: `--cache-prompt` (default on), `--cache-reuse N` (keeps processed chunks after an edit, by KV shifting), `--slot-save-path`, `--cache-ram` (source: our build's `--help`) | the repeated part of a prompt is processed once | layer 2, directly |
| [Agent-X](https://arxiv.org/html/2605.10380v1) (May 2026, on-device agents) | prompt rewriting for prefix reuse + LLM-free speculation: 1.97× prefill, 1.73× decode, **1.61× end to end, identical accuracy** (reported; a reviewer flags uncharacterised workloads) | the same two mechanisms exist in our engine; copy the prompt layout rule: keep the fixed part first, byte-identical |
| [Learning Agent Execution for KV-cache](https://arxiv.org/html/2608.14624), [Continuum](https://arxiv.org/html/2511.02230v5), [CacheWise](https://arxiv.org/pdf/2606.16824) (2025–26) | agents resend a fixed context every call and ordinary caches evict it too early | pin the task context (`--cache-ram`, slot save) |
| [REAP](https://github.com/CerebrasResearch/reap) (Cerebras, open source) | prune experts a task never uses; Qwen3-Coder-30B → [25B](https://huggingface.co/cerebras/Qwen3-Coder-REAP-25B-A3B) at 20% pruning, "near-lossless" on code; [GGUF builds exist](https://huggingface.co/mradermacher/Qwen3-Coder-REAP-25B-A3B-GGUF) | layer 6 for the 30B: ~18.6 → ~15 GB with no retraining. [LEAP](https://openreview.net/forum?id=HDu9u0gYxh) (task-specific, up to 2.5× faster, −40% memory, unreviewed), [OMP-MoE](https://arxiv.org/html/2609.31631), [AIMER](https://arxiv.org/html/2603.18492) are alternatives |
| n-gram speculation (`--spec-type ngram-*`, in our build) | guesses from text already seen, no draft model | layer 3; maynards' pre-event research found only 1.2–1.3 tokens per step in a setup like ours, so measure before promising |

**Gap:** none of these combine prefix reuse, pruning and per-device settings for one repeated task on phones.
That combination is the project.

## 5. Pooled, checked on 9–10 Oct 2026

- Browser-based split of one model across devices (WebGPU + WebRTC), MIT, 580 stars, updated 8 Oct.
- Each device downloads only its own layers, from Hugging Face or a peer in the room.
- Speculative decoding with 70–85% of guesses accepted on code (reported).
- Their own latency table: MoE across 3 devices 24.8 tok/s at 0 ms → 9.9 at 20 ms → 5.2 at 50 ms (reported).
  Splitting across the internet is slow for anyone.
- Plain decoding 1.3–2× slower than llama.cpp, prompt reading 5–14× slower (their numbers).
- Their phones are browser tabs, which Android suspends in the background.

## 6. Security, in short

- Our model data between devices travels unencrypted (llama.cpp's RPC has no encryption). On our own USB
  cable that is fine; on shared Wi-Fi or the internet it is not. **Fixable**: a locked tunnel (WireGuard)
  using the per-device secret from QR pairing.
- A helper device that runs layers always sees the in-between activations, which research shows can be
  turned back into text. **Not fixable** by encryption, for us or Pooled. Mitigation: only your own
  devices; keep embeddings and output head on the host (MeshAI already does).

## 7. What the analyzer itself costs (measured, 10 Oct)

| Item | Cost |
|---|---|
| Reading memory + temperature files on the iQOO over adb | ~27 ms of phone time per read (mostly starting a tiny program) |
| `dumpsys battery` | ~36 ms per read |
| Watching every 2 s | under 0.5% of the phone's CPU |
| Analyzer on the laptop (Python + SQLite) | 17 MB memory; choosing over 10,000 stored runs: 11 ms |
| One screenshot | 0.56 s; 1.9 MB PNG, 0.3 MB JPEG |
| A tuning session | phone fully busy for its duration; run while charging (estimate: 30–60 min per model) |

## 8. Practical findings

- Laptop Wi-Fi (the "iQOO 1" hotspot): Hugging Face at 77 KB/s on one connection, ~0.5–1 MB/s with 8–16
  (measured). The POCO's 5G: 2.38 GB in 70 s in Chrome; through its USB tethering 21.4 MB/s (measured).
- `adb pull` over USB: 42 MB/s (measured).
- vivo/iQOO background survival via adb: battery-optimisation whitelist, `RUN_ANY_IN_BACKGROUND allow`,
  phantom-process monitor off, stay awake while charging (set on 10 Oct).
- A `pkill -f <pattern>` that contains its own pattern kills the calling shell; use `pgrep` with a
  bracketed character class.
