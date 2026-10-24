"""
Build screens/index.html: a before/after review page for two screenshot sets from screens.sh.
Usage: python scripts/contact_sheet.py <before-label> <after-label> [classic-label]
"""
import html
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "screens")
SCENES = os.path.join("..", "launcher", "app", "src", "main", "assets", "scenes")


def shots(label):
    d = os.path.join(ROOT, label)
    return sorted(f[:-4] for f in os.listdir(d) if f.endswith(".png")) if os.path.isdir(d) else []


def fig(src, caption):
    return f'<figure><img loading="lazy" src="{html.escape(src)}"><figcaption>{html.escape(caption)}</figcaption></figure>'


def main():
    before, after = sys.argv[1], sys.argv[2]
    classic = sys.argv[3] if len(sys.argv) > 3 else None
    b, a = shots(before), shots(after)
    out = ['<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
           '<title>Lumen review</title><style>:root{color-scheme:dark}body{background:#0b0b0d;color:#e8e8ea;'
           'font:15px/1.4 system-ui,sans-serif;margin:24px}h2{font-weight:600;margin:36px 0 12px}'
           '.pair{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:20px}'
           '.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(380px,1fr));gap:16px}'
           'figure{margin:0}img{width:100%;border-radius:8px;border:1px solid #222}'
           'figcaption{color:#9a9aa0;margin-top:6px}</style></head><body>',
           f'<h1>Lumen design review</h1><p>Left: <b>{html.escape(before)}</b> &nbsp;·&nbsp; Right: <b>{html.escape(after)}</b></p>',
           '<h2>Before / after</h2>']
    for name in a:
        if name in b:
            out.append('<div class=pair>' + fig(f"{before}/{name}.png", f"before · {name}")
                       + fig(f"{after}/{name}.png", f"after · {name}") + '</div>')
    new = [n for n in a if n not in b]
    if new:
        out.append('<h2>New screens</h2><div class=grid>' + "".join(fig(f"{after}/{n}.png", n) for n in new) + '</div>')
    scene_dir = os.path.join(ROOT, SCENES)
    if os.path.isdir(scene_dir):
        out.append('<h2>Scene wallpapers (as shipped, before the home-screen shading)</h2><div class=grid>')
        out += [fig(f"{SCENES}/{f}".replace(os.sep, "/"), f[:-4]) for f in sorted(os.listdir(scene_dir)) if f.endswith(".jpg")]
        out.append('</div>')
    if classic:
        out.append('<h2>Classic (fallback)</h2><div class=grid>' + "".join(fig(f"{classic}/{n}.png", n) for n in shots(classic)) + '</div>')
    out.append('</body></html>')
    with open(os.path.join(ROOT, "index.html"), "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print("wrote screens/index.html")


if __name__ == "__main__":
    main()
