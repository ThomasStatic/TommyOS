"""Summarises a scripts/soak.sh run: CPU, memory growth, frames, ESPN requests, TSN playback, crashes.

Usage: python scripts/soak_summary.py <run dir>   (writes summary.txt there too)

Pass criteria (from REPORT.md's measurements; a run fails on any):
  - no ticker crash, ANR or unexpected process death;
  - ticker CPU at most 20 % of a core on average while its window is on screen, at most 1 % while it is not;
  - RSS growth under 10 MB per hour after the first 30 minutes (leak check);
  - janky frames at most 1 % while shown;
  - ESPN fetches at most 130 per hour (30 s cadence plus retries) and failures not consecutive for more than 15 min
    while the network is up.
"""
import re
import sys
from pathlib import Path

TICK_HZ = 100  # getconf CLK_TCK on the TV


def parse(path):
    samples = []
    for line in path.read_text().splitlines():
        s = {"procs": {}}
        for field in line.split("|"):
            parts = field.split()
            if not parts:
                continue
            key, rest = parts[0], parts[1:]
            if key == "t":
                s["t"] = int(rest[0])
            elif key == "offline":
                s["offline"] = True
            elif key == "proc" and len(rest) >= 3:
                name, pid, ticks = rest[0], rest[1], int(rest[2])
                rss = int(rest[3]) if len(rest) > 3 and rest[3].isdigit() else None
                s["procs"][name] = (pid, ticks, rss)
            elif key in ("frames", "janky", "missed", "window", "tsnaudio", "pss") and rest and rest[0].isdigit():
                s[key] = int(rest[0])
            elif key == "top":
                s["top"] = rest[0] if rest else ""
        if "t" in s:
            samples.append(s)
    return samples


def cpu_series(samples, name):
    """Percent of one core between consecutive samples of the same process (pid changes are restarts)."""
    out = []
    for a, b in zip(samples, samples[1:]):
        pa, pb = a["procs"].get(name), b["procs"].get(name)
        if not pa or not pb or pa[0] == "-" or pa[0] != pb[0] or b["t"] <= a["t"]:
            continue
        out.append((b, 100.0 * (pb[1] - pa[1]) / TICK_HZ / (b["t"] - a["t"])))
    return out


def mean(xs):
    return sum(xs) / len(xs) if xs else 0.0


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))] if xs else 0.0


def slope_per_hour(points):
    """Least-squares slope of (t seconds, value) in value per hour."""
    if len(points) < 3:
        return 0.0
    n = len(points)
    mt = sum(t for t, _ in points) / n
    mv = sum(v for _, v in points) / n
    den = sum((t - mt) ** 2 for t, _ in points)
    return 3600 * sum((t - mt) * (v - mv) for t, v in points) / den if den else 0.0


