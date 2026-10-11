# MeshAI — complete staged product roadmap

Updated 2026-10-11. This roadmap brings together the app, phone cluster, model selection, compiler/optimizer, telemetry, privacy, developer workflows, and Origami ideas discussed so far. It is intentionally staged: finish and verify one slice before starting the next. The laptop is a monitor/coordinator when useful; model computation remains on the selected phones unless a task explicitly opts into another destination.

## Product language

- **MeshAI**: the distributed inference app and runtime.
- **Use**: the task home for Origami Workshop, Legal Case Room, and Developer Workspace.
- **Web**: the connected-device map and cluster state.
- **Spider Sense**: live telemetry. It must distinguish observed events, measured durations, and estimates.
- **Model aliases**: optional memorable labels (for example Scout or Websmith). Always show the real upstream model ID and file. Aliases never change model IDs, weights, licenses, requests, logs, or cache keys.
- **Ned Capsule**: a versioned per-device execution plan. It describes assigned work and settings; it is not model weights or proof that work ran.
- **Mesh Compiler**: the separate benchmark/optimization project. A compiler recommendation is not active until the Android runtime accepts and applies it.

Keep the spider theme as a visual layer. Protocol names and technical detail remain available in expanded views.

## Current baseline from the audit

- The Android app has Host/Helper roles, model inventory and a local split-inference path. Local WebRTC binary transport and inference have been reported working; WAN inference is not verified.
- Two iQOO devices are visible to ADB. The first is now configured as Helper and the second as Host; they are paired over the same local Wi-Fi. A USB debugging cable to the laptop alone does not mean the phones are connected to each other by USB.
- Host Runner currently runs one model process. Model choice is not yet task-quality-aware, and multi-model chains are not implemented.
- Qwen3.5-9B can combine image and text in one model if the engine build and matching projector support it. A separate vision-to-text chain is another option. The projector is not present on every inspected device.
- The planner has deterministic memory and hardware heuristics, but needs richer task/model metadata and live validation. Current stats do not yet attribute every token's time to compute, RPC, queueing, or transport.
- Ned Capsule, relay, recovery, job-queue, metering, and speed-planner work exists on Antigravity branches in varying prototype states. Those branches were audited, not merged as a complete product. The independent Mesh Compiler is not yet wired to the app.
- Origami V1 is a deterministic organizer generator with 3D preview and fabrication exports. Tables, beds, instruments, and other furniture require additional parametric engineering templates and validation.
- The former pasted-text Private Desk is no longer the selected showcase. Legal Case Room imports PDF/TXT/Markdown with bounded local text extraction; synthetic TXT and a two-page selectable-text PDF were imported on the Host. Local lexical search with file/page/line references passed matching and no-match checks on the Host. There is still no case-document model inference, OCR, or AI-generated cited answer. The selected showcase must process original, unredacted case material and expose its trusted-device boundary. Helpers that execute layers can observe activations.
- There is no production compute-rental marketplace, secure remote job sandbox, or verified automatic recovery of a lost in-flight KV state.

### Latest device check — 2026-10-11

- `:app:assembleDebug` succeeds; the resulting debug APK was installed with `adb install -r` on both connected iQOO I2501 phones and launched on each. Neither produced an `AndroidRuntime` error in the inspected log window.
- The first screen is the readiness gate. On the inspected phone it reported 16 GB RAM, 5.7 GB currently available to a model, and a 2.4 GB Android reserve. It requires the owner to confirm a memory limit before continuing; I did not simulate that input or change either phone's role.
- Both phones have Wi-Fi addresses on the same `192.168.112.0/23` LAN and only `wlan0` was present; no USB tether/RNDIS interface was present. The Host lists the Helper as `wifi · 192.168.112.142` with a 10 ms RTT, and the Helper reports `PATH · Wi-Fi · local TCP` to Host `192.168.112.146`. The Helper UI now draws the Host at the center and this phone as a Helper spoke. The Host reports two devices and 12.6 GB pooled.
- This verifies authenticated local pairing and route identification, not model execution: the Host is idle, no layers are assigned, and no Helper RPC graph event has been observed. USB tethering and WAN remain unverified.
- Follow-up fixes: normal launches show an explicit Host/Helper chooser after readiness instead of silently restoring the saved role. The previous role is shown as a reminder, switching roles stops the old runner/link first, and the chooser scrolls on phone-sized screens. Both devices now have one Host and one Helper. The Helper connection map names the real route and only animates on recent RPC graph evidence.
- Legal Case Room: Host imported a synthetic TXT and a two-page selectable-text PDF from Downloads. The UI displayed the TXT content and reported two text sections / 178 extracted characters for the PDF; page 1 preview displayed the expected synthetic text. A matching `delivery` query returned the expected PDF page 1 evidence; a `platypus` no-match query returned no passages. No model inference, AI citations, Helper execution, or full data-flow audit was verified.

