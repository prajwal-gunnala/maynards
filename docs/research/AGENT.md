# Autonomous agents on the mesh — already working, and the right focus for the finale

Checked 7 Oct 2026 against `~/maynards`. **This is not a new feature to build. It is built, measured,
and has a safety model. It is the most under-used asset in the whole project.**

## 1. It already runs. Here is the evidence.

Three recorded runs of **Aider** — a real autonomous coding agent — fixing a genuine bug
(`parse_duration` in `~/meshai-aider-demo`, 3 of 5 tests failing), editing code, committing, and
passing tests. All in `docs/measurements.md:23-25`.

| Run | Setup | Model | Decode | Prefill | Result |
|---|---|---|---|---|---|
| 27 Sep 06:10 (line 23) | **laptop alone** | Qwen3-**8B** | **2.5 tok/s** | 9.6 tok/s on a 2,757-token prompt | one diff, committed, **pytest 5 passed**. First word **287 s**, total 459 s |
| 27 Sep 06:41 (line 24) | **laptop + 2 phones** | Qwen3-Coder-**30B** | **5.4 tok/s** | **21.1 tok/s** | two model rounds, **commit 4a5e308**, pytest 5 passed. 603 s (~2 min was chat-history summarising, since turned off) |
| 27 Sep 08:18 (line 25) | **laptop + 2 phones**, new phone app, split auto-started in 72 s, launched by pressing **Send** | Qwen3-Coder-**30B** | **4.4–5.1 tok/s** | 19.1 tok/s on a 2,143-token prompt | edit, **commit fa1a81c**, pytest 5 passed. **7 min 34 s end to end** |

**The comparison that matters: the mesh runs a 30B model roughly 2× faster than the laptop alone runs
an 8B.** Faster on decode (4.4–5.4 vs 2.5) *and* faster on prefill (19–21 vs 9.6), while running a
model nearly four times larger. First word went from **287 s to ~20 s**.

That is not "distributed inference is slower but it fits." That is a strictly better machine for this
workload.

## 2. The safety model is real, and it is a judge-proof answer

From `desktop/service.py` — per-task permission toggles, and the key design decision:

- **Tests are run by us, not by the model** (`run_tests()`, line 132), so the pass/fail count comes
  from pytest itself and not from what the model claims. That one choice defeats the obvious
  objection: "how do you know the agent didn't just say it worked?"
- Per-task toggles for **edit / commit / tests / shell** (lines 77–84). Defaults: edit, commit and
  tests on, **shell off** — enforced with `--no-suggest-shell-commands`, because with `--yes-always`
  a suggested command would otherwise execute.
- `--no-auto-commits --no-dirty-commits` when commit permission is withheld, so changes stay in the
  working tree.
- **Every change is a git commit, and Undo is a `git revert` of it** (`undo()`, line 233). Fully
  reversible autonomy.
- Streamed events — text, edit, commit, tokens, tests_start, tests — so the whole run is observable
  live rather than a black box.

Also present: `mesh ask | review | tests | diff | hook | route`, so the agent surface exists on the
CLI too, including a git hook.

## 3. Why agents are the *right* workload for this architecture — five reasons that reinforce each other

This is the strategic point. Agents are not just another use case; they are the workload where every
property of the mesh turns from a liability into an advantage.

1. **Agents are memory-bound, which is exactly what pooling fixes.** An agent sends its instructions
   plus the files it is editing — our runs used **16,384-token context** against 4,096 for chat. The KV
   cache is costed per device per layer (`host.rs:314-316`), so longer context directly consumes pooled
   memory. The thing agents need most is the thing we uniquely supply.
2. **Agents are latency-tolerant, which forgives our one real weakness.** A task runs for minutes;
   nobody watches each word appear. 5 tok/s is perfectly usable for a 7-minute autonomous task and
   would be unacceptable for chat. **Our throughput problem disappears in this workload.**
3. **Agents run long enough that thermals actually bite** — our tasks took 6–10 minutes, and the
   measured decay (11.44 → 5.29 tok/s over ~10 min) lands squarely inside that window. This makes the
   thermal re-issue feature in `BUILD.md` *necessary* rather than decorative: it is the difference
   between a task that finishes and one that crawls. The research and the build plan converge here.
