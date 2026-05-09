"""Per-tile PPG signal analysis.

Reads the calibration-tiles.csv produced by PulseTrace's calibration mode and:
  1. Estimates effective sample rate from frame timestamps.
  2. Bandpasses each tile in the heart-rate band (0.7-4 Hz, zero-phase Butterworth).
  3. Computes per-tile heart-rate-band SNR and dominant frequency.
  4. Saves a heatmap PNG and prints the top tiles + recommended ROI.

Usage: python analyze_calibration.py <path-to-calibration-tiles.csv>
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.signal import butter, filtfilt, welch

# --- CLI ---
if len(sys.argv) < 2:
    print("usage: analyze_calibration.py <calibration-tiles.csv>")
    sys.exit(1)
csv_path = Path(sys.argv[1])
if not csv_path.exists():
    print(f"file not found: {csv_path}")
    sys.exit(1)

# --- Parse header metadata + load data ---
meta: dict[str, str] = {}
header_line: str | None = None
n_skip = 0
with csv_path.open() as f:
    while True:
        line = f.readline()
        if not line:
            break
        n_skip += 1
        if line.startswith("#"):
            m = re.match(r"#\s*([^:]+):\s*(.+?)\s*$", line)
            if m:
                meta[m.group(1).strip()] = m.group(2).strip()
        else:
            # The first non-# line is the column-name row; data starts after it.
            header_line = line.rstrip("\n")
            break

cols = int(meta["grid_cols"])
rows = int(meta["grid_rows"])
n_tiles = cols * rows
print(f"meta: {meta}")
print(f"grid: {rows} rows x {cols} cols, {n_tiles} tiles")
print(f"skipping {n_skip} header lines")

data = np.loadtxt(csv_path, delimiter=",", skiprows=n_skip)

print(f"loaded {data.shape}")
ts_ns = data[:, 0]
tiles = data[:, 1:]  # shape (n_frames, n_tiles)
n_frames = tiles.shape[0]

# Effective sample rate
dur_s = (ts_ns[-1] - ts_ns[0]) / 1e9
fs = (n_frames - 1) / dur_s if dur_s > 0 else 30.0
print(f"frames: {n_frames}, duration: {dur_s:.2f} s, sample rate: {fs:.2f} Hz")

# Resample to uniform grid (linear interpolation) — filtering needs uniform dt
t_s = (ts_ns - ts_ns[0]) / 1e9
t_uni = np.linspace(t_s[0], t_s[-1], n_frames)
tiles_uni = np.empty_like(tiles)
for i in range(n_tiles):
    tiles_uni[:, i] = np.interp(t_uni, t_s, tiles[:, i])

# Drop first 3 s of each tile to skip AE settling
drop_s = 3.0
drop_n = int(drop_s * fs)
tiles_clean = tiles_uni[drop_n:]
print(f"after dropping first {drop_n} samples ({drop_s} s): {tiles_clean.shape}")

# Per-tile DC and saturation flags
tile_mean = tiles_clean.mean(axis=0)
tile_max = tiles_clean.max(axis=0)
tile_min = tiles_clean.min(axis=0)
saturated = tile_max >= 254.5  # at clipping ceiling

# --- Bandpass 0.7-4 Hz ---
low, high = 0.7, 4.0
b, a = butter(2, [low / (fs / 2), high / (fs / 2)], btype="band")
tiles_bp = filtfilt(b, a, tiles_clean, axis=0)

# Per-tile metrics
ac_rms = tiles_bp.std(axis=0)               # in-band amplitude
dc = tile_mean
ac_dc = ac_rms / np.where(dc > 1, dc, 1)    # AC/DC ratio (modulation depth)

# SNR via Welch: ratio of in-band power to out-of-band power
freqs, psd = welch(tiles_clean - tile_mean, fs=fs, nperseg=min(1024, tiles_clean.shape[0]), axis=0)
in_band = (freqs >= low) & (freqs <= high)
out_band = (freqs >= 0.05) & (freqs < low) | (freqs > high) & (freqs < fs / 2 - 0.5)
in_power = psd[in_band].sum(axis=0)
out_power = np.maximum(psd[out_band].sum(axis=0), 1e-9)
snr = 10 * np.log10(in_power / out_power)
# Dominant frequency in HR band (BPM)
dom_idx = np.argmax(np.where(in_band[:, None], psd, 0), axis=0)
dom_freq_hz = freqs[dom_idx]
dom_bpm = dom_freq_hz * 60

# Penalize saturated tiles — their AC is meaningless
ac_rms_eff = ac_rms.copy()
ac_rms_eff[saturated] = 0
ac_dc_eff = ac_dc.copy()
ac_dc_eff[saturated] = 0

# Reshape to grid
def grid(v):
    return v.reshape(rows, cols)

# --- Reports ---
print("\n=== Top 10 tiles by AC/DC modulation depth (saturated tiles excluded) ===")
order = np.argsort(-ac_dc_eff)
for k in range(10):
    i = order[k]
    r, c = divmod(i, cols)
    print(f"  #{k+1:2d}  tile r={r:2d} c={c:2d} | DC={dc[i]:6.1f} | AC_rms={ac_rms[i]:6.3f} | "
          f"AC/DC={ac_dc_eff[i]*100:6.3f}% | SNR={snr[i]:5.1f} dB | "
          f"f_dom={dom_freq_hz[i]:.2f} Hz ({dom_bpm[i]:.0f} BPM){' [SAT]' if saturated[i] else ''}")

print("\n=== Top 10 tiles by SNR (saturated tiles excluded) ===")
snr_eff = snr.copy()
snr_eff[saturated] = -np.inf
order2 = np.argsort(-snr_eff)
for k in range(10):
    i = order2[k]
    r, c = divmod(i, cols)
    print(f"  #{k+1:2d}  tile r={r:2d} c={c:2d} | DC={dc[i]:6.1f} | AC_rms={ac_rms[i]:6.3f} | "
          f"AC/DC={ac_dc_eff[i]*100:6.3f}% | SNR={snr[i]:5.1f} dB | "
          f"f_dom={dom_freq_hz[i]:.2f} Hz ({dom_bpm[i]:.0f} BPM){' [SAT]' if saturated[i] else ''}")

print(f"\nSaturated tiles ({saturated.sum()}/{n_tiles}):")
for i in np.where(saturated)[0]:
    r, c = divmod(i, cols)
    print(f"  r={r:2d} c={c:2d} max={tile_max[i]:.1f}")

# --- Heatmaps ---
out_dir = csv_path.parent
fig, axes = plt.subplots(2, 2, figsize=(13, 9))

ax = axes[0, 0]
im = ax.imshow(grid(dc), aspect="auto", cmap="inferno", vmin=0, vmax=255)
ax.set_title(f"Mean red (DC) — {n_frames} frames @ {fs:.1f} Hz")
plt.colorbar(im, ax=ax, label="0..255")
for r in range(rows):
    for c in range(cols):
        ax.text(c, r, f"{dc[r*cols+c]:.0f}", ha="center", va="center",
                color="white" if dc[r*cols+c] < 128 else "black", fontsize=6)

ax = axes[0, 1]
sat_mask = grid(saturated.astype(float))
im = ax.imshow(sat_mask, aspect="auto", cmap="Reds", vmin=0, vmax=1)
ax.set_title(f"Saturated tiles ({saturated.sum()}/{n_tiles})")
plt.colorbar(im, ax=ax)

ax = axes[1, 0]
im = ax.imshow(grid(ac_dc_eff * 100), aspect="auto", cmap="viridis")
ax.set_title("Modulation depth: AC_rms / DC × 100% (saturated → 0)")
plt.colorbar(im, ax=ax, label="%")
for r in range(rows):
    for c in range(cols):
        v = ac_dc_eff[r*cols+c] * 100
        ax.text(c, r, f"{v:.2f}", ha="center", va="center",
                color="white" if v < 0.05 else "black", fontsize=6)

ax = axes[1, 1]
snr_disp = snr.copy()
snr_disp[saturated] = np.nan
im = ax.imshow(grid(snr_disp), aspect="auto", cmap="viridis")
ax.set_title(f"SNR (dB) — in-band {low}-{high} Hz vs out-of-band")
plt.colorbar(im, ax=ax, label="dB")
for r in range(rows):
    for c in range(cols):
        v = snr_disp[r*cols+c]
        if not np.isnan(v):
            ax.text(c, r, f"{v:.1f}", ha="center", va="center",
                    color="white" if v < 0 else "black", fontsize=6)
        else:
            ax.text(c, r, "SAT", ha="center", va="center", color="red", fontsize=6)

plt.tight_layout()
heatmap_path = out_dir / "heatmap.png"
plt.savefig(heatmap_path, dpi=110)
print(f"\nheatmap saved: {heatmap_path}")

# --- Best-tile time-series chart ---
best = order2[0]  # highest SNR
br, bc = divmod(best, cols)
fig2, axes2 = plt.subplots(2, 1, figsize=(12, 6))
axes2[0].plot(t_uni[drop_n:], tiles_clean[:, best], color="C3", lw=0.8)
axes2[0].set_title(f"Best-SNR tile r={br} c={bc} — raw (DC={dc[best]:.1f})")
axes2[0].set_ylabel("Red")
axes2[1].plot(t_uni[drop_n:], tiles_bp[:, best], color="C3", lw=0.8)
axes2[1].set_title(f"Best-SNR tile r={br} c={bc} — bandpass {low}-{high} Hz "
                   f"(SNR={snr[best]:.1f} dB, AC/DC={ac_dc_eff[best]*100:.3f}%, "
                   f"f_dom={dom_freq_hz[best]:.2f} Hz / {dom_bpm[best]:.0f} BPM)")
axes2[1].set_xlabel("seconds")
axes2[1].set_ylabel("filtered red")
plt.tight_layout()
ts_path = out_dir / "best_tile.png"
plt.savefig(ts_path, dpi=110)
print(f"best-tile chart saved: {ts_path}")

# --- Recommendation ---
print("\n=== Recommendation ===")
top5 = order2[:5]
top5_rc = [(divmod(i, cols), snr[i]) for i in top5]
print(f"Top 5 by SNR: " + ", ".join(f"r{rc[0]}c{rc[1]}({s:.1f}dB)" for rc, s in top5_rc))

# Find spatial cluster of high-SNR tiles
snr_thresh = max(np.nanpercentile(snr_disp, 90), 0.0)
good_mask = (snr_disp >= snr_thresh) & ~saturated
good_grid = grid(good_mask.astype(int))
print(f"Tiles above {snr_thresh:.1f} dB: {good_mask.sum()}")

# bounding box of cluster
if good_mask.sum() > 0:
    rs, cs = np.where(good_grid > 0)
    rmin, rmax = rs.min(), rs.max()
    cmin, cmax = cs.min(), cs.max()
    print(f"Bounding box of good cluster: rows [{rmin}..{rmax}], cols [{cmin}..{cmax}] "
          f"(out of {rows} rows, {cols} cols)")
    print(f"That maps to pixel rect: x={cmin*40}..{(cmax+1)*40}, y={rmin*40}..{(rmax+1)*40} on a 640x480 frame")
