# Origami Lab implementation status

The product scope is governed by [`final plan.md`](final%20plan.md). This file tracks delivery, not product
requirements.

## V0.1 checklist

- [x] Independent Kotlin/Compose Android app and standalone Gradle build.
- [x] Parametric screwless organizer with 1–4 compartments.
- [x] Tab-slot part geometry and joint records shared by the viewer and exports.
- [x] Touch rotation, selectable parts, flat-to-assembled slider, and explode/assemble controls.
- [x] 600 × 400, 600 × 600, and 900 × 600 stock sheets; simple shelf packing with 90° rotation.
- [x] 1:1 SVG cutting pattern and Project JSON via the Android document picker.
- [x] Unit coverage for model generation, validation, layout boundaries, and export structure.
- [ ] Physical test cut and assembly: requires a laser/cutter and suitable sheet stock.
- [ ] Device walkthrough: attempt after the emulator can start; the current host filesystem has only about
  2 GB free and the installed AVDs currently refuse to boot for lack of disk space.

## After V0.1

Do not expand into tutorials, traditional crease patterns, FOLD, physics, irregular nesting, inventory,
camera measurement, AI, or MeshAI until the organizer geometry has been test-cut and the core demo has been
reviewed against [`final plan.md`](final%20plan.md).
