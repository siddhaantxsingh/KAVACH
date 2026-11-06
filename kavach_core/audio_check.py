"""Guardian placement check: is the Guardian phone close enough to the speaker-phone?

estimate_snr_db() looks at 5 s of microphone audio during the call: 20 ms frames, frame power in
dB; the quiet frames (10th percentile) are the room noise, the loud frames (90th percentile) are
speech + noise. SNR = 10*log10((P_loud - P_quiet) / P_quiet). Crude but needs no model, and
bench/e2e_noise.py checks it against the true SNR. Kotlin: kavach.core.AudioCheck (parity-tested).

verdict(): estimate >= 18 dB "good", >= 12 dB "move closer", below "too noisy". The estimate reads
about 5 dB above the true SNR (bench/e2e_noise.py: true 30/15/8/3 dB -> 35/21/14/10 dB); with the
default English model scam calls reached STOP 6/6 at a true 15 dB, 4/6 at 8 dB, 2/6 at 3 dB.
"""
from __future__ import annotations

import math


def frame_powers(x, sr: int, frame_ms: int = 20) -> list[float]:
    n = max(1, sr * frame_ms // 1000)
    out = []
    for i in range(0, len(x) - n + 1, n):
        s = 0.0
        for v in x[i:i + n]:
            s += float(v) * float(v)
        out.append(s / n)
    return out


def _pct(sorted_vals: list[float], q: float) -> float:
    return sorted_vals[min(len(sorted_vals) - 1, int(q * (len(sorted_vals) - 1)))]


def estimate_snr_db(x, sr: int) -> float:
    return estimate_from_powers(frame_powers(x, sr))


def estimate_from_powers(powers: list[float]) -> float:
    p = sorted(powers)
    if not p:
        return 0.0
    quiet = max(_pct(p, 0.10), 1e-12)
    loud = _pct(p, 0.90)
    return 10.0 * math.log10(max(loud - quiet, 1e-12) / quiet)


def verdict(snr_db: float) -> str:
    return "good" if snr_db >= 18.0 else "move_closer" if snr_db >= 12.0 else "too_noisy"
