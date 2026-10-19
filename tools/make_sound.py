#!/usr/bin/env python3
"""Synthesizes the chainsaw sounds from scratch (two-stroke engine pulses + chain noise), writes WAVs and encodes them to
Ogg Vorbis with ffmpeg. Usage: make_sound.py [cut|hit|rev ...] (default: all three)
  cut: rev up, cut through wood, idle down (felling a tree)
  hit: a short throttle blip with the chain biting (a melee hit)
  rev: a seamless 2 s loop of the engine held at high revs (holding right-click)"""
import subprocess, sys, tempfile, wave
from pathlib import Path
import numpy as np

SR = 44100
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/localchainsaws/sounds/item"


def engine_pulses(f):
    """Two-stroke firing pulses for a firing-rate curve f (Hz per sample): sharp decaying pulse per firing, buzzy."""
    phase = np.cumsum(f) / SR
    frac = phase % 1.0
    pulse = np.exp(-frac * 9.0) * (1 - frac) ** 0.5
    pulse -= pulse.mean()
    return np.tanh(3.0 * pulse), frac


def cut():
    dur = 1.6
    t = np.arange(int(SR * dur)) / SR
    rng = np.random.default_rng(1018)
    # Engine firing rate (Hz): idle 45 -> rev to 135 by 0.25 s -> holds under load (sags a little) -> drops back after 1.25 s.
    f = np.interp(t, [0, 0.25, 0.6, 1.2, 1.45, dur], [45, 135, 118, 125, 70, 50])
    f = f * (1 + 0.025 * np.sin(2 * np.pi * 7.3 * t))  # uneven firing wobble
    engine, frac = engine_pulses(f)
    # Exhaust rasp: noise gated by the firing pulses.
    rasp = rng.standard_normal(t.size) * np.exp(-frac * 5.0) * 0.35
    # Chain on wood: band-passed noise during the cut, grainy.
    noise = rng.standard_normal(t.size)
    grind = np.convolve(noise, np.ones(6) / 6, mode="same") - np.convolve(noise, np.ones(40) / 40, mode="same")
    cut_env = np.interp(t, [0, 0.3, 0.4, 1.15, 1.3, dur], [0, 0, 1, 1, 0, 0])
    grind *= cut_env * (0.7 + 0.3 * np.sin(2 * np.pi * 23 * t)) * 1.4
    mix = 0.75 * engine + rasp + grind
    mix *= np.interp(t, [0, 0.03, 1.35, dur], [0, 1, 0.8, 0])
    return mix / (np.max(np.abs(mix)) * 1.12)


def hit():
    dur = 0.62
    t = np.arange(int(SR * dur)) / SR
    rng = np.random.default_rng(1019)
    # Throttle blip: 70 Hz -> 165 Hz in 70 ms, held while the chain bites, falls back.
    f = np.interp(t, [0, 0.07, 0.3, dur], [70, 165, 150, 85]) * (1 + 0.03 * np.sin(2 * np.pi * 11 * t))
    engine, frac = engine_pulses(f)
    rasp = rng.standard_normal(t.size) * np.exp(-frac * 4.0) * 0.45
    # The bite: grainy mid/high noise chopped at the tooth rate (~260 Hz), strongest right after contact.
    noise = rng.standard_normal(t.size)
    band = np.convolve(noise, np.ones(4) / 4, mode="same") - np.convolve(noise, np.ones(24) / 24, mode="same")
    teeth = 0.55 + 0.45 * np.sign(np.sin(2 * np.pi * 260 * t))
    bite = band * teeth * np.interp(t, [0, 0.025, 0.06, 0.3, 0.42, dur], [0, 1, 0.9, 0.6, 0, 0]) * 1.6
    # A low thump at contact.
    thump = np.sin(2 * np.pi * 68 * t) * np.exp(-t / 0.05) * 0.8
    mix = 0.8 * engine + rasp + bite + thump
    mix *= np.interp(t, [0, 0.006, 0.4, dur], [0, 1, 0.75, 0])
    return mix / (np.max(np.abs(mix)) * 1.1)


def periodic_noise(rng, n, lo, hi):
    """White noise band-passed to lo..hi Hz with an FFT, so it wraps around seamlessly."""
    spec = np.fft.rfft(rng.standard_normal(n))
    freqs = np.fft.rfftfreq(n, 1 / SR)
    spec[(freqs < lo) | (freqs > hi)] = 0
    x = np.fft.irfft(spec, n)
    return x / np.std(x)


def rev():
    dur = 2.0  # every periodic part below fits a whole number of cycles into 2 s, so the loop has no seam
    n = int(SR * dur)
    t = np.arange(n) / SR
    rng = np.random.default_rng(1020)
    # High revs, 150 Hz firing with a slow surge (1.5 Hz) and a faster flutter (4 Hz): 300 firings per loop exactly.
    f = 150 + 7 * np.sin(2 * np.pi * 1.5 * t) + 3 * np.sin(2 * np.pi * 4 * t)
    engine, frac = engine_pulses(f)
    rasp = periodic_noise(rng, n, 300, 9000) * np.exp(-frac * 4.5) * 0.3
    # Chain whine: tooth-passing tone (900 Hz + overtone) and hiss, swelling with the surge.
    surge = 0.75 + 0.25 * np.sin(2 * np.pi * 1.5 * t)
    whine = (np.sin(2 * np.pi * 900 * t) + 0.4 * np.sin(2 * np.pi * 1800 * t)) * 0.07 * surge
    hiss = periodic_noise(rng, n, 2500, 7000) * 0.12 * surge
    mix = 0.8 * engine + rasp + whine + hiss
    return mix / (np.max(np.abs(mix)) * 1.15)


SOUNDS = {"cut": ("chainsaw_cut", cut), "hit": ("chainsaw_hit", hit), "rev": ("chainsaw_rev", rev)}


def encode(name, mix):
    out = OUT / f"{name}.ogg"
    pcm = (mix * 32767).astype(np.int16)
    with tempfile.TemporaryDirectory() as d:
        wav = Path(d) / "saw.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "4", "-ac", "1", str(out)], check=True)
    print(out, out.stat().st_size)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for key in sys.argv[1:] or SOUNDS:
        name, make = SOUNDS[key]
        encode(name, make())
