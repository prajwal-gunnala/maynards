# Research working notes

These are the working files behind [`../UNDERSTANDING.md`](../UNDERSTANDING.md), kept so the sources and
the reasoning can be checked rather than taken on trust.

**`UNDERSTANDING.md` supersedes everything here wherever the two disagree.** It was written last, after all
of these, and several of them record conclusions that later evidence changed. Those changes are recorded in
place rather than edited away, because how a conclusion moved is often more useful than the conclusion.

| File | What it holds |
|---|---|
| `FINDINGS.md` | The full research output by stream: risk and trust, the economics with every figure URL-dated, the growth-side evidence, and the spend-composition data |
| `POSITIONING.md` | The prior-art landscape, the claims that had to be retired, and how the novelty statement narrowed — including the Voltron correction and the retraction of the cloud-price axis |
| `CLAIMS-LEDGER.md` | Every number we might say out loud, marked `measured` / `derived` / `cited` / `estimate`, with its file and line or its URL |
| `DEMO.md` | The planner mapped against the brief's proposal, and the five-way 30B run discrepancy resolved |
| `BUILD.md` | What the product is, and what joining the plan to per-device packages would actually require |
| `AGENT.md` | The autonomous-agent runs, the permission model, and why agents suit this architecture |

## Known corrections, so nothing here misleads

- **`POSITIONING.md`** was written before Voltron ([arXiv 2607.07046](https://arxiv.org/abs/2607.07046))
  and Pooled (`pooled.run`) were found, and its main novelty sentence leans on live cloud price as an
  unoccupied signal. **That was wrong** — ShuntServe and HybridFlow both do it. Addendum 2 at the end of
  that file carries the corrections and the revised claim.
- **`CLAIMS-LEDGER.md`** originally recorded the quality claim as mis-scoped, because it had been measured
  on a dense 1.7B while the flagship model is Mixture-of-Experts. That has since been measured directly —
  the 30B scores 15/15 — and the row is updated. A second row was added noting that a perfect score is a
  *ceiling* result and the durable finding is the identical failure set.
- **`AGENT.md` and `BUILD.md`** originally described layers moving between devices while an answer streams.
  **That is not possible** on this stack: `llama-server` fixes the split at launch, so changing it means a
  70–87 s restart. Both files are corrected in place.
- **Not done at all:** the patent sweep, because the session's web-search budget ran out. Anything about
  patentability is unexamined.

## Reading these honestly

Figures are labelled. Where a number is an estimate it says so; where a source could not be reached it
says so; and `FINDINGS.md` §2.6 and §2.8 list sources that should **not** be cited, including several that
circulate widely and do not survive checking.
