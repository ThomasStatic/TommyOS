"""
Render Lumen's cinematic wallpapers ("scenes") procedurally, so the app ships original imagery with
no licensing questions and the set is reproducible. Output: 1280x720 JPEGs in app/src/main/assets/scenes/.

Composition rules shared by every scene (they sit behind the Cinema layout):
  - the top-left (clock) stays calm and fairly dark;
  - visual interest lives in the upper-middle / right;
  - the bottom third falls off towards black, where the dock and controls sit.

Rendered at 2x and downsampled for clean edges; a little grain is added so gradients don't band.
Usage: python tools/scenes.py [name ...]   (python -I is fine; needs numpy + Pillow)
"""
import os
import sys

import numpy as np
from PIL import Image

W, H = 1280, 720
SS = 2  # supersampling factor
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "scenes")


# ---------- helpers ----------

def srgb_to_lin(c):
    c = np.asarray(c, dtype=np.float32) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


def col(hexstr):
    h = hexstr.lstrip("#")
    return srgb_to_lin([int(h[i:i + 2], 16) for i in (0, 2, 4)])


def smooth(t):
    return t * t * (3 - 2 * t)


def mix(a, b, t):
    t = np.asarray(t, dtype=np.float32)
    if t.ndim >= 2 and t.shape[-1] not in (1, 3):  # a per-pixel weight: apply to all channels
        t = t[..., None]
    return a + (b - a) * t


def gradient(y, stops):
    """Vertical colour ramp. y in [0,1]; stops = [(pos, colour), ...]."""
    out = np.zeros(y.shape + (3,), np.float32)
    pos = [p for p, _ in stops]
    for ch in range(3):
        out[..., ch] = np.interp(y, pos, [c[ch] for _, c in stops])
    return out


def noise1(x, rng, octaves=6, persistence=0.5, ridged=False):
    """Fractal value noise along x (x in 'cells'); returns roughly [-1, 1]."""
    total = np.zeros_like(x, dtype=np.float32)
    amp, freq, norm = 1.0, 1.0, 0.0
    for _ in range(octaves):
        xs = x * freq
        n = int(np.ceil(xs.max())) + 3
        grid = rng.uniform(-1, 1, n).astype(np.float32)
        i = np.floor(xs).astype(int)
        f = smooth(xs - i)
        v = grid[i] * (1 - f) + grid[i + 1] * f
        if ridged:
            v = 1 - 2 * np.abs(v)
        total += amp * v
        norm += amp
        amp *= persistence
        freq *= 2.0
    return total / norm


