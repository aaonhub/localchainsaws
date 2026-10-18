#!/usr/bin/env python3
"""Synthesizes the chainsaw sound from scratch (two-stroke engine pulses + chain/wood grind noise): rev up, cut, idle down.
Writes a WAV, then encodes it to Ogg Vorbis with ffmpeg. Usage: make_sound.py"""
import subprocess, tempfile, wave
from pathlib import Path
import numpy as np

SR = 44100
DUR = 1.6
t = np.arange(int(SR * DUR)) / SR
rng = np.random.default_rng(1018)

# Engine firing rate (Hz): idle 45 -> rev to 135 by 0.25 s -> holds under load (sags a little) -> drops back after 1.25 s.
f = np.interp(t, [0, 0.25, 0.6, 1.2, 1.45, DUR], [45, 135, 118, 125, 70, 50])
f = f * (1 + 0.025 * np.sin(2 * np.pi * 7.3 * t))  # uneven firing wobble
phase = np.cumsum(f) / SR
frac = phase % 1.0
# Each firing = a sharp decaying pulse; buzzy, like a small two-stroke.
pulse = np.exp(-frac * 9.0) * (1 - frac) ** 0.5
pulse -= pulse.mean()
engine = np.tanh(3.0 * pulse)
# Exhaust rasp: second harmonic + noise gated by the firing pulses.
rasp = rng.standard_normal(t.size) * np.exp(-frac * 5.0) * 0.35
# Chain on wood: band-passed noise during the cut, grainy.
noise = rng.standard_normal(t.size)
kernel = np.ones(6) / 6
grind = np.convolve(noise, kernel, mode="same") - np.convolve(noise, np.ones(40) / 40, mode="same")
cut_env = np.interp(t, [0, 0.3, 0.4, 1.15, 1.3, DUR], [0, 0, 1, 1, 0, 0])
grind *= cut_env * (0.7 + 0.3 * np.sin(2 * np.pi * 23 * t)) * 1.4

mix = 0.75 * engine + rasp + grind
env = np.interp(t, [0, 0.03, 1.35, DUR], [0, 1, 0.8, 0])
mix *= env
mix /= np.max(np.abs(mix)) * 1.12
pcm = (mix * 32767).astype(np.int16)

out = Path(__file__).resolve().parent.parent / "src/main/resources/assets/localchainsaws/sounds/item/chainsaw_cut.ogg"
with tempfile.TemporaryDirectory() as d:
    wav = Path(d) / "saw.wav"
    with wave.open(str(wav), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "4", "-ac", "1", str(out)], check=True)
print(out, out.stat().st_size)
