# Results so far: Qwen3-4B on one iQOO 15

Generated from the results database (`tuner/summary.py`). Every number below is a run that happened on the
iQOO 15 (I2501, Snapdragon 8 Elite Gen 5, SM8850, 16 GB). Speeds are tokens per second, mean ± spread over
the repeats. Task times use `tokens in ÷ prompt speed + tokens out ÷ writing speed` with chat = 100 in / 150
out and coding-agent step = 2,000 in / 300 out.

**Baseline** = MeshAI's phone settings today: CPU, 6 threads, q8_0 cache, batch 512, flash attn on, model Qwen3-4B-Instruct-2507-Q4_K_M.gguf. Prompt reading 65.8 tok/s, word writing 20.0 tok/s; chat 9.0 s, agent step 45.4 s (runs #1–#2).

## Every speed run

| Runs | Phase | Model | Settings | Prompt tok/s | Writing tok/s | Chat s | Agent s | Battery °C | Phone |
|---|---|---|---|---|---|---|---|---|---|
| #1–#2 | baseline | Q4_K_M | CPU, 6 threads, q8_0 cache, batch 512, flash attn on | 65.8 ± 2.8 | 20.0 ± 0.1 | 9.0 | 45.4 | 34.2→36.0 | in use |
| #3–#4 | screen | Q4_K_M | CPU, 6 threads, q8_0 cache, batch 512, flash attn on | 61.3 ± 3.6 | 19.9 ± 0.2 | 9.2 (+2%) | 47.7 (+5%) | 34.9→36.7 | in use |
| #5–#6 | screen | Q4_K_M | GPU (all layers), 6 threads, q8_0 cache, batch 512, flash attn on | 190.3 ± 0.3 | 12.3 ± 0.2 | 12.7 (+41%) | 34.8 (-23%) | 34.9→35.2 | idle |
| #7–#8 | screen | Q4_K_M | GPU (all layers), 6 threads, f16 cache, batch 512, flash attn on | 195.2 ± 0.2 | 15.7 ± 0.0 | 10.1 (+12%) | 29.4 (-35%) | 34.9→35.4 | idle |
| #9–#10 | search | Q4_K_M | CPU, 6 threads, q8_0 cache, batch 512, flash attn on | 68.3 ± 0.5 | 20.1 ± 0.1 | 8.9 (-1%) | 44.2 (-3%) | 34.9→34.9 | in use |
| #11–#12 | search | Q4_K_M | GPU (36 layers), 6 threads, f16 cache, batch 512, flash attn on | 186.3 ± 0.6 | 14.3 ± 0.1 | 11.0 (+22%) | 31.7 (-30%) | 34.9→34.9 | in use |
| #13–#14 | search | Q4_0 | GPU (36 layers), 4 threads, q8_0 cache, batch 1024, flash attn on | 182.0 ± 0.5 | 12.3 ± 0.0 | 12.8 (+42%) | 35.5 (-22%) | 34.8→35.1 | in use |
| #15–#16 | search | Q4_0 | GPU (36 layers), 8 threads, f16 cache, batch 512, flash attn off | 190.8 ± 0.2 | 13.6 ± 0.5 | 11.5 (+28%) | 32.5 (-28%) | 35.0→35.0 | in use |
| #17–#18 | search | Q4_K_M | GPU (18 layers), 8 threads, q8_0 cache, batch 128, flash attn on | 118.9 ± 8.7 | 12.1 ± 0.1 | 13.2 (+47%) | 41.5 (-9%) | 35.0→35.9 | in use |
| #19–#20 | search | Q4_K_M | GPU (18 layers), 7 threads, q8_0 cache, batch 512, flash attn on | 93.5 ± 7.5 | 11.2 ± 0.5 | 14.4 (+60%) | 48.1 (+6%) | 35.0→35.7 | in use |
| #21–#22 | search | Q4_0 | GPU (36 layers), 5 threads, q8_0 cache, batch 512, flash attn on | 185.9 ± 0.6 | 11.8 ± 0.2 | 13.3 (+48%) | 36.3 (-20%) | 35.0→35.0 | in use |
| #24–#25 | search | Q4_K_M | GPU (36 layers), 8 threads, f16 cache, batch 256, flash attn on | 186.2 ± 0.1 | 12.3 ± 0.1 | 12.8 (+42%) | 35.2 (-22%) | 34.9→35.3 | in use |
| #26–#27 | search | Q4_0 | GPU (36 layers), 7 threads, f16 cache, batch 128, flash attn on | 184.9 ± 1.0 | 13.7 ± 0.9 | 11.5 (+28%) | 32.8 (-28%) | 35.0→35.0 | in use |
| #28–#29 | search | Q4_K_M | GPU (9 layers), 4 threads, f16 cache, batch 256, flash attn on | 68.3 ± 0.1 | 13.2 ± 0.4 | 12.8 (+42%) | 52.0 (+15%) | 34.9→35.9 | idle |
| #30–#31 | search | Q4_K_M | CPU, 6 threads, q8_0 cache, batch 512, flash attn on | 58.8 ± 0.4 | 20.4 ± 0.3 | 9.1 (+1%) | 48.8 (+7%) | 36.3→37.4 | in use |
| #32–#33 | search | Q4_K_M | GPU (9 layers), 5 threads, f16 cache, batch 512, flash attn on | 66.7 ± 0.0 | 13.6 ± 0.3 | 12.5 (+39%) | 52.0 (+15%) | 37.4→38.2 | in use |
| #34–#35 | search | Q4_0 | CPU, 7 threads, q8_0 cache, batch 1024, flash attn on | 70.9 ± 0.2 | 19.8 ± 1.7 | 9.0 (+0%) | 43.3 (-5%) | 38.2→39.6 | in use |
| #36–#37 | search | Q4_0 | CPU, 6 threads, q8_0 cache, batch 256, flash attn on | 28.0 ± 0.3 | 17.9 ± 0.0 | 12.0 (+33%) | 88.2 (+94%) | 39.6→40.0 | in use |
| #38–#39 | search | Q4_0 | CPU, 7 threads, q8_0 cache, batch 1024, flash attn on | 30.6 ± 0.1 | 16.6 ± 1.5 | 12.3 (+37%) | 83.4 (+84%) | 40.0→40.5 | in use |
| #40–#41 | search | Q4_0 | CPU, 5 threads, f16 cache, batch 1024, flash attn on | 65.5 ± 0.2 | 18.2 ± 0.2 | 9.8 (+9%) | 47.0 (+4%) | 38.8→39.2 | idle |
| #42–#43 | search | Q4_K_M | GPU (27 layers), 6 threads, f16 cache, batch 512, flash attn off | 88.3 ± 0.0 | 10.3 ± 0.1 | 15.7 (+74%) | 51.8 (+14%) | 39.4→39.3 | in use |
| #45–#46 | confirm | Q4_K_M | CPU, 6 threads, q8_0 cache, batch 512, flash attn on | 44.7 ± 8.5 | 16.1 ± 0.8 | 11.5 (+28%) | 63.4 (+40%) | 39.0→38.8 | in use |
| #47–#48 | calibration | Q4_K_M | CPU, 6 threads, q8_0 cache, batch 512, flash attn on | 72.4 ± 1.5 | 21.2 ± 0.3 | 8.5 (-6%) | 41.8 (-8%) | 29.4→31.2 | idle |
| #50–#51 | confirm | Q4_K_M | GPU (36 layers), 6 threads, f16 cache, batch 512, flash attn on | 159.1 ± 0.3 | 13.1 ± 0.9 | 12.1 (+34%) | 35.6 (-22%) | 39.3→39.7 | in use |

