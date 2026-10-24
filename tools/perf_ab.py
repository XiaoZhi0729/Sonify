#!/usr/bin/env python3
"""A/B/B/A interleaved regression for the two alpha-layer fixes.

Protocol (agreed before running):
  - both APKs installed in one session, `cmd package compile -m speed -f` after every install
  - order per cycle: A B B A, 5 cycles
  - reinstall only when the variant actually changes
  - battery temperature recorded before/after each run; wait until it is back to
    baseline +-0.1 C (battery reports 0.1 C units) before the next run
  - brightness / IME / DiagLogEnabled untouched and recorded once
  - one gesture = one reset -> tap playlist -> settle -> framestats window

Usage: python tools/perf_ab.py <cycles> [--trace]
"""
import json
import os
import re
import statistics
import subprocess
import sys
import time

SERIAL = os.environ.get("PERF_SERIAL", "adb-a339c54a-aGYkNW._adb-tls-connect._tcp")
PKG = "com.sonify.music"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "perf", "p0", "ab_alpha")
APK = {
    "A": os.path.join(ROOT, "perf", "app-release-b.apk"),
    "B": os.path.join(ROOT, "perf", "app-release-batch1.apk"),
}
TAB_LIB = (785, 2582)
LIB_PLAYLISTS = (640, 1940)
PL_FIXED = (640, 1669)

VSYNC = 1000.0 / 120.0


def sh(cmd, timeout=60):
    p = subprocess.run(["adb", "-s", SERIAL, "shell", cmd],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    return p.stdout.decode("utf-8", "replace")


def sh_out(cmd, timeout=60):
    p = subprocess.run(["adb", "-s", SERIAL, "exec-out", cmd],
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout)
    return p.stdout


def sleep(s):
    time.sleep(s)


def temp():
    t = sh("dumpsys battery | grep temperature")
    m = re.search(r"temperature:\s*(\d+)", t)
    return int(m.group(1)) if m else -1


def sha(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def install(variant):
    path = APK[variant]
    out = subprocess.run(["adb", "-s", SERIAL, "install", "-r", path],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.decode("utf-8", "replace")
    if "Success" not in out:
        raise RuntimeError("install %s failed: %s" % (variant, out.strip()[-200:]))
    sh("cmd package compile -m speed -f %s" % PKG)


def launch():
    sh("am force-stop %s" % PKG)
    sleep(1.0)
    sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % PKG)
    sleep(6.0)


def dump():
    for _ in range(6):
        sh("rm -f /sdcard/ui.xml")
        sh("uiautomator dump /sdcard/ui.xml")
        x = sh_out("cat /sdcard/ui.xml").decode("utf-8", "replace")
        if len(x) > 500 and PKG in x:
            return x
        sleep(0.6)
    return x


PLAYLISTS_PAGE_MARK = "中英平淡"   # only present on the playlists list, not on the Library root


def goto_playlists():
    for _ in range(3):
        x = dump()
        if PLAYLISTS_PAGE_MARK in x:
            return
        if "我的在线歌单" in x and 'content-desc="资料库"' in x:
            # Library root: the row label also reads 我的在线歌单, so tap into the list
            sh("input tap %d %d" % LIB_PLAYLISTS)
            sleep(2.0)
            continue
        if 'content-desc="资料库"' in x:
            sh("input tap %d %d" % TAB_LIB)
            sleep(1.5)
            continue
        # detail page: back out, but never press BACK at a tab root (it would exit the app)
        sh("input keyevent 4")
        sleep(0.8)
    raise RuntimeError("cannot reach online playlists page")


def parse(fs):
    rows, inb = [], False
    for line in fs.splitlines():
        if line.startswith("---PROFILEDATA---"):
            inb = not inb
            continue
        if not inb:
            continue
        c = line.split(",")
        if len(c) < 24:
            continue
        try:
            v = [int(x) if x.strip() else 0 for x in c[:25]]
        except ValueError:
            continue
        if v[2] <= 0 or v[18] <= 0 or v[18] < v[2]:
            continue
        rows.append((v[18] - v[2]) / 1e6)
    if not rows:
        return {}
    s = sorted(rows)
    p = lambda q: s[min(len(s) - 1, int(round(q * (len(s) - 1))))]
    return {
        "frames": len(rows),
        "p50": round(p(.5), 2), "p90": round(p(.9), 2), "p95": round(p(.95), 2),
        "max": round(max(rows), 2),
        "miss2": round(100.0 * sum(1 for x in rows if x > 2 * VSYNC) / len(rows), 1),
        "miss3": round(100.0 * sum(1 for x in rows if x > 3 * VSYNC) / len(rows), 1),
    }


def measure(variant, cycle, pos, baseline_c):
    # cooldown to baseline +-0.1 C
    for _ in range(40):
        if temp() <= baseline_c + 1:
            break
        sleep(2)
    t_before = temp()
    goto_playlists()
    sh("dumpsys gfxinfo %s reset" % PKG)
    sleep(0.15)
    sh("input tap %d %d" % PL_FIXED)
    sleep(2.2)
    fs = sh("dumpsys gfxinfo %s framestats" % PKG)
    t_after = temp()
    r = parse(fs)
    r.update({"variant": variant, "cycle": cycle, "pos": pos,
              "temp_before": t_before, "temp_after": t_after})
    sh("input keyevent 4")
    sleep(1.0)
    return r


def main():
    cycles = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    os.makedirs(OUT, exist_ok=True)
    for v, p in APK.items():
        if not os.path.exists(p):
            raise SystemExit("missing %s APK: %s" % (v, p))
    meta = {v: sha(p) for v, p in APK.items()}
    print("APK sha256:", json.dumps(meta))
    open(os.path.join(OUT, "apk_sha.txt"), "w").write(
        "A %s\nB %s\n" % (meta["A"], meta["B"]))
    env = sh("dumpsys battery | grep -E 'level|temperature'") + \
        sh("settings get system screen_brightness") + \
        sh("settings get secure default_input_method") + \
        sh("dumpsys display | grep -m1 mActiveSfDisplayMode=")
    open(os.path.join(OUT, "env.txt"), "w", encoding="utf-8").write(env)
    baseline_c = temp()
    print("baseline battery temperature:", baseline_c)

    current = None
    rows = []
    order = ["A", "B", "B", "A"]
    for cycle in range(1, cycles + 1):
        for pos, v in enumerate(order, 1):
            if v != current:
                install(v)
                launch()
                current = v
            else:
                # same variant: just make sure we are on the playlists page, no reinstall
                dump()
            r = measure(v, cycle, pos, baseline_c)
            rows.append(r)
            print(json.dumps(r))
            with open(os.path.join(OUT, "ab_results.jsonl"), "w", encoding="utf-8") as f:
                for x in rows:
                    f.write(json.dumps(x, ensure_ascii=False) + "\n")
    print("DONE", len(rows))


if __name__ == "__main__":
    main()