def main(run):
    run = Path(run)
    samples = parse(run / "samples.txt")
    if len(samples) < 2:
        print("not enough samples")
        return 1
    log = (run / "ticker.log").read_text(errors="replace").splitlines() if (run / "ticker.log").exists() else []
    hours = (samples[-1]["t"] - samples[0]["t"]) / 3600
    lines, failures = [], []

    def out(s=""):
        lines.append(s)

    out(f"run {run.name}: {len(samples)} samples over {hours:.2f} h, {sum(1 for s in samples if s.get('offline'))} offline")

    # Where the time went.
    tops = {}
    for s in samples:
        top = (s.get("top") or "?").split("/")[0]
        tops[top] = tops.get(top, 0) + 1
    out("foreground: " + ", ".join(f"{k} {100 * v / len(samples):.0f}%" for k, v in sorted(tops.items(), key=lambda kv: -kv[1])))
    shown = [s for s in samples if s.get("window")]
    out(f"ticker on screen: {100 * len(shown) / len(samples):.0f}% of samples; TSN audio playing: "
        f"{100 * sum(1 for s in samples if s.get('tsnaudio')) / len(samples):.0f}%")

    # CPU.
    for name, label in (("app.lumen.sports", "ticker"), ("surfaceflinger", "SurfaceFlinger"), ("ca.tsn.mobile.android", "TSN")):
        series = cpu_series(samples, name)
        on = [c for s, c in series if s.get("window")]
        off = [c for s, c in series if not s.get("window")]
        out(f"cpu {label:14} shown: mean {mean(on):5.1f}% p95 {pct(on, 0.95):5.1f}%   hidden: mean {mean(off):5.1f}% ({len(on)}/{len(off)} intervals)")
        if name == "app.lumen.sports":
            if on and mean(on) > 20:
                failures.append(f"ticker CPU {mean(on):.1f}% while shown (limit 20)")
            if off and mean(off) > 1:
                failures.append(f"ticker CPU {mean(off):.1f}% while hidden (limit 1)")

    # Memory: growth after a 30 minute warm-up, per process lifetime.
    t0 = samples[0]["t"]
    rss = [(s["t"], s["procs"]["app.lumen.sports"][2] / 1024) for s in samples
           if s["procs"].get("app.lumen.sports", ("-",))[0] != "-" and s["procs"]["app.lumen.sports"][2]]
    pids = {s["procs"]["app.lumen.sports"][0] for s in samples if s["procs"].get("app.lumen.sports", ("-",))[0] != "-"}
    warm = [(t, v) for t, v in rss if t - t0 >= 1800]
    if rss:
        out(f"ticker RSS: first {rss[0][1]:.0f} MB, max {max(v for _, v in rss):.0f} MB, last {rss[-1][1]:.0f} MB; "
            f"slope after 30 min {slope_per_hour(warm):+.1f} MB/h; {len(pids)} process(es)")
        if len(warm) >= 3 and slope_per_hour(warm) > 10:
            failures.append(f"RSS growing {slope_per_hour(warm):.1f} MB/h")
    pss = [s["pss"] / 1024 for s in samples if s.get("pss")]
    if pss:
        out(f"ticker PSS: {' '.join(f'{v:.0f}' for v in pss)} MB")

    # Frames.
    frames = sum(s.get("frames", 0) for s in shown)
    janky = sum(s.get("janky", 0) for s in shown)
    missed = sum(s.get("missed", 0) for s in shown)
    shown_s = sum(b["t"] - a["t"] for a, b in zip(samples, samples[1:]) if b.get("window"))
    if frames:
        out(f"frames while shown: {frames} ({frames / max(shown_s, 1):.1f} fps), janky {janky} ({100 * janky / frames:.2f}%), missed vsync {missed}")
        if janky / frames > 0.01:
            failures.append(f"janky frames {100 * janky / frames:.2f}% (limit 1%)")

    # ESPN requests and failures, from the ticker's own log (epoch timestamps).
    def stamp(line):
        m = re.match(r"\s*(\d+)\.\d+", line)
        return int(m.group(1)) if m else None

    fetched = [stamp(l) for l in log if "fetched week" in l]
    failed = [(stamp(l), l) for l in log if "fetch failed" in l or "fetch:" in l]
    out(f"ESPN: {len(fetched)} fetches ({len(fetched) / max(hours, 1e-9):.0f}/h), {len(failed)} failures")
    if hours > 0 and (len(fetched) + len(failed)) / hours > 130:
        failures.append("more than 130 ESPN requests per hour")
    # Longest stretch of failures with no success in between.
    events = sorted([(t, True) for t in fetched if t] + [(t, False) for t, _ in failed if t])
    worst, start = 0, None
    for t, ok in events:
        if ok:
            start = None
        else:
            start = start or t
            worst = max(worst, t - start)
    if worst:
        out(f"longest failing stretch: {worst / 60:.1f} min")
        if worst > 900:
            failures.append(f"ESPN failing for {worst / 60:.0f} min without recovery")

    alerts = sum(int(m.group(1)) for l in log for m in [re.search(r"(\d+) alert events", l)] if m)
    out(f"alert events detected: {alerts}")
    crashes = [l for l in log if "FATAL" in l or "ANR in app.lumen" in l or ("has died" in l and "app.lumen.sports" in l)]
    out(f"crashes/ANRs/deaths: {len(crashes)}")
    for l in crashes[:5]:
        out("  " + l.strip()[:160])
    if crashes:
        failures.append(f"{len(crashes)} crash/ANR/death line(s)")

    net = (run / "net.txt").read_text().split("\n") if (run / "net.txt").exists() else []
    vals = [l.split()[-2:] for l in net if l.strip()]
    if len(vals) == 2 and all(v.isdigit() for pair in vals for v in pair):
        rb, tb = int(vals[1][0]) - int(vals[0][0]), int(vals[1][1]) - int(vals[0][1])
        out(f"network (app uid): {rb / 1e6:.1f} MB in, {tb / 1e6:.2f} MB out ({rb / 1e6 / max(hours, 1e-9):.1f} MB/h in)")

    out("RESULT: " + ("PASS" if not failures else "FAIL: " + "; ".join(failures)))
    text = "\n".join(lines)
    print(text)
    (run / "summary.txt").write_text(text + "\n")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
