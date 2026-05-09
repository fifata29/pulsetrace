"""Diagnostic per-session analysis. Reads each samples.csv + summary.json, plots:
- raw red signal
- bandpassed signal
- detected peaks (from summary.json) overlaid
- a corrected analysis using a more robust method
- histogram of RR intervals
- printed table comparing reported BPM vs three alternative estimates
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.signal import butter, filtfilt, find_peaks, welch

if len(sys.argv) < 2:
    print("usage: analyze_sessions.py <sessions-root>")
    sys.exit(1)

root = Path(sys.argv[1])
session_dirs = sorted([p for p in root.iterdir() if p.is_dir()])
if not session_dirs:
    print(f"no sessions in {root}")
    sys.exit(1)

print(f"{'session':22s} {'reported':>8s}  {'fs':>5s}  {'n':>4s}  {'med-RR':>8s}  "
      f"{'mode-RR':>8s}  {'peaks-fft':>8s}  {'spec-peak':>8s}  {'valid':>10s}")

results = []
for d in session_dirs:
    summ = json.loads((d / "summary.json").read_text())
    csv = d / "samples.csv"

    # Skip CSV header lines (start with #) + the column-name row.
    n_skip = 0
    with csv.open() as f:
        for line in f:
            n_skip += 1
            if not line.startswith("#"):
                break
    data = np.loadtxt(csv, delimiter=",", skiprows=n_skip)
    if data.ndim == 1:
        data = data[None, :]
    ts = data[:, 0]
    red = data[:, 1]
    luma = data[:, 2]
    coverage = data[:, 3]
    n = len(ts)
    if n < 30:
        continue

    dur = (ts[-1] - ts[0]) / 1e9
    fs = (n - 1) / dur if dur > 0 else 30.0

    # Resample to uniform grid.
    t_s = (ts - ts[0]) / 1e9
    t_uni = np.linspace(t_s[0], t_s[-1], n)
    red_uni = np.interp(t_uni, t_s, red)

    # Detrend with 1.5 s moving-mean.
    win = max(5, int(1.5 * fs))
    kernel = np.ones(win) / win
    detr = red_uni - np.convolve(red_uni, kernel, mode="same")

    # Bandpass 0.7-4 Hz.
    low, high = 0.7, 4.0
    b, a = butter(2, [low / (fs / 2), high / (fs / 2)], btype="band")
    bp = filtfilt(b, a, detr)

    # Method A: scipy find_peaks with a strict minimum separation.
    min_dist = int(0.35 * fs)  # 350 ms = 171 BPM ceiling
    prom = max(0.5 * bp.std(), 1e-6)
    peaks_a, _ = find_peaks(bp, distance=min_dist, prominence=prom)
    rr_a = np.diff(t_uni[peaks_a]) * 1000

    # Method B: spectral estimate — find dominant frequency in heart-rate band.
    freqs, psd = welch(bp, fs=fs, nperseg=min(2048, len(bp)))
    in_band = (freqs >= low) & (freqs <= high)
    if in_band.any():
        spec_freq = freqs[in_band][np.argmax(psd[in_band])]
        spec_bpm = spec_freq * 60
    else:
        spec_bpm = float("nan")

    # Method C: median + mode of RR after coarse outlier rejection.
    rr_clean = rr_a[(rr_a >= 300) & (rr_a <= 2000)]
    median_rr = float(np.median(rr_clean)) if rr_clean.size else float("nan")
    bpm_med = 60000 / median_rr if median_rr > 0 else float("nan")
    if rr_clean.size:
        # Mode via 25 ms bins.
        bins = np.arange(300, 2000, 25)
        h, e = np.histogram(rr_clean, bins=bins)
        if h.max() > 0:
            mode_rr = (e[h.argmax()] + e[h.argmax() + 1]) / 2
            bpm_mode = 60000 / mode_rr
        else:
            mode_rr = float("nan"); bpm_mode = float("nan")
    else:
        mode_rr = float("nan"); bpm_mode = float("nan")

    # FFT-implied BPM (peaks-based).
    if rr_a.size:
        bpm_peaks = 60000 / np.median(rr_a)
    else:
        bpm_peaks = float("nan")

    reported = summ.get("metrics", {}).get("bpm", float("nan"))
    valid = summ.get("metrics", {}).get("valid_beats", 0)
    total = summ.get("metrics", {}).get("total_beats", 0)

    print(f"{d.name:22s} {reported:8.1f}  {fs:5.1f}  {n:4d}  "
          f"{bpm_med:8.1f}  {bpm_mode:8.1f}  {bpm_peaks:8.1f}  {spec_bpm:8.1f}  {valid:>4d}/{total:<4d}")

    # Plots: raw, bandpass, peaks, RR histogram.
    fig, axes = plt.subplots(3, 1, figsize=(12, 9))
    axes[0].plot(t_uni, red_uni, lw=0.8, color="C3")
    axes[0].set_title(f"{d.name} — raw red ({n} samples @ {fs:.1f} Hz)")
    axes[0].set_ylabel("red")

    axes[1].plot(t_uni, bp, lw=0.8, color="C3")
    if peaks_a.size:
        axes[1].plot(t_uni[peaks_a], bp[peaks_a], "o", ms=4, color="C0", label=f"peaks (min-sep 350ms): {len(peaks_a)}")
    axes[1].set_title(f"bandpass 0.7-4 Hz, peaks → median-RR BPM {bpm_med:.1f}, "
                      f"mode-RR BPM {bpm_mode:.1f}, spectral BPM {spec_bpm:.1f}, app reported {reported:.1f}")
    axes[1].set_ylabel("filtered red")
    axes[1].legend(loc="upper right", fontsize=8)

    if rr_clean.size > 1:
        axes[2].hist(rr_clean, bins=np.arange(300, 1600, 25), color="C0", edgecolor="white")
        axes[2].axvline(median_rr, color="C3", ls="--", lw=2, label=f"median {median_rr:.0f} ms")
        if not np.isnan(mode_rr):
            axes[2].axvline(mode_rr, color="C2", ls=":", lw=2, label=f"mode {mode_rr:.0f} ms")
        axes[2].set_title("RR histogram (cleaned 300-2000 ms only)")
        axes[2].set_xlabel("RR (ms)")
        axes[2].legend(loc="upper right", fontsize=8)
    else:
        axes[2].set_title("RR histogram — too few intervals")

    plt.tight_layout()
    out = d / "diagnostic.png"
    plt.savefig(out, dpi=110)
    plt.close(fig)
    results.append((d.name, reported, fs, n, median_rr, bpm_med, bpm_mode, bpm_peaks, spec_bpm, valid, total))

print()
print("Outputs: diagnostic.png inside each session folder.")
