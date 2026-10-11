Yes. I would **cut the scope aggressively**. For the first version, trying to do origami instructions, FOLD compatibility, scrap inventory, irregular nesting, multiple joint systems, physics, AI, camera measurement, etc. will dilute the demo.

I’d build **two things extremely well**:

### 1. Automatic screwless structure generation
### 2. Flat-sheet → assembled 3D visualization

Everything else becomes supporting functionality.The confusion is because we've been discussing two different problems in MeshAI as though they're one.

1. Connectivity problem: How do two phones find and connect to each other when they're on different networks?
2. Inference-speed problem: Once connected, how do they execute one shared AI model without wasting time communicating?

My proposed WebRTC system solves the first problem, not automatically the second. It does not replace llama.cpp RPC.

For now, I would keep your working inference engine and change only the connection layer.

## 1. What you currently have

Phone A — Host

Qwen3.5-9B layers 0–12

Direct TCP connection over local network

Phone B — Helper

Qwen3.5-9B layers 13–31

Your measured result

Decode speed

2.5 tok/s

First token

19.8 seconds

Link RTT

41 ms

The AI splitting works. The two phones can share the model and produce a response.

What fails when the phones are far apart?

Suppose Phone A is on home Wi-Fi and Phone B is on Jio 5G.

Phone A cannot simply connect to Phone B's private IP address because that address only works inside Phone B's local network. Carrier NAT and router NAT prevent ordinary unsolicited inbound connections.

That's a networking problem, not a model problem.

Separately, the current 2.5 tok/s result tells us inference is slow even when the phones can connect. The exact causes still need profiling.

## 2. What I'm proposing to change

We keep the existing llama.cpp RPC protocol and model splitting.

We insert a MeshAI connection layer between the two phones.

Phone A — Host

llama-server → local TCP proxy

NEW: MeshAI Connection Layer

Only new component

WebRTC + ICE

Find the best available network path

Direct P2P

When supported

TURN relay

When direct fails

Authenticated, encrypted DataChannel carrying existing RPC bytes

Phone B — Helper

Local TCP proxy → ggml-rpc-server

The WebRTC standards support binary DataChannels, direct peer connectivity and TURN relaying, with encrypted transfer.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://webrtc.org\&sz=32)

WebRTC

+3



### What happens when the user connects?

1. Phone A and Phone B sign in to MeshAI and pair using your public coordination server.
2. WebRTC attempts to establish a direct connection, even across different networks.
3. If direct connectivity fails, the existing TURN server relays encrypted data.
4. The local adapters carry the original llama.cpp RPC bytes across that connection.
5. Model layers execute on both phones exactly as before.

Neither phone needs a permanently reachable public IP. The public server handles coordination and, when necessary, relaying. It does not run the AI model.

## 3. Is WebRTC replacing RPC?

No. They solve different problems.

| Component         | What it does                                                 | Are we replacing it?         |
| ----------------- | ------------------------------------------------------------ | ---------------------------- |
| llama.cpp RPC     | Executes model computations on the other phone               | No                           |
| TCP               | Carries RPC bytes on your current network                    | Adapted for remote transport |
| WebRTC            | Connects devices across networks and carries binary messages | Added                        |
| ICE/STUN          | Discovers possible connectivity paths                        | Added                        |
| TURN              | Relays when direct connections fail                          | Added                        |
| MeshAI controller | Pairs devices and coordinates execution                      | Extended                     |

We need a TCP-to-DataChannel adapter because llama.cpp expects a TCP byte stream while WebRTC DataChannels are message-oriented. This requires careful buffering, ordering and flow control.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://datatracker.ietf.org\&sz=32)

WebRTC Data Channels



One qualification: WebRTC is not guaranteed to outperform raw TCP. I would preserve your existing direct TCP path as the local performance baseline. The app can offer one unified connection experience without forcing every connection through identical networking code.

## 4. But what about making inference drastically faster?

That's a separate future change.

MeshAI Connectivity V2

Build now

Problem solved: Phones cannot connect reliably across different networks.

Implementation: WebRTC, ICE, TURN and a TCP adapter.

Inference engine: Existing llama.cpp RPC, unchanged.

MeshAI FastPath V3

Build after profiling

Problem targeted: Too much time potentially spent on remote graph operations, synchronization and slow device kernels.

Implementation: Persistent layer execution, lighter activation transfer, and CPU/GPU/NPU optimizations where useful.

Inference engine: This is where we may actually modify or replace the execution mechanism of generic RPC.

The V3 architecture could exchange entire layer-stage activations instead of remotely issuing numerous computation-graph operations. But it is not implemented or proven faster in MeshAI yet.

And neither WebRTC nor FastPath can remove the fundamental delay of waiting for a remote phone during sequential decoding. When two phones are on distant networks, the runtime may have to choose a different workload strategy.

## 5. My final recommendation

Implement the following and nothing more for the next milestone:

- Keep the current model, layer split, RPC binaries and working local TCP connection.
- Add authenticated WebRTC connectivity with STUN/TURN fallback.
- Build a bounded, reliable TCP-to-DataChannel adapter.
- Test direct Wi-Fi, different Wi-Fi networks, cellular and forced TURN relay.
- Confirm real distributed inference and record network latency and token speeds.

The public server can be a small signaling and TURN service. Your laptop remains optional and does not execute model layers.

The distinction I want you to retain is:

WebRTC helps the phones reach each other. FastPath is what we would investigate to make their shared model run faster.

So we're not throwing away your work. We're first making the existing MeshAI accessible across networks, then improving the part responsible for inference speed.

## Final product concept

> **Enter dimensions + material thickness → app generates a screwless flat-pack object → see exactly how the flat pieces assemble in 3D → export the cutting pattern.**

That is enough for a strong visual demo and also gives you something genuinely functional.

The app can still be called/positioned around **origami-inspired computational design**, because the key idea is:

**2D sheet → geometry → joints/folds → 3D object**

---

# Hero object: build only ONE first

I would choose a:

## **Screwless Desk Organizer / Storage Box**

Not a chair, furniture, or anything structural.

Why this one:

- geometry is simple enough to generate reliably
- has multiple panels
- tab/slot joints are clearly visible
- assembly animation looks impressive
- dimensions can be changed interactively
- can actually be laser-cut from cardboard/MDF
- no screws/glue required
- easy to physically manufacture for the demo
- easy to understand even for a non-technical judge

Think of something like:

```text
       BACK
    ┌──────────┐
    │          │
 ┌──┴──────────┴──┐
 │                 │
 │   COMPARTMENT   │
 │                 │
 └─────────────────┘
      BASE
```

Maybe 5–7 pieces maximum.

Don't make a complex drawer system.

---

# The full app should initially have only 4 screens

## Screen 1 — Create

Very simple.

### Object

**Screwless Organizer**

Then:

```text
Width       300 mm
Height      160 mm
Depth       180 mm

Material Thickness
3 mm

Joint Fit
Normal
```

Optional:

`Compartments: 2`

Then one huge button:

# GENERATE

That's it.

Don't ask about kerf, grain, materials, tolerances, manufacturing processes, etc. on the main screen.

Advanced parameters can exist under:

`Advanced ▾`

but remain hidden.

---

# Screen 2 — Generated design

This is where the wow factor starts.

Immediately display the assembled object in 3D.

Something like:

```text
        GENERATED ✓

        [3D MODEL]

     300 × 180 × 160 mm

7 PARTS
3 mm MATERIAL
NO SCREWS
```

The user can rotate the object with touch.

Tap a panel and highlight it.

Tiny labels:

`Side A`

`Side B`

`Base`

`Back`

`Divider`

etc.

---

# Feature 1 — automatic screwless joints

For V1, implement **only one joint family**:

# Tab + Slot

Don't implement finger joints, dovetails, wedges, living hinges and half-slots yet.

A tab/slot system is enough to demonstrate the idea.

The user enters:

```text
Material thickness = 3 mm
```

The engine automatically generates something like:

```text
Tab width       15 mm
Tab depth       3 mm

Slot width      3.2 mm
Slot length     15.2 mm
```

These values should ultimately depend on your configurable clearance.

Internally:

```text
slotWidth =
materialThickness + clearance
```

That's enough.

---

# Important architecture

Don't simply draw tabs onto SVGs.

Represent an actual relationship:

```text
Joint {
    partA
    edgeA

    partB
    edgeB

    type = TAB_SLOT

    insertionDirection
    clearance
}
```

So the same information powers:

**2D cutting**

and

**3D assembly animation**.

That is the important technical foundation.

---

# Feature 2 — FLAT → ASSEMBLED

This should be the showcase feature.

At the bottom of the 3D screen:

```text
FLAT  ━━━━━━━●━━━━━━━━  ASSEMBLED
```

Move slider left:

All pieces separate and lay flat.

Move it right:

Pieces move together.

Tabs enter slots.

Final object appears.

### 0%

```text
┌────┐  ┌─────┐  ┌────┐

┌───────────┐

     ┌──────┐
```

### 40%

Side panels rise.

### 70%

Back slides into slots.

### 100%

```text
      COMPLETE ✓
```

This alone will look extremely good in a hackathon/demo.

---

# Add an Explode button

Beside the slider:

**EXPLODE**

Tap it:

all components move outward.

Something like:

```text
         BACK

SIDE     BASE      SIDE

       DIVIDER
```

Then tap:

**ASSEMBLE**

and everything comes back together.

This is much easier than real physics simulation.

You're simply interpolating predefined transforms:

```text
Part {
    flatTransform
    explodedTransform
    assembledTransform
}
```

Then:

```text
transform =
lerp(flatTransform,
     assembledTransform,
     progress)
```

No physics engine required.

---

# Screen 3 — Cutting layout

Now switch to:

# FLAT PATTERN

Show the pieces on one stock sheet.

For example:

