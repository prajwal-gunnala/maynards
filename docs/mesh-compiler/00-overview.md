# Mesh Compiler: specialize the AI to the task it keeps doing

## The idea

LLM engines are tuned for *any* task. MeshAI runs one fixed setting on every phone for everything.
But a real deployment mostly runs **the same kind of task again and again**: a coding agent editing one
repository, a support bot answering the same catalogue, a form reader. When a task repeats, a lot of the
work repeats and a lot of the model is not needed. The Mesh Compiler learns the task and makes each
device run it **faster, on fewer resources, with the task's own tests as the judge.**

It is a separate project that sits on top of MeshAI (`../maynards`). It writes no engine code: every
mechanism it uses already exists in llama.cpp. What nobody does is choose and combine them **per device,
per task, from measurements**, and prove the result.

## The layers, and where each stands

| # | Layer | What it does for a repeated task | Status |
|---|---|---|---|
| 1 | **Device settings per task** | CPU or GPU, cache format, threads, batch, picked by measured task time, verified on quality and heat | **done on the 4B** (`03-results.md`) |
| 2 | **Reuse the fixed part of the prompt** (prefix cache, `--cache-reuse`) | the instructions and files that repeat every call are processed once; later calls only process what is new | **measured on the 4B** (see results) |
| 3 | **Guess from what the task already said** (n-gram speculation, no second model) | repetitive outputs come several words per step | next; prior research says the gain is small, so measure |
| 4 | **Smallest model and format that still passes the task** | test 0.6B / 4B / 8B and formats against the task's tests; keep the cheapest that passes | next |
| 5 | **Right-size memory** (context, answer length) | allocate only what the task uses | next |
| 6 | **Drop the parts of the model the task never uses** (MoE expert pruning, REAP) | the 30B keeps only the experts a coding task routes to: ~18.6 → ~15 GB, "near-lossless" on code per Cerebras | for the 30B step; ready-made pruned files exist |
| 7 | **Speed-aware split across phones** | more layers to the device that runs them fastest for this task | for the two-phone step |

Layer 1 alone gives ~30% on coding-agent steps (measured). Layer 2 is where the big saving is expected,
because prompt reading dominates agent steps and most of the prompt repeats.

## Rules

- **Baseline first**: MeshAI's settings today, on the same phone, in the same session.
- **The task's own tests decide quality**: a faster setting must pass at least as many of the 15 coding
  problems as the baseline, and ideally fail the same ones (then it changed nothing about the answers).
- **Noise guard**: a change counts only above 3% and above the run-to-run spread.
- **Same start temperature** for every comparison; the heat test then runs with no limit until speed
  levels off.
- **Every run is kept**, winners and losers, with the phone's state around it, and a run card is shown on
  the phone and screenshotted.
- **Scope**: three sizes, in order: Qwen3-4B (done), Qwen3-8B, then the 30B across phones.
- **Battery advice** for lenders: keep the phone between 20 and 80% while lending; heat and full-load
  running wear the battery, so the tests are not limited but users are told.

## How the pieces connect

```
MeshAI (maynards)                     Mesh Compiler (this repo)
 planner → engines on phones          measure each phone per task ──► results database
 one setting for everything    ◄──    manifest per device per task (settings + evidence run ids)
                                      re-tuned only when something changes (new phone, engine update)
```

## What was achieved on the 4B (10 Oct 2026, iQOO 15, all measured)

| | MeshAI today (CPU) | Tuned | Gain |
|---|---|---|---|
| Prompt reading, 512 tokens | 65.8 tok/s cool · 44.7 warm | 195 cool · 159 warm (GPU, f16 cache) | **~3×** |
| Reading a 2,048-token agent prompt (warm) | 82 s | 19 s | **−77%** |
| Coding-agent step (2,000 in / 300 out) | 44–45 s cool · 63 s warm | 29–32 s cool · 36 s warm | **−28 to −43%** |
| Chat answer (100 in / 150 out) | **8.9 s** | 10–11 s | CPU stays best for chat |
| Writing speed, ~6 min of back-to-back runs at ~40 °C | 18.8 → 19.2 tok/s (phone 2) | 11.3 → 13.6 tok/s (phone 1) | both hold at 40 °C; writing stays ~30% slower on the GPU, so the GPU win is prompt reading |
| Quality, 15 coding problems | 14/15, fails `parse_duration` | 14/15, fails `parse_duration` | **unchanged** |
| **Repeat request, same context + new task: prompt work** | 48.7–54.8 s (CPU) · 11.3 s (GPU), all 1,778 tokens | **0.5–1.1 s (CPU) · 0.5–0.6 s (GPU)**, 12–21 tokens | **−98% / −95%** |
| **Repeat request, reply time** | 50–56 s (CPU) · 13.3 s (GPU) | **2.1–2.8 s (CPU) · 2.2–3.6 s (GPU)** | **~20× / ~5×** |
| Edit in the middle of the context, then a new task | 1,778 tokens | 1,435 tokens: only the part before the edit is reused | `--cache-reuse` did not engage (checked with q8_0 and f16 caches, flash attention on and off; the engine log shows no reuse). A known limit of this llama.cpp build for insertions; the target for layer 2's next step |

Two phones of the same model agreed within 6% (calibration run), so the quality gate and the baseline heat test ran
on the second unit. The heat runs are back-to-back llama-bench calls (a model reload between samples), not one unbroken generation; a starved sample while the phone was in use is excluded and listed. Reuse was measured twice (first pass and a clean pass with a true cold start); both agree.

Settings file for the iQOO + Qwen3-4B, as the tuner would hand to MeshAI:
`chat → CPU, 6 threads, q8_0 cache` · `agent → GPU all layers, f16 cache, 6 threads, batch 512, cache-prompt on`.

## Read next

- `01-findings.md`: what we learned (engine facts, research, Pooled, security, analyzer cost)
- `02-implementation.md`: what is built, tested or not
- `03-results.md`: every run on the 4B, generated from the database, with run cards
