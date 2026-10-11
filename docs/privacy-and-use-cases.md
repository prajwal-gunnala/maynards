# MeshAI privacy use cases and execution visibility

## What the system should do

MeshAI has two related jobs:

1. Show which devices are paired, which link they use, and which devices actually receive model work.
2. Let a person pick a task and have the Host choose an installed model that supports it and fits the current memory plan.

The Host remains the request endpoint. Helpers run the contiguous transformer layers assigned by the planner. The laptop panel can monitor a phone-hosted run, but if the laptop is itself assigned model layers it is also a compute participant.

## Current implementation

- **Origami** uses the Host's loopback llama-server endpoint. Before an AI-assisted request, MeshAI chooses the largest installed model that fits the current device plan. For a photo request, it requires that model's matching `mmproj` file and restarts the server with that vision model when necessary. The image understanding proposes supported Origami parameters; deterministic Origami code still generates the geometry and cutting layout.
- **Legal Case Room (local import/search prototype)** accepts PDF, TXT, and Markdown through Android's document picker. Selectable PDF text is extracted page by page; TXT/Markdown are split into bounded line sections. Synthetic TXT and a two-page selectable-text PDF were imported on the Host; extracted text and page sections appeared in the UI. Local lexical evidence search ranks matching passages and labels them with source IDs plus file/page/line references. On-device checks passed: a matching query returned the expected PDF page 1 passage, and a no-match query returned no passages. **Ask** (built, not yet device-verified) sends only the retrieved passages and the question to the Host's running model. Before sending, the screen shows the model and each device's layer range; if Helpers hold layers, the user must tick a box naming them. The answer's `[Sxx]` citations are checked against the supplied IDs: unknown IDs or missing citations are flagged in red. **Timeline** lists every dated sentence in the open files with its file and page/line; it uses pattern matching on the Host and calls no model. There is no OCR. File content is held in screen memory only. The UI warns that a cloud-backed Android provider may fetch a selected file when opened. Scanned-only PDFs are not supported yet.
- **Compute activity** is sourced from the Helper's native `GGML_RPC_DEBUG` log. Android Helpers report observed `graph_recompute` events (and `graph_compute` if that engine build logs it) to the Host over the paired control channel. The laptop Helper now does the same and prints a matching event to its terminal. The Host phone and laptop-host dashboard show the event type and sequence when they receive it. Upstream currently logs `graph_recompute` at debug level before calling the backend; the normal successful `graph_compute` path does not log a matching event. This is evidence that a graph command reached the Helper, not a kernel-level utilization meter, proof of a particular accelerator, or per-layer timing.
- **Link visibility** shows the Host's accepted interface type where Android can map it (USB, Wi-Fi, hotspot, Tailscale, WebRTC, or network) and the peer address. WebRTC direct/relay candidate details remain in the remote-link panel.
- **Model API access** uses a random per-install bearer key for llama-server requests. The Host shares it only with a paired laptop client so the CLI can make authorized requests; the app's own requests use loopback. Health/model metadata endpoints may remain available without that key, depending on the pinned llama.cpp build.
- **Model selection** operates on models already installed on the Host and compatible with its current memory plan. It does not silently download a model or override the chosen memory cap. If no suitable model is installed, the use case directs the user to Models.

## Privacy boundary

The user prompt is sent to the Host phone's model endpoint. The laptop control panel does not receive the prompt through the activity telemetry. A Helper assigned layers receives intermediate activations and computes on them; those activations must be treated as sensitive. A participant could potentially infer information from them, so only pair devices the user trusts.

Local direct TCP is not encrypted. The bearer key blocks unauthenticated API use but does not encrypt HTTP or defend against a passive observer on the same link. Remote WebRTC DataChannels are encrypted in transit, but transport encryption does not protect data from an authorized Helper. Signaling carries coordination; it does not execute model inference. Do not describe this system as mathematically private, confidential from Helpers, or secure against a compromised paired device.

The Legal Case Room holds imported document text in screen memory until cleared or the screen is discarded. This is not secure memory erasure; Android may capture app surfaces or retain process memory. MeshAI does not currently provide a privacy audit, hardware-backed confidential execution, encrypted-at-rest document vault, or proof that third-party Android components cannot observe the screen.

## Vision plus text

llama.cpp's multimodal path uses a text GGUF plus its matching multimodal projector (`mmproj`). The Host process accepts the image through the OpenAI-compatible chat-completions endpoint, encodes the image, and passes model work through the same model runtime. This is a compatible way to combine vision and text for Origami without a separate caption model. A separate vision model followed by a text model is possible, but it adds a second model load and another orchestration hop; it is not the first design to ship.

The current Android router requires an installed matching projector before selecting a model for a photo. It does not download an absent projector automatically. Actual image inference on the currently connected phones still needs a device run to confirm the installed `mmproj` and packaged `libmtmd` work together.

## Reasonable next use cases

1. **Legal Case Room (local prototype):** local PDF/TXT/Markdown selection, extraction, and lexical passage retrieval with source references. AI answers with citation validation, timeline/comparison workflows, and OCR remain future gates.
2. **Scanned document review:** OCR/vision for scanned pages, after a compatible local vision path is verified.
3. **Private photo intake:** image plus text for receipt fields, room measurements, or object identification, with user-confirmed results. Do not present vision estimates as verified measurements.
4. **Origami:** image/text request to supported deterministic furniture templates, then inspect 3D geometry and exports. Current V1 remains an organizer generator; broader tables/beds/building designs need deterministic engineering templates.

Prefer a narrow menu of supported actions, explicit model/input disclosure, and a visible per-request device route over an unrestricted “AI can do anything” entry point.

## Research references

- [llama.cpp multimodal documentation](https://github.com/ggml-org/llama.cpp/blob/master/tools/mtmd/README.md): model-specific projector and multimodal architecture.
- [llama.cpp server documentation](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md): OpenAI-compatible chat endpoint and multimodal support.
- [llama.cpp RPC documentation](https://github.com/ggml-org/llama.cpp/blob/master/tools/rpc/README.md): distributed backend; upstream explicitly warns RPC is proof-of-concept and unsafe on open networks.
- [llama.cpp RPC implementation](https://github.com/ggml-org/llama.cpp/blob/master/ggml/src/ggml-rpc/ggml-rpc.cpp): `graph_recompute` debug log and backend call; successful `graph_compute` currently has no matching activity log.
- [llama.cpp server API-key settings](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md): `LLAMA_API_KEY` / bearer authentication for model API routes.
