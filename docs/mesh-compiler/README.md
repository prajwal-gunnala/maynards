# Mesh Compiler docs (copied from github.com/prajwal-gunnala/mesh-compiler at e21545b)

The Mesh Compiler is the separate project that tunes each device for the task it runs repeatedly, on top of
MeshAI. Code, the results database and the tuner live in that repository; these docs are copied here so the
team can read them without leaving this one.

- `00-overview.md`: the idea (specialise the AI to a repeated task), the layers, what was achieved on the 4B
- `01-findings.md`: engine facts, research (incl. repeated-task work), Pooled, security, analyzer cost
- `02-implementation.md`: what is built and whether each piece was tested
- `03-results.md`: every run on the iQOO 15, generated from the database, with the run cards in `screenshots/`
- `04-task-compiler-research.md`: research brief and build order for the task compiler
