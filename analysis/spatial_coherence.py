"""Find tiles where the dominant frequency agrees with neighbors — these are the
ones actually containing pulse, vs. random in-band noise that fooled the SNR metric.

Plots:
  1. Coherence map (how well does each tile's f_dom match the median across the frame?)
  2. Time-series of the top coherent tiles, overlaid, to visually verify pulse.
  3. Top tile vs centered ROI for comparison.
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

if len(sys.argv) < 2:
    print("usage: spatial_coherence.py <calibration-tiles.csv>")
    sys.exit(1)

csv_path = Path(sys.argv[1])

meta: dict[str, str] = {}
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
            break

cols = int(meta["grid_cols"]); rows = int(meta["grid_rows"]); n_tiles = cols * rows

data = np.loadtxt(csv_path, delimiter=",", skiprows=n_skip)
ts_ns = data[:, 0]; tiles = data[:, 1:]
n_frames = tiles.shape[0]
dur_s = (ts_ns[-1] - ts_ns[0]) / 1e9
fs = (n_frames - 1) / dur_s

t_s = (ts_ns - ts_ns[0]) / 1e9
t_uni = np.linspace(t_s[0], t_s[-1], n_frames)
tiles_uni = np.empty_like(tiles)
for i in range(n_tiles):
    tiles_uni[:, i] = np.interp(t_uni, t_s, tiles[:, i])

drop_n = int(3.0 * fs)
tiles_clean = tiles_uni[drop_n:]
t_clean = t_uni[drop_n:] - t_uni[drop_n]

low, high = 0.7, 4.0
b, a = butter(2, [low / (fs / 2), high / (fs / 2)], btype="band")
tiles_bp = filtfilt(b, a, tiles_clean, axis=0)

tile_mean = tiles_clean.mean(axis=0)
tile_max = tiles_clean.max(axis=0)
saturated = tile_max >= 254.5

freqs, psd = welch(tiles_clean - tile_mean, fs=fs, nperseg=min(2048, tiles_clean.shape[0]), axis=0)
hr_band = (freqs >= low) & (freqs <= high)
psd_hr = np.where(hr_band[:, None], psd, -np.inf)
dom_idx = np.argmax(psd_hr, axis=0)
dom_f = freqs[dom_idx]
dom_f[saturated] = np.nan

# Most-likely true heart-rate frequency: peak of an AC-weighted histogram of the
# per-tile dominant frequencies. Robust to outliers and skewed distributions.
ac_rms = tiles_bp.std(axis=0)
weights = np.where(saturated, 0, ac_rms)
valid_mask = ~np.isnan(dom_f)
if valid_mask.any():
    bins = np.arange(low, high + 0.05, 0.05)
    hist, edges = np.histogram(dom_f[valid_mask], bins=bins, weights=weights[valid_mask])
    centers = (edges[:-1] + edges[1:]) / 2
    median_f = centers[int(np.argmax(hist))]
else:
    median_f = 1.0
print(f"weighted-median dominant frequency across frame: {median_f:.3f} Hz "
      f"({median_f * 60:.1f} BPM)")

# Coherence: |dom_f - median_f|, smaller is better.
coh = np.abs(dom_f - median_f)
coh[saturated] = np.nan
# Combined score: high AC * low freq deviation, normalized
ac_norm = ac_rms / np.nanmax(ac_rms[~saturated])
coh_norm = 1 - np.clip(coh / 0.3, 0, 1)  # within 0.3 Hz of median = score 1
score = ac_norm * coh_norm
score[saturated] = np.nan

def grid(v): return v.reshape(rows, cols)

print("\n=== Top 10 by coherence-weighted score ===")
score_eff = np.where(np.isnan(score), -np.inf, score)
top = np.argsort(-score_eff)
for k in range(15):
    i = top[k]
    r, c = divmod(i, cols)
    if score[i] is None or np.isnan(score[i]):
        continue
    print(f"  #{k+1:2d}  r={r:2d} c={c:2d} | score={score[i]:.3f} | "
          f"AC_rms={ac_rms[i]:5.2f} | f_dom={dom_f[i]:.2f} Hz ({dom_f[i]*60:.0f} BPM) | "
          f"|df|={coh[i]:.2f} Hz | DC={tile_mean[i]:.0f}")

# Plots
fig, axes = plt.subplots(2, 2, figsize=(13, 9))

ax = axes[0, 0]
fdisp = dom_f.copy(); fdisp_grid = grid(fdisp)
im = ax.imshow(fdisp_grid, cmap="twilight_shifted", vmin=low, vmax=high, aspect="auto")
ax.set_title(f"Dominant frequency per tile (Hz)  median={median_f:.2f} Hz / {median_f*60:.0f} BPM")
plt.colorbar(im, ax=ax, label="Hz")
for r in range(rows):
    for c in range(cols):
        v = fdisp_grid[r, c]
        if not np.isnan(v):
            ax.text(c, r, f"{v:.2f}", ha="center", va="center", color="white", fontsize=6)
        else:
            ax.text(c, r, "—", ha="center", va="center", color="red", fontsize=6)

ax = axes[0, 1]
sg = grid(score)
im = ax.imshow(sg, cmap="viridis", aspect="auto")
ax.set_title("Coherence-weighted pulse score (AC × proximity to median frequency)")
plt.colorbar(im, ax=ax)
for r in range(rows):
    for c in range(cols):
        v = sg[r, c]
        if not np.isnan(v):
            ax.text(c, r, f"{v:.2f}", ha="center", va="center",
                    color="white" if v < 0.5 else "black", fontsize=6)
        else:
            ax.text(c, r, "SAT", ha="center", va="center", color="red", fontsize=6)

# Top-3 vs centered ROI bandpassed time series
ax = axes[1, 0]
top3 = top[:3]
for i in top3:
    r, c = divmod(i, cols)
    ax.plot(t_clean, tiles_bp[:, i], lw=0.7, label=f"r{r}c{c} ({dom_f[i]:.2f}Hz)")
ax.set_title("Bandpassed signal — top 3 coherent tiles")
ax.set_xlabel("seconds"); ax.set_ylabel("filtered red"); ax.legend(loc="upper right", fontsize=8)

# Centered ROI = average of 4 tiles around the middle
ax = axes[1, 1]
centerR = rows // 2; centerC = cols // 2
# 4 tiles around center; skip saturated
center_ids = []
for dr in (-1, 0):
    for dc in (-1, 0):
        idx = (centerR + dr) * cols + (centerC + dc)
        if not saturated[idx]:
            center_ids.append(idx)
if center_ids:
    centered_signal = tiles_bp[:, center_ids].mean(axis=1)
    ax.plot(t_clean, centered_signal, lw=0.7, color="C0", label=f"center ROI (4-tile avg, "
            f"{len(center_ids)}/4 non-sat)")
top1 = top[0]; r1, c1 = divmod(top1, cols)
ax.plot(t_clean, tiles_bp[:, top1], lw=0.7, color="C3", label=f"best tile r{r1}c{c1}")
ax.set_title("Centered ROI (current algorithm) vs best coherent tile")
ax.set_xlabel("seconds"); ax.set_ylabel("filtered red"); ax.legend(loc="upper right", fontsize=8)

plt.tight_layout()
out = csv_path.parent / "coherence.png"
plt.savefig(out, dpi=110)
print(f"\ncoherence chart saved: {out}")

# Recommendation: cluster of high-score tiles
top_n = max(5, int(0.05 * n_tiles))
top_ids = top[:top_n]
top_rcs = [divmod(i, cols) for i in top_ids]
print(f"\nTop {top_n} cluster (by coherence-weighted score):")
print(f"  rows: {sorted({r for r, _ in top_rcs})}")
print(f"  cols: {sorted({c for _, c in top_rcs})}")
rows_seen = [r for r, _ in top_rcs]
cols_seen = [c for _, c in top_rcs]
print(f"  bounding box: r=[{min(rows_seen)}..{max(rows_seen)}], c=[{min(cols_seen)}..{max(cols_seen)}]")
print(f"  pixel rect on 640x480 frame: x=[{min(cols_seen)*40}..{(max(cols_seen)+1)*40}], "
      f"y=[{min(rows_seen)*40}..{(max(rows_seen)+1)*40}]")
