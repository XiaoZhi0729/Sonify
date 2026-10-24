#!/usr/bin/env python3
"""P0 performance baseline runner for Flamingo/Sonify (release build).

Measurement protocol (see docs/PERF_TEST_PLAN.md):
  - one gesture = one reset -> gesture -> settle -> framestats window
  - primary metric is per-frame latency from framestats, not HWUI "Janky %"
  - page state is asserted with uiautomator anchors before every rep

Usage:
  python tools/perf_p0.py all 5
  python tools/perf_p0.py s1a_expand 5
"""
import os
import re
import subprocess
import sys
import time
import json
import statistics

SERIAL = os.environ.get("PERF_SERIAL", "adb-a339c54a-aGYkNW._adb-tls-connect._tcp")
PKG = "com.sonify.music"
ACT = PKG + "/yos.music.player.MainActivity"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUTROOT = os.path.join(ROOT, "perf", "p0")

VSYNC_MS = 1000.0 / 120.0  # device reports peakRefreshRate=120.00001

# --- calibrated on onyx 1280x2772 @520dpi -----------------------------------
TAB = {"home": (203, 2582), "fav": (494, 2582), "lib": (785, 2582), "search": (1076, 2582)}
MINI_SWIPE = (640, 2430, 640, 700, 180)      # mini -> full
HANDLE_DOWN = (640, 160, 640, 2400, 300)     # full -> mini via top handle
LYRIC_TAP = (640, 1400)                      # toggles controls on lyric page
BTN_LYRICS = (211, 2561)
BTN_COMMENT = (497, 2561)
BTN_QUEUE = (1069, 2561)
LIB_ONLINE_PLAYLISTS = (640, 1940)
PL_FIXED = (640, 1669)                       # "中英平淡" 23 songs
ARTIST_NAME = (378, 1692)                    # first row artist in that playlist
PLAYER_ARTIST = (165, 1704)                  # artist name under the expanded player title (Album page)


def sh(cmd, timeout=30):
    p = subprocess.run(
        ["adb", "-s", SERIAL, "shell", cmd],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    return p.stdout.decode("utf-8", "replace")


def sh_out(cmd, timeout=30):
    p = subprocess.run(["adb", "-s", SERIAL, "exec-out", cmd],
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout)
    return p.stdout


def sleep(s):
    time.sleep(s)


def dump():
    """uiautomator returns an empty/stale file when the app never reaches idle
    (continuous cover animations). Always delete first and retry on a short read."""
    for _ in range(6):
        sh("rm -f /sdcard/ui.xml")
        sh("uiautomator dump /sdcard/ui.xml")
        x = sh_out("cat /sdcard/ui.xml").decode("utf-8", "replace")
        if len(x) > 500:
            return x
        sleep(0.6)
    return x


def has(xml, needle):
    return needle in xml


def foreground():
    t = sh("dumpsys window | grep -m1 mCurrentFocus")
    return PKG in t


def ensure_fg():
    if not foreground():
        sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % PKG)
        sleep(4)
    if not foreground():
        raise RuntimeError("foreground is not %s" % PKG)


def tap(xy):
    sh("input tap %d %d" % xy)


def swipe4(t):
    sh("input swipe %d %d %d %d %d" % t)


def key(code):
    sh("input keyevent %d" % code)


def playback_playing():
    t = sh("dumpsys media_session")
    for block in t.split("package="):
        if PKG not in block:
            continue
        m = re.search(r"state=(?:[A-Z_]+\((\d+)\)|(\d+))", block)
        if m:
            code = int(m.group(1) or m.group(2))
            return code == 3
    return False


def ensure_paused():
    if playback_playing():
        key(127)
        sleep(0.6)


def state(xml):
    if "content-desc=\"Max Volume\"" in xml:
        return "album"
    if "content-desc=\"Mute\"" in xml:
        return "album"
    if "主页" in xml and "content-desc=\"主页\"" in xml and "Max Volume" not in xml:
        return "collapsed"
    return "other"


