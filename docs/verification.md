# Testing & Verification Guide: Ned Capsule & Runtime Integration

This guide outlines the procedures for verifying the Ned Capsule wire contract, both via local protocol testing and on target Android hardware.

---

## 1. Automated Wire Protocol Tests (Host/Local)

To verify schema conformance, backwards compatibility, and handshake semantics without target hardware:

```bash
python3 scripts/test_capsule_wire.py
```

### What It Verifies:
1. **Schema Integrity:** Ensures emitted capsule payloads contain all required fields (`capsule_id`, `task`, `model_info`, `layers_slice`, `runtime`, and `evidence`).
2. **Dual-Wire Compatibility:** Confirms top-level legacy fields (`layers: "9-27"`, `model: "..."`) are populated for un-updated devices, and confirms the `Capsule.kt` parser gracefully falls back when receiving legacy 3-field `"t": "run"` messages.
3. **Acknowledgment Handshake:** Confirms helper `"ready"` responses carry `capsule_id` matching the issued contract.
4. **Stale Capsule Detection:** Confirms Host flags outdated/mismatched capsule IDs across re-emission cycles.

---

## 2. On-Device Hardware Verification (Target Device)

Follow these steps on a machine equipped with the Android SDK/NDK and connected iQOO 15 hardware:

### Step 1: Build the Android APK
```bash
cd android
./gradlew assembleDebug
```
Ensure the APK builds cleanly with the updated `Capsule.kt`, `Engine.kt`, and `MeshClient.kt`.

### Step 2: Deploy & Monitor Helper
1. Install the debug APK to the phone via adb:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Launch the app in Helper mode:
   ```bash
   adb shell am start -n ai.maynards.mesh/.MainActivity
   ```
3. Monitor logcat for capsule receipt and engine startup:
   ```bash
   adb logcat -s MeshClient Engine
   ```
   **Expected logs:**
   - `handleCapsuleOrRun`: parsed capsule ID (e.g. `cap-...`), backend `opencl`/`cpu`, threads `6`.
   - Process command spawned with `-t 6 -c`.
   - Outgoing acknowledgment: `{"t": "ready", "addr": "...", "capsule_id": "cap-..."}`.

### Step 3: Validate Host & Dashboard Rendering
1. Launch the laptop host orchestrator:
   ```bash
   cargo run --bin mesh -- host
   ```
2. Open `http://localhost:8080` in Chrome.
3. Connect the phone helper via USB / QR code.
4. Select a model and initiate a run.
5. In the device card, verify:
   - Capsule ID and Task badge appear.
   - Active Backend (`opencl` or `cpu`) and Thread count match the capsule.
   - Evidence chip displays `✓ Gate: 14/15 · Runs: [412,418,431]`.

---

## 3. Remote Relay Testing & Deployment (Part 2)

### Automated Relay Wire Test (Local)
To verify relay matching, handshake slicing, and bidirectional piping:
```bash
python3 scripts/test_relay_wire.py
```

### Running the Public / VPS Relay
To enable remote devices over mobile data (behind NAT) to join:
1. Start the relay on a public VPS or test machine:
   ```bash
   python3 scripts/relay.py --port 7071 --bind 0.0.0.0
   ```
2. Launch `mesh host` with `MESH_RELAY`:
   ```bash
   MESH_RELAY="your-vps.com:7071" cargo run --bin mesh -- host
   ```
   The generated QR code and invite payload will include `"relay": "your-vps.com:7071"`.
3. If the phone is not on the same LAN / cable, `MeshClient.kt` automatically dials the relay, negotiates the matching token, and splices the connection transparently.

---

## 4. Supervisor Fault Tolerance & Automatic Re-Plan Recovery (Part 3)

### Automated Supervisor Wire Test (Local)
To verify survivor detection, automatic layer re-planning upon node departure, capsule re-emission, and in-flight request buffer recovery:
```bash
python3 scripts/test_supervisor_wire.py
```

### On-Device Hardware Verification: Mid-Run Node Departure
Follow these steps with multiple phones or simulated workers:
1. **Initial Cluster Setup:**
   - Launch Host orchestrator with a model partitioned across 2+ helpers (e.g. Host + Phone A + Phone B).
   - Initiate a continuous stream or prompt evaluation via `scripts/bench.py` or the `/v1/chat/completions` endpoint.
2. **Simulate Helper Dropout:**
   - On Phone A: Toggle Airplane Mode, terminate the app via `adb shell am force-stop ai.maynards.mesh`, or pull the USB cable.
3. **Observe Host Dashboard & Activity Feed:**
   - Host detects control loss / engine exit.
   - Host supervisor inspects surviving devices (`Phone B` + Host).
   - If survivors have enough capacity:
     - `⚡ Supervisor: engine stopped. Surviving devices can host <model>. Auto-recovering...`
     - Automatic re-distribution of layers (e.g. from 3-way split to 2-way split).
     - New Ned Capsules emitted to survivors with updated layer bounds.
     - New `llama-server` engine spawned and health-checked.
     - Replay of any mid-flight user request: `✓ Supervisor: in-flight request recovered and replayed successfully`.
   - If survivors cannot fit the model:
     - Graceful failure report with capacity shortage diagnostics instead of an uncaught crash.
4. **On-Device Android Host Recovery:**
   - If running an Android phone as Host (`Runner.kt`), a helper `"gone"` event triggers `Planner.plan(model, survivorDevices)` and automatically transitions `Runner` into `STARTING` $\rightarrow$ `READY` across survivors without UI lockup.

