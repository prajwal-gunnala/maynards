# MeshAI (event build)

Plan: `/home/prajwal/.claude/plans/so-i-want-you-floofy-snowflake.md`. Build step by step, one small commit per step,
and show proof (a run, a test, a screenshot) before each commit.

- All code is written during the event. `../MeshAI-IQOO` is reference only: read it for facts, never copy code.
- Every number in `docs/measurements.md` is measured, with model, quant, context, link and layers. Otherwise it is
  marked as an estimate.
- Never split a model that fits on one device.
- UI: short labels, no long descriptions.
- Out of scope: the Oracle mirror, image generation, NPU/GPU backends.
- Two agents work on this repo. Every commit has a subject line plus a body another agent can act on without
  asking: What changed (files, functions, messages), Why, what stays Unchanged, and how it was Checked (the
  measured numbers or test names). After a pull, read the new commit bodies before changing the same files.
