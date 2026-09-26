#!/usr/bin/env python3
"""
Turn the benchmark runs into one table and one chart.

    scripts/bench.py --label "phone alone, 1.7B"        ...
    scripts/bench.py --label "laptop alone, 8B"         ...
    scripts/bench.py --label "laptop + 2 phones, 30B"   ...
    scripts/bench-report.py

Reads every results/*.json, writes results/REPORT.md and results/accuracy.svg.
Order the runs smallest to largest with --order so the chart reads left to right.

Standard library only: the chart is written as SVG by hand, so this works offline.
"""

import argparse
import json
import pathlib

RESULTS = pathlib.Path(__file__).resolve().parent.parent / "results"

INK, MUTED, BAR, BAR2, GRID = "#111418", "#5b6475", "#f26b1d", "#3d6bff", "#d8dde6"
PAPER = "#fff"   # what the chart sits on, used to keep a label readable where it crosses a bar


def bars(runs, width=760, height=320):
    """One chart: accuracy as bars, speed as a line of labelled dots."""
    pad_l, pad_r, pad_t, pad_b = 56, 56, 28, 74
    w = width - pad_l - pad_r
    h = height - pad_t - pad_b
    n = max(len(runs), 1)
    slot = w / n
    bw = min(slot * 0.5, 90)
    top_tps = max([r["median_tps"] for r in runs] + [1])

    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
           f'font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="12">',
           f'<rect width="{width}" height="{height}" fill="{PAPER}"/>']

    for pct in (0, 25, 50, 75, 100):
        y = pad_t + h - h * pct / 100
        out.append(f'<line x1="{pad_l}" y1="{y:.1f}" x2="{pad_l+w}" y2="{y:.1f}" stroke="{GRID}"/>')
        out.append(f'<text x="{pad_l-8}" y="{y+4:.1f}" text-anchor="end" fill="{MUTED}">{pct}%</text>')

    pts = []
    for i, r in enumerate(runs):
        cx = pad_l + slot * i + slot / 2
        bh = h * r["accuracy"] / 100
        x = cx - bw / 2
        y = pad_t + h - bh
        out.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bw:.1f}" height="{bh:.1f}" fill="{BAR}"/>')
        out.append(f'<text x="{cx:.1f}" y="{y-7:.1f}" text-anchor="middle" fill="{INK}" '
                   f'font-weight="bold">{r["accuracy"]:.0f}%</text>')
        # keep the speed line inside the plot and clear of the title
        ty = pad_t + h - (h * 0.82) * min(r["median_tps"] / top_tps, 1.0)
        pts.append((cx, ty, r["median_tps"], y))
        for j, line in enumerate(wrap(r["label"])):
            out.append(f'<text x="{cx:.1f}" y="{pad_t+h+18+j*14:.1f}" text-anchor="middle" '
                       f'fill="{MUTED}">{esc(line)}</text>')

    if len(pts) > 1:
        out.append('<polyline fill="none" stroke="{}" stroke-width="2" stroke-dasharray="5 4" points="{}"/>'
                   .format(BAR2, " ".join(f"{x:.1f},{y:.1f}" for x, y, _, _ in pts)))
    for x, y, tps, bar_top in pts:
        out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="{BAR2}"/>')
        # above the dot normally; beside it when that would land on the bar or the title
        # a label centred above the dot lands on the bar whenever the dot sits below the bar's top,
        # so it moves beside the dot and carries the paper behind it to stay readable over a bar
        label = f"{tps:.0f} tok/s"
        w = len(label) * 6.4 + 8
        if y - 12 < pad_t + 6 or y > bar_top - 14:
            lx, ly, anchor = x + 10, y + 4, "start"
            rx = lx - 4
        else:
            lx, ly, anchor = x, y - 11, "middle"
            rx = lx - w / 2
        out.append(f'<rect x="{rx:.1f}" y="{ly - 11:.1f}" width="{w:.1f}" height="15" fill="{PAPER}"/>')
        out.append(f'<text x="{lx:.1f}" y="{ly:.1f}" text-anchor="{anchor}" fill="{BAR2}">{label}</text>')

    out.append(f'<text x="{pad_l}" y="16" fill="{INK}" font-weight="bold">'
               f'accuracy (bars) and speed (dots) as devices join</text>')
    out.append(f'<text x="{pad_l}" y="{height-8}" fill="{MUTED}">'
               f'same {runs[0]["tasks"] if runs else 0} coding tasks, same scoring, executed against their tests</text>')
    out.append("</svg>")
    return "\n".join(out)


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def wrap(label, width=18):
    words, lines, cur = label.split(), [], ""
    for word in words:
        if len(cur) + len(word) + 1 > width and cur:
            lines.append(cur)
            cur = word
        else:
            cur = f"{cur} {word}".strip()
    if cur:
        lines.append(cur)
    return lines[:3]


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--order", nargs="*", default=None,
                   help="labels in the order they should appear, smallest setup first")
    args = a.parse_args()

    runs = [json.loads(p.read_text()) for p in sorted(RESULTS.glob("*.json"))]
    if not runs:
        print("no results yet: run scripts/bench.py first")
        return
    if args.order:
        rank = {l.lower(): i for i, l in enumerate(args.order)}
        runs.sort(key=lambda r: rank.get(r["label"].lower(), 99))

    rows = ["| setup | accuracy | passed | median speed | median per task | total | measured |",
            "|---|---|---|---|---|---|---|"]
    for r in runs:
        rows.append(f"| {r['label']} | **{r['accuracy']}%** | {r['passed']}/{r['tasks']} | "
                    f"{r['median_tps']} tok/s | {r['median_seconds']} s | {r['total_seconds']} s | {r['when']} |")

    notes = [f"- **{r['label']}**: {r['note']}" for r in runs if r.get("note")]
    md = ["# What each configuration is worth", "",
          f"The same {runs[0]['tasks']} coding problems, asked of every setup, scored by running the",
          "answers against their tests. No partial credit, no human judgement.", "",
          *rows, "",
          "![accuracy](accuracy.svg)", ""]
    if notes:
        md += ["## Conditions", "", *notes, ""]
    md += ["## Failures", ""]
    for r in runs:
        bad = [x for x in r["results"] if not x["ok"]]
        md.append(f"**{r['label']}** failed {len(bad)}: " +
                  (", ".join(f"{x['id']} ({x['why']})" for x in bad) if bad else "none"))
        md.append("")

    (RESULTS / "REPORT.md").write_text("\n".join(md))
    (RESULTS / "accuracy.svg").write_text(bars(runs))
    print("\n".join(rows))
    print(f"\nwritten to {RESULTS/'REPORT.md'} and {RESULTS/'accuracy.svg'}")


if __name__ == "__main__":
    main()