def noise2(h, w, cell, rng, octaves=5, persistence=0.5, stretch=(1.0, 1.0)):
    """Fractal 2D value noise, roughly [-1, 1]. stretch=(sx, sy) elongates features."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    total = np.zeros((h, w), np.float32)
    amp, norm, c = 1.0, 0.0, float(cell)
    for _ in range(octaves):
        gx = xx / (c * stretch[0])
        gy = yy / (c * stretch[1])
        nx, ny = int(gx.max()) + 3, int(gy.max()) + 3
        grid = rng.uniform(-1, 1, (ny, nx)).astype(np.float32)
        ix, iy = np.floor(gx).astype(int), np.floor(gy).astype(int)
        fx, fy = smooth(gx - ix), smooth(gy - iy)
        a = grid[iy, ix] * (1 - fx) + grid[iy, ix + 1] * fx
        b = grid[iy + 1, ix] * (1 - fx) + grid[iy + 1, ix + 1] * fx
        total += amp * (a * (1 - fy) + b * fy)
        norm += amp
        amp *= persistence
        c /= 2.0
    return total / norm


def blur(a, sigma):
    """Separable Gaussian blur over the first two axes (works on float arrays of any channel count)."""
    r = max(1, int(3 * sigma))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2).astype(np.float32)
    k /= k.sum()
    out = a.astype(np.float32)
    for axis in (0, 1):
        pad = [(0, 0)] * out.ndim
        pad[axis] = (r, r)
        p = np.pad(out, pad, mode="edge")
        acc = np.zeros_like(out)
        for i, kv in enumerate(k):
            acc += kv * np.take(p, np.arange(i, i + out.shape[axis]), axis=axis)
        out = acc
    return out


def glow(xx, yy, cx, cy, r, aspect=1.0):
    d2 = ((xx - cx) / aspect) ** 2 + (yy - cy) ** 2
    return np.exp(-d2 / (r * r)).astype(np.float32)


def stars(h, w, rng, density, ymax, brightness=1.0):
    """Sparse stars in the top part of the frame, fading towards ymax (fraction of height)."""
    img = np.zeros((h, w), np.float32)
    n = int(w * h * density)
    xs = rng.integers(0, w, n)
    ys = (rng.random(n) ** 1.6 * ymax * h).astype(int)
    mag = rng.random(n) ** 6 * brightness
    np.add.at(img, (ys, xs), mag)
    s = blur(img, 0.9 * SS) * 6
    fade = np.clip(1 - np.linspace(0, 1, h) / ymax, 0, 1)[:, None] ** 1.5
    return s * fade


def ridge_mask(yy, ridge):
    """Anti-aliased coverage of the area below a ridge line (ridge in pixels, per column)."""
    return np.clip(yy - ridge[None, :] + 0.5, 0, 1)


def finish(lin, name, rng, vignette=0.35, clock_dim=0.30, bottom_dim=0.45):
    """Shared grading: clock-area calm, bottom fall-off, vignette; then downsample, grain, save."""
    h, w = lin.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    u, v = xx / w, yy / h
    shade = np.ones((h, w), np.float32)
    shade *= 1 - clock_dim * glow(u, v, 0.08, 0.10, 0.42, aspect=1.4)
    shade *= 1 - bottom_dim * smooth(np.clip((v - 0.55) / 0.45, 0, 1))
    shade *= 1 - vignette * smooth(np.clip(np.hypot((u - 0.5) * 1.1, (v - 0.45) * 1.25) - 0.25, 0, 1) / 0.55)
    lin = lin * shade[..., None]
    rgb = lin_to_srgb(lin) * 255.0
    img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).resize((W, H), Image.LANCZOS)
    a = np.asarray(img, np.float32)
    a += rng.normal(0, 1.6, a.shape[:2])[..., None]  # monochrome grain: dithers smooth skies
    img = Image.fromarray(np.clip(a + 0.5, 0, 255).astype(np.uint8))
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{name}.jpg")
    img.save(path, "JPEG", quality=90, optimize=True, progressive=False, subsampling=0)
    print(f"{name}: {os.path.getsize(path) // 1024} KB")


def canvas():
    h, w = H * SS, W * SS
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    return h, w, yy, xx


def ridges(lin, yy, w, rng, layers, light_x, light_strength, rim_color):
    """Layered mountain ranges with atmospheric perspective and valley mist."""
    h = lin.shape[0]
    x = np.arange(w, dtype=np.float32)
    for i, L in enumerate(layers):
        base, amp, freq, rough, color, haze, fog = (L[k] for k in ("base", "amp", "freq", "rough", "color", "haze", "fog"))
        n = noise1(x / w * freq + rng.uniform(0, 50), rng, octaves=7, persistence=rough, ridged=L.get("ridged", False))
        ridge = (base - amp * (0.5 + 0.5 * n)) * h
        cov = ridge_mask(yy, ridge)
        # Mist pools in the valleys: lighter towards the layer's base, by absolute height.
        top = (base - amp * 0.75) * h
        valley = np.clip((yy - top) / (L.get("fog_depth", 0.12) * h), 0, 1)
        texture = 1 + 0.05 * noise2(h, w, 90 * SS, rng, octaves=4, stretch=(1.0, 2.5))  # faint rock/forest grain
        layer = mix(color, haze, (fog * smooth(valley))[..., None]) * texture[..., None]
        # Light catching the crest close to the light source.
        crest = np.exp(-np.clip(yy - ridge[None, :], 0, None) / (1.4 * SS))
        facing = np.exp(-((x - light_x) / (0.16 * w)) ** 2)[None, :]
        layer = layer + rim_color * (crest * facing * light_strength * L.get("rim", 1.0))[..., None]
        lin[:] = mix(lin, layer, cov[..., None])
    return lin


def mist(lin, h, w, rng, y0, y1, color, strength, cell=220):
    n = noise2(h, w, cell * SS, rng, octaves=5, stretch=(4.0, 1.0))
    yy = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    band = smooth(np.clip((yy - y0) / max(1e-3, (y1 - y0) / 2), 0, 1)) * smooth(np.clip((y1 - yy) / max(1e-3, (y1 - y0) / 2), 0, 1))
    a = np.clip(band * (0.55 + 0.45 * n) * strength, 0, 1)
    return mix(lin, color, a[..., None])


# ---------- scenes ----------

def blue_hour(rng):
    h, w, yy, xx = canvas()
    u, v = xx / w, yy / h
    lin = gradient(v, [(0.0, col("#070b14")), (0.30, col("#13213a")), (0.52, col("#3b4f6e")), (0.62, col("#a3a7ad")), (1.0, col("#2a3346"))])
    lin += col("#ffb07a") * (0.55 * glow(u, v, 0.70, 0.60, 0.22, aspect=2.6))[..., None]
    lin += col("#7f94b8") * (0.25 * glow(u, v, 0.66, 0.50, 0.45, aspect=2.0))[..., None]
    lin += stars(h, w, rng, 0.00035, 0.38, 0.6)[..., None] * col("#dfe6ff")
    layers = [
        dict(base=0.64, amp=0.20, freq=5, rough=0.52, ridged=True, color=col("#4a5873"), haze=col("#8c97a8"), fog=0.55, rim=0.4),
        dict(base=0.70, amp=0.17, freq=4, rough=0.50, ridged=True, color=col("#2c3850"), haze=col("#66748c"), fog=0.60),
        dict(base=0.78, amp=0.15, freq=3, rough=0.50, color=col("#1a2236"), haze=col("#465269"), fog=0.55),
        dict(base=0.88, amp=0.14, freq=2.5, rough=0.48, color=col("#0d121e"), haze=col("#2a3245"), fog=0.45),
        dict(base=1.00, amp=0.13, freq=2, rough=0.45, color=col("#05070c"), haze=col("#121826"), fog=0.3, rim=0.2),
    ]
    lin = mist(lin, h, w, rng, 0.62, 0.78, col("#8d98aa"), 0.35)
    lin = ridges(lin, yy, w, rng, layers, 0.70 * w, 0.22, col("#ffb48a"))
    lin = mist(lin, h, w, rng, 0.74, 0.92, col("#3e4a60"), 0.35, cell=160)
    finish(lin, "blue-hour", rng)


def ember_peaks(rng):
    h, w, yy, xx = canvas()
    u, v = xx / w, yy / h
    lin = gradient(v, [(0.0, col("#0c0a12")), (0.28, col("#2a1e2c")), (0.48, col("#7a4a3e")), (0.60, col("#e0956a")), (1.0, col("#3a2422"))])
    lin += col("#ffd2a0") * (1.6 * glow(u, v, 0.66, 0.58, 0.035))[..., None]
    lin += col("#ff9a5c") * (0.65 * glow(u, v, 0.66, 0.58, 0.16, aspect=1.8))[..., None]
    lin += col("#c46a52") * (0.25 * glow(u, v, 0.62, 0.50, 0.50, aspect=2.2))[..., None]
    layers = [
        dict(base=0.66, amp=0.22, freq=4.5, rough=0.55, ridged=True, color=col("#7d5150"), haze=col("#c98a74"), fog=0.6, rim=0.8),
        dict(base=0.72, amp=0.18, freq=3.5, rough=0.52, ridged=True, color=col("#4f3238"), haze=col("#93605a"), fog=0.6, rim=0.7),
        dict(base=0.80, amp=0.16, freq=3, rough=0.50, color=col("#2c1c24"), haze=col("#5e3c3e"), fog=0.55, rim=0.5),
        dict(base=0.90, amp=0.14, freq=2.4, rough=0.48, color=col("#160e14"), haze=col("#33222a"), fog=0.45, rim=0.3),
        dict(base=1.01, amp=0.12, freq=2, rough=0.45, color=col("#080507"), haze=col("#150d12"), fog=0.3, rim=0.15),
    ]
    lin = mist(lin, h, w, rng, 0.62, 0.80, col("#d39478"), 0.30)
    lin = ridges(lin, yy, w, rng, layers, 0.66 * w, 0.55, col("#ffb27a"))
    lin = mist(lin, h, w, rng, 0.76, 0.94, col("#4a3036"), 0.35, cell=160)
    finish(lin, "ember-peaks", rng)


def still_lake(rng):
    h, w, yy, xx = canvas()
    u, v = xx / w, yy / h
    horizon = 0.60
    sky_v = np.minimum(v, horizon)
    lin = gradient(sky_v, [(0.0, col("#0b1018")), (0.30, col("#24324a")), (0.50, col("#6d7d96")), (horizon, col("#c4c3c2"))])
    lin += col("#ffd9b8") * (0.40 * glow(u, v, 0.72, 0.56, 0.20, aspect=2.4))[..., None]
    lin += stars(h, w, rng, 0.0003, 0.30, 0.5)[..., None] * col("#e4e9ff")
    layers = [
        dict(base=0.585, amp=0.26, freq=4, rough=0.55, ridged=True, color=col("#5d6a80"), haze=col("#9aa4b4"), fog=0.55, fog_depth=0.08, rim=0.5),
        dict(base=0.595, amp=0.17, freq=3, rough=0.50, ridged=True, color=col("#36425a"), haze=col("#76839a"), fog=0.6, fog_depth=0.06),
        dict(base=0.602, amp=0.08, freq=6, rough=0.45, color=col("#141a26"), haze=col("#2e384b"), fog=0.3, fog_depth=0.03),
    ]
    sky = lin.copy()
    above = ridges(sky, yy, w, rng, layers, 0.72 * w, 0.4, col("#ffc79c"))
    hz = int(horizon * h)
    out = above.copy()
    # Mirror the scene into the lake: flipped, darker, slightly blurred, with gentle horizontal ripples.
    refl = above[2 * hz - h:hz][::-1] if 2 * hz >= h else np.pad(above[:hz][::-1], ((0, h - 2 * hz), (0, 0), (0, 0)), mode="edge")
    refl = refl[:h - hz]
    ripple = noise2(h - hz, w, 18 * SS, rng, octaves=3, stretch=(14.0, 1.0)) * 10 * SS
    cols = np.clip((np.arange(w)[None, :] + ripple).astype(int), 0, w - 1)
    refl = refl[np.arange(h - hz)[:, None], cols]
    refl = blur(refl, 2 * SS)
    depth = np.linspace(0, 1, h - hz, dtype=np.float32)[:, None, None]
    out[hz:] = refl * (0.62 - 0.30 * depth) + col("#0a0e16") * (0.25 * depth)
    out = mist(out, h, w, rng, 0.55, 0.64, col("#b9bcc2"), 0.22, cell=180)
    finish(out, "still-lake", rng, bottom_dim=0.5)


def moonlit_dunes(rng):
    h, w, yy, xx = canvas()
    u, v = xx / w, yy / h
    lin = gradient(v, [(0.0, col("#05070c")), (0.35, col("#121a2a")), (0.56, col("#3c4659")), (1.0, col("#1a1d24"))])
    lin += col("#e9eef7") * (1.6 * np.clip((0.018 - np.hypot((u - 0.76) * 16 / 9, v - 0.20)) / 0.002, 0, 1))[..., None]
    lin += col("#a9b6cc") * (0.30 * glow(u, v, 0.76, 0.20, 0.10, aspect=9 / 16))[..., None]
    lin += col("#6f7d95") * (0.20 * glow(u, v, 0.70, 0.40, 0.45, aspect=2.0))[..., None]
    lin += stars(h, w, rng, 0.00045, 0.45, 0.7)[..., None] * col("#e4e9ff")
    x = np.arange(w, dtype=np.float32)
    dunes = [
        (0.60, 0.07, 1.6, col("#4f5869"), col("#7e8799"), col("#2e3442")),
        (0.67, 0.09, 1.3, col("#3d4553"), col("#6c7586"), col("#222733")),
        (0.76, 0.11, 1.1, col("#2b313d"), col("#59616f"), col("#161a22")),
        (0.88, 0.13, 0.9, col("#1b1f27"), col("#454b57"), col("#0c0e13")),
        (1.02, 0.15, 0.7, col("#101217"), col("#30343c"), col("#060709")),
    ]
    for base, amp, freq, mid, lit, dark in dunes:
        n = noise1(x / w * freq * 3 + rng.uniform(0, 40), rng, octaves=2, persistence=0.3)
        crest = (base - amp * (0.5 + 0.5 * n)) * h
        slope = np.gradient(crest) / SS  # >0: crest descending to the right
        face = np.tanh(-blur(slope[:, None], 18 * SS)[:, 0] * 9.0)  # smoothed: no vertical banding  # moonlight from the upper right lights faces rising to the right
        cov = ridge_mask(yy, crest)
        below = np.clip((yy - crest[None, :]) / (amp * h * 1.2), 0, 1)
        shade = mix(mix(mid, lit, np.clip(face, 0, 1)[None, :, None] * np.ones((h, 1, 1))),
                    dark, (np.clip(-face, 0, 1)[None, :] * (0.35 + 0.65 * smooth(below)))[..., None])
        shade = mix(shade, dark, (0.55 * smooth(below))[..., None])
        rim = np.exp(-np.clip(yy - crest[None, :], 0, None) / (1.8 * SS)) * (0.4 + 0.6 * np.clip(face, 0, 1))[None, :]
        shade = shade + col("#c9d3e6") * (rim * 0.12)[..., None]
        lin = mix(lin, shade, cov[..., None])
    finish(lin, "moonlit-dunes", rng, bottom_dim=0.4)


def aurora(rng):
    h, w, yy, xx = canvas()
    u, v = xx / w, yy / h
    lin = gradient(v, [(0.0, col("#020309")), (0.40, col("#050c16")), (0.68, col("#0b1a22")), (1.0, col("#030507"))])
    lin += stars(h, w, rng, 0.0006, 0.70, 0.9)[..., None] * col("#e8f0ff")
    x = np.arange(w, dtype=np.float32)
    aur = np.zeros_like(lin)
    for yb, amp, strength, cx, spread in [(0.44, 0.12, 1.0, 0.64, 0.30), (0.30, 0.08, 0.40, 0.84, 0.22)]:
        phase = rng.uniform(0, 6.3)
        curve = (yb + 0.06 * np.sin(x / w * 2 * np.pi * 1.2 + phase) + 0.03 * np.sin(x / w * 2 * np.pi * 3.1 + 2 * phase)
                 + amp * 0.5 * noise1(x / w * 1.6 + rng.uniform(0, 30), rng, octaves=3, persistence=0.4)) * h
        d = (curve[None, :] - yy) / h  # >0 above the curtain's lower edge
        # Fine vertical rays with gaps, and broader folds where the curtain turns towards us.
        rays = np.clip(0.55 + 0.8 * noise1(x / w * 40 + rng.uniform(0, 99), rng, octaves=3, persistence=0.55), 0, 1)
        folds = 0.35 + 0.65 * np.clip(0.5 + 0.8 * noise1(x / w * 7 + rng.uniform(0, 99), rng, octaves=2), 0, 1) ** 2
        body = np.where(d > 0, np.exp(-d / 0.13) * (0.6 + 0.4 * np.exp(-d / 0.03)), np.exp(-(d / 0.022) ** 2))
        envelope = np.exp(-((x / w - cx) / spread) ** 2)
        inten = body * (rays * folds * envelope)[None, :] * strength
        hue = np.clip(d / 0.20, 0, 1)
        c = mix(mix(col("#6dffbf"), col("#22c7a6"), np.clip(hue * 1.8, 0, 1)), col("#5b46c0"), np.clip(hue * 1.6 - 0.55, 0, 1))
        aur += c * (inten * 0.50)[..., None]
    aur = blur(aur, 2.5 * SS)
    lin += aur
    lin += col("#145a4c") * (0.06 * glow(u, v, 0.62, 0.48, 0.35, aspect=2.2))[..., None]
    layers = [
        dict(base=0.72, amp=0.09, freq=3, rough=0.5, ridged=True, color=col("#070c10"), haze=col("#0c1719"), fog=0.4, fog_depth=0.08, rim=0.0),
        dict(base=0.79, amp=0.07, freq=10, rough=0.7, color=col("#030506"), haze=col("#060b0c"), fog=0.2, rim=0.0),
    ]
    lin = ridges(lin, yy, w, rng, layers, 0.6 * w, 0.0, col("#000000"))
    finish(lin, "aurora", rng, bottom_dim=0.35)


def silk(rng):
    h, w, yy, xx = canvas()
    u, v = xx / w, yy / h
    # Long, smooth folds: a few sinusoids along a gently warped diagonal coordinate.
    warp = 0.22 * np.sin(v * 3.3 + u * 1.7) + 0.12 * np.sin(u * 4.1 - v * 2.2) + 0.10 * noise2(h, w, 1400 * SS, rng, octaves=2)
    p = u * 1.9 + v * 1.25 + warp
    hgt = 0.55 * np.sin(p * 9.0) + 0.25 * np.sin(p * 17.0 + 1.3 + 2.0 * v) + 0.12 * np.sin(p * 4.0 - 0.7)
    gy, gx = np.gradient(hgt)
    k = 0.05 * w / SS
    nx, ny, nz = -gx * k, -gy * k, np.ones_like(hgt)
    norm = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / norm, ny / norm, nz / norm
    lx, ly, lz = 0.60, -0.50, 0.62  # light from the upper right
    ll = np.sqrt(lx * lx + ly * ly + lz * lz)
    lx, ly, lz = lx / ll, ly / ll, lz / ll
    diffuse = np.clip(nx * lx + ny * ly + nz * lz, 0, 1)
    hx, hy, hz = lx, ly, lz + 1
    hl = np.sqrt(hx * hx + hy * hy + hz * hz)
    spec = np.clip((nx * hx + ny * hy + nz * hz) / hl, 0, 1) ** 28
    base = mix(col("#07080a"), col("#4a4e57"), diffuse ** 2.2)
    lin = base + col("#efe9df") * (spec * 0.45)[..., None]
    lin *= (0.30 + 0.70 * glow(u, v, 0.72, 0.28, 0.62, aspect=1.5))[..., None]
    finish(lin, "silk", rng, clock_dim=0.25, bottom_dim=0.5)


SCENES = {
    "blue-hour": blue_hour,
    "ember-peaks": ember_peaks,
    "still-lake": still_lake,
    "moonlit-dunes": moonlit_dunes,
    "aurora": aurora,
    "silk": silk,
}

if __name__ == "__main__":
    names = sys.argv[1:] or list(SCENES)
    for i, name in enumerate(SCENES):
        if name in names:
            SCENES[name](np.random.default_rng(1000 + i))
