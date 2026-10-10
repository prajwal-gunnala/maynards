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