## Sequence and completion gates

### 0. Preserve a reproducible baseline — done as an audit; repeat for each release

Record source commit, APK/build ID, engine version, model and projector hashes, phone identity/role, actual layer assignment, backend, context, network path, and benchmark input. Preserve the last known-good local inference build before risky changes.

**Gate:** another person can repeat the recorded baseline and distinguish a phone result from a laptop simulation.

### 1. Make the app understandable — in progress

Keep top-level navigation focused on **Mesh, Models, Use, Stats**. Put Origami Workshop, Legal Case Room, and Developer Workspace inside Use. Add friendly model labels while retaining official IDs. Use consistent names and explain terms such as Host, Helper, split inference, and capsule in context.

**Gate:** a first-time user can find a use case, identify the actual model, understand which device will work, and return to the cluster without losing state.

### 2. Device setup, pairing, and real connectivity

Create a startup readiness flow before Host/Helper role selection: show battery, thermal status, available memory, storage, model availability, network/link, and required Android permissions. Explain why a permission is needed, ask the user, and block only features that truly require a denied permission. Let the user set a memory reserve / maximum budget; never consume the reserve just to force a split.

Keep QR-based pairing and explicit peer authorization. Show each phone's role, identity, app/runtime health, transport, and last-seen time. Detect USB tethering, same-LAN TCP, WebRTC direct, or TURN relay from actual connection evidence; don't infer the route from cable presence or a connected badge. Keep local direct TCP as a benchmark option. Run long jobs through a foreground service with an ongoing notification and clear stop controls; explain Android battery-optimization settings without pretending the app can override them.

The laptop panel is an optional observer/coordinator: show phone telemetry and logs, but never imply it hosts the model unless a laptop model is explicitly selected.

**Gate:** pairing is authenticated; app and panel agree on device identity, role, live route, and readiness; restart and permission-denial states are clear.

### 3. Truthful model catalog and preflight planner

Maintain a model record with exact file/hash, architecture, quantization, license/source, supported input modalities, matching projector/tokenizer, engine requirements, context limit, download/cache state, and per-device location. Include estimated resident weights, projector, KV/recurrent state, scratch/runtime buffers, Android headroom, and a user-selected memory reserve. Mark estimates as estimates until measured on-device.

Plan only over compatible, available devices. Explain why a model fits or does not fit, what each device receives, and whether the system will run whole-model locally or split. Account for battery, thermal state, backend support, network measurements, and measured device speed. Do not silently download a model or force a split when the selected device can run it within the user's budget.

**Gate:** the same validated model metadata and plan feed the UI and runtime; launch-time checks reject stale memory, model hashes, unsupported projectors, and changed peers with a useful explanation.

### 4. Use-case router and multiple-model workflows

Add explicit task profiles for general chat, image understanding, Origami design, legal case analysis, and developer tasks. Choose from compatible installed models using capability, measured quality/performance evidence, resource budget, and user choice. Show the selected model(s), device assignment, and reason before execution. The Legal Case Room must pass the original, unredacted source to local parsing/retrieval and the selected local model; it must not promise privacy by redacting or summarizing the user's input.

Start with one model per request. Then add bounded sequential chains with typed/validated handoffs: for example, image understanding → dimension proposal → user confirmation → deterministic Origami generation; or code review → proposed tests → user approval → sandboxed test run. Reuse one multimodal model when supported. Do not run multiple large models concurrently unless the full combined memory and KV budget fits. Never let model output bypass deterministic engineering constraints or execute arbitrary tools without approval.

**Gate:** supported use cases complete with reproducible routing, typed outputs, visible stages, and safe failure if a model or stage is missing.

### 5. Origami Workshop — improve the real design workflow

