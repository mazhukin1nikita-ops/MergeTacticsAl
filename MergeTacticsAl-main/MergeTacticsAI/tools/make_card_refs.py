#!/usr/bin/env python3
"""
Собирает app/src/main/assets/card_refs.json из фото карточек app/src/main/assets/cards/<id>.webp.

Запуск из папки MergeTacticsAI:   python3 tools/make_card_refs.py
Нужны: Python 3.9+, numpy, pillow.

Новая карточка: положите её арт (вид из каталога, ~208x264) в assets/cards/<id>.webp, где <id> — id войска
из troops.json, и запустите скрипт. Эталоны берутся только для текущего пула (не legacy, цена > 0).

В файл попадает по 1 + AUG_PER_TROOP эталонов на войско: "id" — чистое фото, "id#1".."id#N" — то же фото с
типичными помехами экрана (сдвиг рамки, затемнение, значки в углах, размытие, шум). Результат детерминирован.
"""
import json, math, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

AUG_PER_TROOP = 12
SEED = 7
N = 10  # как CardMatcher.N

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"


def rnd(x):  # Math.round из Kotlin: округление .5 вверх
    return math.floor(x + 0.5)


def describe(arr):
    """Точная копия CardMatcher.describe для изображения, целиком занятого карточкой."""
    h, w, _ = arr.shape
    rx0, rx1, ry0, ry1 = 0.22 * w, 0.78 * w, 0.12 * h, 0.62 * h
    out = np.zeros((N * N, 3))
    for j in range(N):
        for i in range(N):
            xa = rnd(rx0 + (rx1 - rx0) * i / N); xb = max(xa + 1, rnd(rx0 + (rx1 - rx0) * (i + 1) / N))
            ya = rnd(ry0 + (ry1 - ry0) * j / N); yb = max(ya + 1, rnd(ry0 + (ry1 - ry0) * (j + 1) / N))
            xa, xb = int(np.clip(xa, 0, w - 1)), int(np.clip(xb, 0, w - 1))
            ya, yb = int(np.clip(ya, 0, h - 1)), int(np.clip(yb, 0, h - 1))
            xb = max(xb, xa + 1); yb = max(yb, ya + 1)
            out[j * N + i] = arr[ya:yb, xa:xb].reshape(-1, 3).mean(axis=0)
    for ch in range(3):
        c = out[:, ch]; c -= c.mean(); out[:, ch] = c / (math.sqrt((c ** 2).mean()) + 1e-6)
    return out.reshape(-1)


def degrade(img, rng):
    w, h = img.size
    s = rng.uniform(0.90, 1.10); dx = rng.uniform(-0.05, 0.05); dy = rng.uniform(-0.05, 0.05)
    pw, ph = int(w * 1.3), int(h * 1.3)
    pad = img.resize((pw, ph)).filter(ImageFilter.GaussianBlur(8))
    pad.paste(img, (int(w * 0.15), int(h * 0.15)))
    cw, ch = w * s, h * s
    x0 = int(w * 0.15) + (w - cw) / 2 + dx * w; y0 = int(h * 0.15) + (h - ch) / 2 + dy * h
    im = pad.crop((round(x0), round(y0), round(x0 + cw), round(y0 + ch))).resize((w, h), Image.BILINEAR)
    a = np.array(im).astype(float)
    k = rng.uniform(0.40, 1.0)
    a = 255 * ((a / 255) ** rng.uniform(0.9, 1.3)) * k
    mix = rng.uniform(0, 0.25)
    a = (1 - mix) * a + mix * np.array([30, 25, 60])
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(im)
    if rng.random() < 0.7:
        r = int(w * rng.uniform(0.10, 0.22)); cx = rng.choice([0, w]); cy = rng.choice([0, int(h * 0.1)])
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=tuple(int(v) for v in rng.integers(20, 230, 3)))
    if rng.random() < 0.5:
        im = im.filter(ImageFilter.GaussianBlur(rng.uniform(0.4, 1.6)))
    a = np.array(im).astype(float) + rng.normal(0, rng.uniform(0, 6), (h, w, 3))
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def pool_ids():
    troops = json.loads((ASSETS / "troops.json").read_text(encoding="utf-8"))
    return [t["id"] for t in troops if not t.get("legacy", False) and t["cost"] > 0]


def main():
    ids = pool_ids()
    missing = [i for i in ids if not (ASSETS / "cards" / f"{i}.webp").exists()]
    if missing:
        sys.exit("Нет фото для: " + ", ".join(missing) + "  (положите assets/cards/<id>.webp)")
    rng = np.random.default_rng(SEED)
    out = {}
    for i in sorted(ids):
        img = Image.open(ASSETS / "cards" / f"{i}.webp").convert("RGB")
        out[i] = [round(float(v), 3) for v in describe(np.array(img))]
        for n in range(1, AUG_PER_TROOP + 1):
            out[f"{i}#{n}"] = [round(float(v), 3) for v in describe(np.array(degrade(img, rng)))]
    (ASSETS / "card_refs.json").write_text(json.dumps(out, separators=(",", ":")), encoding="utf-8")
    print(f"card_refs.json: {len(ids)} войск, {len(out)} эталонов")


if __name__ == "__main__":
    main()
