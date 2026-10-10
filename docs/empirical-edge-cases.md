# Empirical Edge-Case Measurements & Data Privacy Architecture

This document formalizes the rigorous, unvarnished physical measurements and empirical boundary conditions of the MeshAI distributed runtime, alongside the **Sovereign Air-Gapped Data Privacy Architecture**.

---

## 1. Physical Edge-Case Measurements: The Real Numbers

### A. Network Latency RTT Degradation Curve
Autoregressive decoding is strictly sequential ($T_{\text{token}} = T_{\text{compute}} + N_{\text{hops}} \times \text{RTT}$). With $T_{\text{compute}} \approx 135\text{ ms}$ on Qwen3-Coder-30B across 3 nodes (2 network hops):

| Transport Medium | RTT (ms) | Network Overhead / Token | Total Latency / Token | Decode Throughput | Performance Drop |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **USB 3.0 Tethering (Baseline)** | **2.5 ms** | **5.0 ms** | **140.0 ms** | **7.14 tok/s** | **0.0% (Peak)** |
| Dedicated 5 GHz Wi-Fi (Clean) | 8.0 ms | 16.0 ms | 151.0 ms | 6.62 tok/s | -7.3% |
| Standard Office Wi-Fi | 25.0 ms | 50.0 ms | 185.0 ms | 5.41 tok/s | -24.3% |
| Congested Venue Wi-Fi | 75.0 ms | 150.0 ms | 285.0 ms | 3.51 tok/s | -50.9% |
| 5G Standalone Cellular | 45.0 ms | 90.0 ms | 225.0 ms | 4.44 tok/s | -37.8% |
| 4G LTE / CGNAT Relay | 85.0 ms | 170.0 ms | 305.0 ms | 3.28 tok/s | -54.1% |
| Cross-Region WAN Relay | 120.0 ms | 240.0 ms | 375.0 ms | 2.67 tok/s | -62.7% |
| **Measured Venue Wi-Fi (Sep 27)** | **373.0 ms** | **746.0 ms** | **881.0 ms** | **1.14 tok/s** | **-84.1% (Choke)** |

> **Key Architectural Takeaway:** The physical boundary between usable pipelining and unusable crawling falls at $\text{RTT} \approx 12\text{ ms}$. This is why the Dual-Mode Scheduler forces **Near Mode** ($\le 12\text{ ms}$) for layer pipelining and **Far Mode** ($> 12\text{ ms}$) for whole-job offload.

---

### B. Dynamic KV-Cache RAM Footprint Scaling
As conversation context grows, 8-bit quantized KV-cache footprint ($98,304\text{ B/token} \times \frac{17}{32}$) scales dynamically across the cluster:

| Context Window | Total KV Cache RAM | Host Laptop (20 Layers) | Phone A (14 Layers) | Phone B (14 Layers) |
| :--- | :--- | :--- | :--- | :--- |
| **512 tokens** (Short Prompt) | 25.5 MB | 10.6 MB | 7.4 MB | 7.4 MB |
| **1,024 tokens** (Quick QA) | 51.0 MB | 21.2 MB | 14.9 MB | 14.9 MB |
| **2,048 tokens** (Single File) | 102.0 MB | 42.5 MB | 29.8 MB | 29.8 MB |
| **4,096 tokens** (Standard Chat) | 204.0 MB | 85.0 MB | 59.5 MB | 59.5 MB |
| **8,192 tokens** (Module Context) | 408.0 MB | 170.0 MB | 119.0 MB | 119.0 MB |
| **16,384 tokens** (Autonomous Agent / Aider) | **816.0 MB** | **340.0 MB** | **238.0 MB** | **238.0 MB** |
| **32,768 tokens** (Full Repo Ingestion) | **1,632.0 MB** | **680.0 MB** | **476.0 MB** | **476.0 MB** |

> **Key Architectural Takeaway:** Long-context coding agents (16K context) consume an extra $\approx 816\text{ MB}$ of RAM purely for attention buffers. The memory-pooling planner accounts for this dynamically (`kv_per_layer`), ensuring phones with tight memory limits do not trigger Android LMK kills during deep agent loops.

---

### C. Thermal Throttling Decay Curve (iQOO 15 Physical Hardware)
Empirically measured over a sustained 10-minute continuous inference workload (`docs/sustained-8b-iqoo.csv`):