Keep Origami inside Use. Preserve its deterministic geometry, nesting, assembly preview, and SVG/JSON exports. Improve the task catalog and visual presentation, then add parametric templates one at a time (organizer first, then a simple shelf/table as engineering permits). Add image-assisted dimension suggestions only as proposals; ask the user to confirm manufacturing-critical measurements. Show the generated 3D object, parts, dimensions, sheet layout, joints, and assembly steps—not just changed parameter values. Label untested structural or fabrication claims clearly.

**Gate:** each template has constraint checks, stable exports, and at least one physically fabricated/assembled reference before it is presented as fabrication-validated.

### 6. Spider Sense and Stats — evidence before decoration

Give the Mesh screen a live device/web view and a Spider Sense activity stream: paired/authorized, model loaded, plan applied, RPC request sent, helper graph event, stage started/finished, output returned, cancellation, or disconnect. Only show events emitted by the relevant process. Separate link RTT from model compute time; do not label a graph event as a completed compute duration.

Upgrade Stats with reproducible comparisons: model/hash, prompt and output tokens, context, split, backends, phone temperatures/memory/battery, transport and selected ICE path, load time, TTFT, prefill/decode rates, total time, bytes, RPC counts, errors, and repeat count. Compare single-device, direct TCP, and WebRTC only with matched inputs/configuration. Explain a measured slowdown from available evidence and mark unknown causes as unknown. Show predicted vs measured resource savings separately.

**Gate:** telemetry values can be traced to a runtime event or device metric; benchmark comparisons have matching conditions; no simulated metric is presented as live.

### 7. Optimizer, Mesh Compiler, and Ned Capsules

First define what may be optimized: layer split, model/context/KV settings, backend, and supported runtime options. The planner selects a safe candidate; the Mesh Compiler evaluates candidates against a baseline with repeated speed, memory, thermal, and quality measurements. Import profiles only when device class, model/hash, engine version, task, and context match. Keep a known-good fallback and show the measured trade-off; do not claim lower resource use or higher speed without paired evidence.

Harden the Antigravity Ned Capsule prototype into a signed/versioned plan bound to the inference session, authorized device, model hash, layer range, runtime/backend, context and memory ceiling, and expiry. Each device validates the capsule and reports the settings it actually applied. Do not put model weights or prompts in a capsule. Remove hard-coded benchmark evidence and reject unsupported settings.

**Gate:** the UI can show proposed vs applied plan per device, and a repeatable before/after run proves any claimed optimization without breaking output quality or safety limits.

### 8. Privacy, security, background execution, and failure behavior

Define the trust boundary for each workflow: host-only, trusted helper, laptop, or explicitly remote. Explain that distributed helpers process model activations and may see information represented there; local networking alone is not a privacy guarantee. Keep prompts and source out of logs by default, protect secrets, verify model hashes, use authenticated pairing and encrypted transport, keep native RPC bound to local interfaces, bound queues/buffers, and provide retention/delete controls. Do not describe a prototype as enterprise-secure or compliant without a specific review.

For battery/background operation, use a foreground service for active inference, persistent notification, user stop control, and recovery after process death. On helper loss, fail/cancel clearly. If KV state is gone, restart the request in a new session only when safe and disclose that it restarted; do not claim transparent mid-generation resume. Reconnect the mesh separately from resuming inference.

**Gate:** test revocation, malformed plans, corrupted model, permission denial, process death, network switch, and disconnect during prefill/decode; sensitive data handling is inspectable.

### 9. Legal Case Room and Developer Workspace

The selected showcase is a **Legal Case Room** for authorized matter files. A user imports original documents and asks for a cited timeline, cross-document comparisons, or potential contradictions. The first implementation should support local PDF/TXT/Markdown ingestion, page-aware source references, bounded retrieval, and typed findings that link back to source passages. It should retain the originals as supplied, avoid a redaction/summarization privacy pre-step, and keep prompts, extracted text, embeddings, and answers out of telemetry. Start with extractable-text PDFs and test fixtures; add scanned-page OCR/vision as a separately verified stage. Show the chosen model, actual device/layer route, and each processing stage. Make clear that findings are review aids, not legal advice or verified conclusions.

The initial data-flow target is Host-local document selection and parsing, followed by local model inference. If a Helper is assigned layers, it receives activations and must be an organization-trusted device. Keep document content off the laptop monitor and public coordination service. Provide clear per-case deletion and session clearing. Do not claim enterprise security or legal compliance without a specific review and evidence.

