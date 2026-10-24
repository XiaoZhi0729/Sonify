#!/usr/bin/env python3
"""Aggregate perf_p0.py result files into a table / markdown report.

Usage: python tools/perf_summary.py perf/p0/summary_YYYYmmdd_HHMMSS.json
"""
import json
import sys
import os
import statistics


def med(v):
    v = [x for x in v if x is not None]
    return round(statistics.median(v), 2) if v else float("nan")


def main():
    path = sys.argv[1]
    rows = json.load(open(path, encoding="utf-8"))
    by = {}
    for r in rows:
        by.setdefault(r["tag"], []).append(r)

    order = ["s1a_expand", "s1b_collapse", "s1c_lyric_in", "s1c_queue_in",
             "s2a_enter_playlist", "s2b_exit_playlist", "s2c_fling_playlist",
             "s3a_enter_artist", "s3b_exit_artist", "s3c_scroll_artist"]
    tags = [t for t in order if t in by] + [t for t in by if t not in order]

    lines = []
    lines.append("| 场景 | n | 帧数 | p50 | p90 | p95 | max | miss2% | miss3% |")
    lines.append("|---|---|---|---|---|---|---|---|---|")
    print("%-22s %3s %6s %7s %7s %7s %7s %7s %7s" %
          ("scenario", "n", "frames", "p50", "p90", "p95", "max", "miss2", "miss3"))
    for t in tags:
        all_rs = [r for r in by[t] if r.get("frames")]
        # rep 1 is the warm-up and is discarded (hot scenarios only)
        rs = [r for r in all_rs if r.get("rep", 1) > 1] or all_rs
        if not rs:
            print("%-22s FAILED (%s)" % (t, by[t][0].get("error", "")[:40]))
            continue
        p50 = med([r.get("ui_p50") for r in rs])
        p90 = med([r.get("ui_p90") for r in rs])
        p95 = med([r.get("ui_p95") for r in rs])
        mx = med([r.get("max") for r in rs])
        m2 = med([r.get("miss2") for r in rs])
        m3 = med([r.get("miss3") for r in rs])
        fr = med([r.get("frames") for r in rs])
        print("%-22s %3d %6.0f %7.2f %7.2f %7.2f %7.2f %7.1f %7.1f" %
              (t, len(rs), fr, p50, p90, p95, mx, m2, m3))
        lines.append("| %s | %d | %.0f | %.2f | %.2f | %.2f | %.2f | %.1f | %.1f |" %
                     (t, len(rs), fr, p50, p90, p95, mx, m2, m3))

    out = os.path.splitext(path)[0] + "_report.md"
    open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("WROTE", out)


if __name__ == "__main__":
    main()
