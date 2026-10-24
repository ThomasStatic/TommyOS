"""Compare which packages held established outbound TCP connections across two verify runs.
Each run dir holds net_tcp_*.txt snapshots + uids.txt (written by verify.sh).
Usage: python -I netcompare.py <before_dir> <after_dir>"""
import sys, os, re, glob, collections, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))

def packages(run_dir):
    seen = collections.Counter()  # package -> number of snapshots it appeared in
    files = sorted(glob.glob(os.path.join(run_dir, "net_tcp_*.txt")))
    for f in files:
        out = subprocess.run([sys.executable, "-I", os.path.join(HERE, "netsnap.py"), run_dir, os.path.basename(f)],
                             capture_output=True, text=True).stdout
        for who in {re.split(r"\s+->", l.strip())[0].strip() for l in out.splitlines()[1:] if "->" in l}:
            seen[who] += 1
    return seen, len(files)

a, na = packages(sys.argv[1]); b, nb = packages(sys.argv[2])
print(f"{'package':60} {'before':>8} {'after':>8}   (snapshots containing it: {na} before, {nb} after)")
for p in sorted(set(a) | set(b), key=lambda p: (p in b, p)):
    tag = "  GONE" if p not in b else ("  NEW" if p not in a else "")
    print(f"{p[:60]:60} {a.get(p, 0):>8} {b.get(p, 0):>8}{tag}")
print(f"\ndistinct packages connecting: {len(a)} before -> {len(b)} after")
