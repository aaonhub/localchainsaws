#!/usr/bin/env python3
"""Draws the three 16x16 chainsaw item sprites (our own pixel art, vanilla tool style: handle bottom-left, tip top-right)
and an upscaled preview. Usage: make_textures.py [preview.png]"""
import sys
from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/localchainsaws/textures/item"

# Shared palette: orange body, grey engine/handles, dark steel chain.
BODY = {"O": (232, 122, 28), "o": (170, 78, 14), "Y": (255, 176, 72), "D": (110, 48, 10)}
GREY = {"G": (88, 88, 92), "g": (52, 52, 56), "H": (140, 140, 146), "K": (30, 30, 34)}
CHAIN = {"T": (46, 46, 50), "t": (176, 176, 182)}

# Per tier: bar light / mid / dark.
TIERS = {
    "iron_chainsaw": {"L": (236, 236, 236), "B": (190, 190, 194), "b": (118, 118, 124)},
    "diamond_chainsaw": {"L": (170, 255, 244), "B": (74, 222, 208), "b": (24, 140, 132)},
    "netherite_chainsaw": {"L": (112, 100, 108), "B": (74, 64, 72), "b": (40, 34, 40)},
}

SPRITE = [
    "...........TtT..",
    "..........tLLBt.",
    "........TtLBBbtT",
    "........tLBBbt..",
    "..KKKKTtLBBbtT..",
    ".KHGGHKLBBbt....",
    ".KG..KYLBbtT....",
    ".KGKKYOOOoK.....",
    "..KYOOOOOoK.....",
    ".KYOOGGGOoK.....",
    "KHOOGHggOoK.....",
    "KgOOGggKOoK.....",
    ".KOOOOOOoK......",
    "..KDooooDK......",
    "...KKKKKK.......",
    "................",
]


def draw(tier):
    pal = {**BODY, **GREY, **CHAIN, **TIERS[tier]}
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(SPRITE):
        assert len(row) == 16, (y, row)
        for x, c in enumerate(row):
            if c != ".":
                img.putpixel((x, y), pal[c] + (255,))
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    imgs = []
    for tier in TIERS:
        img = draw(tier)
        img.save(OUT / f"{tier}.png", optimize=True)
        imgs.append(img)
    if len(sys.argv) > 1:
        scale, pad = 16, 16
        sheet = Image.new("RGBA", (len(imgs) * (16 * scale + pad) + pad, 16 * scale + 2 * pad), (198, 198, 198, 255))
        for i, img in enumerate(imgs):
            sheet.alpha_composite(img.resize((16 * scale, 16 * scale), Image.NEAREST), (pad + i * (16 * scale + pad), pad))
        sheet.save(sys.argv[1])


if __name__ == "__main__":
    main()
