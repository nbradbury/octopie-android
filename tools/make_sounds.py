"""Synthesizes the game's cartoon sound effects into res/raw.

    /tmp/octopie-venv/bin/python tools/make_sounds.py
"""
import wave
from pathlib import Path

import numpy as np

RATE = 44100
RAW = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"
NOISE = np.random.default_rng(3)


def sweep(start_hz: float, end_hz: float, seconds: float) -> np.ndarray:
    """Sine whose pitch glides exponentially from start_hz to end_hz."""
    t = np.arange(int(RATE * seconds)) / RATE
    freq = start_hz * (end_hz / start_hz) ** (t / seconds)
    return np.sin(2 * np.pi * np.cumsum(freq) / RATE)


def envelope(n: int, attack: float, decay: float) -> np.ndarray:
    t = np.arange(n) / RATE
    env = np.minimum(t / attack, 1) * np.exp(-t / decay)
    # Fade the tail to zero so the segment doesn't end in a click.
    fade = min(n, int(RATE * 0.008))
    env[-fade:] *= np.linspace(1, 0, fade)
    return env


def save(name: str, samples: np.ndarray, peak: float = 0.7) -> None:
    samples = samples * peak / np.abs(samples).max()
    RAW.mkdir(parents=True, exist_ok=True)
    with wave.open(str(RAW / f"{name}.wav"), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes((samples * 32767).astype("<i2").tobytes())


def gulp() -> np.ndarray:
    """When the octopus swallows a pie."""
    out = np.zeros(int(RATE * 0.2))

    # "glk": a low throat thump that drops in pitch, with a touch of second harmonic.
    throat = sweep(230, 85, 0.1)
    throat = (throat + 0.3 * np.sign(throat) * throat**2) * envelope(len(throat), 0.003, 0.035)
    out[: len(throat)] += 0.9 * throat

    # "ulp": a bubble whose pitch rises as it closes.
    bubble = sweep(280, 680, 0.13) * envelope(int(RATE * 0.13), 0.004, 0.04)
    start = int(RATE * 0.055)
    out[start : start + len(bubble)] += 0.6 * bubble

    # A short wet burst of softened noise at the very start.
    noise = NOISE.normal(0, 1, int(RATE * 0.025))
    noise = np.convolve(noise, np.ones(12) / 12, mode="same") * envelope(len(noise), 0.001, 0.008)
    out[: len(noise)] += 0.5 * noise
    return out


def swoosh() -> np.ndarray:
    """When a tentacle whips out: noise through a band-pass filter that sweeps upward."""
    n = int(RATE * 0.24)
    t = np.arange(n) / n
    centre = 500 * (3000 / 500) ** t
    noise = NOISE.normal(0, 1, n)
    # State-variable filter, re-tuned every sample.
    low = band = 0.0
    out = np.empty(n)
    for i in range(n):
        f = 2 * np.sin(np.pi * centre[i] / RATE)
        high = noise[i] - low - 0.4 * band
        band += f * high
        low += f * band
        out[i] = band
    # Swell up, then die away.
    return out * np.sin(np.pi * t**0.6) ** 2


def pop() -> np.ndarray:
    """When the octopus bursts: a sharp crack, a falling "bloop" and a soft low thump."""
    out = np.zeros(int(RATE * 0.16))
    crack = NOISE.normal(0, 1, int(RATE * 0.04))
    crack = np.convolve(crack, np.ones(3) / 3, mode="same") * envelope(len(crack), 0.0005, 0.008)
    out[: len(crack)] += 0.9 * crack
    bloop = sweep(900, 180, 0.09) * envelope(int(RATE * 0.09), 0.001, 0.025)
    out[: len(bloop)] += 0.8 * bloop
    thump = sweep(130, 60, 0.14) * envelope(int(RATE * 0.14), 0.002, 0.045)
    out[: len(thump)] += 0.6 * thump
    return out


def main() -> None:
    save("gulp", gulp())
    save("swoosh", swoosh(), peak=0.1)
    save("pop", pop(), peak=0.8)


if __name__ == "__main__":
    main()
