# Data flow: where a Case Room or Code answer's data goes

Written 2026-10-11 against commit `189d853` and the USB-only phone run of 07:06–07:08 (Host iQOO `…00XP`,
Helper iQOO `…01GG`, Qwen3.5-9B Q4_K_M split 8:24, `scripts/link-watch.py` running). Each line says how it was
checked: **code** (file and function), **measured** (a run), or **not verified**.

## The path of one question

| # | Data | Where it is | Who can see it | How checked |
|---|---|---|---|---|
| 1 | Original file (PDF/TXT/MD, or a code folder) | read through Android's picker into the Host app's memory; never copied to app storage | the Host app; the file's own provider (a cloud provider may fetch it when opened) | code: `CaseDocuments.load`, `CodeFiles.load` read bytes into memory only |
| 2 | Extracted text and passages | Host app memory (screen state) until Clear/Close or the app ends | the Host app | code: `PrivateUseTab`, `DeveloperTab` hold `documents` / `project` in Compose state; no write calls |
| 3 | Search (which passages match) | Host CPU, in memory | the Host app | code: `CaseDocuments.retrieve` — pure function, no I/O |
| 4 | Question + matched passages (the prompt) | HTTP POST to `127.0.0.1:8080` on the Host | the Host's llama-server | code: `AskSession.ask` → `Chat(runner.endpoint)`, `endpoint = http://127.0.0.1:8080` |
| 5 | Embeddings and output head | Host only | Host | code: `EngineArgs.HEAD_ON_HOST` pins `token_embd`, `output`, `output_norm` to the Host CPU |
| 6 | Hidden-state activations, one per token per boundary | Host ↔ Helper over the llama.cpp RPC connection | the Helper holding layers (it computes on them) | measured: 218 samples, every RPC connection on `rndis0` (USB), none on Wi-Fi; ~550 MB crossed at load (weights), little per answer |
| 7 | Answer text | streamed back to the Host app over loopback; shown on screen | the Host app | code: `Chat.ask` stream; `CitedAnswer` |
| 8 | Citation check | Host memory | the Host app | code: `CaseCitations.check` — pure function |
| 9 | Numbers about the answer (tokens, tok/s, times, model, devices) | Host `SharedPreferences` "mesh-stats" (last 100) and the in-memory Spider Sense log | the Host app | code: `Stats.add` stores numbers only; `Sense` holds names, counts, timings |

## What never leaves the Host

- The original file, its extracted text, the question and the answer text: no code path sends them to a
  Helper, the laptop, the remote signalling service or any cloud service (code: the only network calls carrying
  them are `Chat.ask` to `127.0.0.1`).
- Spider Sense and Stats hold no prompt, document or answer text (code: `Sense` and `Record` fields).
- Nothing is written to disk: no copy of a case file or code file in app storage (code: no file writes in the
  case/code paths).
- Android backup is off (`android:allowBackup="false"`), so app settings — pairing secrets, the local API key,
  stats — are not copied to a cloud backup.

## What a Helper does receive

- Its assigned layers' weights (from the Host over RPC, or its own copy of the model file).
- Activations for every token of the prompt and answer, at its layer boundary. These are derived from the
  text; a Helper's owner could in principle try to infer content from them. That is why the Case Room asks
  the user to tick each Helper before sending, and asks again when the set of Helpers changes.

## Known limits (not hidden)

- Local TCP between phones is not encrypted; on USB it runs over the cables through the laptop relay, which
  forwards bytes and could read them. WebRTC links are encrypted in transit.
- llama-server listens on all interfaces (port 8080) with a bearer key, so a paired laptop can use `mesh ask`;
  the Case Room and Code rooms only use loopback.
- The engine keeps its last 60 log lines in memory; whether llama-server's normal log lines include prompt
  text was **not verified** (verbose logging is not enabled).
- Clearing a case is not secure memory erasure; Android may keep process memory or screen captures.
- Not a compliance or enterprise-security claim; no external review has been done.

## How to repeat the measured part

```bash
python3 scripts/phone-usb-relay.py HOST_SERIAL HELPER_SERIAL --join-helper   # USB-only pairing
python3 scripts/link-watch.py HOST_SERIAL HELPER_SERIAL > link.log           # then ask in the Case Room
grep -c "via Wi-Fi" link.log; grep -c "via USB" link.log
```
