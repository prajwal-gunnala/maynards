# Origami Lab

Standalone Kotlin Android app for origami-inspired computational fabrication. Enter dimensions and sheet
thickness to generate a screwless desk organizer, inspect the flat-to-assembled design, arrange the cut
parts on stock, and export a 1:1 SVG cutting pattern or editable project JSON.

The geometry engine remains independent of MeshAI. An optional local bridge can send a text request and
reference photo to the MeshAI host, validate its supported organizer settings, generate the CAD locally,
and open the result screen automatically.
The original V1 scope is documented in [`final plan.md`](final%20plan.md).

## Build and test

Requires Android SDK 36, Java 17, and Gradle access for Android dependencies.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Set `ANDROID_HOME` or create a local `local.properties` with `sdk.dir=...` if Gradle cannot locate your SDK.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Current features

- Parametric organizer generator: width, height, depth, stock thickness, and 1–4 compartments.
- Tab-and-slot joint records generate both panel tabs and mating cutouts. Slot width is stock thickness plus
  the selected clearance.
- Touch-rotatable 3D assembly preview, part highlighting, flat-to-assembled slider, and explode/assemble
  motion.
- Linked 2D sheet layout with 90° rotations, a fixed 5 mm edge margin, and 2 mm part gap. It compares
  deterministic shelf orders and calls the result “Best layout found”; it does not claim global optimality.
- 600 × 400, 600 × 600, and 900 × 600 mm stock options.
- SVG cut paths and Project JSON export using Android's document picker.
- Manual design works offline without an account or cloud service.
- Optional MeshAI requests go only to `127.0.0.1:8080`; a local MeshAI host must already be running. A
  selected photo can be sent with the text request, or by itself using the default visual-context prompt.
- “Ask local MeshAI” runs the structured proposal, validates it, generates the organizer geometry and sheet
  layout, then opens the 3D result. AI can select only compartment count and joint clearance; Origami still
  owns dimensions, CAD generation and validation.
- Design Explorer contains touch-rotatable table, bed, bookcase, guitar and cabin concept previews. These
  examples are visual-only and cannot yet produce fabrication exports. The organizer is the sole validated
  CAD template.

## Geometry and manufacturing limits

The generated model is a visual and dimensional prototype. It uses one tab-slot family and zero-thickness
panel surfaces in the viewer. There is no kerf compensation, material-specific fit profile, fold/crease data,
physics simulation, or structural rating. SVG geometry is 1:1 in millimetres, but joint tolerances still need
a test cut in the actual stock before producing the full set.

The layout packer uses rectangular part bounds, row/column shelf packing, a few orderings, and 90° rotations.
It does not nest parts into concave gaps and may say a sheet is too small even when a more advanced nesting
algorithm could fit them.

## Research

Open-source tool findings and licensing notes are in [`../docs/research/ORIGAMI-TOOLS.md`](../docs/research/ORIGAMI-TOOLS.md).
FOLD compatibility, traditional folding instructions, paper inventory, camera measurement and structural
validation remain outside this milestone. A reference photo is visual context only; it does not measure or
change dimensions. The other gallery objects need separately validated parametric templates before they can
produce fabrication files.
