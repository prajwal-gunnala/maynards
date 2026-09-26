#!/usr/bin/env bash
# Keep a phone generating for N minutes and log speed, temperature and battery every round.
# Phones slow down in steps as they heat up; this shows where.
#   scripts/sustained.sh <serial> <host ip> [minutes=10] > docs/sustained.csv
set -euo pipefail
SERIAL=$1 HOST=$2 MINUTES=${3:-10}
END=$(( $(date +%s) + MINUTES * 60 ))
PROMPT='Write a Python function that parses a CSV line with quoted fields, with a docstring and two tests.'

echo "time,seconds,tok_per_s,tokens,battery_temp_c,skin_temp_c,battery_pct"
t0=$(date +%s)
while [ "$(date +%s)" -lt "$END" ]; do
  tps=$(curl -s "http://$HOST:8080/v1/chat/completions" -H 'content-type: application/json' \
    -d "{\"messages\":[{\"role\":\"user\",\"content\":\"$PROMPT\"}],\"max_tokens\":200,\"cache_prompt\":false}" |
    python3 -c 'import json,sys; t=json.load(sys.stdin)["timings"]; print("%.2f,%d" % (t["predicted_per_second"], t["predicted_n"]))')
  # Android's thermal service reports each sensor as Temperature{mValue=.., mType=.., mName=..}
  temps=$(adb -s "$SERIAL" shell dumpsys thermalservice | sed -n '/Current temperatures from HAL/,/Current cooling devices/p')
  cpu=$(grep -oE "mValue=[0-9.]+, mType=2," <<<"$temps" | head -1 | grep -oE "[0-9.]+" | head -1)
  skin=$(grep -oE 'mValue=[0-9.]+, mType=3' <<<"$temps" | head -1 | grep -oE '[0-9.]+' | head -1)
  bat=$(adb -s "$SERIAL" shell dumpsys battery | awk '/level:/ {print $2; exit}')
  echo "$(date +%H:%M:%S),$(( $(date +%s) - t0 )),$tps,${cpu:-},${skin:-},${bat:-}"
  sleep 20
done