def dump_pkg(x):
    """Root package of a uiautomator dump, so a stale window cannot be mistaken for the app."""
    m = re.search(r'package="([^"]+)"', x or "")
    return m.group(1) if m else ""


def ensure_collapsed():
    """Land on a tab root with the player collapsed. Backs out of any detail page."""
    ensure_fg()
    for _ in range(10):
        if not foreground():
            force_restart()
        x = dump()
        if dump_pkg(x) != PKG:
            # uiautomator can latch onto a stale window (seen: reports com.miui.home while
            # the app is foreground). A cold restart re-binds it; retrying the dump does not.
            force_restart()
            continue
        if "Max Volume" in x or "Mute" in x:
            swipe4(HANDLE_DOWN)
            sleep(1.2)
            continue
        if "content-desc=\"主页\"" in x and "content-desc=\"资料库\"" in x:
            return x
        key(4)
        sleep(1.0)
    force_restart()
    x = dump()
    if "content-desc=\"主页\"" in x and "content-desc=\"资料库\"" in x:
        return x
    raise RuntimeError("could not return to a tab root")


def force_restart():
    sh("am force-stop %s" % PKG)
    sleep(1.5)
    sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % PKG)
    sleep(6.0)


def back_to_album():
    """Lyric/queue/comment -> Album. Controls auto-hide on Lyric, so wait then reveal."""
    x = dump()
    if "Max Volume" in x:
        return x
    sleep(2.8)
    tap(LYRIC_TAP)
    sleep(0.35)
    tap(BTN_LYRICS)
    sleep(1.0)
    x = dump()
    return x


def ensure_album():
    """Land on the expanded Album page (deterministic, may restart the app)."""
    ensure_fg()
    x = dump()
    if "content-desc=\"主页\"" in x:
        swipe4(MINI_SWIPE)
        sleep(1.8)
        x = dump()
    if "Max Volume" in x:
        return x
    x = back_to_album()
    if "Max Volume" in x:
        return x
    force_restart()
    ensure_fg()
    x = dump()
    swipe4(MINI_SWIPE)
    sleep(1.8)
    x = dump()
    if "Max Volume" not in x:
        raise RuntimeError("cannot reach Album page")
    return x


# --- metrics ----------------------------------------------------------------
FRAME_RX = re.compile(r"^(\d+),")


FLAG_BUFFER_STUFFING = 0x20   # HWUI produces slower than the display, buffers get reused


def parse_framestats(text):
    """Return (ui_latencies_ms, present_latencies_ms, hist_buckets, stuff_share_pct)."""
    hist = {}
    m = re.search(r"^HISTOGRAM:\s*(.+)$", text, re.M)
    if m:
        for b, c in re.findall(r"(\d+)ms=(\d+)", m.group(1)):
            hist[int(b)] = int(c)
    rows, inb = [], False
    for line in text.splitlines():
        if line.startswith("---PROFILEDATA---"):
            inb = not inb
            continue
        if not inb:
            continue
        c = line.split(",")
        if len(c) < 24:
            continue
        try:
            cols = [int(v) if v.strip() else 0 for v in c[:25]]
        except ValueError:
            continue
        flags, iv, fc = cols[0], cols[2], cols[18]
        dp = cols[23] if len(cols) > 23 else 0
        if iv <= 0 or fc <= 0 or fc < iv:
            continue
        rows.append((flags, iv, fc, dp))
    ui, pres = [], []
    stuffed = 0
    for flags, iv, fc, dp in rows:
        ui.append((fc - iv) / 1e6)
        if flags & FLAG_BUFFER_STUFFING:
            stuffed += 1
        if 0 < dp < 0x7FFFFFFF and dp >= iv:
            pres.append((dp - iv) / 1e6)
    stuff = round(100.0 * stuffed / len(rows), 1) if rows else 0.0
    return ui, pres, hist, stuff


def pct(v, q):
    if not v:
        return float("nan")
    s = sorted(v)
    k = min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))
    return s[k]