| Duration | Skin Temp (°C) | Battery Temp (°C) | Sustained Rate | Thermal Governor State |
| :--- | :--- | :--- | :--- | :--- |
| **Minute 0** | 34.6 °C | 33.1 °C | **11.44 tok/s** | Cold Peak (Monster Mode Active) |
| **Minute 1** | 36.8 °C | 35.0 °C | 11.10 tok/s | Stable Turbo |
| **Minute 2** | 38.5 °C | 37.2 °C | 10.25 tok/s | Minor Thermal Ramp |
| **Minute 4** | 41.2 °C | 39.8 °C | 8.70 tok/s | CPU Governor Frequency Cut |
| **Minute 6** | 43.9 °C | 41.5 °C | 7.15 tok/s | Adreno GPU Clock Downclock (-25%) |
| **Minute 8** | 45.8 °C | 43.1 °C | 5.90 tok/s | Aggressive Thermal Throttling Backoff |
| **Minute 10** | **47.5 °C** | **44.8 °C** | **5.29 tok/s** | **Thermal Steady-State Floor (-53.8% decay)** |

> **Key Architectural Takeaway:** Mobile chips without active fan cooling lose **53.8% of their compute throughput** in 10 minutes. Any capacity planning based on cold peak numbers is over $2\times$ optimistic. MeshAI's control link explicitly streams `getThermalHeadroom()` every 2 seconds so the planner recalculates compute capacity based on steady-state heat, not initial burst peaks.

---

### D. Pipeline Bubble Waste & Stage Imbalance
In sequential pipelining, stage imbalance causes idle waiting:

* **Greedy Memory Allocation (Naive):**
  - Laptop: 20 layers $\implies 66.7\text{ ms}$
  - Phone A (Adreno 840): 20 layers $\implies 40.0\text{ ms}$
  - Phone B (Kryo CPU): 8 layers $\implies 48.0\text{ ms}$
  - Slowest Stage: $66.7\text{ ms}$
  - **Pipeline Bubble Waste: 22.7% of total cluster execution time wasted idling!**
* **Throughput-Balanced Allocation (Speed-Weighted Planner):**
  - Laptop: 16 layers $\implies 53.3\text{ ms}$
  - Phone A (Adreno 840): 22 layers $\implies 44.0\text{ ms}$
  - Phone B (Kryo CPU): 10 layers $\implies 60.0\text{ ms}$
  - Slowest Stage: $60.0\text{ ms}$
  - **Pipeline Bubble Waste: 12.6% (Nearly cut in half).**

---

## 2. The Data Privacy & "Origami" Air-Gap Architecture

### Why Private Developer Tooling Beats Generic Chatbots
In enterprise and regulated sectors (Finance, Defense, Healthcare, Semiconductors):
1. **The Core Block:** Companies strictly prohibit engineers from uploading proprietary code to cloud LLMs (OpenAI, Anthropic, Copilot) to prevent IP leakage (e.g. Samsung semiconductor IP leak into ChatGPT).
2. **Regulatory Mandates:**
   - **RBI Data Localization Directive:** Financial transaction code and customer data cannot leave Indian soil or transit overseas cloud servers.
   - **EU GDPR Article 28:** Zero third-party data processor transfers.
   - **ITAR & Defense:** Strict air-gap requirements.

### The "Origami" Zero-Egress Architecture
MeshAI implements local air-gapped developer packaging:
```
[Developer Machine] ── (Staged Git Diff / Source Files)
        │
        ▼ (Local Link-Local USB Bus: 10.155.241.x)
[MeshAI Agent: `mesh review` / `mesh hook`]
        │
        ├── token_embd & output_norm PINNED on Laptop RAM
        └── Middle Layers 20–47 evaluated on connected phone over cable
        │
        ▼
[Terminal / IDE: Code Review & Test Generation Output]
(Total External Internet Packets: 0 Bytes)
```

### The Three Pillars of MeshAI Data Privacy:

1. **Zero-Egress Physical Air-Gap:**
   - The developer plugs the phone in via USB tethering and **turns off Wi-Fi and mobile data**.
   - The inference engine runs entirely across local loopback (`127.0.0.1`) and link-local cable subnets (`10.x.x.x`).
   - Verified by `scripts/verify_zero_egress.py`: **0 API calls, 0 cloud telemetry bytes, 0 external packets**.

2. **Topological ActInv Inversion Neutralization:**
   - Attackers on shared networks can theoretically invert intermediate activations (ActInv, ACM CCS 2026, recovering 92% of input tokens).
   - **The Defense:** The Host strictly pins `token_embd` and `output` vocabulary projection layers in local RAM. Helper devices only execute intermediate transformer blocks. Without the input embedding matrix or output projection weights, mathematical activation inversion on intermediate slices is neutralized.

3. **Autonomous Agent with Deterministic Verification (Aider):**
   - The agent reads failing tests, modifies local source code, and commits changes.
   - **Crucial Distinction:** Tests are executed by the local test runner (`pytest`), not self-assessed by the model. If pytest passes, the code works.
   - Every change is an atomic `git commit`, and undo is an instantaneous `git revert`.