```text
600 × 400 mm SHEET

┌───────────────────────────┐
│ SIDE │ SIDE │    BACK     │
│──────│──────│─────────────│
│        BASE        │ DIV  │
└───────────────────────────┘

Material used: 78%
Waste: 22%
```

This is where your existing paper-planner work becomes useful.

But **do not build irregular optimal nesting yet**.

For V1:

- rectangular pieces
- 90° rotation
- fixed margin
- simple row/column packing
- choose best of a few layouts

Call it:

**BEST LAYOUT FOUND**

not:

**OPTIMAL LAYOUT**

---

# One nice visual feature

Tap any flat part:

`SIDE PANEL`

and the same panel highlights in the 3D object.

So:

**2D ↔ 3D are linked.**

That makes the tool feel like a genuine design application.

---

# Screen 4 — Export

Only support:

### SVG

and

### Project JSON

for V1.

Maybe PNG/PDF preview if easy.

Skip:

DXF  
STEP  
STL  
OBJ  
FOLD

for now.

SVG is enough to physically cut the design using laser/CNC workflows or print it as a template.

Later add DXF.

---

# The whole technical engine becomes tiny

Instead of the huge architecture I previously suggested:

```text
app/
│
├── model/
│   ├── Project
│   ├── Part
│   └── Joint
│
├── generator/
│   └── OrganizerGenerator
│
├── geometry/
│   └── TabSlotGenerator
│
├── layout/
│   └── SheetLayout
│
├── viewer/
│   └── 3D Assembly
│
└── export/
    └── SVG
```

That's enough.

---

# Core data structure

Something roughly like:

```text
Project
│
├── width
├── height
├── depth
├── materialThickness
│
├── Parts[]
│
└── Joints[]
```

Each:

```text
Part
├── id
├── outline
├── thickness
├── tabs[]
├── slots[]
├── flatTransform
└── assembledTransform
```

And:

```text
Joint
├── partA
├── partB
├── position
├── tabSize
├── slotSize
└── insertionDirection
```

Very manageable.

---

# Don't build these now

I would explicitly postpone all of these:

- traditional origami model library
- folding tutorials
- FOLD import/export
- ORIPA integration
- crease-pattern editor
- full origami simulator
- physics
- FEA
- curved folds
- living hinges
- finger joints
- dovetails
- wedge locks
- camera measurement
- irregular scrap detection
- advanced nesting
- paper inventory
- user accounts
- cloud
- collaboration
- AI
- MeshAI
- natural-language generation

None of them are required for your demo.

---

# The 60-second demo should literally be

Start with an empty app.

### 1. Enter

```text
300 × 180 × 160 mm
3 mm material
```

Tap:

**GENERATE**

### 2. Boom — 3D object

Screwless organizer appears.

Rotate it.

Show:

`7 PARTS • 12 JOINTS • NO SCREWS`

### 3. Move slider

**ASSEMBLED → FLAT**

Whole structure unfolds/explodes into flat panels.

Then:

**FLAT → ASSEMBLED**

and watch everything lock back together.

### 4. Switch to sheet view

All pieces arranged on:

`600 × 400 mm`

Show:

**78% MATERIAL UTILIZATION**

### 5. Export SVG

Tap:

**EXPORT**

Done.

That is already an impressive product.

---

# Then make one physical prototype

This is important.

After software works:

export SVG  
→ laser cut cardboard / 3 mm MDF  
→ physically assemble it  
→ verify every tab fits.

Now your demo becomes:

**App-generated**

→ **visualized in 3D**

→ **optimized in 2D**

→ **exported**

→ **actually manufactured**

→ **assembled without screws**

That is much stronger than showing twenty unfinished features.

---

# Where Origami fits

Don't force traditional origami into V1.

Present the underlying concept as:

> **Origami-inspired computational fabrication — transforming flat sheet material into useful 3D structures.**

Later you can add actual folds.

For example, V2 could introduce a second object:

## Foldable cardboard stand

One sheet.

Cut + crease lines.

Starts flat:

```text
───────────────
```

Then folds:

```text
       /\
      /  \
_____/    \____
```

into a phone/tablet stand.

That would be your first **real origami/folding generator**.

But don't build it until the screwless organizer works.

---

# Final scope I would lock

### V0.1

**ONE OBJECT**
Screwless organizer.

**ONE JOINT**
Tab + slot.

**ONE GENERATOR**
Width × Height × Depth → panels.

**ONE MATERIAL PARAMETER**
Thickness.

**ONE WOW FEATURE**
Flat ↔ assembled 3D animation.

**ONE MATERIAL FEATURE**
Simple sheet layout + utilization %.

**ONE EXPORT**
SVG.

That's it.

And from a demonstration perspective, the killer sentence becomes:

> **“I give it dimensions and material thickness. It automatically generates a screwless structure, creates all mating joints, converts it into flat cutting pieces, shows exactly how those pieces assemble in 3D, calculates material usage, and exports the pattern for fabrication.”**

That is focused enough to actually finish, but visually and technically substantial enough to impress.