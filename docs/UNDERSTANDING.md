# MeshAI and the Adaptive Mesh Compiler — everything, from the start

*A complete account: what the system is, how it works, what it measures, what the research says,
what is new and what is not, what is possible and what is not, and whether the business closes.*

Written 9 October 2026. Team Maynards, iQOO Hackathon 2026 — Hyderabad city round won (1st place),
finale pending. Assumes no prior knowledge. Every claim carries a reason and, where it exists, a source.

This is a description, not a set of instructions. It explains the state of understanding.

---

## Contents

0. [The short version](#0)
1. [The problem, from first principles](#1)
2. [How MeshAI works](#2)
3. [What actually exists in the repository](#3)
4. [Every measurement, and what it means](#4)
5. [What the Adaptive Mesh Compiler document proposes](#5)
6. [The research: every paper, quoted](#6)
7. [What is new and what is not](#7)
8. [What is possible, what is not, and why](#8)
9. [The business model](#9)
10. [Risks](#10)
11. [What remains unknown](#11)
12. [Questions worth asking](#12)
13. [The browser question, and the project that already answered it](#13)

---

<a name="0"></a>
## 0. The short version

A large language model has to fit in memory to run. A laptop has about 8 GB spare; a 30-billion-parameter
model needs about 18.5 GB. So it does not run — not slowly, *at all*.

MeshAI splits the model across several devices — a laptop and two Android phones — so that together they
hold it. The phones are connected by USB cable. Each device holds a slice of the model's layers; a token
passes through all of them in turn. Behind one OpenAI-compatible address, it looks like a single server.

It works. A 30B coding model runs across one laptop and two phones at about 6.8 words per second and
scores **15 out of 15** on a coding benchmark where the biggest model the laptop could hold alone scored
7 out of 15. An autonomous coding agent runs on it, fixes a real bug, commits, and passes the project's
tests — offline.

The new document in the folder proposes extending this into an "Adaptive Mesh Compiler": software that
profiles the task and each device, decides the cheapest way to execute, builds a custom package for each
device, and keeps adjusting. It proposes charging 10% of the money it saves a customer.

The research found: **every mechanism the document claims as new is already published.** One combination
is not — joining "decide across devices" with "build per device" — and eight survey papers confirm no
category exists for it. The 10%-of-savings business does not close at any scale reachable here. A real
buyer does exist, but it is narrower and differently motivated than the document assumes.

---

<a name="1"></a>
## 1. The problem, from first principles

### 1.1 Why a model has to fit in memory

A language model is a large pile of numbers called weights. To produce one word, the machine multiplies
the input by essentially *all* of those numbers. Not some — all of them, every single word.

This means the weights must be somewhere the processor can reach quickly. That is RAM. If they are on
disk instead, every word would mean reading gigabytes off storage, and the model would produce a word
every several minutes rather than several words a second.

So the rule is hard: **the model fits in memory, or it does not run.** There is no graceful degradation.

### 1.2 Why this is a memory problem and not a speed problem

This is the single most important idea in the project, and it is counter-intuitive.

When producing words one at a time, the machine is not limited by how fast it can calculate. It is
limited by **how fast it can read the weights out of memory**. Every word requires a full pass over the
weights, so the time per word is roughly *(size of weights) ÷ (memory bandwidth)*. The arithmetic units
sit idle waiting for data.

Why that matters: it means **adding more devices does not make things faster.** Each device still has to
read its own slice. What more devices buy you is *capacity* — the ability to hold a model that would not
otherwise fit at all.

So the honest claim for the whole project is: **pooling memory buys accuracy, not speed.** You get a
bigger, more capable model running slowly, rather than a small model running quickly. The measurements in
[Part 4](#4) show exactly this trade, and it is the reason the accuracy benchmark matters more than the
tokens-per-second number.

### 1.3 Why the quantities work out the way they do

- A 30-billion-parameter model, compressed to roughly 4 bits per weight (the "Q4_K_M" format), is
  **18.56 GB** on disk and needs about that much in memory, plus a little more for working space.
- A mid-range laptop has perhaps 8 GB it can spare after the operating system and browser.
- A modern phone has 12–16 GB total, of which perhaps 7–8 GB can be lent.

One laptop: no. Laptop plus two phones: **yes, with room to spare.** That is the entire opportunity.

---

<a name="2"></a>
## 2. How MeshAI works

### 2.1 Splitting by layer

A transformer model is built as a stack of near-identical **layers** — the 30B model has **48** of them.
A token enters at layer 0, is transformed, passes to layer 1, and so on to layer 47, after which the
model picks the next word.

Because the layers run strictly one after another, they can live on different machines. Layers 0–8 on the
laptop, 9–27 on the first phone, 28–47 on the second. The token's state travels along the chain.

This is called **pipeline parallelism**, and it is the natural choice here for a specific reason: the
alternative (tensor parallelism, splitting each layer across devices) requires devices to exchange data
*within* every layer, which needs enormous bandwidth. Pipeline parallelism needs one handoff per device
boundary per token. Over a USB cable, that difference decides whether the thing works at all.

### 2.2 What actually crosses the cable

At each boundary, what passes is the token's current internal state — a single list of numbers called the
hidden state.

Its size is the model's internal width × 2 bytes. For this model the width is **2048**, so:

> 2048 × 2 bytes = 4096 bytes = **4 KB per token per boundary**

This is a *derived* figure, not a measured one, but its inputs are authoritative: `hidden_size: 2048` and
`num_hidden_layers: 48` come from Qwen's official configuration file, and the code computes it this way in
`android/.../brain/Gguf.kt:127` (`hiddenBytes = embd * 2`).

**Why this number is the crux of the design:** 4 KB per token is almost nothing. At 7 tokens per second
that is 28 KB/s. A USB cable moves about 24,000 KB/s. So the link is roughly a thousand times faster than
the traffic needs — *provided the weights never travel.* The entire architecture exists to keep weights
local and send only this 4 KB.

### 2.3 Why the weights must not travel

Sending the model over the cable at 24 MB/s would take about 13 minutes for 18.56 GB. The measurements
confirm this: the first attempt at a split the phones had not seen before took **437 seconds** to become
ready, because layer weights crossed the cable.

The solution in the repository: each phone obtains the model file itself and keeps the layer data locally.
Then starting a split takes **70 seconds** instead of 437. The mechanism is described in [3.4](#34).

### 2.4 Why the embeddings and output head stay on the laptop

Two parts of the model are not layers: the **token embeddings** (turning words into numbers at the start)
and the **output head** (turning numbers back into a word at the end). The code pins both to the host
machine (`EngineArgs.kt:8`, the pattern `^(output|output_norm|token_embd)\.(weight|bias)$=CPU`).

Originally this was a memory decision — these tensors are large and the laptop had room.

It turns out to also be a **security** property, and an important one. A remote device that only ever sees
hidden states never sees the actual words, nor the final probabilities. This matters because of the
activation-inversion research in [10.3](#103) — hidden states can be turned back into the original text.
Keeping the first and last pieces on the machine you control limits what a remote device could ever learn.

### 2.5 The shape of the running system

```
                        ┌──────────────────────────────────────────┐
                        │  LAPTOP  (the Host)                      │
                        │                                          │
   user / agent  ──────▶│  one OpenAI-compatible address :8080     │
   ("fix this bug")     │        │                                 │
                        │        ├── control link  :7070 ──────────┼──┐  JSON lines,
                        │        │   (who is here, how much        │  │  every 2 s
                        │        │    memory, how hot, battery)    │  │
                        │        │                                 │  │
                        │        ├── the planner                   │  │
                        │        │   decides which layers go where │  │
                        │        │                                 │  │
                        │        └── llama-server  :8090           │  │
                        │            holds layers 0–8              │  │
                        │            + embeddings + output head    │  │
                        │                   │                      │  │
                        └───────────────────┼──────────────────────┘  │
                                            │ 4 KB per token          │
                            ┌───────────────┴───────────────┐         │
                            ▼                               ▼         │
              ┌──────────────────────┐        ┌──────────────────────┐ │
              │ PHONE A              │        │ PHONE B              │ │
              │ ggml-rpc-server      │───────▶│ ggml-rpc-server      │ │
              │ layers 9–27          │  4 KB  │ layers 28–47         │ │
              │ 6.41 GB              │        │ 7.33 GB              │ │
              │ layer cache on disk  │        │ layer cache on disk  │ │
              └──────────┬───────────┘        └──────────┬───────────┘ │
                         └───────────────────────────────┴─────────────┘
                              USB tethering, 2–3 ms round trip
```

The three things worth noticing about this diagram:

1. **The user sees one address.** Anything that speaks to an OpenAI-compatible endpoint — a chat page, a
   coding agent, a script — works unmodified. That is why an off-the-shelf agent could be pointed at it.
2. **The cable carries 4 KB per token**, nothing more, because the weights are already on the phones.
3. **The control link is separate from the data path.** Devices continuously report memory, heat, battery
   and latency on port 7070, while the model's traffic goes over the RPC connection. This separation is
   what makes it possible to know a phone is overheating without disturbing the run.

---

<a name="3"></a>
## 3. What actually exists in the repository

Everything below is built and running. File references are to `~/maynards`.

### 3.1 The agent — one Rust binary

`agent/src/main.rs`, `agent/src/host.rs`. Subcommands: `host`, `join`, `route`, `ask`, `review`, `tests`,
`diff`, `hook`.

- `mesh host` — runs on the laptop: serves the dashboard, listens for devices, plans, starts the engine.
- `mesh join` — runs on another machine to lend its memory.
- `mesh ask` / `review` / `tests` / `diff` / `hook` — command-line access, including a git hook.

**Why one binary:** at a hackathon, one file to copy and one command to run removes a whole class of
failure. There is no install step, no dependency tree, no service manager.

### 3.2 The planner

`agent/src/host.rs:313`, the function `plan`. Given a model and the devices present, it decides which
device holds which layers. It is a single greedy pass, not a search.

What it does, and why each part exists:

| Behaviour | Where | Why it is there |
|---|---|---|
| Rejects a device whose round-trip is over 60 ms | `host.rs:321` | Measured: over Wi-Fi at 13–181 ms the first word took 94 seconds and then the connection dropped. A slow link does not degrade the mesh, it destroys it. |
| Rejects a device under 20% battery and not charging | `host.rs:322` | A phone that dies mid-run takes the whole model down, since its layers are irreplaceable. |
| Rejects a device with less memory than a single layer | `host.rs:323` | A device that cannot hold even one layer contributes nothing but adds a network hop. |
| Records a human-readable reason for each rejection | `host.rs:325` | So the dashboard can say *why* a phone was not used, rather than silently ignoring it. |
| Returns "runs on this laptop alone" when the laptop suffices | `host.rs:347` | Distributing when it is unnecessary only adds latency. The planner is rewarded for declining. |
| Counts the conversation memory (KV cache) **per device, per layer** | `host.rs:314-316` | This is subtle and important — see below. |
| Gives the biggest phone the tail of the model | `host.rs:336-346` | A deliberate choice so the laptop keeps the front layers alongside the embeddings it already holds. |
| Reserves 0.30 GB on the host and 0.15 GB on each helper | `host.rs:310-311` | Without headroom the operating system kills the process when memory tightens. |
| Reports a verdict: *doable* (15% spare), *tight*, or *not possible* with the shortfall in GB | `host.rs:329` | A refusal that names its own reason is actionable; a generic failure is not. |

**The KV-cache point, because it is the most interesting property of the existing planner.** As a
conversation grows, the model keeps a cache of its past attention state. That cache grows with the
conversation length and is held *per layer*, so each device stores the cache for its own layers.

The consequence: **the conversation length changes the plan.** A short chat (4,096 tokens of context) and
a long coding session (16,384) produce *different* layer splits on the same hardware, because the memory
each layer needs is different. This is visible in the measurements — the chat runs used 4,096 and the
agent runs used 16,384, with different splits.

This matters for [Part 7](#7): the proposal document presents "the plan adapts to the task" as something
to be built. One dimension of it already exists and has been running.

### 3.3 Pinned splits

`host.rs:366-380`. A file (`~/.config/meshai/pinned.json`) can fix which device holds which layers for a
given model. While it exists, the planner is not consulted for that model.

**Why:** reproducibility. Every run puts the same layers on the same devices, which already hold them
locally, so startup is fast and predictable. All three autonomous-agent runs used a pinned split.

The code validates the pin against the model's real layer count, so a stale pin made for a different file
cannot place layers that do not exist.

### 3.4 The layer store — and what it actually is

`android/.../brain/LayerStore.kt`.

The mechanism: when the host needs to send a layer larger than 10 MB, it first sends only a **hash** of
the bytes (FNV-1a 64). If the phone already has a file matching that hash in `cache/rpc/<hash>`, it loads
it from its own storage and **nothing crosses the cable**.

The phone fills that folder from its own copy of the model file. The code comment is explicit:
*"Store **every** big layer of these models."* Each phone keeps **144 layer tensors, 17.6 GB** — the
whole model, not just its assigned slice.

**Why store everything rather than only what is assigned:** because then *any* split starts fast. If the
planner decides tomorrow that this phone should hold layers 30–47 instead of 9–27, the phone already has
them. The measured effect is 437 seconds down to 70.

**Why this distinction matters later:** this is a *content-addressed cache*, not a per-device package. It
is the deliberate opposite of giving each device only what it needs. It is a significant design decision
and it bears directly on the novelty question in [Part 7](#7).

Integrity is checked: the hash of `blk.40.ffn_down_exps` is `94a16f7ad603732b`, matching the engine's own
file, covered by `LayerStoreTest.kt`. **Why that test exists:** a silently corrupted layer would not crash
anything — it would produce subtly wrong output, which is far worse.

### 3.5 The Android app

Kotlin and Compose. A phone becomes a mesh member through a real app, not a terminal.

- **Foreground service of type `connectedDevice`.** Chosen deliberately: Android 15 caps `dataSync`
  services at six hours, and a phone that silently leaves the mesh mid-demo would be worse than one that
  never joined.
- **Memory reported from `/proc/meminfo`, not `ActivityManager.availMem`** (`Specs.kt`). The latter
  under-reported by about 11 GB — the Android API reports what the system is willing to give an app, not
  what the kernel actually has free.
- **An owner-controlled lending cap** (`Offer.kt`) — 0/2/4/6/8/12/16 GB. The device's owner decides how
  much to lend. Both a courtesy and, for any future business, a consent mechanism.
- **Thermal headroom reported every poll** (`Specs.kt:75`): `pm.getThermalHeadroom(10)`, a number from 0
  (cool) to 1 (about to throttle), −1 if unknown. *This arrives at the laptop and the planner does not
  use it* — see [Part 8](#8).
- **Scan-to-join by QR code**, on-device speech recognition, a camera path for image questions.

### 3.6 The dashboard and the desktop app

`agent/src/host.html` — a web dashboard with a left navigation rail: Overview, Devices, Models, Run, Chat,
Measure, Diagnostics. Polls state every 2 seconds.

**Why a dashboard rather than a command line:** every action that would otherwise be a typed command is a
button, so the system can be operated by someone who did not build it.

`desktop/service.py` and `desktop/ui` — the desktop app and the task runner that drives the coding agent.
A recent commit (`a7857c1`) unified the task runner so the dashboard and the desktop app share one.

### 3.7 The autonomous coding agent

`desktop/service.py`, with `desktop/aider/run-aider.sh`. It drives **Aider**, an existing open-source
coding agent, pointed at the mesh's own OpenAI-compatible endpoint.

The design decisions, and why each one is there:

- **The tests are run by the service, not by the model** (`run_tests()`, line 132). The pass/fail count
  comes from the project's own test command. **Why:** otherwise "it worked" is the model's opinion. This
  one choice is what makes the result evidence rather than a claim.
- **Per-task permissions for edit / commit / tests / shell** (lines 77–84). Default: edit, commit and
  tests on, **shell off**, enforced with `--no-suggest-shell-commands`. **Why:** the agent runs with
  `--yes-always`, so a suggested shell command would execute without being asked.
- **Every change is a git commit, and Undo is a `git revert`** (line 233). **Why:** autonomy is only
  acceptable if it is reversible.
- **Output is streamed as structured events** — text, edit, commit, tokens, tests. **Why:** a multi-minute
  autonomous run that shows nothing until it finishes is indistinguishable from one that has hung.

### 3.8 The measurement harness

`scripts/bench.py`, `scripts/bench-tasks.jsonl` (15 problems), `scripts/bench-report.py`, results in
`results/`.

Fifteen coding problems, temperature 0, 700-token limit. **The generated code is executed against its
tests.** `results/REPORT.md` states the method: *"scored by running the answers against their tests. No
partial credit, no human judgement."*

**Why this exists and why it is the most valuable thing in the repository:** it converts the project's
central claim from an assertion into a measurement. Without it, "pooling memory is worth it" is a
feeling. With it, there is a number. See [4.1](#41).

---

<a name="4"></a>
## 4. Every measurement, and what it means

All from `docs/measurements.md` and `results/REPORT.md`.

<a name="41"></a>
### 4.1 The accuracy ladder — the single most important result

| Setup | Accuracy | Passed | Median speed |
|---|---|---|---|
| Laptop alone, 0.6B | **13.3%** | 2/15 | 20.9 tok/s |
| Laptop alone, 1.7B | **46.7%** | 7/15 | 8.6 tok/s |
| Laptop + laptop helper, 1.7B **split** | **46.7%** | 7/15 | 7.4 tok/s |
| **Laptop + 2 phones, 30B** | **100.0%** | **15/15** | **6.8 tok/s** |

Two separate findings live in this table.

**Finding one: splitting a model costs nothing in quality.** The 1.7B scored identically whole and split —
and failed *the same eight problems* (`word_frequencies`, `merge_intervals`, `roman_to_int`,
`group_anagrams`, `parse_duration`, `matrix_spiral`, `csv_column_sum`, `chunk_text`).

**Why the identical failure set is much stronger evidence than the identical score:** two systems of equal
ability failing independently would agree on all fifteen items with probability about **3 in 100,000**.
Matching scores could be coincidence; matching *failures* means the split produced the same output.

**Why it is theoretically expected**, which matters because a result that is both measured and predicted is
far more robust than one that is merely measured: splitting by layer does not change the order of
arithmetic *inside* any layer. It only moves a tensor between machines. Production non-determinism in
language models comes from batch-size-dependent reduction strategies, not from splitting — so at batch
size 1, greedy decoding, identical weights and identical precision, bit-identical output is the expected
result.

**Finding two: pooled memory buys capability, and the trade is cheap.** 13% → 47% → 100% as pooled memory
grows, while speed falls only 20.9 → 6.8 tok/s. Roughly 3× slower for 13% → 100% correct.

**Why this is the argument for the whole project:** it quantifies what the memory is *for*. The only reason
the 30B can run at all is the phones.

**Two honest limits, and the second matters more than the first.**

*Sample size.* Fifteen problems is small. The 95% confidence interval around 7/15 runs from roughly 25% to
70%. The *comparison* between configurations is sound, because it is the same fifteen problems each time;
any single absolute percentage carries wide uncertainty.

*What a perfect score actually tells you.* **15/15 says as much about the test as about the system.** A
benchmark a model solves completely has stopped discriminating: it can no longer separate a good
configuration from a better one, and it cannot show whether splitting costs anything, because there is no
headroom left to lose. The tasks are ordinary coding problems — `parse_duration`, `roman_to_int`,
`matrix_spiral`, `group_anagrams` and similar — fair, but unremarkable, and a frontier API would also score
15/15. Against a harder set (HumanEval+, MBPP+, or repository-level tasks in the style of SWE-bench) the
ladder above would compress, and the honest expectation is that the 30B would land well below 100%.

So the defensible reading of the table is its **shape** — capability rising with pooled memory while speed
falls only modestly — not its top number. The 13% → 47% → 100% progression is real and is the argument for
the architecture. "Our system is 100% accurate" is not a claim this benchmark can carry.

### 4.2 The 30B runs — and why there are five of them

Five separate runs exist, which caused real confusion because different documents quote different ones.

| Run | Laptop holds | Phones hold | Speed | Cold start |
|---|---|---|---|---|
| 26 Sep 20:18 | layers 0–17 | 10.68 GB | 6.6–7.1 tok/s | 70 s (first ever load 517 s) |
| 27 Sep 00:5x | 0–19 | ~7.0 GB | — | **437 s** (layers crossed the cable) |
| 27 Sep 01:10 | 0–22, 9.19 GB | 9.57 GB | 6.6 tok/s | 70 s |
| **27 Sep 01:25** | **0–8, 4.09 GB** | **13.74 GB** | **6.7–7.1 tok/s** | **87 s** |
| 27 Sep 05:28 | 0–14 | — | **7.2–7.3 tok/s** | 69 s |

**Why the 01:25 run is the significant one:** the phones hold **13.74 GB of 18.56 GB — 74% of the model**
— and the laptop only 4.09 GB. For any claim about phones supplying the memory, this is the strongest
configuration measured. Elsewhere in published work the phone is a small helper beside a real graphics
card; here the phones *are* the machine.

**The 437 → 70 second pair** is the evidence for the layer store. Same hardware, same model; the only
difference is whether the phones already held the layer data.

**A number to be careful with:** "17.8 GB total" appears in the proposal document but nowhere in the
repository. It is 4.09 + 6.41 + 7.33 added together, and it quietly includes the laptop. Separately,
`measurements.md:20` records "17.6 GB per phone" — a *disk* figure for the cache, not a memory total. The
two are easy to confuse.

### 4.3 The link — why USB and not Wi-Fi

| Condition | Latency | First word on a 359-token prompt |
|---|---|---|
| USB tethering | 2–3 ms | **1.1 s** (short) / 11.6 s |
| Venue Wi-Fi | 13–181 ms, worst 10.5 s | **94 s, then the connection dropped** |
| Venue Wi-Fi (earlier) | 266–483 ms, average 373 | — |

**Why the difference is so violent.** Every token crosses every device boundary. With three devices that
is two boundaries per token. At 7 tokens per second, that is 14 round trips per second. At 2 ms each,
28 ms of overhead — invisible. At 373 ms each, 5.2 seconds of overhead *per second of output* — the system
cannot keep up and falls apart.

This is why the planner refuses a device over 60 ms, and it is the strongest technical argument for
physical custody of hardware: a controlled wired link is not a convenience, it is a requirement.

### 4.4 Heat

`docs/sustained-8b-iqoo.csv`, 15 rows over ten minutes of continuous work:

| Time | Speed | Skin temp | Battery temp |
|---|---|---|---|
| Start | **11.44 tok/s** | 34.6 °C | 30.5 °C |
| 5 min | ~10.4 tok/s | — | — |
| 7 min | 9.8 tok/s | — | — |
| 10 min | **5.29 tok/s** | **47.5 °C** | 42.0 °C |

**Throughput more than halves in ten minutes.** The phone is not broken; Android is protecting itself by
slowing the chip down.

**Why this matters beyond safety:** cost per word is inversely proportional to speed, so the cost per word
**doubles** ten minutes into a job. Averaged over an hour the phone delivers about **51% of its peak**. Any
capacity estimate based on the opening speed is roughly twice as optimistic as reality.

**Why it matters specifically for the agent workload:** the agent tasks took 6–10 minutes, which is exactly
the window in which this decay happens.

### 4.5 Transfer

0.64 GB in about 27 seconds over USB = **~24 MB/s**, resumable. Extrapolating, the 18.56 GB model would
take about **13 minutes** to copy this way. **Why this is recorded:** it establishes that moving weights is
expensive and sets the cost of the alternative the layer store avoids.

### 4.6 The autonomous agent runs

Same task each time: a real bug (`parse_duration`, 3 of 5 tests failing) in `~/meshai-aider-demo`.

| Setup | Model | Decode | Prefill | First word | Outcome |
|---|---|---|---|---|---|
| Laptop alone | Qwen3-**8B** | 2.5 tok/s | 9.6 tok/s | **287 s** | committed, pytest 5 passed, 459 s total |
| Laptop + 2 phones | **30B** | **5.4 tok/s** | **21.1 tok/s** | — | commit `4a5e308`, 5 passed, 603 s |
| Laptop + 2 phones | **30B** | 4.4–5.1 tok/s | 19.1 tok/s | — | commit `fa1a81c`, 5 passed, **7 min 34 s** |

**The comparison that matters:** the mesh runs a **30B** model about **twice as fast** as the laptop alone
runs an **8B** — faster at both reading the prompt and generating. First word fell from 287 s to roughly 20.

**Why it is faster rather than merely larger**, which is surprising given [1.2](#12): the laptop alone was
*memory-starved*. Squeezing an 8B model plus a 16,384-token conversation cache into 8 GB leaves nothing
spare, and the system thrashes. Spreading the work across three devices gives each one room. The gain is
not from more compute; it is from no longer being desperate for memory.

**A caveat worth holding:** all three agent runs used a **pinned** split, not the planner's own choice.

### 4.7 Other recorded figures

Phone alone with an 8B: 11.8 tok/s. Dual-phone routing: text 12.3, vision 19.1 tok/s. Phone alone with a
0.6B: 78.1 tok/s. Model file 18.56 GB, 48 layers, context 4,096 (chat) or 16,384 (agent), KV cache
compressed to 8-bit.

**One stale-data trap:** `docs/pre-event/handoff/02-state-and-numbers.md` describes a *different laptop*
(i3-7020U, 7.7 GB) and *different phones* (realme). Its 19.6–24.9 tok/s figures look like contradictions
of everything above unless one notices the hardware changed.

---

<a name="5"></a>
## 5. What the Adaptive Mesh Compiler document proposes

`~/IQOO/Adaptive_Mesh_Compiler_Research_Validation_Brief_Regenerated.docx`, dated 30 September 2026,
531 paragraphs, 20 sections, 19 references.

### 5.1 The core idea

Rather than treating every device as a generic worker, profile the exact task and the exact hardware,
compile a **different execution package for each device**, and continuously re-plan against cost, latency,
memory, energy, thermals and accuracy.

Its own summary: *"We do not merely distribute a model. We compile the distributed computer itself around
the task."*

### 5.2 The pipeline it describes

1. Receive a task — model, context distribution, concurrency, service target, quality floor, privacy, budget
2. Discover resources — phone CPU/GPU/NPU, laptops, local GPUs, cloud GPUs, and the network between them
3. Build device fingerprints from quick benchmarks plus stored tuning records
4. Generate candidate execution graphs — placement, quantization, batching, cache layout, local/cloud mix
5. Score candidates with a performance predictor; discard those breaking hard constraints
6. Compile a **Node Capsule** per device
7. Execute and collect telemetry
8. Compare predicted against observed; update the model; re-plan when the gap grows

### 5.3 The "Node Capsule"

The central artefact. Per the document, each one contains: only the assigned layers or operators; a
per-layer precision choice; the backend kernels; a memory plan; an execution policy; a network policy; a
thermal and battery safety envelope; and a telemetry schema.

### 5.4 Its commercial proposal

Install alongside a customer's existing stack in shadow mode; observe a baseline for 2–4 weeks; identify
and validate cheaper configurations; auto-apply within guardrails; **charge 10% of verified realized
savings.**

### 5.5 What it claims is defensible, and what it says to avoid

It is honest in places. It explicitly says *"do not claim novelty"* for hardware-specific compilation
(citing TensorRT) and for distributed inference (citing exo and Petals). It lists claims to avoid,
including "we train the GPU" and "we invented distributed inference."

Its asserted novelty is narrower: *"task-conditioned per-node generated artifacts across phone + laptop +
cloud"*, which its landscape table marks as *"fragmented / no dominant public end-to-end system found."*

**That specific sentence is where the research concentrated**, because it is the load-bearing claim.

---

<a name="6"></a>
## 6. The research: every paper, quoted

Organised by which claim each one bears on. Papers marked **verified** were opened and read directly;
quotes are from the papers' own text.

### 6.1 Pooling memory across devices

**NVIDIA DGX Spark** — *verified from NVIDIA's own documentation.* 128 GB coherent unified memory per unit;
memory bandwidth **273 GB/s**; ConnectX-7 networking at 200 Gbps; NVIDIA's documentation states
**"405B for dual-Spark configuration"**, and up to four units can be linked. Price history: **$3,999 at
launch → $4,699 (Feb 2026) → $6,950** for the 128 GB model, the rise attributed to LPDDR5x memory supply
constraints.

> **What it means.** Pooling memory across devices to run a model too large for one is a *shipping
> commercial product*. The concept cannot be claimed as new. Two units at $6,950 = 256 GB for $13,900, or
> **$54.30 per GB**. Salvage phones at roughly $31 for ~8 GB usable are about **$3.88 per GB** — around
> **14× cheaper per GB of capital, and about 1,000× slower per GB of bandwidth.** That trade is the honest
> statement of where a phone mesh sits. The *price history* is also the strongest external evidence for
> the thesis that already-paid-for memory is becoming more valuable: new memory is getting more expensive,
> not cheaper, for physical supply reasons.

**NVLink / NVLink Switch** — *verified.* 72 fully-connected GPUs in NVL72, 3 TB/s per GPU, 216 TB/s
aggregate, described as *"effectively forming a data-center-sized GPU"* that functions *"as a single
high-performance accelerator."*

> **What it means.** The same idea at datacentre scale, with purpose-built fabric roughly 100,000× faster
> than a USB cable. The gap between MeshAI and NVIDIA is not the idea; it is the interconnect.

**prima.cpp** — [arXiv 2504.08791](https://arxiv.org/abs/2504.08791). *Venue unverified: the arXiv metadata
carries no journal reference, so "ICLR 2026" should not be stated.* Runs 30–70B models on consumer home
clusters. Its "Halda" scheduler co-optimises per-device CPU/GPU workloads **and device selection** under
RAM/VRAM constraints. Testbed verbatim: *"a host PC (5 GB RAM, 1080 TI GPU with 11 GB VRAM), a Mac Mini
(10 GB UMA RAM), a laptop (23 GB RAM, 3060 GPU with 6 GB VRAM), and a **Redmi phone** (7 GB RAM)."* On
re-planning: *"workloads can be repartitioned whenever the task queue is empty to adapt to environmental
changes."* Results: 70B at 674 ms/token, 5–17× lower time-per-token than llama.cpp, exo and dllama.

> **What it means, and it is the most directly competitive paper.** Pooling *heterogeneous consumer
> devices including a phone* is published. So is re-planning from changing conditions. Two things it does
> *not* do: it runs a **single uniform runtime** (no device gets distinct software — quantization is fixed
> at Q4K across every experiment), and the words **"thermal", "battery" and "throttle" appear zero times
> in its 38 pages.** Also notable: in its cluster the phone is a 7 GB node beside two desktop graphics
> cards. The phone assists; it does not carry the model.

**exo** — [github.com/exo-explore/exo](https://github.com/exo-explore/exo). Automatic discovery and model
partitioning across everyday devices, with a *"realtime view of your device topology."*

**Petals** — [arXiv 2312.08361](https://arxiv.org/abs/2312.08361). Fault-tolerant distributed inference
over unreliable internet nodes. Reported performance around 6 tok/s on a 70B model.

> **What it means.** Volunteer-internet inference, which establishes both that the idea works and that
> bandwidth is its binding constraint.

**Cascadia** — [arXiv 2609.38697](https://arxiv.org/abs/2609.38697), 30 Sep 2026. *Verified.*
*"Cascadia: A Control-Plane-Free Alternative to Hyperconverged AI Infrastructure."* Peer connections via
cryptographic certificates and gossip; 3.10–4.06× throughput over a single node.

**Pre-Compiled Pipeline Shards** — [arXiv 2608.19147](https://arxiv.org/abs/2608.19147), 19 Aug 2026.
*Verified.* Verbatim: *"a model is split by layer into per-stage shards, **each pre-compiled into an
OpenVINO graph**, so that every machine runs one shard and passes activations to the next."*

> **What these two mean together, and this is the most important finding in the whole prior-art search.**
> This pair already ships **per-node compiled artifacts across a heterogeneous fleet with a live control
> plane.** That is the Node Capsule, implemented, in August 2026 — before the proposal document was
> written. The document's claim of *"no dominant public end-to-end system found"* is false as written.
> What these systems do **not** do: they compile **once, offline, at fixed INT4**, and Cascadia's chain
> formation uses *"advertised resources, not a link-cost optimizer."* There is also no phone tier at all —
> it is Intel AI PCs — and no thermal signal anywhere in either paper.

### 6.2 Building device-specific software

**NVIDIA TensorRT** — *verified from NVIDIA's documentation.* Verbatim: *"A build phase in which a builder
compiles your network and selects the fastest available kernel (a low-level GPU function) for each layer
on your target GPU."* And: *"By default, serialized engines are only guaranteed to work correctly when
used with the same OS, CPU architectures, GPU models, and TensorRT versions used to serialize the
engines."*

> **What it means.** Compiling software tuned to one specific device — timing candidate implementations on
> the real chip and keeping the fastest, producing an artefact bound to that hardware — has been NVIDIA's
> core product since 2016. The proposal document cites this and correctly says not to claim novelty for
> it. It is worth being blunt about scale: TensorRT does kernel autotuning, layer fusion, precision
> calibration and memory planning. Describing MeshAI's layer-set-plus-settings as a "compiler" invites a
> comparison that cannot be won.

**Microsoft Olive**, **Qualcomm AI Hub**, **ExecuTorch**, **Apache TVM MetaSchedule**, **MLC LLM** — all
produce per-device optimised artifacts. An ExecuTorch `.pte` file is backend-specific with memory planning
performed before serialisation. TVM MetaSchedule has used a learned cost model with real on-device
measurement for years.

> **What they mean.** The "package containing a subgraph, kernels and a memory plan" is an established
> artefact format with several mature implementations. One useful detail in the other direction: Qualcomm
> AI Hub's own documentation states *"Mixed precision is not currently supported"* — so per-layer precision
> selection genuinely does not exist on the phone tier today.

### 6.3 Adapting the plan to the task and to live conditions

**Voltron** — [arXiv 2607.07046](https://arxiv.org/abs/2607.07046), 8 July 2026, Cho et al., Korea
University. *Verified in full text.* Targets *"multiple user-end devices available at the edge."* Verbatim:

> *"At the beginning of each conversation turn, Voltron determines a model execution plan that specifies
> the layer-wise parallelism strategy and precision configuration, to maximize accuracy while satisfying
> QoS requirements under the current execution environment."*
>
> *"During the LLM execution, Voltron continuously monitors execution environment, and elastically adjusts
> the execution strategy by adapting to the runtime variance."*
>
> *"The computation time is estimated exploiting a regression model (of which average accuracy is 90%)."*

Reports up to 16.5% higher accuracy. **Verified absent from the paper:** the words "thermal",
"temperature", "price" and "compile" do not appear; there is no public cloud tier in its objective.

> **What it means, and it is the single most dangerous paper for the proposal.** On a mesh of phones and
> tablets, Voltron already does: a plan re-derived **per conversation turn**, over layer placement **and**
> per-layer precision jointly, scored by a **learned predictor**, with a **continuous live adjustment
> loop**, under a quality constraint. That is four of the proposal's six claimed novelties, in one paper,
> three months before the document was written. What it does *not* do: it never **compiles** anything —
> "compile" does not occur in the paper — so there is no per-node package, no backend selection, no baked
> memory plan. It emits a *plan*, never an *artifact*.

**HybridInfer** — [arXiv 2609.30270](https://arxiv.org/abs/2609.30270), **14 July 2026**. *Verified.*
*"HybridInfer: Thermal-Aware Reinforcement-Learning Tier Routing for On-Device, Edge, and Cloud LLM
Inference."* Verbatim: *"uses the phone's thermal headroom and a query-complexity estimate as state and
selects a tier by an offline-trained Q-learning policy."* Cost is in the reward.

> **What it means.** Using a phone's thermal headroom — the exact same Android API MeshAI already reads —
> to route work across device, edge and cloud, with cost in the objective, is published. Note the date: it
> sits *inside* the proposal document's own review window and was simply missed. What it does *not* do: it
> selects **which whole model answers the query**; it does not place layers. In its full text "layer"
> appears once and "partition" **zero** times. Its decision space is three discrete tiers, not a
> combinatorial space of execution graphs.

**Neurosurgeon** — ASPLOS **2017**. A device-class profile built once per platform pair; regression models
predicting per-layer latency *and* energy on both mobile and cloud; then *"with these predictions, combined
with the current wireless connection bandwidth and datacenter load level, Neurosurgeon selects the best
partition point"* — re-decided per inference.

> **What it means.** The *architecture* of the proposal — profile devices, predict with a learned model,
> choose a split point from live network and load conditions — is nine years old. The proposal's pipeline
> is Neurosurgeon's pipeline with language-model-specific decision variables. Anyone who knows mobile
> systems research will recognise it.

### 6.4 Choosing precision per layer

**LLM-PQ** — [arXiv 2403.01136](https://arxiv.org/abs/2403.01136). Verbatim: *"We introduce adaptive
mixed-precision into the search space of pipeline serving"* — jointly choosing per-decoder-layer bit-width,
micro-batch sizes and model partition on heterogeneous clusters, scored by a fitted regression predictor,
under a quantization-perturbation indicator acting as a quality floor, emitting per-device configurations
*"derived automatically and registered."*

> **What it means.** Per-layer precision chosen *inside* a cross-device placement search, with a learned
> predictor and a quality constraint, is published. This specific claim is closed. Its gaps: GPU clusters
> only, offline, no phone, no thermal signal, no monetary cost.

**NVIDIA ModelOpt `auto_quantize`** searches per-layer precision under a bit budget; **Olive** has a
`SelectiveMixedPrecision` pass inside a search.

**SWEET** — Frontiers in Complex Systems, 2026. *Verified as accepted.* Joint layer-wise quantization and
edge/server partitioning with an accuracy-degradation term; described as the first serving system to
optimise layer-wise quantization bit-width against a theoretical accuracy measure.

### 6.5 Cost-aware placement

**Mélange** — [arXiv 2404.14527](https://arxiv.org/abs/2404.14527). Cost-aware bin packing across GPU types;
finds the cheapest allocation is typically a *mix*; reports up to 77% / 33% / 51% cost reduction in
conversational, document and mixed settings.

**Helix** — [arXiv 2406.01566](https://arxiv.org/abs/2406.01566), **ASPLOS 2025** (confirmed in the arXiv
comment). Formulates inference over heterogeneous GPUs *and network* as a max-flow problem, solved with
mixed-integer linear programming, **jointly optimising model placement and request scheduling**. 3.3×
throughput, −66% prefill and −24% decode latency.

**ShuntServe** — [arXiv 2606.18600](https://arxiv.org/abs/2606.18600). Spot-instance-aware serving:
*"a roofline model-based analytical serving performance estimator and a dynamic programming-based model
placement optimizer that jointly determines node configuration, parallelization strategy, and layer
assignment"*, reacting to interruptions and volatile availability.

**HybridFlow** — [arXiv 2512.22137](https://arxiv.org/abs/2512.22137). *"routes each subtask online to the
edge or cloud via a learned benefit-cost utility model that dynamically trades accuracy gains against
token/API and latency budgets."*

**SkyPilot** — *"chooses the cheapest and available zone/region/cloud"* and re-provisions elsewhere on
preemption. Free and open source.

> **What these mean together.** Cost-aware, price-aware, market-aware placement across heterogeneous
> hardware — including at layer granularity and including live spot-market reaction — is thoroughly
> occupied. An earlier working assumption that "cloud price as a live re-planning signal" was unoccupied
> was **wrong** and was retracted. ShuntServe does exactly that at layer level.

### 6.6 Performance prediction

**Vidur** — [arXiv 2405.05465](https://arxiv.org/abs/2405.05465). *Verified exactly.* Estimates inference
latency with *"less than 9% error"*; Vidur-Search found an optimal LLaMA2-70B deployment in **one CPU-hour**
where exhaustive exploration would need **42,000 GPU-hours ≈ $218,000**.

> **What it means.** Simulation-before-measurement is established, and the economic case for it is
> dramatic. The proposal cites this correctly.

**LLMCompass** — [arXiv 2312.03134](https://arxiv.org/abs/2312.03134). *Verified:* *"an average 4.1% error
rate for LLM inference."*

**nn-Meter** — *verified with a caveat.* The dataset is **26,000 CNN models**, not neural networks
generally, and its four target devices (Pixel 4, Mi 9, Pixel 3XL, Movidius NCS2) date from 2019–21.

> **What it means.** The proposal describes "26k DNNs." Kernel-level latency prediction for 2019-era
> *image* networks does not transfer to transformer token generation. The caveat supports the proposal's
> own framing that this would need extending.

**OmniPilot** — [arXiv 2607.01579](https://arxiv.org/abs/2607.01579). *Unverified; reported.* Contains a
*"conformally calibrated quantile cost model with an out-of-distribution abstention layer"*, and reportedly
a section titled *"Cluster telemetry: a negative result"* — a 2026 paper finding that cluster telemetry did
**not** improve its predictor.

> **What it would mean if verified.** The best available evidence that closing the loop on live telemetry
> is non-obvious rather than trivially correct.

### 6.7 The surveys — the strongest positive finding

Eight survey papers from 2024–2026 were examined for their taxonomy sections. Surveys exist to sort a field
into named categories, so their category lists are a map of what the field considers to be a topic.

> **The finding: not one of the eight has a category for joining cross-device planning with per-device
> artifact generation.** In every taxonomy, the cross-device axis stops at placement, partitioning, routing
> or offloading, and "compilation" sits in a **separate, single-device, task-agnostic** bucket containing
> MLC LLM, TVM and ExecuTorch. Nothing bridges them. Likewise no survey names thermal- or battery-aware
> adaptive inference as a category, and no survey names cost-aware cross-tier planning as one — cost
> appears only as a metric inside model-routing taxonomies.

Three published statements of the gap:

> *"joint optimization of communication, computation, and memory for LLM edge inference remains at an early
> stage. Further work is needed on online and robust optimization under time-varying wireless channels,
> cost models that capture prefill and decode asymmetry and KV growth, and cross-layer mechanisms that
> enforce strict latency and energy targets without sacrificing service quality."*
> — [arXiv 2604.22906](https://arxiv.org/abs/2604.22906) §5.4

> Heterogeneity *"requires: 1) Platform-specific compilation using dozens of compiler toolchains;
> 2) Architecture-aware model partitioning strategies; 3) Dynamic resource allocation algorithms;
> 4) Cross-platform performance optimization"* — named as an unsolved burden with no system cited against it.
> — [arXiv 2501.03265](https://arxiv.org/abs/2501.03265) §3.3

> *"Future systems must implement sophisticated resource monitoring and adaptation strategies that can
> dynamically adjust the collaboration ratio based on real-time constraints including battery level,
> thermal conditions, network bandwidth, and computational load."*
> — same paper, §6.5

> **What this means.** This is better evidence of a gap than any self-assessment could be: the field's own
> reference works have no shelf for it, and three of them describe it as future work. The caveat matters
> though — this is a gap in the **survey taxonomies**, not in the component literature, which is crowded.

Also already in print: [arXiv 2609.23130](https://arxiv.org/abs/2609.23130), 19 Sep 2026, *"From Inference
Engine to Inference Control Plane"* — a position paper proposing *"an Inference Execution Planner that
selects feasible execution plans rather than only endpoints."* The proposal document's thesis exists as a
published research agenda.

### 6.8 The naming collision worth knowing about

Two unrelated papers are called **Splitwise**:

- [arXiv 2311.18677](https://arxiv.org/abs/2311.18677) — Microsoft/UW, ISCA 2024. Prefill/decode phase
  splitting inside a datacentre. 1.4× throughput at 20% lower cost. **This is the famous one.**
- [arXiv 2512.23310](https://arxiv.org/abs/2512.23310) — Younesi et al. Edge-cloud partitioning with
  Lyapunov-assisted deep reinforcement learning. 1.4–2.8× latency improvement, up to 41% energy reduction,
  p95 latency down 53–61% versus cloud-only, on Jetson Orin NX and Raspberry Pi 5.

The proposal cites the second while separately describing the first's mechanism under NVIDIA Dynamo.

> **What it means.** Nothing is wrong factually — both papers are real and the citation is accurate. But a
> reader who knows the famous Splitwise will read the citation as a mistake.

---

<a name="7"></a>
## 7. What is new and what is not

### 7.1 The ledger

| Claim in the proposal | Status | Who holds it |
|---|---|---|
| Pool memory across devices for a too-large model | **Closed** | NVIDIA DGX Spark (product), NVLink, prima.cpp, exo, Cascadia |
| Pool *heterogeneous consumer* devices including a phone | **Closed** | prima.cpp (Redmi phone in its testbed) |
| Build software tuned to a specific device | **Closed** | TensorRT (since 2016), Olive, Qualcomm AI Hub, ExecuTorch, TVM, MLC |
| A per-device package of subgraph + kernels + memory plan | **Closed** | An ExecuTorch `.pte`, a TensorRT engine, a QNN context binary |
| Per-node *compiled* artifacts across a heterogeneous fleet | **Closed** | Cascadia + arXiv 2608.19147, August 2026 |
| Plan adapted per task/request from live state | **Closed** | Voltron (per conversation turn), prima.cpp, Neurosurgeon (2017) |
| Per-layer precision inside a placement search | **Closed** | LLM-PQ, ModelOpt `auto_quantize`, Olive, SWEET |
| Learned performance predictor for placement | **Closed** | TVM MetaSchedule, Vidur, LLMCompass, Voltron (90% accuracy) |
| Thermals and battery as inputs to inference decisions | **Closed** | HybridInfer |
| Cost-aware cloud/tier selection | **Closed** | Mélange, ShuntServe, HybridFlow, SkyPilot |
| Price as a live re-planning signal | **Closed** | ShuntServe (an earlier assumption that this was open was wrong) |
| The overall architecture | **Closed** | Neurosurgeon, ASPLOS 2017 |
| **Per-device artifacts generated as the output of a cross-device plan, re-emitted on state change** | **Open** | Nobody. Eight survey taxonomies have no category for it |

### 7.2 Why that last row is the only one left, in plain terms

There are two separate jobs:

- **Planning** — deciding which device holds which part of the model.
- **Building** — making software tuned to one specific device.

Both are mature, and **they have never been connected.** TensorRT tunes a device superbly and has no idea
other devices exist. The mesh systems hand out layer ranges and then run *identical generic software* on
every device.

Nobody says: *"you take layers 10–27, and here is the build tuned for your chip"* — with one plan choosing
both, and re-choosing when conditions change.

**Why they have never been connected** is worth understanding, because it explains both the opportunity and
the difficulty: they belong to different research communities with different tools. Compiler people
optimise one target at a time; distributed-systems people schedule uniform workers. The surveys reflect
that split exactly — separate buckets, no bridge.

### 7.3 Where MeshAI actually stands against that gap

Honestly: **on the far side of it.** The layer store caches *every* layer on *every* phone. It is the
deliberate opposite of giving each device only what it needs — and for a good reason, since it is what
makes any split start in 70 seconds instead of 437.

And every phone runs the **identical generic `ggml-rpc-server`** with identical settings. There is no
per-device tuning at all: not backend choice, not thread count, not kernel selection, not memory layout.

So the gap is real and MeshAI does not currently occupy it. What exists is a mesh that plans well and runs
uniform software — which is prima.cpp's position, reached independently.

### 7.4 What *is* distinctive, as measured fact rather than mechanism

Three things survive scrutiny because they are observations rather than claims:

**Phones carry the model rather than assisting it.** 13.74 GB of 18.56 GB — **74%** — on two phones. In
prima.cpp the phone is a 7 GB node beside two desktop graphics cards. Cascadia has no phone tier.
HybridInfer runs a whole small model on the phone rather than part of a large one. **Why it matters beyond
novelty:** if phones can only ever be a fifth of a cluster there is no fleet business; at 74% the question
is at least open.

**Task correctness is measured, which the field does not do.** prima.cpp reports 674 ms/token. Petals
reports tokens per second. Cascadia reports 3.1–4.1× throughput. exo reports nothing systematic. **None
reports whether the split model still gets the right answer.** The 15/15 result, and the identical-failure
result beneath it, are evidence of a kind the literature does not contain. As a *novelty* claim this is
unexciting; as *evidence* it is unusually strong, and it is unassailable because it is a measurement.

**With the caveat from [§4.1](#41) carried forward, because it bites hardest here.** The strong half of
this is the **identical-failure** result on the 1.7B — that is a genuine, discriminating finding about
whether splitting changes a model's output. The 15/15 on the 30B is weaker than it looks: a benchmark that
is solved completely has hit its ceiling and stops measuring. So the durable form of this contribution is
*"splitting a model across devices does not change what it answers, and we tested that by executing the
code"* — not *"our configuration is 100% accurate."* The method is the contribution; the headline number is
the part a harder benchmark would take away.

**An autonomous agent runs on it.** Editing code, committing, passing externally-run tests, offline, on a
30B model held mostly by two phones. No published work shows an autonomous coding agent on pooled consumer
phones.

### 7.5 Why agents fit this architecture so well

Five properties reinforce each other, which is what makes the agent framing more than a demo choice:

1. **Agents are memory-hungry.** They send their instructions plus the files being edited — 16,384 tokens
   of context against 4,096 for chat. Since the conversation cache is held per layer per device, the thing
   agents need most is exactly what pooling supplies.
2. **Agents are patient.** A task runs for minutes and nobody watches each word appear. 6.8 tok/s is
   perfectly usable here and would be unacceptable for chat. **The architecture's one real weakness
   disappears in this workload.**
3. **Agents run long enough that heat matters.** Tasks took 6–10 minutes; the measured thermal decay
   happens over exactly that window.
4. **Agents touch private code**, which is a concrete reason not to send the work to an external API
   rather than a slogan about privacy.
5. **Agents are what the only viable buyer wants** — see [9.5](#95). The two conclusions were reached from
   opposite directions and met.

---

<a name="8"></a>
## 8. What is possible, what is not, and why

This section exists because three different things were being confused.

### 8.1 Generic software: possible, and it is what runs today

Every phone runs the same program with the same settings, unaware of which chip it is on. **This works** —
it produced 15/15 at 6.8 tok/s. It is the normal way to build such a system and it is what prima.cpp and
exo do too. Nothing about it is broken or impossible. The word "generic" is a description, not a criticism.

### 8.2 Device-specific software: possible, simply unused

Not blocked, not difficult to reach. llama.cpp already exposes the relevant choices:

- whether to run on the phone's **GPU (Vulkan) or CPU**
- how many **threads**
- which **implementation of each operation** to use
- how memory and the attention cache are **laid out**

None of these is set per device today; every phone receives the same defaults.

**One honest uncertainty:** the *benefit* is unmeasured. On Android, llama.cpp's GPU path is sometimes
*slower* than its CPU path depending on the chip and the operation. Choosing per device might gain 20%,
might gain nothing, might differ in direction between the two phones. What is certain is that choosing per
device is the unoccupied part; what is uncertain is whether choosing well matters. It is measurable by
timing both backends on each phone and seeing whether they even disagree.

### 8.3 What is genuinely impossible: changing the split while running

This is the only hard "no."

`llama-server` is told the layer split **at launch** — through `--rpc <addresses>`, `-ngl <count>` and
`--tensor-split <proportions>` (`host.rs:727-732`). There is no mechanism to change it afterwards. Moving
layers between devices requires stopping the engine and starting it again: **70–87 seconds**.

**Why this is structural rather than a missing feature:** at startup the engine allocates memory on each
RPC device for the tensors it will hold and builds a fixed compute graph across them. Re-assigning layers
means re-allocating and rebuilding. It is not a flag that was left out; it is the shape of the design.

**What follows from it.** Any description of layers moving between phones *while an answer streams* is not
achievable on this stack. What is achievable is re-planning **between** tasks. For chat that is intrusive.
For an autonomous agent working in multi-minute tasks, a 70-second re-stage between tasks is cheap — which
is another reason the agent workload and this architecture suit each other.

### 8.4 Why per-layer precision is harder here than the proposal assumes

Two independent reasons, either of which is sufficient:

**Storage.** The layers are Q4_K_M tensors from one model file. A different precision on a different device
needs a re-quantised copy of those tensors, and good re-quantisation wants the original 16-bit weights as
input. Holding variants means multiples of 18.56 GB on every phone.

**It would undermine the quality evidence.** The 15/15 and identical-failure results were measured at
**uniform** precision. Mixed precision is not numerical noise — it is a *different model per device*. Layer
sensitivity to quantization varies by orders of magnitude and early-layer error compounds forward. The
claim of quality-neutrality would become unmeasured at exactly the point it was being relied upon.

And per [6.4](#64), LLM-BQ already owns precision-inside-placement-search, so it was never going to be the
novel part.

### 8.5 One architectural detail that makes the quality question subtler

Qwen3-Coder-30B-A3B is a **Mixture-of-Experts** model: `Qwen3MoeForCausalLM`, **128 experts, 8 active per
token** (verified from Qwen's official configuration). Rather than using all its weights for every token,
it routes each token to a small subset of specialists.

**Why this complicates things in principle:** routing decisions are close calls. A gate-score perturbation
of around 0.001 near the selection threshold changes *which expert runs*, and that divergence compounds
across layers. So numerical differences in a MoE model do not stay numerical — they become different
answers.

**Why it turned out not to matter in practice:** the 30B scored **15/15**. A perfect score cannot be
degraded, and the test ran the generated code rather than judging it. Whatever routing does under
splitting, every problem was solved. This is also why running the model's correctness through an external
test suite is more informative than any internal quality metric would be.

---

<a name="9"></a>
## 9. The business model

### 9.1 The two different businesses being discussed

These were repeatedly conflated and they are not the same company:

- **The proposal document's business:** software that sits on a customer's existing infrastructure, finds
  cheaper ways to run their inference, and takes **10% of the savings**. A *software* business sold to
  people who already own infrastructure.
- **The business that won the city round:** collect old and idle phones, rack them, pool their memory with
  MeshAI, and **rent that memory to developers who want to run open-source models**. A *supply* business.

Different buyers, different economics, different risks.

### 9.2 Why the 10%-of-savings model does not close

Five figures, each independently fatal.

**$1.40.** The full monthly commodity-API bill for the workload the document itself specifies (50M input +
10M output tokens on an 8B-class model, at $0.02/$0.04 per million). Ten percent of saving *all* of it is
**fourteen cents a month**.

**2.49 billion output tokens per month.** The volume needed merely to keep one cheap cloud GPU busy, below
which self-hosting loses to just buying tokens. That is 249× the document's workload. Even at that volume
the entire savings pool is **$49.81/month** and the fee **$4.98**.

**$830 against $651.** In the better 30B case at 1.5 billion output tokens/month: self-hosting on spot
instances costs $415/month against $651 on an API — a 36% saving. But spot instances get reclaimed, so a
real deployment needs a spare. With that spare the cost becomes **$830 — more than the API.** The
optimiser's honest recommendation becomes *"delete the infrastructure you hired us to optimise,"* and there
is no savings stream in that answer.

**5.3 years.** Payback on one customer's 2–4 week shadow-mode onboarding (roughly $1,490 of engineer time
at Indian rates) against $283/year of fee revenue. The **minimum viable customer at a 10% fee is around 17
continuously-busy A100s — roughly a billion self-hosted output tokens a day.** Any Indian company that
size already employs an infrastructure team.

**~$13.5M/year.** The entire *global* revenue pool for this fee model across all vendors, via a labelled
chain: $37B enterprise generative-AI spend (Menlo, Dec 2025) → $1.5B in the "AI infrastructure" bucket →
roughly $900M of that being inference serving (an estimate) → 15% realistically achievable savings → 10%
fee. India's slice is a few hundred thousand dollars a year.

**And one structural finding:** **not one vendor anywhere charges a percentage of verified savings on
inference.** Everyone who genuinely optimises the serving runtime monetises by **selling compute** —
NVIDIA Dynamo, vLLM, SGLang and KAI Scheduler are free; Red Hat's AI Inference Server is *"priced per
accelerator"*; Baseten and Fireworks charge per token plus per GPU-hour. Gainshare in cloud cost management
exists but is confined to *commitment* arbitrage: Usage.ai at 20%/35%, Zesty at 25% + $250/month (both
primary-sourced from AWS Marketplace metering dimensions, which literally encode the share as "$0.25 per
dollar of savings"). ProsperOps uses the model but does not disclose the rate; comparables suggest 20–35%.

Adjacent gainshare industries run **20–50% for a fixed 12–60 month term; nobody charges in perpetuity.**
10% sits at the floor — the rate paid by the most sophisticated institutional buyers (Medicare recovery
audit at 9–12.5%). The one company that priced at exactly 10%, Antimetal, no longer publishes it and has
pivoted away from cost gainshare.

Three contract-level problems compound this: **inference prices deflate roughly an order of magnitude a
year**, so a savings baseline collapses on its own and the customer ends up being invoiced for market
deflation; the measurement denominator used in practice is *list price*, which cannot attribute savings to
the vendor at all; and free native tooling keeps absorbing the overlap.

*A correction worth recording: an earlier claim here that Cast AI charges on cluster savings was false.
Cast AI charges **$0.00694444 per managed-vCPU-hour** (~$5.07/vCPU/month) plus a monthly tier — resources
under management, not savings.*

### 9.3 Why the phone-rental model does not close either — and the reason is surprising

**The break-even rule.** Per million output tokens, with `E` = energy per token (J), `p` = electricity price
at the wall, `C` = asset replacement cost, `N` = life in battery cycles, `B` = battery energy (Wh),
`r` = owner reward per device-hour, `T` = *sustained* throughput, `G` = cloud spot price per hour,
`Q` = cloud throughput:

> include the phone only if **(E/3.6)·[ p + (1000/B)·(C/N) ] + 277.78·(r/T) < 277.78·(G/Q)**

**Evaluated in the most generous honest configuration** — E = 0.65 J/token, ₹6.52/kWh, a 19.25 Wh battery,
1,000 cycles of life, **C = $31 (a broken salvage phone)**, r = $0.00184/hour (Acurast's actual rate),
**T = 5.29 tok/s (the measured sustained rate, not the peak)**, against a cloud A10G at $0.4093/hour spot
and 947 tok/s:

| Term | $/M output tokens |
|---|---|
| Electricity | **0.0152** |
| Device amortisation — **9.38 full battery cycles per million tokens** | **0.291** |
| Owner reward | **0.0966** |
| **Phone total** | **0.403** |
| **Cloud spot** | **0.1201** |

**The phone loses by 3.4× at its absolute best**, and by 14–82× with a phone anyone would miss. Solving for
the device cost at which it would win gives **C < $0.885.** There is no such phone.

**The surprising part, and the most important correction to the whole thesis: electricity is never the
binding term — it is 1.5 cents per million tokens.** The phone does not lose on power. It loses on **the
phone**. Running a model drains a battery, and per million tokens that is 9.4 full charge cycles out of a
life of maybe 1,000. Each battery is worth roughly 100 million tokens and then it is finished.

**Five independent confirmations that this is robust rather than an artefact of chosen inputs:**

1. **Heat caps a phone at 5–10 queries per hour** before throttling dominates — an independent study
   measured iPhone 16 Pro at −44.1% and a Galaxy S24 Ultra benchmark *terminating* when Android enforced a
   231 MHz GPU floor at 78.3 °C, while a laptop GPU decayed only −7.4%. The measured −54% here is squarely
   in family. At ~500 tokens per query that is 2,500–5,000 tokens/hour per phone against **3.4 million
   tokens/hour from one cloud A10 — so one A10 is worth roughly 680–1,360 phones.**
2. **A hardware-instrumented peer-reviewed study reached the same conclusion from carbon accounting.**
   *The Battery Price of edge AI*, [arXiv 2609.11940](https://arxiv.org/abs/2609.11940): *"on-device
   inference is on average **3 times less energy-efficient** than batched server inference"*; 88–90% of
   local impact is embodied manufacturing rather than electricity; and the decisive line — *"the break-even
   endurance lies between 10⁵ and 10⁶ charge cycles, two to three orders of magnitude above realistic
   ratings (500–2000)… above the world-average grid intensity, no finite endurance suffices."*
3. **The phone is anti-synergistic with batching**, which is where cloud cheapness comes from. The $0.12
   figure assumes 64 concurrent requests on one GPU; a phone serves about one. Every request moved to a
   phone is removed from the batch, *raising* the per-token cost of the remaining cloud work.
4. **The network failure already measured here is the category's documented failure.** Petals reaches about
   6 tok/s on a 70B model with churn and bandwidth as structural limits. A researcher could not get a
   **0.5B** model running on Acurast across 8 mainnet and 2 canary deployments.
5. **The reward market is far below viability and the networks know it.** Acurast pays **$1.34 per device
   per month, funded entirely by token inflation with no published customer revenue**, and only about 15%
   of its marketed 250,000 phones actually share compute. Community reports include *"I shut down my phone
   farm"* and *"20 phones, 3 of them have a swelled battery"* — a 15% hardware failure rate. By contrast
   SaladCloud pays from a real price list (RTX 3090 at $0.0692/hour) and discloses a 40%/30% take; **a
   Salad 3090-hour is worth 15.6× an Acurast phone-hour.**

**And one trap that closes the last door.** The phone's only tolerable workload is latency-insensitive
batch work — but **batch APIs are already 50% off** at OpenAI, Anthropic, Fireworks and Together. So the
price a phone must beat *halves* precisely in the regime where a phone is usable.

**Where phones genuinely do win, stated fairly:** capital per GB. Roughly **$3.88/GB** for salvage phones
against **$54.30/GB** for DGX Spark memory — about 14× cheaper. They are the cheapest way to *buy* memory
capacity and among the most expensive ways to *produce tokens*. Those two facts are both true, and which
one dominates depends entirely on utilisation. Memory sitting idle costs almost nothing; it is computing
that consumes batteries.

### 9.4 What local hardware does work, if not phones

Normalising everything to **dollars per GB of accelerator memory per hour** (a metric no published analysis
appears to compute):

| What | VRAM | $/GB-hour |
|---|---|---|
| **Owned used RTX 3090, 3 years at 100% duty, Indian power** | 24 | **0.00319** |
| Owned used RTX 3090, **2 years at 50% duty** | 24 | 0.00835 ← advantage gone |
| Azure A100 spot, US East | 80 | 0.00848 |
| Azure A100 spot, Central India | 80 | 0.01188 |
| AWS A10G spot, US East | 24 | 0.01705 |
| Fireworks H100 dedicated | 80 | 0.10000 |

**The legitimate local tier is idle owned consumer graphics cards** — 2.2–2.6× cheaper per GB-hour than the
best verified hyperscaler spot pricing. **Duty cycle decides everything:** at 2 years and 50% utilisation
the advantage vanishes entirely.

**One legal note:** NVIDIA's GeForce driver licence states GeForce software *"is not licensed for datacenter
deployment."* It binds the driver rather than the hardware — fine on an employee's own workstation, which
is exactly a mesh's legitimate case, and not fine in a rack. Vast.ai and Salad operating as peer-to-peer
marketplaces rather than datacentre operators is the market's response to this constraint.

<a name="95"></a>
### 9.5 Who the buyer actually is, and why the motivation is not cost

**The decisive number.** McKinsey (April 2025, n=703): **40% of enterprise leaders prefer hosting on their
own infrastructure**, and 32% regularly use open source at the hosting and inference layer. a16z (March
2024): **over 25% self-host.** But Menlo (December 2025): the bucket closest to self-hosted serving is
**$1.5B of $37B — about 4%** of enterprise generative-AI spend.

> **40% want to self-host; about 4% of the money is there.** That gap is simultaneously the opportunity and
> the risk. A product sold on *cost* competes for the 4%. A product sold on *compliance* addresses the 40%.

**Two headwinds, stated plainly.** Open-weight share of the enterprise market is **shrinking**: Menlo's
consistent series runs ~20% (2023) → 19% (2024) → 13% (mid-2025) → **11% (end-2025)**, with Anthropic 40% +
OpenAI 27% + Google 21% = 88% of enterprise LLM API spend. Buying beat building **76% to 53%** in one year.
And **fine-tuning is becoming less central** (a16z, June 2025) — improved base models have *"made
fine-tuning less critical"* — which removes a main historical reason to self-host.

**Why the motivation is control rather than cost, consistently across every survey.** a16z ranks reasons as
control → customization → cost, noting cost ranked *lower* than expected. McKinsey: those preferring
proprietary cite security, risk and control **72%** of the time, and the top *barrier* to open-source AI is
security and compliance at **56%**. Two quotes from NVIDIA's UK sovereign-AI coverage (June 2026) name it
directly:

> *"Sovereign AI is most impactful at the inference layer"* — Meryem Arik, CEO, Doubleword
> *"Sovereignty is actually now a buying criterion"* — Talfan Evans, CEO, Cursive

> **What follows.** The message that matches the evidence is not *"we save you money"* — that is the
> third-ranked reason. It is *"you cannot legally use the API, so the infrastructure you are required to own
> may as well work properly."*

**Where the capital actually sits, which explains why the gainshare pool is small.** Big-four hyperscaler
*total* capital expenditure was approximately **$357.6B in FY2025** (Amazon $131.8B CY2025, $173.0B in the
twelve months to June 2026; Microsoft $116.0B FY26; Alphabet $80.6B in H1 2026 alone; Meta $69.7B CY2025),
against Menlo's **$18B entire infrastructure layer**. CoreWeave reported Q2 2026 revenue of $2,575M (+112%)
with **$103.7B of contracted backlog**; Nebius grew 454%. Dell's AI-server backlog went **$43B to $95B in
two quarters**. NVIDIA restructured its segment reporting specifically to separate hyperscale from
enterprise AI — itself a signal — though the dollar split is not published.

**Almost all capital going into inference capacity is spent by model and cloud providers, not by enterprises
buying their own hardware. Enterprises rent the output.**

**The one buyer segment that is both growing and unable to leave.** Sovereign and regulated-industry
programmes where on-premises is a *procurement requirement* rather than an economic choice: Korea 260,000
GPUs; the UK's £2B NVIDIA investment; 35 new NVIDIA AI supercomputers across Europe; Saudi/HUMAIN heading
toward 1 GW; Deutsche Telekom up to 10,000 GPUs.

**And India is the strongest version of it.** IndiaAI Mission: *"over $1 billion"* and *"tens of thousands
of NVIDIA GPUs"* — **34,381 GPUs onboarded from 14 empanelled providers, ₹10,371.92 crore** outlay,
subsidised to approximately **₹65/GPU-hour (~$0.674)** and **₹92/GPU-hour (~$0.954)** for H100-class. Yotta
Shakti Cloud is *"powered by over 20,000 NVIDIA Blackwell Ultra GPUs"*, explicitly *"designed to make
advanced AI training and inference affordable and compliant for Indian enterprises and public sector
customers."* L&T is building *"sovereign, gigawatt-scale"* infrastructure. GB200 NVL4 is being manufactured
in India by Netweb.

Those subsidised rates sit **2.5–4× below every Indian commercial provider** (Krutrim $2.21, E2E $2.69,
Yotta $3.64, Neysa $4.39, Tata $5.06) and below most Western on-demand pricing.

> **The shape of the opportunity, then.** Tens of thousands of GPUs exist in India under a policy mandate,
> heavily subsidised, held by organisations that cannot use a commodity API — and with no efficiency layer
> on top of them. For that buyer the question is not *"self-host or not"* but *"is this subsidised fleet
> being wasted."* The waste is documented: an instrumented national HPC cluster showed **59% of compute
> wasted across 122,000 jobs in one month**; one serverless GPU business ran at about 20% aggregate
> utilisation; Flexera reports wasted cloud spend rose 29% in 2026, the first increase in five years.

**And one reason this buyer also changes the pricing question.** On *subsidised* compute the savings
baseline is policy-set and partly fictional, so a percentage of it is **unauditable**. Whereas "₹X per GPU
per month to keep this fleet efficient" is a line item a public-sector procurement office can actually
sign. The same conclusion arrives from the comparables: every vendor doing real runtime optimisation
charges per accelerator or per hour, not per saving.

---

<a name="10"></a>
## 10. Risks

### 10.1 Quality under splitting

The evidence is good — identical failures on the 1.7B, 15/15 on the 30B — but its *scope* needs stating:
measured at **uniform quantization, identical kernels, batch size 1, greedy decoding, with the boundary
tensor not re-quantised.** Change any of those and the result no longer covers the configuration.

Ranked by how likely each is to break it: per-layer mixed precision (a different model, not noise);
MoE routing flips; different backends or kernels per device; the boundary tensor's precision; batch-size
variation under concurrent users; speculative decoding.

**One architectural advantage worth noting:** tensor-parallel systems face an all-reduce ordering hazard
where different elements see different summation orders. Layer-wise splitting avoids it entirely.

### 10.2 Heat as an economic input rather than a safety limit

Throughput halves in ten minutes, so cost per token doubles; sustained hourly output is about 51% of peak;
sustaining peak aggregate throughput would need **2.16× the device count** — a capital multiplier.

Battery wear, stated honestly in both directions: at 42 °C and high charge the device is in the worst
quadrant of the standard reference data (40 °C at 100% charge leaves 65% capacity after a year, against 80%
at 25 °C and **85% at 40 °C with 40% charge**). But in money it is negligible — about ₹0.01 per million
tokens — so the real cost is maintenance labour and swelling or fire risk in dense racks, not rupees per
token. Operating racked devices at **40–60% charge** more than halves annual degradation for free.

<a name="103"></a>
### 10.3 Privacy — why "we only send hidden states" is not a defensible claim

ActInv, *"What Does the Server See? Understanding Privacy Leakage from Large Language Models in Split
Inference"* — [arXiv 2605.23158](https://arxiv.org/abs/2605.23158), accepted to **ACM CCS 2026**. *Verified.*
It reconstructs client inputs from intermediate activations with high fidelity, and explicitly *"even in the
presence of common perturbation-based defenses such as Gaussian noise injection and activation
sparsification."* Separately, embedding inversion recovers **92% of 32-token inputs exactly**
([arXiv 2310.06816](https://arxiv.org/abs/2310.06816)).

> **What it means.** The hidden states crossing the cable can be turned back into the original text, and
> the obvious defences do not work. Since the attack requires a **malicious node** rather than an
> eavesdropper, the mitigation is **node trust**, not obfuscation. Which is exactly the argument for
> physical custody. And the existing design choice of pinning embeddings and the output head to the host
> ([2.4](#24)) limits what a remote device could ever see.

### 10.4 Verification — an unsolved problem, and why custody sidesteps it

Proving that a remote device computed a layer correctly rather than returning plausible noise is not
solved at this scale. The published state of the art proves **GPT-2** — zkGPT does it in under 25 seconds
with a 279× speedup over prior work (USENIX Security 2025). GPT-2 is 0.1B parameters; this is 30B. zkML
circuit expansion runs 10³–10⁴.

The obvious fallback also fails, for a reason that cuts at the heart of the product: BOINC had to invent
**homogeneous redundancy** — partitioning hosts into hardware and software equivalence classes — because
*"two computers may return different but equally valid results"* from floating-point and library
differences. **Heterogeneity and exact-match verification are mutually exclusive**, and heterogeneity is
the product.

TEE attestation does not close it either. Android key attestation and Play Integrity attest *which software
booted*, not that the arithmetic was right, and Android's virtualisation framework does not provide attested
GPU or NPU execution — so confidential compute on a phone is effectively CPU-bound. Acurast's 270,000+
devices across 175+ countries is strong evidence that **phone fleets at scale are operable**, and no
evidence that **computation is verifiable.**

> **What follows.** Custody does not *solve* verifiable computation — it makes it **unnecessary**, replacing
> a cryptographic problem with a contractual one. The customer trusts the operator, which is the ordinary
> trust model every cloud already uses and a solved commercial problem (contracts, audits, processor
> agreements). What custody does *not* remove is silent hardware corruption: Google reported "mercurial
> cores" at a few per several thousand machines, a Meta study reported defect rates on the order of 1 in
> 1,000 chips — and **phones have no error-correcting memory**, strictly worse than those server fleets.
> That residue is addressed by periodic known-answer self-tests, not attestation.

### 10.5 What custody removes, and what it creates

**Removes** (the adversary class "self-interested device owner" ceases to exist): result forgery for
payment; Sybil attacks and spoofed capacity — precisely io.net's **~1.8 million fake GPUs** incident;
Byzantine returns; activation inversion as an external threat; churn as an unpredictable process;
residential ISP terms and carrier-grade NAT reachability; and the entire Indian tax and gig-worker payee
question.

**Creates:** physical safety — 47.5 °C surface temperatures per device, lithium cells at 42 °C in a dense
rack, swelling and fire; insider risk, since the operator becomes the single point of trust for every
customer's prompts; and ordinary data-protection obligations as a processor.

### 10.6 India legal and tax — why custody avoids nearly all of it

Not legal advice. The structural point is that buying or leasing hardware means no payee relationship with
thousands of individuals. The at-home model creates several problems simultaneously:

- **Payment characterisation is genuinely arguable** — rent on plant and machinery (s.194-I, 2%, threshold
  raised to ₹50,000/month by the Finance Act 2025), fees for technical services (s.194J, 2%), contractor
  payments (s.194C), or e-commerce operator withholding (s.194-O). The company carries the risk of choosing
  wrong. At Acurast-like economics almost every payee falls below every threshold, but **s.206AA forces
  higher withholding without a PAN**, so collecting and validating PAN from tens of thousands of
  micro-payees becomes an unmodelled operational cost.
- **GST:** individuals will not cross the ₹20 lakh services threshold, so they will be unregistered, which
  pushes the question to the operator via reverse charge. Anyone liable under reverse charge must register
  *regardless* of threshold.
- **Gig-worker exposure is underrated:** the Code on Social Security 2020 obliges aggregators to contribute
  1–2% of turnover (capped at 5% of amounts paid) to a Social Security Fund, and Rajasthan's 2023 Act is
  already live with a per-transaction cess. Whether a person whose *phone* works counts as a gig worker is
  novel, and that novelty is itself the risk.
- **DPDP Act 2023 and Rules 2025** (notified 13 Nov 2025; security obligations phasing in around 13 May
  2027): Rule 6 requires demonstrable encryption, controlled access, continuous logging and **one-year log
  retention**. "Controlled access to systems" is very hard to evidence for a device in a home that cannot
  be audited — especially given that the activations being sent are invertible to the original text. The
  at-home model looks difficult to defend under Rule 6; custody is straightforwardly defensible.

### 10.7 Consumer-supply precedents

**io.net:** ~1.8 million fake GPUs attempted to connect. **Storj:** cut node payouts three times in 2023.
**SaladCloud:** nodes *"interruptible by default"*, >90% stable, mean availability >99% with a measured
**minimum of 94%** — and a 94% worst-case node across a three-device split implies roughly a 17% chance of
a degraded token stream per unit time under independence. Statefulness makes churn far costlier here than
for Salad's stateless batch work or Storj's erasure-coded storage. **Honeygain and proxyware:** Cisco Talos
documented malware bundling a patched client with a miner and an information stealer; earnings of $1–3 a
month select for bulk and fraudulent operators.

### 10.8 Two levers in the proposal that are wrong on the merits at this scale

**Prefill/decode disaggregation is a ~1,000-GPU problem.** Below that, chunked prefill is the better
default; at 8–16 GPUs the specialisation gains are consumed by incomplete worker utilisation; each request
moves roughly 2.6 GB of cache for a 70B model; and **small or untuned deployments see a 20–30% performance
drop.** vLLM's own documentation notes it does not inherently improve throughput. Chunked prefill gives
about +50% and is free.

**Quantization can move cost in the wrong direction.** The best-documented 2026 example: a 1-bit
quantization on 8×A100 at 2.8× lower hourly cost produced **3.3× higher cost per token** than native
4-bit on newer hardware. Quality was fine; throughput killed it. **Quantization buys *fit*, not necessarily
*cost*** — which is precisely the right framing for a memory-capacity thesis.

---

<a name="11"></a>
## 11. What remains unknown

Honest gaps, since a document that hides them is less useful than one that lists them.

**Not verified:**
- prima.cpp's venue. The arXiv metadata has no journal reference; "ICLR 2026" should not be stated. Helix's
  ASPLOS 2025 *is* confirmed in its arXiv comment; Mélange has no venue in metadata.
- **ThermE** ([arXiv 2610.00267](https://arxiv.org/abs/2610.00267)), reported to contain a *"Fast
  LLM-to-Heat Compiler"* and a learned thermal-headroom predictor. If real, it lands directly on the
  thermal idea. Unverified.
- **ServeTwin** (2610.02732, a benchmark-validated digital twin for distributed serving) and **DySCo**
  (2610.08268, runtime-reconfigurable edge/cloud layer-range splitting without weight reload). The second
  is particularly relevant — "without weight reload" is exactly the constraint described in [8.3](#83).
- **OmniPilot's** negative result on cluster telemetry.
- **iServe** (2501.13111), reported to use the literal term "fingerprint" and to let developers specify
  intents such as minimising latency or cost.
- **SOSP 2026** ran 29 September – 2 October 2026 and its proceedings were not audited.
- No patent search was performed at all. The proposal document sensibly disclaims patentability.

**Numbers that could not be sourced:**
- The split between API spend and self-hosted inference spend. **No published estimate exists.** Any figure
  must be constructed and labelled as an estimate.
- A verified enterprise GPU-utilisation figure. The widely circulated "60% versus 85% utilised fleet" line
  is vendor illustration in a blog post, not survey data.
- A quantified inference-versus-training spend split. Not found by any of seven research agents.
- Official battery-replacement pricing from Samsung, Xiaomi or OnePlus India (only OnePlus's ₹2,599
  replacement plan was verifiable — any "₹1,500–5,000" range is unsourced).
- Measured sustained inference wattage for a 3090 or 4090.
- NVIDIA's Hyperscale versus enterprise dollar split — the one figure that would settle the
  enterprise-versus-hyperscaler question.
- Zero FinOps Foundation data was obtained (403 on every path).
- The Menlo 2026 enterprise edition is **not yet published** — due November or December 2026, and it will be
  the first reading on open-weight share after Llama's discontinuation as an open-weight line.

**Sources explicitly not to cite:** the dev.to "I went back to APIs" posts (an SEO cluster), nordiccrypto's
Acurast earnings table (affiliate-incentivised, and its own author concedes the in-app leaderboard
overstates by roughly 20×), Red Hat's "24× throughput" claim (footnotes to a June 2023 post) and its "233%
ROI" (a commissioned Forrester study), vLLM's PyPI download counts as a growth metric (they fell 30%
May–August 2026 while every comparable package rose), and the "$20 → $0.07 per million tokens" inference-cost
figure (attributed to Stanford's AI Index everywhere but not found on the chapter page).

**No published postmortem exists from a named company that shut down a GPU cluster and returned to APIs.**

---

<a name="12"></a>
## 12. Questions worth asking

Framed as what is genuinely undecided or unknown, rather than as tasks.

**On the technical claim**
- Whether choosing a backend per phone changes anything measurable. The two phones could be timed on Vulkan
  and on CPU to see whether they even disagree. If they do not, the entire per-device tuning idea is
  decorative; if they do, it is the only live part of the open gap.
- Whether DySCo's "without weight reload" means it has already solved the mid-run reconfiguration problem
  described in [8.3](#83). If so, that closes the last open row in the ledger.
- Whether ThermE already occupies the thermal-planning idea.
- What the 15 benchmark problems actually test, and whether 15/15 means the benchmark is too easy rather
  than the model being excellent. A perfect score is as much a statement about the test as about the system.

**On the architecture**
- Whether the content-addressed cache ([3.4](#34)) and per-device packages are genuinely incompatible, or
  whether a manifest layer could sit above the cache — giving each device its own configuration while every
  device still holds every layer. These may be orthogonal rather than opposed.
- What happens with four or five devices rather than three. Every measurement is at three.
- Whether the 74% figure can go higher — what the limit is on how little the laptop can hold.

**On the business**
- Whether the subsidised IndiaAI fleet is actually accessible to a small team, and on what procurement
  terms. The published rates come from government statements via secondary press, not a rate card.
- Whether anyone is already selling efficiency services on that fleet.
- What a regulated Indian buyer would actually pay per accelerator per month, which no comparable in the
  research answers.
- Whether the phone thesis survives being restated as capacity rather than throughput — renting memory to
  someone who wants a large model to *fit* occasionally, where idle memory costs almost nothing and only
  computing burns batteries. The arithmetic in [9.3](#93) measures cost per token, which is the wrong metric
  for that framing, and the right one has not been worked out.
- Whether the LPDDR5x supply constraint driving DGX Spark's price from $3,999 to $6,950 is a durable
  tailwind for reusing existing memory, or a temporary distortion.

**On the framing**
- Whether a measured result can serve as the contribution when no mechanism is new. The 15/15 and the
  identical-failure result are evidence of a kind the literature does not contain, but "we measured
  something nobody measures" is a weaker-sounding claim than "we built something nobody built," even when
  it is more defensible.
- Whether naming Cascadia, Voltron and HybridInfer directly is better than hoping they do not come up.
  A team that names the closest prior work and states its delta looks like it knows the field.
- Whether the right unit of novelty here is the system at all, rather than the *combination* of a working
  phone mesh, a real autonomous agent, and measured task correctness — none of which is individually new,
  and which no published work puts together.

---

<a name="13"></a>
## 13. The browser question, and the project that already answered it

### 13.1 What WebAssembly is

WebAssembly (WASM) is a compiled binary format that runs inside a browser at close to native speed, built
from C, C++ or Rust. The appeal for a project like this is obvious: **one binary runs on every operating
system that has a browser**, which would replace a Rust agent for desktops plus a Kotlin app for Android
plus whatever iOS would need, with a single URL.

Alongside it sits **WebGPU**, which gives a browser page access to the machine's graphics processor —
without which browser inference would be far too slow to matter.

### 13.2 A ceiling that moved, which is worth recording because it goes stale

The standard objection to browser inference has always been memory. 32-bit WebAssembly can address at most
**4 GB**, which would make holding a 6–7 GB slice of a model impossible.

**That ceiling is gone.** [WebAssembly 3.0](https://byteiota.com/webassembly-30-spec-release/) was finalised
on 13 June 2026 and standardised **Memory64**, which shipped in **Chrome 133 and Firefox 134**. Browsers now
set their own caps, typically around **16 GB per tab**. Photoshop Web and AutoCAD Web had both been pressing
against the old 4 GB limit; they are no longer.

> **Why this is worth writing down.** The 4 GB figure is still repeated everywhere and was, until recently,
> correct. Anyone evaluating browser inference from material written before 2026 will reach the wrong
> conclusion about whether it is feasible at all.

### 13.3 Pooled — the closest system to MeshAI found anywhere in this research

[github.com/Nehanth/pooled](https://github.com/Nehanth/pooled), served at `pooled.run`, by Nehanth
Narendrula. Previously named SwarmLLM. *Read directly from the repository; an MIT affiliation was mentioned
in conversation but **no institutional affiliation appears in the project itself**, so it should be treated
as unconfirmed.*

**What it does**, in its own description: peer-to-peer LLM inference in the browser. Friends open a shared
link; a laptop, a desktop and a phone each hold some of the model's layers and together run a model none of
them could run alone.

| Dimension | Pooled | MeshAI |
|---|---|---|
| How a device joins | **Opens a URL.** No install, any OS | Rust binary (Linux) or Android app |
| Compute backend | **WebGPU, custom WGSL kernels** written by the author | llama.cpp / ggml, native |
| Transport between devices | **WebRTC direct connections**, falling back to a TCP/TLS relay on port 443 | raw TCP — `ggml-rpc` over USB tethering |
| What crosses per token | *"small hidden states (4–10 KB per token)"* | 4 KB (derived: 2048 × 2 bytes) |
| Which layers a device holds | **Only its own**, downloaded from Hugging Face *or from another device in the room that already has them*, then cached | **Every** layer cached on every phone (17.6 GB) |
| Models | Qwen 3.8 27B (~17 GB, Q4_0, 16K ctx); Qwen 3.6 35B MoE (~22.5 GB, Q4_0, 32K ctx); Qwen3 1.7B | Qwen3-Coder-30B-A3B (18.56 GB, Q4_K_M, 4K/16K ctx) |
| Coding agent | **Yes** — "Code mode": writes files, serves them on a virtual localhost, *"reads its own console errors, and fixes them"* | Aider through a task runner; **tests run by the service, not the model** |
| Speculative decoding | Yes | No |
| Reported speed | Single NVIDIA **GB10**: 35B MoE at 40–46 tok/s plain, 65–84 with speculation. **Two devices at 20 ms latency: 13 / 21 tok/s.** 27B: 10.1–10.8 plain, 20–27 speculative | **Three devices: 6.8 tok/s** on a 30B, over USB at 2–3 ms |
| Reported accuracy | None | **15/15** on an executed coding benchmark |

**Its own stated limitations**, quoted, because they are instructive:

> *"Safari on a Mac reloads the tab under memory pressure when it holds most of the 27B"*
> Prefill is slow: first token in ~1.0–2.5 s against 0.07–0.5 s natively
> *"hidden states are not private against a determined peer"*

### 13.4 What Pooled settles, and what it does not

**It settles the transport question.** The blocker for browser-based meshing was that a browser cannot open
a raw TCP socket, and llama.cpp's RPC backend speaks nothing else. Pooled's answer was not to work around
it but to **not use llama.cpp at all** — it writes its own WebGPU kernels and moves hidden states over
WebRTC data channels. That is a complete reimplementation of the compute and transport layers, which is why
nobody had done it before, and it works.

**It confirms the design convergence.** Two projects, independently, arrived at: split by layer, keep weights
local, send only the hidden state, one address for the user. The hidden-state size agrees (4 KB against
their 4–10 KB across different model widths). Convergent design by independent teams is reasonably strong
evidence that the architecture is the right one — it is just no longer distinctive.

**It takes the browser idea outright.** Zero-install, any-OS joining is built and shipped. Anyone proposing
it now is proposing something that exists.

**It also takes the per-device-layers idea**, which is the more interesting loss. Pooled's devices download
*only their own layers*, optionally from a peer in the room that already holds them. That is materially
closer to a per-device package than MeshAI's cache-everything design — and it means the open gap in
[§7.1](#71) is being approached from the browser side rather than the native side.

**What it does not settle:**

- **Phones as real participants.** Its headline numbers come from an **NVIDIA GB10** — the chip inside DGX
  Spark, a $4,000–7,000 machine — and from desktops. Its only phone-relevant note is Safari *reloading the
  tab under memory pressure*, which is the failure mode described in [§13.5](#135). A phone in a browser tab
  is not a durable mesh member, and Pooled's own documentation says so in passing.
- **Task correctness.** It reports tokens per second, like every other system in [§6](#6). No accuracy
  figure. The gap identified in [§7.4](#74) is untouched.
- **Verified agent outcomes.** Its agent *"reads its own console errors, and fixes them"* — self-assessed.
  MeshAI's service runs the project's own test command and reports what pytest says
  ([§3.7](#37)). That difference is the whole distance between a demonstration and evidence.
- **Hidden-state privacy.** It concedes hidden states are *"not private against a determined peer"*, which
  is precisely what the ActInv work in [§10.3](#103) establishes. MeshAI's pinning of embeddings and the
  output head to the host ([§2.4](#24)) is a partial structural answer that Pooled does not appear to have.
- **Bad networks.** Its two-device figure assumes **20 ms** latency. The measurement in [§4.3](#43) — first
  word in 1.1 s on a 2–3 ms cable against 94 s and a dropped connection on 373 ms venue Wi-Fi — suggests
  what happens outside that assumption.

<a name="135"></a>
### 13.5 Why the browser works for laptops and not for phones

The division is not browser-versus-native. It is **desktop-versus-mobile**, and the reason is operating
system policy rather than anything about WebAssembly.

**Phones suspend background tabs.** The Android app uses a foreground service of type `connectedDevice`
specifically to survive — a deliberate choice, because Android 15 caps the more obvious service type at six
hours. A browser tab has no equivalent anywhere. Switch apps and the tab is throttled or discarded; on iOS,
sooner. A phone holding 7 GB of layers that disappears when its owner opens a messaging app is not a mesh
member, and Pooled's note about Safari reloading under memory pressure is the same phenomenon.

**Phones also have harsher graphics limits.** Safari caps GPU buffers at roughly **256 MB on older iPhones
and 993 MB on an iPad Pro**. The practical ceiling for browser inference is reported as
[7–8B parameters at 4-bit, with 1–3B the reliable range](https://tianpan.co/blog/2026-04-17-browser-native-llm-inference-webgpu)
— against the 6–7 GB slices a phone carries here.

**And WASM gives up the instructions that matter.** It has 128-bit SIMD, but no access to the ARM
instructions that dominate quantized inference (`dotprod`, `i8mm`) and no path to a neural processing unit.

**None of this applies to a laptop or desktop.** A browser window on a machine that is awake and plugged in
is not suspended, has no 993 MB buffer cap, and has memory to spare.

### 13.6 Where it genuinely helps here

**The useful shape is a browser page for desktops only**, which is exactly the division that holds up
technically:

- **Laptops, desktops, Windows, macOS, Linux — a URL.** Today a Windows or Mac owner who wants to lend
  memory has nothing to run; the agent is a Linux binary. A browser page removes that entirely and removes
  the install objection with it. For a demo in a room full of strangers' laptops, that is the difference
  between participation and none.
- **Phones keep the native app**, because only a native app survives backgrounding, reaches the NPU, and
  holds a multi-gigabyte local cache without eviction.

**And one thing already true that is easy to overlook:** the MeshAI dashboard is *already* a web page. The
"see and control everything from one place, whatever the machine" half is solved. The open question was only
ever whether the **workers** could be browser-based — and the honest answer is: desktops yes, phones no.

**A cheaper route to the same desktop coverage**, worth weighing against a WASM port: the existing Rust
agent cross-compiles to Windows and macOS with little work, giving one codebase, native speed, raw sockets
and no browser limits. That solves operating-system diversity more directly than a reimplementation does.
A browser page's advantage over it is not capability but **the absence of a download** — which matters for
strangers and matters not at all for one's own machines.

### 13.7 What remains distinctive after Pooled

Stated plainly, because Pooled removes several things that previously looked distinctive:

| Previously thought distinctive | Status after Pooled |
|---|---|
| Splitting a large model across devices in a room | **Also theirs** |
| One address, any client | **Also theirs** |
| Only a small hidden state crosses per token | **Also theirs**, independently derived |
| Zero-install, any-OS joining | **Theirs, not ours** — built and shipped |
| Each device holding only its own layers | **Theirs** — and closer to the open gap than our design |
| A coding agent running on the mesh | **Also theirs**, though self-assessed rather than externally tested |
| **Phones carrying the majority of the model, in an app that survives backgrounding** | **Still ours.** Their phone is a browser tab that reloads under pressure; their numbers come from a GB10 |
| **Measured task correctness — 15/15, executed, no human judgement** | **Still ours.** Nobody in this literature reports accuracy at all |
| **Agent outcomes verified by an external test runner rather than the model** | **Still ours** |
| **A planner that refuses a device on link latency, battery or memory, with stated reasons** | **Still ours** — Pooled appears to assume a good network (20 ms) |