Developer Workspace is a separate workflow: pair/import a chosen repository, scope files, show what context is sent to each trusted helper, propose patches, generate tests, and run tests only with approval in a constrained workspace. The existing job-queue branch must be redesigned before use because its prototype stores plaintext prompts/results and is not a secure remote-worker execution system.

**Gate:** a documented data-flow test shows where each input/output went; tool execution is permissioned, bounded, and isolated; no cloud or helper sharing occurs without the selected trust policy.

### 10. Optional rented/shared compute — future product, not current capability

Only after secure identity, consent, device attestation or an equivalent trust model, workload isolation, scheduling, abuse controls, metering, pricing, settlement, dispute handling, and legal review are designed should MeshAI offer rented memory/compute. A model catalog/download flow and a distributed inference session are not a marketplace. Treat current Antigravity metering rates as prototype assumptions, not earnings or savings.

**Gate:** a threat model, economic model, and audited end-to-end payment/metering path exist before any public claim or user funds are involved.

## Architecture decisions

1. **Split one model** means contiguous layers of one model execute across devices through the current llama.cpp RPC path.
2. **Use multiple models** means a task routes to one model or passes validated output through a bounded sequence. This is not yet a general Android Runner capability.
3. **Connectivity and compute are separate.** USB/LAN/WebRTC/TURN carry traffic; llama.cpp performs inference. A laptop panel observes/co-ordinates unless explicitly assigned compute.
4. **KV state belongs to an inference session and its assigned layers.** Increasing context or adding concurrent models increases memory. A lost helper KV state cannot be reconstructed by merely reconnecting.
5. **Compiler/optimizer claims require measurements.** A capsule conveys settings; it does not optimize by itself.
6. **Privacy is a trust-boundary property.** A trusted helper receives activations. A public relay can transport encrypted traffic but cannot make an untrusted compute peer confidential.

## Immediate slice and sequencing rule

The Use navigation now has separate Mesh Chat, Origami Workshop, Legal Case Room, and Developer Workspace cards. Local PDF/TXT extraction and lexical matching/no-match retrieval are device-verified. Cited answers are now implemented but not device-verified: Ask sends only the retrieved passages (S01…) to the running model, shows the route (model, Host/Helper layer ranges) first, requires consent naming each Helper when the plan is split, and checks every `[Sxx]` citation against the supplied IDs (`CaseCitationsTest`, 8 unit tests pass). Laptop prompt check (not a phone result): the same system text and prompt layout against Qwen3-4B-Instruct-2507 Q4_0 on the laptop's llama-server (ctx 4096, 6 threads, temperature 0) gave `Delivery is due on 22 March [S01].` (CITED), `Not found in the supplied sources.` for an unanswerable question (NOT_FOUND), and cited both [S01] and [S02] when two synthetic sources disagreed. A deterministic timeline is also built (`CaseTimeline`, `CaseTimelineTest`, 5 tests): it lists every dated sentence with file and page/line, found by pattern and not by a model. On the Host the owner confirmed cited answers work (2026-10-11). Follow-ups built after that: Ask shows live stages (model ready, request sent, reading sources with llama-server `prompt_progress` percentage, writing answer with live token count, checking citations) and a stats card (prompt tokens/time/rate, first word, output tokens/rate, total); llama-server now runs with `-b 512` so progress arrives every 512 prompt tokens (physical batch unchanged at the default 512; speed effect not measured). Search matches word forms (sign/signed, agree/agreement) and, when nothing matches, sends a small case whole, labelled as such. Next, verify the progress view, stats and timeline on the Host, then a split-route consent. Developer Workspace v1 (built, not device-verified): open a project folder on the Host (Android folder picker; skips .git/build/node_modules/binaries/.env; ≤400 files, ≤256 KB each), search with camelCase-aware matching, and ask with the same route/consent/progress/citation check, cited as file:lines. Read-only: nothing is run or changed. Laptop prompt check, Qwen3-4B-Instruct-2507 Q4_0, temp 0: "Where is the API key created and stored?" was answered from LocalApiKey.kt and cited [S01]; "How does the app encrypt traffic between phones?" gave NOT_FOUND. Add timeline/comparison views and scanned-page OCR/vision only after those gates. Continue through device setup, runtime telemetry, model routing, compiler integration, developer execution, and compute rental in separate gates. At each gate, report what is implemented, what was physically verified, what failed, and what remains untested. Do not bundle all roadmap stages into one change.
