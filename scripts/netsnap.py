"""Summarize established TCP connections from /proc/net/tcp{,6} dumps, mapped to packages by UID.
Usage: python -I netsnap.py <dir containing net_tcp_*.txt and uids.txt> [tcp file name]"""
import sys, socket, struct, collections, re, os
d = sys.argv[1]; tcp = sys.argv[2] if len(sys.argv) > 2 else "net_tcp_baseline.txt"
uid2pkg = collections.defaultdict(list)
for l in open(os.path.join(d, "uids.txt")):
    m = re.match(r"package:(\S+) uid:(\d+)", l)
    if m: uid2pkg[int(m.group(2))].append(m.group(1))
names = {0: "root", 1000: "system(uid 1000)", 1051: "netd/dns"}
conns = collections.Counter()
for l in open(os.path.join(d, tcp)):
    f = l.split()
    if len(f) < 8 or f[0] == "sl" or f[3] != "01": continue  # 01 = ESTABLISHED
    ip_hex, port = f[2].split(":"); uid = int(f[7]); b = bytes.fromhex(ip_hex)
    if len(b) == 16:
        ip = socket.inet_ntop(socket.AF_INET6, b"".join(struct.pack("<I", w) for w in struct.unpack(">4I", b))).replace("::ffff:", "")
    else:
        ip = socket.inet_ntoa(struct.pack("<I", int(ip_hex, 16)))
    if ip.startswith(("127.", "::1", "10.0.0.24")): continue
    who = ",".join(uid2pkg.get(uid, [])[:2]) or names.get(uid, f"uid{uid}")
    conns[(who, ip, int(port, 16))] += 1
print(f"{sum(conns.values())} established outbound connections (excluding loopback/this PC)")
for (who, ip, port), n in sorted(conns.items()):
    print(f"  {who[:60]:60} -> {ip}:{port}" + (f" x{n}" if n > 1 else ""))