4. **Agents touch private code, so local execution has real value** — not a privacy slogan but the
   actual reason to not send a repository to an API.
5. **It is the exact workload the viable buyer wants.** `FINDINGS.md` §2.7 concluded the only buyer
   that cannot defect to an API is the regulated/sovereign one — Indian BFSI under RBI localisation,
   government, defence, healthcare. **What those organisations want is an autonomous agent on their
   private codebase that is legally not allowed to leave the building.** The use case and the buyer are
   the same finding arrived at from two directions.

## 4. What to build for the finale

The demo writes itself, and nearly all of it exists:

1. Press **Send** in the desktop app. Split auto-starts in ~72 s (measured).
2. The 30B runs across laptop + two phones, **phones holding 74% of the model**.
3. The agent reads the failing tests, edits the code, commits, and **we** run pytest — 5 passed.
4. Mid-task, one phone heats. The panel says so, quantified — "about 54% less speed from it" — and the
   next task is planned with that phone holding fewer layers. **The split cannot change while the engine
   runs** (llama-server fixes it at launch via `--rpc`/`-ngl`/`--tensor-split`), so re-planning takes
   effect at the next start, ~70-87 s. For an agent working in multi-minute tasks that is cheap, and it
   is the honest version of the claim.
5. Show the git log: a real commit hash, reversible with one button.

Only step 4 is new, and it is now built — see §6. Everything else has run end to end three times.

**The honest headline:** *an autonomous coding agent, running a 30B model on a laptop and two phones,
fixing a real bug and passing real tests, with no internet — about twice as fast as the same laptop
running a model a quarter of the size.*

## 5. Two notes

- **The quality claim is now optional.** The user has confirmed the quality-neutrality claim can be
  dropped. That removes the MoE exposure in `FINDINGS.md` §5.1 entirely — we no longer need the 30B
  exam to defend a claim we are not making. **Pass/fail on real tests replaces it**, and is better
  evidence: pytest does not care about expert routing. If the agent's commit passes the project's own
  test suite, the model worked, and that is verified by the test runner rather than asserted.
- **One gap to close honestly:** all three agent runs used a **pinned** split
  (`~/.config/meshai/pinned.json`), not the planner's own. For a demo about adaptive planning, either
  run the planner live or say plainly that the split was pinned for reproducibility.


## 6. Built 7 Oct 2026 — thermals in the planner

`agent/src/host.rs`, building and tested on this laptop with no phones needed.

- **`Dev` carries `heat`** — Android's `getThermalHeadroom` (0 cool, 1 about to throttle, -1 unknown),
  which the phones were already sending and the planner was already discarding.
- **`heat_factor(heat)`** — the speed we expect, calibrated on our own measured curve: 11.44 -> 5.29
  tok/s is 0.46 of peak, so a device at the throttling edge is worth a little under half a cool one.
- **`heat_capacity(heat)`** — the planning proxy, deliberately gentler: at most a quarter off, only past
  halfway. Heat costs throughput, not RAM, but in a layer pipeline a slow device holding many layers
  sets the pace for everyone, so giving it fewer is the right response and shaving capacity is the
  smallest change that produces it.
- **`plan_warm()`** — tries the heat-aware plan, and **falls back to the heat-blind one if the model
  would no longer fit**, recording which it used in a `thermal` field. A mesh that runs slowly beats one
  that refuses to start.
- **Slices now carry `heat` and `speed`**, so the panel can show why a device holds fewer layers.
- **Advice is quantified and plan-aware**: a hot device already holding layers is told the expected loss
  and that re-planning applies at the next start; a hot device not yet in a plan is told it will be given
  fewer layers.

**Measured effect** (6 tests, `cargo test`, all passing). Two identical 8 GB phones, one cool and one at
the throttling edge, 48 layers of 0.35 GB:

| | laptop | cool phone | hot phone |
|---|---|---|---|
| heat-blind (before) | 4 | 22 | **22** |
| heat-aware (now) | 10 | 22 | **16** |

Six layers moved off the hot phone. The tests also pin the two properties that matter: a cool mesh is
planned **byte-identically** to before, and a tight fit falls back rather than refusing to run.
