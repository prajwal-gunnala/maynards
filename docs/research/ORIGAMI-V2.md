# Origami V2: the real flow, a catalogue, realistic 3D, and where the mesh does the work

Written 2026-10-11. Plan only; nothing here is built yet. Sources at the end. AR is out of scope (owner's call).

## 1. The flow, as a person uses it

```
 YOU                          MESH (phones, private)                    ORIGAMI ENGINE (deterministic)
 ───                          ─────────────────────                     ──────────────────────────────
 photo of the space   ─┐
 + one sentence        ├──► 1 Vision model reads the photo:
   "a shelf for 30           what is there, free wall/floor size,
    books under this         style; a known object (A4 sheet,
    window"                  bank card) gives the scale
 + what you have:     ─┘  2 Design model picks a catalogue item and
   sheet size, thickness,    fills its parameters as typed JSON
   laser / CNC / saw         {template:"bookshelf", width:780, shelves:3, …}
                                         │
 3 YOU CONFIRM the sizes ◄───────────────┘   (never cut on a guessed number)
                                         │
                                         └──────────────────────────────►  4 parts, joints, nesting on your sheet,
                                                                              sag check, cost, waste %, assembly order
                              5 Critique model reads the engine's report ◄─┘
                                 "800 mm span in 6 mm ply sags 4.1 mm with
                                  books: use 12 mm or add a divider" → back to 4
 6 YOU SEE: realistic 3D (wood grain, light, shadow), explode/assemble, the cut sheets,
   step-by-step assembly, and export SVG/DXF for the cutter + a PDF of the steps
```

What is shared with the model: the photo and your sentence, the catalogue's item names and parameter ranges, and later
the engine's numeric report. Never shared: nothing leaves your phones. Geometry is never invented by a model: it
only chooses a template and numbers inside checked ranges.

## 2. Catalogue: our own parametric generators (not downloaded files)

Public plans are mostly unusable as a catalogue: OpenDesk no longer shares designs; most free laser-cut toys and
furniture are non-commercial (CC BY-NC-SA); marketplace files are all-rights-reserved. Boxes.py (GPL-3.0) is the
best reference for joints and generator ideas, read for facts only (CLAUDE.md), never copied.

| Group | Items (each a generator with ranges and checks) | Status |
|---|---|---|
| Storage | desk organizer · toy box · crate · drawer insert | organizer done |
| Tables | side table · monitor riser · coffee table · kids' table | small table done (3c812a8) |
| Shelves | wall shelf · bookshelf (N shelves) · shoe rack | — |
| Seating | stool · bench · kids' chair | — |
| Toys | rocking horse · doll house · ball run · puzzle box | — |
| Outdoor | planter · bird house | — |

Every generator shares: tab-and-slot and finger joints with a kerf ("burn") setting so parts press-fit without
glue; constraint checks; the same exports.

## 3. Technical upgrades

| Upgrade | How | Open source used |
|---|---|---|
| Realistic 3D | Filament directly (no AR): each part extruded to a mesh with real thickness, PBR wood (colour, roughness, normal maps), image-based lighting from an HDRI, soft shadows, explode/assemble animation | Filament (Apache-2.0); CC0 wood textures and HDRIs (Poly Haven / ambientCG; confirm per file) |
| Joints | finger joints along whole edges, dovetails, kerf compensation, living hinges for curves | ideas from Boxes.py (GPL, read only) |
| Will it hold? | shelf/table-top sag from material stiffness, span and load (beam deflection), shown as "sags 1.2 mm under 10 kg ✓" | textbook formula |
| Less waste | better nesting: bottom-left fill + rotations + a short genetic search; show waste % before/after | ideas from SVGnest / Deepnest |
| Exports | DXF (laser and CNC shops), PDF assembly steps, bill of materials and cost | written in-app |
| Scale from photo | ask for a reference object in the photo; the model reports the pixel-to-mm ratio it used, and the user confirms | — |

## 4. Where the mesh makes this possible

- **A model one phone cannot comfortably hold.** Photo understanding + design reasoning needs a multimodal model
  (Qwen3.5-9B with its projector, or Qwen3-VL-8B) and the Host also renders 3D. Splitting the model leaves the
  Host's memory for the renderer.
- **Private by construction.** Photos of a person's home and their measurements stay on their own devices
  (docs/data-flow.md).
- **A chain of models** (roadmap 4b): vision → design → critique, each step routed to the best installed model.
- **Fast iteration.** Each tweak re-sends the same context (catalogue + photo description + constraints) with a
  small change; prompt reuse turns a ~63 s re-read into ~2.5 s (docs/mesh-compiler/03-results.md, CPU).

## 5. Order

1. Realistic 3D renderer (Filament, wood, light, shadow) for the two existing templates.
2. Catalogue screen (grouped, searchable) + 3 new generators: bookshelf, stool, toy box.
3. Sag check + kerf/finger joints.
4. AI flow: photo + sentence → template + JSON → confirm sizes → generate → critique loop.
5. Nesting upgrade, DXF and PDF exports.

## Sources

- Boxes.py: https://florianfesti.github.io/boxes · https://boxes-audreyfeldroy.readthedocs.io/en/latest/README.html
- OpenDesk status: https://en.wikipedia.com/wiki/OpenDesk · open CNC furniture overview: https://makezine.com/article/workshop/woodworking/open-source-furniture-2/
- Free plan licences: https://www.shareable.net/20-open-source-furniture-designs/ · https://www.freepatternsarea.com/designs/downloads/free-wooden-rocking-horse-laser-cut-template-diy-download
- Filament materials: https://google.github.io/filament/main/materials.html · https://google.github.io/filament/Filament.md.html
- CC0 textures/HDRIs (verify per file): https://app.cinevva.com/guides/free-textures-hdris-materials
- Nesting: https://github.com/functionalprototype/Deepnest · https://ps.wdka.nl/SVGnest
