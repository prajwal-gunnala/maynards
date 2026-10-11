# Origami Lab: open-source tool research

Checked 10 October 2026. The original fabrication scope follows [`../../origami/final plan.md`](../../origami/final%20plan.md):
one screwless flat-pack organizer, tab-slot joints, 2D cutting layout, 3D assembly view, SVG and project JSON.
The current UI also has a visual-only gallery for a study table, bed, bookcase, guitar and cabin; these samples
are previews, not validated cut-ready templates.

## Tool review

| Project | What it offers | Decision for this app |
|---|---|---|
| [FOLD format and libraries](https://github.com/edemaine/fold) | Extensible JSON format for crease patterns, folded states, and mesh geometry; the JavaScript package declares MIT. | Keep FOLD out of V1 as requested. Revisit only if the app adds traditional crease-pattern models. |
| [ORIPA](https://github.com/oripa/oripa) | Mature desktop crease-pattern editor and folded-shape calculator; repository declares GPL-3.0. | Do not embed or copy it. It addresses a different use case and brings copyleft obligations. |
| [Oriedita](https://github.com/oriedita/oriedita) | Java crease-pattern editor and folding simulator with `.ori`, `.cp`, and `.fold` support. | Desktop reference only. Check its current license and dependencies before considering reuse. |
| [Origami Simulator](https://origamisimulator.org/) | Browser folding simulation; documents SVG crease-line colors and FOLD support. | Not needed for the flat-pack organizer. Do not add a WebView in V1. |
| [Rabbit Ear](https://github.com/rabbit-ear/rabbit-ear) | JavaScript library for origami geometry, FOLD graphs, SVG/WebGL rendering, and calculations; repository declares GPL-3.0. | Not bundled. It is a possible later geometry reference if licensing is deliberately accepted. |
| [Kami](https://github.com/maxwase/kami) | MIT-licensed browser/desktop paper-folding simulation with hinge controls. | Interesting interaction example, but not relevant to tab-slot fabrication. |
| [awesome-origami](https://github.com/oriedita/awesome-origami) | Community index of origami software and resources. | Discovery list; check primary project sources for current features and licenses. |

## Architecture choice

- Keep the Kotlin geometry engine as the source of truth for panel outlines, cutouts, mating joints, and
  assembled placement.
- Export that same design graph to SVG and Project JSON. A future renderer or service should consume those
  formats rather than create a second set of dimensions.
- Keep the app native and offline. No origami editor, physics engine, AI endpoint, MeshAI dependency, or
  third-party geometry library is needed for this scope.
- Project JSON uses the app schema `maynards.origami.organizer.v1`; add explicit migrations before changing
  its fields in a later release.
- SVG uses a millimetre viewBox and red 0.1 mm cut strokes. It does not include material kerf or engraving
  paths.

## Current AI and furniture tooling

| Component | Current use | License / status |
|---|---|---|
| [Qwen3.5-9B](https://huggingface.co/Qwen/Qwen3.5-9B) | The active MeshAI model. Its Q4_K_M GGUF is served by llama.cpp. Origami sends a local structured request, optionally with a reference photo. This is not an Origami CAD model. | Base model card declares Apache-2.0. |
| [Qwen3-VL-8B-Instruct](https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct) | Compatibility fallback named in the finalized architecture plan. Not installed or active in the current app flow. | Not bundled. |
| Kotlin geometry engine + Jetpack Compose Canvas | Generates the validated organizer and renders the 3D assembly. The gallery's other objects are hand-authored visual previews in Kotlin. | Existing project code; no generative CAD weights or external renderer are currently used. |
| [SceneView](https://github.com/sceneview/sceneview) | Candidate for a later GLB/STL viewer and AR placement; not present in this app's Gradle dependencies. | Apache-2.0; upstream describes Compose/Filament and AR support. |
| [JSCAD](https://github.com/jscad/OpenJSCAD.org) | Candidate parametric geometry library if a carefully scoped JS runtime is acceptable. It does not provide furniture engineering rules by itself and is not integrated. | MIT. |

The first implementation should continue extending deterministic, validated parametric templates. A vision model can
describe visible context, but a photo alone is not a reliable measurement source; manufacturing dimensions need a
scale reference and user confirmation. The current model schema accepts only organizer compartment count and joint
clearance, and the bridge runs generation locally after the structured response.

## Sources

- [FOLD documentation](https://edemaine.github.io/fold/) and [edemaine/fold package](https://github.com/edemaine/fold)
- [ORIPA repository and GPL-3.0 license](https://github.com/oripa/oripa)
- [Oriedita repository](https://github.com/oriedita/oriedita)
- [Origami Simulator import documentation](https://origamisimulator.org/)
- [Rabbit Ear repository and GPL-3.0 license](https://github.com/rabbit-ear/rabbit-ear)
- [Kami repository and MIT license](https://github.com/maxwase/kami)
- [awesome-origami index](https://github.com/oriedita/awesome-origami)
- [Qwen3.5-9B model card and license](https://huggingface.co/Qwen/Qwen3.5-9B)
- [SceneView source repository and Apache-2.0 license](https://github.com/sceneview/sceneview)
- [JSCAD source repository and MIT license](https://github.com/jscad/OpenJSCAD.org)
