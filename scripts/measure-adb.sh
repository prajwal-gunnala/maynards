#!/usr/bin/env bash
# What adb costs for the three jobs the Office Kit slide compares: moving a big file to the phone, sending one
# input to the phone, and getting a screenshot back to the laptop.
#   scripts/measure-adb.sh [-s serial] [size_mb]
# The "tap" is KEYCODE_UNKNOWN: it travels the same path as a tap (adb -> input service) but presses nothing.
# The test file is random bytes (nothing to compress), written to the phone's Download folder and deleted after.
set -euo pipefail
ADB=(adb)
if [ "${1:-}" = "-s" ]; then ADB=(adb -s "$2"); shift 2; fi
MB=${1:-1024}
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
dest=/sdcard/Download/meshai-adb-speed.bin
now() { date +%s.%N; }

head -c $((MB * 1024 * 1024)) /dev/urandom > "$tmp/f.bin"
t0=$(now); "${ADB[@]}" push "$tmp/f.bin" "$dest" > /dev/null; t1=$(now)
"${ADB[@]}" shell rm -f "$dest"
push_s=$(echo "$t1 - $t0" | bc -l)
mbs=$(echo "$MB / $push_s" | bc -l)
printf 'push      %d MB in %.1f s = %.1f MB/s -> 18.6 GB in %.1f min\n' "$MB" "$push_s" "$mbs" "$(echo "18600 / $mbs / 60" | bc -l)"

taps=()
for i in $(seq 20); do
  t0=$(now); "${ADB[@]}" shell input keyevent 0; t1=$(now)
  taps+=("$(echo "($t1 - $t0) * 1000" | bc -l)")
done
printf 'input     median %.0f ms over 20 (adb shell input keyevent)\n' "$(printf '%s\n' "${taps[@]}" | sort -n | sed -n 10p)"

shots=()
for i in $(seq 5); do
  t0=$(now); "${ADB[@]}" exec-out screencap -p > "$tmp/s.png"; t1=$(now)
  shots+=("$(echo "($t1 - $t0) * 1000" | bc -l)")
done
printf 'screenshot median %.0f ms over 5, %d KB PNG (adb exec-out screencap -p)\n' \
  "$(printf '%s\n' "${shots[@]}" | sort -n | sed -n 3p)" "$(( $(stat -c %s "$tmp/s.png") / 1024 ))"
