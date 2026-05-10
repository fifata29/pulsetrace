"""Mirror PulseMorphology.compute() in Python on a raw samples.csv. Plots every
intermediate so we can see exactly where the systolic-peak position goes wrong.

Steps:
  1. Read samples.csv → detrend raw red (1.5 s moving average) — that's the morph signal
  2. Bandpass 0.7-4 Hz (peak detection signal)
  3. Find peaks on bandpass (cycle markers)
  4. Snap each cycle marker to the nearest local max on the morph signal (±0.15 s)
  5. For each snapped peak, walk backward to find the foot (local min before)
  6. Plot the first 8 s with peak/foot markers on raw, morph, and bandpass
  7. Print where the systolic peak lands relative to the beat (foot[i]..foot[i+1])
"""
import json, sys
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.signal import butter, filtfilt, find_peaks

if len(sys.argv) < 2:
    print("usage: trace_morphology.py <session-dir>")
    sys.exit(1)
d = Path(sys.argv[1])

n_skip = 0
with (d / "samples.csv").open() as f:
    for line in f:
        n_skip += 1
        if not line.startswith("#"): break
data = np.loadtxt(d / "samples.csv", delimiter=",", skiprows=n_skip)
ts = (data[:, 0] - data[0, 0]) / 1e9
red = data[:, 1]
fs = (len(ts) - 1) / (ts[-1] - ts[0])

# Step 1: detrend (no bandpass) — this is the morphology signal
win = max(5, int(1.5 * fs))
ker = np.ones(win) / win
morph = red - np.convolve(red, ker, mode="same")

# Step 2: bandpass 0.7-4 Hz for peak detection
b, a = butter(2, [0.7 / (fs / 2), 4.0 / (fs / 2)], btype="band")
bp = filtfilt(b, a, morph)

# Step 3: find peaks on bandpass
min_dist = int(0.35 * fs)
peaks_bp, _ = find_peaks(bp, distance=min_dist, prominence=0.5 * bp.std())

# Step 4: snap to nearest local max on morph signal within ±0.15 s
snap_win = int(0.15 * fs)
peaks_morph = []
for p in peaks_bp:
    lo = max(0, p - snap_win); hi = min(len(morph) - 1, p + snap_win)
    peaks_morph.append(lo + np.argmax(morph[lo:hi+1]))
peaks_morph = np.array(peaks_morph)

# Step 5: foot for each snapped peak — local min between previous peak and this one
feet = []
for i, p in enumerate(peaks_morph):
    lo = peaks_morph[i-1] + 1 if i > 0 else max(0, p - 600)
    hi = p
    feet.append(lo + np.argmin(morph[lo:hi+1]))
feet = np.array(feet)

# Compute beat segments and where the systolic peak lands within each
print("Beats: each is feet[i]..feet[i+1]; systolic peak inside should be near start")
for i in range(len(feet) - 1):
    a, c = feet[i], feet[i + 1]
    peak_in_beat = peaks_morph[i + 1] if i + 1 < len(peaks_morph) else None
    # The peak that falls inside beat i is peaks_morph[i] (which is RIGHT AT feet[i])?
    # Or peaks_morph[i+1] which is RIGHT AT feet[i+1]?
    # Depends on segmentation convention
    contained = [p for p in peaks_morph if a <= p <= c]
    if contained:
        rel = (contained[0] - a) / (c - a)
        print(f"  beat {i}: feet=[{a},{c}], dur={(c-a)/fs:.2f}s; "
              f"peak at idx {contained[0]} = {rel*100:.1f}% of beat")
    else:
        print(f"  beat {i}: feet=[{a},{c}], NO peak inside")
    if i > 8: break

# Plot first 8 s
mask = ts < 8
fig, axes = plt.subplots(3, 1, figsize=(13, 8), sharex=True)

axes[0].plot(ts[mask], red[mask], color="C3", lw=1.0)
axes[0].set_title(f"{d.name}: raw red")
axes[0].set_ylabel("red")

axes[1].plot(ts[mask], morph[mask], color="C3", lw=1.0)
axes[1].axhline(0, color="white", alpha=0.2)
axes[1].set_title("morph signal (detrended raw, no bandpass)")
axes[1].set_ylabel("detrended")

axes[2].plot(ts[mask], bp[mask], color="C3", lw=1.0)
axes[2].axhline(0, color="white", alpha=0.2)
axes[2].set_title("bandpass 0.7-4 Hz (used for peak detection)")
axes[2].set_xlabel("seconds"); axes[2].set_ylabel("bandpass")

# Overlay peaks (orange) and feet (blue) on raw + morph
for ax in (axes[0], axes[1]):
    for p in peaks_morph:
        if ts[p] < 8: ax.plot(ts[p], (red if ax is axes[0] else morph)[p], "o", color="C1", ms=8)
    for f_ in feet:
        if ts[f_] < 8: ax.plot(ts[f_], (red if ax is axes[0] else morph)[f_], "v", color="C0", ms=8)
for p in peaks_bp:
    if ts[p] < 8: axes[2].plot(ts[p], bp[p], "o", color="C1", ms=8)

plt.tight_layout()
out = d / "trace.png"
plt.savefig(out, dpi=110)
print(f"\nsaved: {out}")