## Long prompt: reading 2,048 tokens (an agent-sized prompt)

| Run | Label | Settings | Prompt tok/s | Seconds to read | vs baseline |
|---|---|---|---|---|---|
| #49 | 4B baseline (trial 0) · 2k prompt | CPU, 6 threads, q8_0 cache, batch 512, flash attn on | 24.9 ± 3.5 | 82.3 | – |
| #52 | 4B best-agent (trial 1) · 2k prompt | GPU (36 layers), 6 threads, f16 cache, batch 512, flash attn on | 108.0 ± 0.3 | 19.0 | -77% time |

## Quality gate: 15 coding problems, answers run against tests

| Run | Label | Settings | Passed | Median writing tok/s | Total s | Failed problems |
|---|---|---|---|---|---|---|
| #68 | 4B baseline (MeshAI today) | CPU, 6 threads, q8_0 cache, batch 512, flash attn on | 14/15 | 18.9 | 124.5 | parse_duration |
| #80 | 4B best-agent trial 1 | GPU (36 layers), 6 threads, f16 cache, batch 512, flash attn on | 14/15 | 10.9 | 195.8 | parse_duration |

## Heat: writing non-stop

- **4B agent sustained on phone ·01GG**: 11.3 → 13.6 tok/s over 372 s; battery 40.0 → 39.5 °C (26 samples, runs #53–#79).
- **4B baseline sustained on phone ·00XP**: 18.8 → 19.2 tok/s over 348 s; battery 39.7 → 39.7 °C (6 samples, runs #88–#122). 1 sample(s) excluded: the benchmark was starved while the phone was in use (#97, 0.22 tok/s).

## Run cards, as shown on the phone

Each card was opened on the iQOO and screenshotted; the phone's own clock and battery are in the status bar.

<img src="screenshots/card-1-2.jpg" width="260" alt="Qwen3-4B-Q4_K_M baseline">
<img src="screenshots/card-7-8.jpg" width="260" alt="Qwen3-4B-Q4_K_M engine:gpu-f16cache">
<img src="screenshots/card-9-10.jpg" width="260" alt="4B Q4_K_M trial 0">
<img src="screenshots/card-11-12.jpg" width="260" alt="4B Q4_K_M trial 1">
<img src="screenshots/card-13-14.jpg" width="260" alt="4B Q4_0 trial 2">
<img src="screenshots/card-15-16.jpg" width="260" alt="4B Q4_0 trial 3">
<img src="screenshots/card-17-18.jpg" width="260" alt="4B Q4_K_M trial 4">
<img src="screenshots/card-19-20.jpg" width="260" alt="4B Q4_K_M trial 5">
<img src="screenshots/card-21-22.jpg" width="260" alt="4B Q4_0 trial 6">
<img src="screenshots/card-24-25.jpg" width="260" alt="4B Q4_K_M trial 8">
<img src="screenshots/card-26-27.jpg" width="260" alt="4B Q4_0 trial 9">
<img src="screenshots/card-28-29.jpg" width="260" alt="4B Q4_K_M trial 10">
<img src="screenshots/card-30-31.jpg" width="260" alt="4B Q4_K_M trial 26">
<img src="screenshots/card-32-33.jpg" width="260" alt="4B Q4_K_M trial 27">
<img src="screenshots/card-34-35.jpg" width="260" alt="4B Q4_0 trial 28">
<img src="screenshots/card-36-37.jpg" width="260" alt="4B Q4_0 trial 29">
<img src="screenshots/card-38-39.jpg" width="260" alt="4B Q4_0 trial 30">
<img src="screenshots/card-40-41.jpg" width="260" alt="4B Q4_0 trial 31">
<img src="screenshots/card-42-43.jpg" width="260" alt="4B Q4_K_M trial 32">
<img src="screenshots/card-45-46.jpg" width="260" alt="4B baseline (trial 0)">
<img src="screenshots/card-47-48.jpg" width="260" alt="4B baseline on phone ·00XP">
<img src="screenshots/card-49.jpg" width="260" alt="4B baseline (trial 0) · 2k prompt">
<img src="screenshots/card-50-51.jpg" width="260" alt="4B best-agent (trial 1)">
<img src="screenshots/card-52.jpg" width="260" alt="4B best-agent (trial 1) · 2k prompt">
<img src="screenshots/card-68.jpg" width="260" alt="4B baseline (MeshAI today)">
<img src="screenshots/card-80.jpg" width="260" alt="4B best-agent trial 1">
<img src="screenshots/card-81-82-83-84-85-86-87.jpg" width="260" alt="4B agent winner + reuse">
<img src="screenshots/card-90-91-92-93-94-95-96.jpg" width="260" alt="4B baseline + reuse">
<img src="screenshots/card-98-99-100-101-102-103-104.jpg" width="260" alt="4B agent winner + reuse (clean)">
<img src="screenshots/card-105-106-107-108-109-110-111.jpg" width="260" alt="4B baseline + reuse (clean)">
<img src="screenshots/card-112-113-114-115-116-117-118.jpg" width="260" alt="4B baseline + reuse, f16 cache, fa off">

## Repeated task: how much prompt work the second request skips

The same ~1,900-token context (instructions + a 6 KB source file) sent again with a new task at the end.
`tokens processed` is what llama-server actually computed for that request. The `no cache` row was sent first
with caching off for that request, so it shows the cost of processing everything; the context it left in the
slot is what the following cached requests reuse.

**4B agent winner + reuse** · GPU (all layers), 6 threads, f16 cache, batch 512, flash attn on · `--cache-reuse 256`

| Request | Tokens processed | Prompt time | Reply time | vs no cache |
|---|---|---|---|---|
| no cache: every token | 1778 | 13.1 s | 15.3 s | – |
| repeat, same prompt | 1 | 0.1 s | 2.14 s | −99% |
| repeat, same prompt | 1 | 0.1 s | 2.17 s | −99% |
| same context, new task | 12 | 0.6 s | 2.41 s | −95% |
| same context, new task | 21 | 0.7 s | 3.71 s | −95% |
| file edited mid-way, new task | 1435 | 11.2 s | 13.17 s | −15% |
| same context, new task | 16 | 0.7 s | 3.39 s | −95% |

**4B baseline + reuse** · CPU, 6 threads, q8_0 cache, batch 512, flash attn on · `--cache-reuse 256`

| Request | Tokens processed | Prompt time | Reply time | vs no cache |
|---|---|---|---|---|
| no cache: every token | 1778 | 61.5 s | 63.34 s | – |
| repeat, same prompt | 1 | 0.2 s | 1.98 s | −100% |
| repeat, same prompt | 1 | 0.2 s | 1.87 s | −100% |
| same context, new task | 12 | 0.7 s | 2.38 s | −99% |
| same context, new task | 21 | 1.1 s | 2.8 s | −98% |
| file edited mid-way, new task | 1435 | 53.6 s | 55.37 s | −13% |
| same context, new task | 16 | 0.9 s | 2.62 s | −99% |

**4B agent winner + reuse (clean)** · GPU (all layers), 6 threads, f16 cache, batch 512, flash attn on · `--cache-reuse 256`

| Request | Tokens processed | Prompt time | Reply time | vs no cache |
|---|---|---|---|---|
| repeat, same prompt | 1778 | 11.3 s | 13.25 s | −0% |
| no cache: every token | 1778 | 11.3 s | 13.26 s | – |
| repeat, same prompt | 1 | 0.1 s | 2.11 s | −99% |
| same context, new task | 12 | 0.6 s | 2.18 s | −95% |
| same context, new task | 21 | 0.6 s | 3.59 s | −95% |
| file edited mid-way, new task | 1435 | 9.6 s | 11.36 s | −15% |
| same context, new task | 16 | 0.5 s | 3.19 s | −95% |

**4B baseline + reuse (clean)** · CPU, 6 threads, q8_0 cache, batch 512, flash attn on · `--cache-reuse 256`

| Request | Tokens processed | Prompt time | Reply time | vs no cache |
|---|---|---|---|---|
| repeat, same prompt | 1778 | 54.8 s | 56.32 s | −-12% |
| no cache: every token | 1778 | 48.7 s | 50.42 s | – |
| repeat, same prompt | 1 | 0.1 s | 1.79 s | −100% |
| same context, new task | 12 | 0.6 s | 2.2 s | −99% |
| same context, new task | 21 | 1.1 s | 2.78 s | −98% |
| file edited mid-way, new task | 1435 | 48.4 s | 50.05 s | −1% |
| same context, new task | 16 | 0.9 s | 2.66 s | −98% |

**4B baseline + reuse, f16 cache, fa off** · CPU, 6 threads, f16 cache, batch 512, flash attn off · `--cache-reuse 256`

| Request | Tokens processed | Prompt time | Reply time | vs no cache |
|---|---|---|---|---|
| repeat, same prompt | 1778 | 53.7 s | 55.52 s | −0% |
| no cache: every token | 1778 | 54.0 s | 55.64 s | – |
| repeat, same prompt | 1 | 0.1 s | 1.72 s | −100% |
| same context, new task | 12 | 0.5 s | 2.1 s | −99% |
| same context, new task | 21 | 0.9 s | 2.53 s | −98% |
| file edited mid-way, new task | 1435 | 48.2 s | 49.94 s | −11% |
| same context, new task | 16 | 0.6 s | 2.3 s | −99% |

## Runs that failed

- #23 4B Q4_K_M trial 7 (GPU (27 layers), 5 threads, q8_0 cache, batch 1024, flash attn off): `eno(TM) 840 (OpenCL 3.0 Adreno(TM) 840)'
llama_bench: error: failed to create context with model '/data/local/tmp/mc/models/Qwen3-4B-Instruct-2507-Q4_K_M.gguf'`
- #44 4B Q4_0 trial 33 (CPU, 8 threads, q8_0 cache, batch 128, flash attn off): `llama_bench: error: failed to create context with model '/data/local/tmp/mc/models/Qwen3-4B-Instruct-2507-Q4_0.gguf'`

