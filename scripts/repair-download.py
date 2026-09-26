#!/usr/bin/env python3
"""Re-fetch the parts of a downloaded file that came out as zeros (a browser's parallel download can leave holes).

    scripts/repair-download.py <file> <url> [sha256]

Finds every all-zero 1 MB block and downloads just those bytes with HTTP Range, 16 MB per request, retrying each
piece up to 10 times, writing each piece in place as it arrives. Safe to stop and run again: finished pieces are no
longer zero, so they are skipped. Checks the SHA-256 at the end if one is given.
"""
import hashlib
import sys
import time
import urllib.request

MB = 1 << 20
PIECE = 16 * MB
path, url = sys.argv[1], sys.argv[2]
want = sys.argv[3] if len(sys.argv) > 3 else None


def empty_blocks():
    zero = bytes(MB)
    out = []
    with open(path, "rb") as f:
        i = 0
        while True:
            b = f.read(MB)
            if not b:
                return out, i
            if b == zero[: len(b)]:
                out.append(i)
            i += 1


def fetch(f, start, end):
    """Download bytes [start, end] into the file, retrying on network errors."""
    for attempt in range(10):
        try:
            req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
            with urllib.request.urlopen(req, timeout=30) as r:
                assert r.status == 206, f"server ignored Range: {r.status}"
                data = r.read()
            assert len(data) == end - start + 1, f"short read {len(data)}"
            f.seek(start)
            f.write(data)
            f.flush()
            return
        except Exception as e:  # network hiccup: wait and try again
            print(f"  retry {attempt + 1} at {start // MB} MB: {e}", flush=True)
            time.sleep(min(2 ** attempt, 30))
    raise SystemExit(f"gave up at {start // MB} MB")


holes, blocks = empty_blocks()
# the block just before and after each hole may be half-written: fetch those too
todo = sorted({b + d for b in holes for d in (-1, 0, 1) if 0 <= b + d < blocks})
print(f"{len(holes)} empty MB, fetching {len(todo)} MB", flush=True)

size = None
with open(path, "r+b") as f:
    f.seek(0, 2)
    size = f.tell()
    i, done, t0 = 0, 0, time.time()
    while i < len(todo):
        # group consecutive blocks into pieces of at most 16 MB
        j = i
        while j + 1 < len(todo) and todo[j + 1] == todo[j] + 1 and (todo[j + 1] - todo[i] + 1) * MB <= PIECE:
            j += 1
        start, end = todo[i] * MB, min((todo[j] + 1) * MB, size) - 1
        fetch(f, start, end)
        done += j - i + 1
        i = j + 1
        rate = done / max(time.time() - t0, 1)
        print(f"  {done}/{len(todo)} MB  {rate:.1f} MB/s  ~{(len(todo) - done) / max(rate, 0.01) / 60:.0f} min left", flush=True)

if want:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8 * MB), b""):
            h.update(chunk)
    ok = h.hexdigest() == want
    print("sha256", h.hexdigest(), "OK" if ok else "MISMATCH", flush=True)
    sys.exit(0 if ok else 1)