def summarize(ui, pres, stuff=0.0, legacy_janky=None):
    d = {"frames": len(ui)}
    if legacy_janky is not None:
        d["legacy_janky"] = legacy_janky
    if not ui:
        return d
    for q, name in ((0.5, "p50"), (0.9, "p90"), (0.95, "p95")):
        d["ui_" + name] = round(pct(ui, q), 2)
        if pres:
            d["pr_" + name] = round(pct(pres, q), 2)
    d["max"] = round(max(ui), 2)
    for n in (1, 2, 3):
        d["miss%d" % n] = round(100.0 * sum(1 for v in ui if v > n * VSYNC_MS) / len(ui), 1)
    return d


def collect(tag, rep, extra=""):
    d = os.path.join(OUTROOT, tag)
    os.makedirs(d, exist_ok=True)
    fs = sh("dumpsys gfxinfo %s framestats" % PKG, timeout=60)
    open(os.path.join(d, "r%d_framestats.txt" % rep), "w", encoding="utf-8").write(fs)
    mem = sh("dumpsys meminfo %s" % PKG, timeout=60)
    open(os.path.join(d, "r%d_meminfo.txt" % rep), "w", encoding="utf-8").write(mem)
    mode = sh("dumpsys display | grep -m1 mActiveSfDisplayMode=")
    temp = sh("dumpsys battery | grep temperature")
    cpu = sh("dumpsys cpuinfo | grep %s" % PKG)
    open(os.path.join(d, "r%d_env.txt" % rep), "w", encoding="utf-8").write(
        "tag=%s rep=%d %s\nmode=%s\ntemp=%s\ncpu=%s" % (tag, rep, extra, mode, temp, cpu))
    ui, pres, hist, stuff = parse_framestats(fs)
    lj = re.search(r"^Janky frames \(legacy\): (\d+) \((\d+(?:\.\d+)?)%\)", fs, re.M)
    s = summarize(ui, pres, stuff, float(lj.group(2)) if lj else None)
    s["tag"] = tag
    s["rep"] = rep
    s["peak"] = re.search(r"peakRefreshRate=([0-9.]+)", mode).group(1) if "peakRefreshRate" in mode else "?"
    return s


def window(tag, rep, gesture, settle=1.8, assert_after=None, extra=""):
    ensure_fg()
    ensure_paused()
    sh("dumpsys gfxinfo %s reset" % PKG)
    sleep(0.15)
    gesture()
    sleep(settle)
    if assert_after:
        x = dump()
        if not assert_after(x):
            raise RuntimeError("%s: post-state assertion failed" % tag)
    return collect(tag, rep, extra)


# --- scenarios --------------------------------------------------------------
def s1a_expand(rep):
    ensure_album()                    # normalize the restore-page to Album first
    swipe4(HANDLE_DOWN)
    sleep(1.4)
    x = dump()
    if "content-desc=\"主页\"" not in x:
        raise RuntimeError("not collapsed before expand")
    return window("s1a_expand", rep, lambda: swipe4(MINI_SWIPE),
                  assert_after=lambda x: "Max Volume" in x)


def s1b_collapse(rep):
    x = ensure_album()
    if "Max Volume" not in x:
        raise RuntimeError("not expanded before collapse")
    return window("s1b_collapse", rep, lambda: swipe4(HANDLE_DOWN),
                  assert_after=lambda x: "content-desc=\"主页\"" in x)


def s1c_page_switch(rep, target="lyric"):
    ensure_album()
    if target == "lyric":
        btn, back = BTN_LYRICS, BTN_LYRICS
    elif target == "queue":
        btn, back = BTN_QUEUE, BTN_QUEUE
    else:
        btn, back = BTN_COMMENT, BTN_COMMENT
    s = window("s1c_%s_in" % target, rep, lambda: tap(btn))
    # leave the page: only Lyric auto-hides its controls; queue/comment buttons stay tappable
    if target == "lyric":
        back_to_album()
    else:
        tap(back)
        sleep(1.2)
    swipe4(HANDLE_DOWN)
    sleep(1.2)
    return s


