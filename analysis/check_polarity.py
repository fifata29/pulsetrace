"""For one session: plot raw red, detrended, bandpassed, and overlay the peak times
from the JSON. Tells us whether the bandpassed peaks land on systolic events (raw
signal troughs in finger-on-flash setup) or diastolic events (raw signal peaks).
"""
import json, sys
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.signal import butter, filtfilt

if len(sys.argv) < 2:
    print("usage: check_polarity.py <session-dir>")
    sys.exit(1)
d = Path(sys.argv[1])
s = json.loads((d / "summary.json").read_text())
csv = d / "samples.csv"

n_skip = 0
with csv.open() as f:
    for line in f:
        n_skip += 1
        if not line.startswith("#"): break
data = np.loadtxt(csv, delimiter=",", skiprows=n_skip)
ts = (data[:, 0] - data[0, 0]) / 1e9
red = data[:, 1]
fs = (len(ts) - 1) / (ts[-1] - ts[0])
peak_times = np.array(s.get("peak_times_sec", []))

# Detrend (1.5 s moving mean)
win = max(5, int(1.5 * fs))
ker = np.ones(win) / win
det = red - np.convolve(red, ker, mode="same")

# Bandpass 0.7-4 Hz
b, a = butter(2, [0.7 / (fs / 2), 4.0 / (fs / 2)], btype="band")
bp = filtfilt(b, a, det)

# Plot first 8 seconds with peak markers
mask = ts < 8
fig, axes = plt.subplots(3, 1, figsize=(13, 8), sharex=True)
axes[0].plot(ts[mask], red[mask], color="C3", lw=1.0)
axes[0].set_title(f"Raw red — {d.name}")
axes[0].set_ylabel("red (0..255)")

axes[1].plot(ts[mask], det[mask], color="C3", lw=1.0)
axes[1].axhline(0, color="white", alpha=0.2)
axes[1].set_title("Detrended raw (morphology signal source)")
axes[1].set_ylabel("detrended")

axes[2].plot(ts[mask], bp[mask], color="C3", lw=1.0)
axes[2].axhline(0, color="white", alpha=0.2)
axes[2].set_title("Bandpassed 0.7-4 Hz (used for peak detection)")
axes[2].set_xlabel("seconds"); axes[2].set_ylabel("bandpass")

# Overlay peak markers on all three
for t in peak_times:
    if t > 8: break
    for ax in axes:
        ax.axvline(t, color="C0", alpha=0.5, lw=0.8)

# Sample raw value at each peak time to see whether it lands at a max or min
sampled = np.interp(peak_times[peak_times < 8], ts, red)
det_at_peak = np.interp(peak_times[peak_times < 8], ts, det)
print(f"Raw value at peak times: mean={sampled.mean():.2f} (overall mean={red.mean():.2f})")
print(f"Detrended at peak times: {det_at_peak.tolist()}")
print("If detrended values at peaks are POSITIVE → bandpass peaks align with raw maxima (DIASTOLIC events).")
print("If detrended values at peaks are NEGATIVE → bandpass peaks align with raw minima (SYSTOLIC events).")

plt.tight_layout()
out = d / "polarity_check.png"
plt.savefig(out, dpi=110)
print(f"saved: {out}")
