# Research brief: the task compiler (10 Oct 2026)

**The idea.** Given a task that is run repeatedly (prompt template + examples + its own tests), produce
the cheapest package that still passes the tests on a given device: the smallest model, its unused
experts removed, the fixed context pre-processed or baked in, the per-device settings, and a prompt
layout that keeps the cache hitting. The mesh is the runtime. Nothing in the package is new on its own;
nobody builds or verifies the combination on consumer devices.

Labels: **source** = read in llama.cpp 66fba63; **measured** = ours; everything else is as reported by
the cited work, mostly preprints read from abstracts.

## 1. Each layer of the package: what exists, what it costs, what we take

| Layer | Existing work | Reported result | Fit for phones | Our take |
|---|---|---|---|---|
| **Device settings per task** | llama-optimus, Pooled autotune, our tuner | our 4B: agent step −28 to −43%, chat unchanged (measured) | done | layer 1, built |
| **Prefix reuse** | llama.cpp `--cache-prompt`, `--cache-reuse`, `--slot-save-path` (source); [Agent-X](https://arxiv.org/html/2605.10380v1) prompt rewriting for cache hits (1.61× end to end) | our 4B: repeat request −95 to −98% prompt work (measured) | done; edit-in-the-middle does not engage in this build (measured) | layer 2, built; fix the edit case via Agent-X's layout rule (fixed part first, byte-identical) |
| **Smallest model that passes** | [FrugalGPT](https://arxiv.org/pdf/2305.05176) (cascade, −80% cost), [Cluster-Route-Escalate](https://arxiv.org/html/2606.27457) (97–99% of best model's accuracy with only correctness labels), [UCCI](https://arxiv.org/html/2605.18796) (calibrated escalation, −31% cost) | savings depend on the task; ranking is task-specific | yes: our quality gate *is* the correctness label | layer 4: run 0.6B / 1.7B / 4B / 8B against the task's tests, keep the cheapest that passes; escalate to the mesh's big model only when the small one fails a test |
| **Drop unused experts** | [REAP](https://github.com/CerebrasResearch/reap) (Cerebras, open source): Qwen3-Coder-30B → [25B](https://huggingface.co/cerebras/Qwen3-Coder-REAP-25B-A3B), near-lossless on code; [LEAP](https://openreview.net/forum?id=HDu9u0gYxh) task-specific (2.5× faster, −40% memory, unreviewed); [OMP-MoE](https://arxiv.org/html/2609.31631), [AIMER](https://arxiv.org/html/2603.18492) | 20–50% of experts removed with small loss | yes, ready-made GGUFs for the 30B | layer 6: for the 30B, test the REAP-25B against the coding tests; memory per phone drops ~20% |
| **Bake the context into weights** | [Prompt Baking](https://www.alphaxiv.org/abs/2409.13697v1) (LoRA, ~5 min on a GPU); [Compiling Agentic Workflows into LLM Weights](https://arxiv.org/abs/2605.22502) (128–462× cheaper per conversation, 87–98% of frontier quality); [Context Memorization](https://arxiv.org/pdf/2605.18226) | removes the prefix cost even on cold requests, where caching gives nothing | **training on a phone is slow and battery-heavy**: [3B LoRA on an iPhone 17 Pro](https://arxiv.org/html/2610.06325): ~0.01 s and 0.04 J per training token; a 405-record history costs about half a charge; [MobileFineTuner](https://arxiv.org/html/2512.08211v2): LoRA rank 8 ran 4 h to step 53 on a mid-range phone | layer 7, later: bake on the laptop overnight, ship the LoRA to the phone; llama.cpp hot-swaps adapters (`--lora`, `--lora-init-without-apply`, `llama_set_adapters_lora`, source) so one base model serves many tasks |
| **Task-trained drafts** | [Draft-OPD](https://arxiv.org/html/2605.29343v2) (on-policy draft training, >5× claimed), [domain drafts](https://arxiv.org/pdf/2503.07807) (generic drafts lose acceptance on domain tasks), [OmniDraft](https://openreview.net/pdf?id=RALtozQipi) (adapts online across task switches) | acceptance rises when the draft matches the task | needs training; n-gram variants need none (source: `--spec-type ngram-*`) but maynards' research measured only 1.2–1.3 tokens per step | layer 3: measure n-gram first; a task-trained 0.6B draft later |
| **Compile model + runtime** | [Ditto](https://arxiv.org/abs/2603.29813): K-Means codebook quantization + an LLVM pass that swaps matrix-vector ops for tuned BLAS; up to 10.5× faster, 6.4× less memory, 0.27% pass@1 loss (laptops) | large, but laptop CPUs only | not on ARM/Adreno yet | the long-term shape of the package: one executable per device |
| **Compile the workflow** | [FlowCompile](https://arxiv.org/abs/2605.13647): profile each sub-agent under many configurations, estimate whole-workflow accuracy/latency, pick a set of configurations in one pass; up to 6.4× faster | no hardware targets | the profiling half is our tuner | the chooser for multi-step tasks |
| **Context compression** | [ACON](https://arxiv.org/html/2510.00615v2) (task-specific compression guidelines, distillable into small models), LLMLingua-2 | fewer tokens per step | yes | optional layer: shrink what cannot be cached |

**Toolkits that exist for the compression steps:** [Olive](https://microsoft.github.io/Olive/why-olive.html) (auto-tunes passes against accuracy/latency targets, MIT), [LLM Compressor](https://docs.vllm.ai/projects/llm-compressor/en/0.7.0/), Intel Neural Compressor (quantize + prune + distill), [LightCompress](https://github.com/ModelTC/LightCompress). None targets one task on a phone; Olive's "passes tuned against a target metric" is the closest structure to copy.

## 2. What is genuinely open

1. **Nobody combines these per task on the device that will run it.** Distillation and compression services run in the cloud; on-device SDKs (RunAnywhere, Cactus, Nexa) run models but do not specialise them.
2. **Cold requests are the gap in caching.** Caching gives ~0 on the first request of a new context; baking removes the cost but needs training. The decision between the two is a measurable per-task trade-off, and no published work measures it on phones.
3. **Draft + adapter interaction.** When the target runs a task LoRA, a draft trained on the base model loses acceptance; no source documents this on llama.cpp. Measurable.
4. **Edit-in-the-middle reuse** did not engage in llama.cpp 66fba63 (measured). Either a newer build, a different chunk size, or Agent-X-style prompt layout fixes it; unknown until tested.

## 3. Feasibility on our hardware

| Step | On the iQOO | On the laptop |
|---|---|---|
| Pick the smallest passing model | yes, ~15 min per model with our quality gate | — |
| Prefix reuse | yes (measured) | yes |
| REAP-pruned 30B | run only; GGUF download ~15 GB via the POCO link | pruning itself needs a GPU; use the published files |
| Bake a LoRA | possible but ~0.04 J/token and hours (reported, iPhone); not for the event | minutes on a GPU, ~1 h on this i5 CPU (estimate) |
| LoRA hot-swap at serve time | yes (source) | yes |
| Task-trained draft | run yes; training on the laptop | same |

## 4. Recommended build order

1. **Layer 4 now, with the tools we have:** run the 15-task gate for 0.6B, 1.7B, 4B, 8B on the iQOO; publish "cheapest model that passes this task" with memory and speed. One afternoon.
2. **Layer 6 on the 30B step:** REAP-25B vs the full 30B on the coding tests and the two-phone split. Download + one evening.
3. **Fix edit-in-the-middle** by prompt layout (fixed part first, edits at the end) and re-measure. One hour.
4. **Package format + `build` / `run` commands:** a JSON manifest (model, format, pruned experts, settings per task, saved slot path, prompt layout, test command, evidence run ids). Two days.
5. **Later:** baked LoRA on the laptop + hot-swap on the phone; task-trained draft; Ditto-style compiled executable.

## 5. What to say about novelty

Safe: *"A task compiler for the devices you own: it measures, chooses and verifies the cheapest package for a task you repeat, per device, with the task's own tests as the judge."* Every mechanism cites prior work (above); the measured combination on phones does not exist in the literature or in the products found.
