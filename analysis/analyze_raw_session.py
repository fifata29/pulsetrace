"""Analyze a raw_sessions/<ts>/raw-tiles.csv produced by Raw Mode.

For each tile and channel (R, G, B):
  - DC level (mean) and AC amplitude (signal range after detrend)
  - SNR via PSD: power in 0.7-3.5 Hz / power in 4-15 Hz
  - Pulse-band peak frequency
  - Pulse-band coherence between channels (correlation of detrended signals)

Then for the best-SNR tile per channel, plots the averaged beat across R, G, B
on the same time axis, the spectrum, and the vasomotion (0.05-0.15 Hz) and
respiratory (0.15-0.5 Hz) bands.

Output: <session-dir>/raw_report.png + raw_report.json + per-channel heatmaps.

Usage:
  python analyze_raw_session.py <session-dir>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from scipy.signal import butter, filtfilt, find_peaks, welch


# ----------------------------- IO -----------------------------

def read_raw_session(d: Path):
    """Returns (header dict, ts_sec array, dict[(row,col)][channel] = N-array)."""
    csv_path = d / "raw-tiles.csv"
    if not csv_path.exists():
        raise FileNotFoundError(f"missing {csv_path}")

    header = {}
    column_names = None
    n_skip = 0
    with csv_path.open() as f:
        for line in f:
            n_skip += 1
            if line.startswith("#"):
                if ":" in line:
                    k, v = line[2:].split(":", 1)
                    header[k.strip()] = v.strip()
                continue
            column_names = line.strip().split(",")
            break

    grid_cols = int(header.get("grid_cols", 16))
    grid_rows = int(header.get("grid_rows", 12))

    data = np.loadtxt(csv_path, delimiter=",", skiprows=n_skip)
    ts = (data[:, 0] - data[0, 0]) / 1e9
    # Layout: timestamp_ns, frame_idx, then 6 cols per tile (R,G,B,Rs,Gs,Bs)
    # in row-major order over (row, col).
    cols_per_tile = 6
    expected = 2 + grid_rows * grid_cols * cols_per_tile
    if data.shape[1] != expected:
        raise ValueError(f"unexpected column count {data.shape[1]}, expected {expected}")

    tiles = {}
    base = 2
    for row in range(grid_rows):
        for col in range(grid_cols):
            slc = data[:, base:base + cols_per_tile]
            tiles[(row, col)] = {
                "R": slc[:, 0], "G": slc[:, 1], "B": slc[:, 2],
                "Rs": slc[:, 3], "Gs": slc[:, 4], "Bs": slc[:, 5],
            }
            base += cols_per_tile

    fs = (len(ts) - 1) / (ts[-1] - ts[0]) if len(ts) > 1 else 0.0
    header["computed_fs_hz"] = fs
    header["n_frames"] = len(ts)
    header["duration_sec"] = float(ts[-1] - ts[0]) if len(ts) > 1 else 0.0
    return header, ts, tiles


# ----------------------------- DSP -----------------------------

def detrend(x, fs, win_sec=1.5):
    win = max(5, int(win_sec * fs))
    ker = np.ones(win) / win
    return x - np.convolve(x, ker, mode="same")


def bandpass(x, fs, lo, hi, order=2):
    ny = fs / 2
    b, a = butter(order, [lo / ny, hi / ny], btype="band")
    return filtfilt(b, a, x)


def snr_pulse(x, fs):
    """SNR = peak pulse-band power / median out-of-band power."""
    if len(x) < int(4 * fs):
        return 0.0, 0.0
    f, P = welch(x, fs=fs, nperseg=min(len(x), int(8 * fs)))
    in_band = (f >= 0.7) & (f <= 3.5)
    out_band = (f >= 4.0) & (f <= min(15.0, fs / 2 - 0.5))
    if not in_band.any() or not out_band.any():
        return 0.0, 0.0
    peak = P[in_band].max()
    floor = np.median(P[out_band]) + 1e-12
    snr_db = 10 * np.log10(peak / floor)
    f_peak = f[in_band][P[in_band].argmax()]
    return float(snr_db), float(f_peak)


def ac_amplitude(x):
    return float(np.percentile(x, 95) - np.percentile(x, 5))


# ----------------------------- per-tile scoring -----------------------------

def channel_score_map(tiles, fs, rows, cols, channel):
    snr = np.zeros((rows, cols))
    fpeak = np.zeros((rows, cols))
    ac = np.zeros((rows, cols))
    dc = np.zeros((rows, cols))
    for (r, c), ch in tiles.items():
        x = ch[channel]
        dc[r, c] = float(np.mean(x))
        det = detrend(x, fs)
        ac[r, c] = ac_amplitude(det)
        bp = bandpass(det, fs, 0.7, 8.0)
        snr[r, c], fpeak[r, c] = snr_pulse(bp, fs)
    return {"snr_db": snr, "f_peak_hz": fpeak, "ac": ac, "dc": dc}


def best_tile(snr_map, dc_map):
    """Best = highest SNR among tiles whose DC is between 6 and 245 (avoid both
    dark and saturated)."""
    valid = (dc_map > 6) & (dc_map < 245)
    if not valid.any():
        idx = np.unravel_index(np.argmax(snr_map), snr_map.shape)
        return tuple(int(i) for i in idx)
    masked = np.where(valid, snr_map, -np.inf)
    idx = np.unravel_index(np.argmax(masked), masked.shape)
    return tuple(int(i) for i in idx)


# ----------------------------- averaged beat -----------------------------

def averaged_beat(x, fs, n_pre=0.2, n_post=0.7):
    """Foot-aligned averaged beat. Returns (t_axis, beat) or (None, None)."""
    bp = bandpass(x, fs, 0.7, 8.0)
    min_dist = int(0.35 * fs)
    pks, _ = find_peaks(bp, distance=min_dist, prominence=0.4 * bp.std())
    if len(pks) < 4:
        return None, None
    # Compute beat duration from RR
    rr = np.diff(pks) / fs
    if len(rr) == 0:
        return None, None
    med = float(np.median(rr))
    # Foot for each peak: local min on (peaks[i-1] .. peaks[i]) on the detrended
    detr = detrend(x, fs)
    feet = []
    for i, p in enumerate(pks):
        lo = pks[i - 1] + 1 if i > 0 else max(0, p - int(med * fs))
        seg = detr[lo:p]
        if len(seg) > 0:
            feet.append(lo + int(np.argmin(seg)))
    feet = np.array(feet)
    if len(feet) < 4:
        return None, None
    # Window from -n_pre to +n_post seconds around each foot
    pre = int(n_pre * fs); post = int(n_post * fs)
    n = pre + post
    beats = []
    for f in feet:
        lo = f - pre; hi = f + post
        if lo < 0 or hi > len(detr): continue
        seg = detr[lo:hi]
        # Hampel-style outlier filter on duration uses RR around this foot
        beats.append(seg)
    if len(beats) < 3:
        return None, None
    beats = np.array(beats)
    # Robust average: trim 10% extreme values per sample
    trimmed = np.sort(beats, axis=0)
    k = max(1, int(0.1 * len(beats)))
    avg = trimmed[k:-k].mean(axis=0) if len(beats) > 2 * k + 1 else beats.mean(axis=0)
    t_axis = np.arange(n) / fs - n_pre
    return t_axis, avg


# ----------------------------- vasomotion / respiration -----------------------------

def low_freq_psd(x, fs):
    """PSD limited to 0.02-0.5 Hz (respiratory + Mayer wave region)."""
    if len(x) < int(30 * fs):
        return None, None
    f, P = welch(x, fs=fs, nperseg=min(len(x), int(60 * fs)))
    mask = (f >= 0.02) & (f <= 0.5)
    return f[mask], P[mask]


# ----------------------------- main -----------------------------

def main():
    if len(sys.argv) < 2:
        print("usage: analyze_raw_session.py <session-dir>"); sys.exit(1)
    d = Path(sys.argv[1])
    print(f"loading {d}")
    header, ts, tiles = read_raw_session(d)
    fs = float(header["computed_fs_hz"])
    rows = int(header["grid_rows"])
    cols = int(header["grid_cols"])
    print(f"  fs={fs:.2f} Hz, frames={header['n_frames']}, duration={header['duration_sec']:.1f}s")
    print(f"  site={header.get('site', '?')}, target_fps={header.get('target_fps', '?')}")

    # Per-channel score maps
    print("computing per-channel SNR maps...")
    maps = {}
    for ch in ("R", "G", "B"):
        maps[ch] = channel_score_map(tiles, fs, rows, cols, ch)
        bt = best_tile(maps[ch]["snr_db"], maps[ch]["dc"])
        s = maps[ch]["snr_db"][bt]
        a = maps[ch]["ac"][bt]
        dc = maps[ch]["dc"][bt]
        print(f"  {ch}: best tile {bt}, SNR={s:.1f} dB, AC={a:.2f}, DC={dc:.1f}, fpeak={maps[ch]['f_peak_hz'][bt]:.2f} Hz")
        maps[ch]["best_tile"] = bt

    # Pick a single representative tile (highest G SNR among tiles where R is also good)
    g_snr = maps["G"]["snr_db"]
    g_dc = maps["G"]["dc"]
    valid = (g_dc > 6) & (g_dc < 245) & (maps["R"]["dc"] > 6) & (maps["R"]["dc"] < 250)
    masked = np.where(valid, g_snr, -np.inf)
    if np.isfinite(masked).any():
        rep_tile = tuple(int(i) for i in np.unravel_index(np.argmax(masked), g_snr.shape))
    else:
        rep_tile = maps["G"]["best_tile"]
    print(f"  representative tile (G-best, all channels valid): {rep_tile}")

    # Per-channel time series at rep tile
    rt = tiles[rep_tile]
    bps = {ch: bandpass(detrend(rt[ch], fs), fs, 0.7, 8.0) for ch in ("R", "G", "B")}

    # Averaged beats
    beats = {}
    for ch in ("R", "G", "B"):
        t_axis, beat = averaged_beat(rt[ch], fs)
        beats[ch] = (t_axis, beat)

    # Inter-channel correlation in pulse band
    pulse_corr = {
        "R-G": float(np.corrcoef(bps["R"], bps["G"])[0, 1]),
        "R-B": float(np.corrcoef(bps["R"], bps["B"])[0, 1]),
        "G-B": float(np.corrcoef(bps["G"], bps["B"])[0, 1]),
    }
    print(f"  pulse-band corr R-G={pulse_corr['R-G']:.2f}, R-B={pulse_corr['R-B']:.2f}, G-B={pulse_corr['G-B']:.2f}")

    # Vasomotion / respiration
    lf_psd = {}
    for ch in ("R", "G", "B"):
        det = detrend(rt[ch], fs)
        f_lf, P_lf = low_freq_psd(det, fs)
        lf_psd[ch] = (f_lf, P_lf)

    # ---------- plots ----------
    fig = plt.figure(figsize=(15, 11))
    gs = fig.add_gridspec(4, 3, hspace=0.45, wspace=0.25)

    # Row 0: SNR heatmap per channel
    for i, ch in enumerate(("R", "G", "B")):
        ax = fig.add_subplot(gs[0, i])
        im = ax.imshow(maps[ch]["snr_db"], cmap="viridis", origin="upper", aspect="auto")
        bt = maps[ch]["best_tile"]
        ax.plot(bt[1], bt[0], "rx", ms=10, mew=2)
        ax.set_title(f"{ch} channel SNR (dB) — best at {bt}, {maps[ch]['snr_db'][bt]:.1f} dB")
        plt.colorbar(im, ax=ax, fraction=0.04)

    # Row 1: AC amplitude heatmap per channel
    for i, ch in enumerate(("R", "G", "B")):
        ax = fig.add_subplot(gs[1, i])
        im = ax.imshow(maps[ch]["ac"], cmap="magma", origin="upper", aspect="auto")
        ax.set_title(f"{ch} AC amplitude (5-95 pct, detrended)")
        plt.colorbar(im, ax=ax, fraction=0.04)

    # Row 2: time-series first 8s @ rep tile, per channel
    mask = ts < 8
    for i, ch in enumerate(("R", "G", "B")):
        ax = fig.add_subplot(gs[2, i])
        det = detrend(rt[ch], fs)[mask]
        ax.plot(ts[mask], det, lw=1.0, color={"R": "C3", "G": "C2", "B": "C0"}[ch])
        ax.axhline(0, color="white", alpha=0.2)
        ax.set_title(f"{ch} detrended @ {rep_tile} (first 8s)")
        ax.set_xlabel("seconds")

    # Row 3: averaged beats overlaid + low-freq PSD
    ax = fig.add_subplot(gs[3, 0])
    for ch, color in (("R", "C3"), ("G", "C2"), ("B", "C0")):
        t_axis, beat = beats[ch]
        if beat is not None:
            # Normalize amplitude for shape comparison
            b = beat - beat.min()
            if b.max() > 0:
                b = b / b.max()
            ax.plot(t_axis, b, color=color, lw=1.5, label=ch)
    ax.axvline(0, color="white", alpha=0.3, ls="--")
    ax.set_title(f"Averaged beat (foot-aligned, normalized) @ {rep_tile}")
    ax.set_xlabel("seconds from foot"); ax.legend(fontsize=8)

    ax = fig.add_subplot(gs[3, 1])
    for ch, color in (("R", "C3"), ("G", "C2"), ("B", "C0")):
        f_lf, P_lf = lf_psd[ch]
        if f_lf is not None:
            ax.semilogy(f_lf, P_lf + 1e-12, color=color, lw=1.2, label=ch)
    for x_, lbl in [(0.1, "Mayer 0.1Hz"), (0.25, "resp 0.25Hz")]:
        ax.axvline(x_, color="white", alpha=0.2, ls=":")
    ax.set_title("Low-freq PSD (vasomotion / respiratory band)")
    ax.set_xlabel("Hz"); ax.legend(fontsize=8)

    ax = fig.add_subplot(gs[3, 2])
    summary_lines = [
        f"site = {header.get('site', '?')}",
        f"fs   = {fs:.2f} Hz",
        f"dur  = {header['duration_sec']:.1f}s",
        f"frames= {header['n_frames']}",
        "",
        "Best SNR per channel:",
    ]
    for ch in ("R", "G", "B"):
        bt = maps[ch]["best_tile"]
        summary_lines.append(
            f"  {ch}: tile {bt}  SNR={maps[ch]['snr_db'][bt]:.1f}dB  "
            f"AC={maps[ch]['ac'][bt]:.2f}  DC={maps[ch]['dc'][bt]:.1f}  "
            f"f={maps[ch]['f_peak_hz'][bt]:.2f}Hz"
        )
    summary_lines += [
        "",
        "Pulse-band correlation (rep tile):",
        f"  R-G {pulse_corr['R-G']:+.2f}",
        f"  R-B {pulse_corr['R-B']:+.2f}",
        f"  G-B {pulse_corr['G-B']:+.2f}",
    ]
    ax.text(0.02, 0.98, "\n".join(summary_lines), va="top", ha="left",
            family="monospace", fontsize=8.5, color="white",
            transform=ax.transAxes)
    ax.set_xticks([]); ax.set_yticks([])
    ax.set_title("Summary")

    plt.suptitle(f"{d.name} — {header.get('site', '?')} raw session", color="white")
    out_png = d / "raw_report.png"
    fig.savefig(out_png, dpi=110, facecolor="#1b1b1f")
    print(f"saved: {out_png}")

    # JSON summary
    summary = {
        "session_id": d.name,
        "header": header,
        "rep_tile": rep_tile,
        "best_tile_per_channel": {ch: list(maps[ch]["best_tile"]) for ch in ("R", "G", "B")},
        "best_snr_db": {ch: float(maps[ch]["snr_db"][maps[ch]["best_tile"]]) for ch in ("R", "G", "B")},
        "best_ac": {ch: float(maps[ch]["ac"][maps[ch]["best_tile"]]) for ch in ("R", "G", "B")},
        "best_dc": {ch: float(maps[ch]["dc"][maps[ch]["best_tile"]]) for ch in ("R", "G", "B")},
        "best_fpeak_hz": {ch: float(maps[ch]["f_peak_hz"][maps[ch]["best_tile"]]) for ch in ("R", "G", "B")},
        "pulse_band_corr": pulse_corr,
    }
    out_json = d / "raw_report.json"
    out_json.write_text(json.dumps(summary, indent=2))
    print(f"saved: {out_json}")


if __name__ == "__main__":
    main()