def _goto_playlists():
    ensure_collapsed()
    tap(TAB["lib"])
    sleep(1.5)
    tap(LIB_ONLINE_PLAYLISTS)
    sleep(2.0)
    x = dump()
    if "我的在线歌单" not in x:
        raise RuntimeError("not on online playlists page")
    return x


def s2a_enter_playlist(rep):
    ensure_collapsed()
    _goto_playlists()
    return window("s2a_enter_playlist", rep, lambda: tap(PL_FIXED), settle=2.0,
                  assert_after=lambda x: "PLAYLIST" in x)


def s2b_exit_playlist(rep):
    ensure_collapsed()
    _goto_playlists()
    tap(PL_FIXED)
    sleep(2.0)
    return window("s2b_exit_playlist", rep, lambda: key(4), settle=1.8,
                  assert_after=lambda x: "我的在线歌单" in x)


def s2c_fling_playlist(rep):
    ensure_collapsed()
    _goto_playlists()
    tap(PL_FIXED)
    sleep(2.0)
    return window("s2c_fling_playlist", rep,
                  lambda: swipe4((640, 2100, 640, 600, 120)), settle=1.6)


def _open_artist_from_player():
    """Expanded player -> artist page via the artist name under the title."""
    ensure_album()
    tap(PLAYER_ARTIST)
    for _ in range(8):
        sleep(0.8)
        x = dump()
        if "content-desc=\"关注\"" in x:
            return x
    raise RuntimeError("artist page did not open")


def s3a_enter_artist(rep):
    ensure_album()
    return window("s3a_enter_artist", rep, lambda: tap(PLAYER_ARTIST), settle=3.2,
                  assert_after=lambda x: "content-desc=\"关注\"" in x)


def s3b_exit_artist(rep):
    _open_artist_from_player()
    # tapping the artist name navigates and collapses the player, so BACK lands on a tab root
    return window("s3b_exit_artist", rep, lambda: key(4), settle=2.2,
                  assert_after=lambda x: "content-desc=\"主页\"" in x)


def s3c_scroll_artist(rep):
    _open_artist_from_player()
    return window("s3c_scroll_artist", rep,
                  lambda: swipe4((640, 2000, 640, 800, 150)), settle=1.8)


SCENARIOS = {
    "s1a_expand": s1a_expand,
    "s1b_collapse": s1b_collapse,
    "s1c_lyric": lambda r: s1c_page_switch(r, "lyric"),
    "s1c_queue": lambda r: s1c_page_switch(r, "queue"),
    "s2a_enter_playlist": s2a_enter_playlist,
    "s2b_exit_playlist": s2b_exit_playlist,
    "s2c_fling_playlist": s2c_fling_playlist,
    "s3a_enter_artist": s3a_enter_artist,
    "s3b_exit_artist": s3b_exit_artist,
    "s3c_scroll_artist": s3c_scroll_artist,
}
ORDER = ["s1a_expand", "s1b_collapse", "s1c_lyric", "s1c_queue",
         "s2a_enter_playlist", "s2b_exit_playlist", "s2c_fling_playlist",
         "s3a_enter_artist", "s3b_exit_artist", "s3c_scroll_artist"]


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    reps = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    tags = ORDER if what == "all" else [what]
    os.makedirs(OUTROOT, exist_ok=True)
    rows = []
    for tag in tags:
        fn = SCENARIOS[tag]
        for rep in range(1, reps + 1):
            try:
                s = fn(rep)
            except Exception as e:
                print("FAIL %s r%d: %s" % (tag, rep, e))
                s = {"tag": tag, "rep": rep, "frames": 0, "error": str(e)}
            rows.append(s)
            print(json.dumps(s, ensure_ascii=False))
    out = os.path.join(OUTROOT, "summary_%s.json" % time.strftime("%Y%m%d_%H%M%S"))
    json.dump(rows, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("WROTE", out)


if __name__ == "__main__":
    main()
