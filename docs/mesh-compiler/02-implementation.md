# What is implemented

Everything below is in this repository (`mesh-compiler`). Nothing in `maynards` was changed.

## The pieces

```
laptop                                                    iQOO 15 (adb over USB)
┌────────────────────────────────────────────┐            ┌──────────────────────────────────┐
│ tuner/optimize.py   Optuna search           │──push────▶│ /data/local/tmp/mc/cpu    (CPU)   │
│ tuner/speed.py      llama-bench runs        │──run─────▶│ /data/local/tmp/mc/opencl (GPU)   │
│ tuner/quality.py    15 coding problems      │           │ /data/local/tmp/mc/models         │
│ tuner/phone.py      adb, state, cool-down   │◀─numbers──│ llama-bench / llama-server        │
│ tuner/store.py      runs.sqlite             │           │                                  │
│ tuner/card.py       run card web page ──────┼─reverse──▶│ Chrome tab "meshcompiler"         │
│ tuner/report.py     reports/index.html      │◀─png──────│ screencap                         │
└────────────────────────────────────────────┘            └──────────────────────────────────┘
```

| File | What it does | Tested |
|---|---|---|
| `scripts/build-opencl.sh` | Builds llama.cpp 66fba63 for Android arm64 with the Adreno GPU backend (`GGML_OPENCL`, Adreno kernels, kernels embedded). OpenCL headers and the ICD loader are built in `/mnt/storage/meshai/build/opencl`; the shared NDK is not modified. Same CPU flags as MeshAI (`armv8.2-a+dotprod+i8mm`). Output stripped to 22 MB. | yes: the iQOO lists `GPUOpenCL: QUALCOMM Adreno(TM) 840` |
| `tuner/phone.py` | `Phone(serial)`: run shell commands, push files, read identity (model, SoC, RAM) and state (battery %, battery °C, charging, thermal status, app in front, screen on, free memory), cool down to a temperature, take a screenshot. | yes |
| `tuner/speed.py` | `BASELINE` = MeshAI's phone settings today (CPU, 6 threads, flash attention on, q8_0 cache, ub 512, b 2048). `measure()` pushes the engine and model if needed, cools down, runs llama-bench with one configuration, records one row per test (prompt reading, word writing) with the state before and after. `sustained()` writes words non-stop for N minutes to see heat slow it down. | `measure`: yes. `sustained`: yes: GPU winner levelled at 13.6 tok/s after 372 s at 39.5 °C |
| `tuner/optimize.py` | Optuna TPE, multi-objective. Search space: format (Q4_K_M, Q4_0), engine (cpu, opencl), GPU layers (9–36), cache (q8_0, f16), threads (4–8), ubatch (128–1024), flash attention. Each trial is scored as two task times. The baseline and the GPU + f16 configuration are tried first. The study is saved, so a restart resumes. | yes: 16 complete trials on the iQOO (`optuna.sqlite`), resumed twice after cable drops |
| `tuner/reuse.py` | Repeated-task test: the same ~1,900-token context (instructions + `quality/bench.py`) sent 7 times with different short tasks, once with the file edited in the middle; records how many tokens llama-server actually processed per request (`prompt_n`, `prompt_ms`) and the wall time, for a given setting and `--cache-reuse`. | see `03-results.md` |
| `tuner/optimize.py` `part()` | The finish step in parts (`confirm`, `calibrate`, `gate`, `heat-baseline`, `heat-agent`) so two phones of the same model can share it. A second iQOO ran the quality gate and the baseline heat test; a calibration run showed the two units within 6% (72.4 vs 68.3 tok/s prompt, 21.2 vs 20.1 writing, both cool). | yes |
| `tuner/tasks.py` | Task sizes: chat = 100 tokens in, 150 out; agent = 2,000 in, 300 out. `task time = in ÷ prompt speed + out ÷ writing speed`. | yes |
| `tuner/quality.py` | Starts llama-server on the phone with a configuration (reasoning off, as MeshAI does), forwards a port, runs the 15-problem benchmark, stores passed/failed and speed. | yes: baseline 14/15, GPU winner 14/15, same failed problem |
| `quality/` | `bench.py` and `bench-tasks.jsonl`, copied unchanged from maynards `scripts/` at 5b20df9, so scores compare with maynards `results/REPORT.md`. | yes: baseline 14/15, GPU winner 14/15, same failed problem |
| `tuner/store.py` | SQLite at `/mnt/storage/meshai/tuner/runs.sqlite`, one row per run: phase, label, device, model, engine, config (JSON), test, speed mean and spread, phone state before/after, cool-down time, screenshot, ok. | yes |
| `tuner/card.py` | A run card: a light-themed page sized to one phone screen, served from the laptop (port 8766, `adb reverse`), opened on the phone in its own Chrome tab, then screenshotted. Shows settings as chips, both speeds against the baseline, both task times against the baseline, and the conditions. | yes: cards captured on the iQOO |
| `tuner/report.py` | `reports/index.html`: every run against the baseline, the quality table, sustained runs, failed runs. | yes |

## Commands

```bash
python3 -m tuner part --part gate --serial S --cool 40        # quality gate on another phone of the same model
python3 -m tuner reuse --serial S --model M --set engine=opencl ngl=99 ctk=f16 ctv=f16 --cache-reuse 256
```


```bash
python3 -m tuner baseline  --serial S --model M                 # MeshAI's settings today
python3 -m tuner screen    --serial S --model M --family engine  # cpu / gpu / gpu + f16 cache
.venv/bin/python -m tuner optimize --serial S --trials 24       # Optuna search (needs the venv)
python3 -m tuner quality   --serial S --model M --set engine=opencl ngl=99 ctk=f16 ctv=f16
python3 -m tuner sustained --serial S --model M --minutes 10
python3 -m tuner cards     --serial S                            # cards for runs that have none
python3 -m tuner report
```

## Rules built into the code

- **Baseline first**, on the same phone, in the same session.
- **Same start temperature**: every run waits until the battery is at or below 35 °C (up to 15 min, then
  runs anyway and records the temperature).
- **Every run kept**, failures included, with the phone's state around it.
- **App in front is recorded**, so runs made while the owner used the phone can be told apart.
- **Noise guard** in reports and cards: a change counts only above 3% and above the run-to-run spread.
- **Quality gate** before any setting is called a winner.
- **Start temperature 40 °C** (was 35) after the owner asked for faster runs; the baseline was re-confirmed under the same rule so comparisons stay fair.
- **The tuner waits for a phone that drops off the cable** (`adb wait-for-device`) instead of failing the run.
- **No screenshots of the owner's screen.** The first version captured whatever was on screen; those were
  deleted and only run cards are captured now.

## Decisions and why

| Decision | Why |
|---|---|
| A separate repository | maynards' CLAUDE.md puts GPU backends out of scope for the event build, and another agent works there |
| Optuna instead of hand-written test lists | existing practice (llama-optimus); fewer runs on a phone that heats up |
| Score task time, two tasks at once | the GPU makes prompt reading faster and writing slower, so "fastest" depends on the task |
| Run cards in the phone's browser, not a new app | gives on-device screenshots without changing the MeshAI app |
| f16 cache is a searched knob | on the GPU it raised writing speed from 12.3 to 15.7 tok/s (measured) |
