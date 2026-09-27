#!/usr/bin/env bash
# Aider against the mesh. Run inside the git repo to work on:  run-aider.sh [test command] [aider args...]
# The host must run with MESH_CTX=16384 so Aider's prompts fit.
here="$(cd "$(dirname "$0")" && pwd)"
test_cmd="${1:-pytest -q}"; [ $# -gt 0 ] && shift
exec aider \
  --model openai/mesh \
  --openai-api-base "${MESH_URL:-http://localhost:8080/v1}" --openai-api-key "${MESH_KEY:-local}" \
  --model-settings-file "$here/model-settings.yml" --model-metadata-file "$here/model-metadata.json" \
  --map-tokens 1024 --test-cmd "$test_cmd" --auto-test \
  --no-show-model-warnings --analytics-disable --no-check-update --no-show-release-notes \
  --timeout 1800 "$@"
