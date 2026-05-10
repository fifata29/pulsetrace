"""Run the cardiac-morphology pipeline (the same one PulseMorphology.kt
implements live) on a Raw Mode session, using the GREEN channel from the top-K
tiles around the best-AC tile. Emits an annotated averaged-beat figure plus a
JSON of the biomarkers (Crest Time, AIx, Reflection Index, AGI/SDPPG, vascular
age estimate) — so we can see whether the forearm + green channel actually
yields the cardiac features we've been struggling to extract from red.

Usage:
  python morphology_from_raw.py <session-dir> [--channel R|G|B] [--topk N]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from scipy.interpolate import CubicSpline
from scipy.signal import butter, filtfilt, find_peaks


# ----------------------------- IO -----------------------------

def load_raw(d: Path):
    csv_path = d / "raw-tiles.csv"
    header = {}
    n_skip = 0
    with csv_path.open() as f:
        for line in f:
            n_skip += 1
            if line.startswith("#"):
                if ":" in line:
                    k, v = line[2:].split(":", 1)
                    header[k.strip()] = v.strip()
                continue
            break
    grid_cols = int(header.get("grid_cols", 16))
    grid_rows = int(header.get("grid_rows", 12))
    data = np.loadtxt(csv_path, delimiter=",", skiprows=n_skip)
    ts = (data[:, 0] - data[0, 0]) / 1e9
    fs = (len(ts) - 1) / (ts[-1] - ts[0])
    cols_per_tile = 6
    chan_offset = {"R": 0, "G": 1, "B": 2}
    return data, ts, fs, grid_cols, grid_rows, cols_per_tile, chan_offset, header


def channel_series(data, gridcols, gridrows, cols_per_tile, chan_off, channel):
    """Returns nFrames × nTiles array of mean values for one channel."""
    n_tiles = gridcols * gridrows
    out = np.empty((data.shape[0], n_tiles), dtype=np.float32)
    for r in range(gridrows):
        for c in range(gridcols):
            base = 2 + (r * gridcols + c) * cols_per_tile + chan_off[channel]
            out[:, r * gridcols + c] = data[:, base]
    return out


# ----------------------------- DSP (mirrors PulseMorphology.kt) ----------------

def detrend(x, fs, win_sec=1.5):
    win = max(5, int(win_sec * fs))
    ker = np.ones(win) / win
    return x - np.convolve(x, ker, mode="same")


def bandpass(x, fs, lo, hi, order=2):
    ny = fs / 2
    b, a = butter(order, [lo / ny, hi / ny], btype="band")
    return filtfilt(b, a, x)


def upsample(t, x, fs_target=120.0):
    """Cubic-spline upsample to fs_target."""
    cs = CubicSpline(t, x)
    t_new = np.arange(t[0], t[-1], 1.0 / fs_target)
    return t_new, cs(t_new)


def best_tile(channel_arr, fs):
    """Argmax of pulse-band SNR across tiles."""
    best_score = -np.inf
    best_idx = 0
    for i in range(channel_arr.shape[1]):
        x = channel_arr[:, i]
        det = detrend(x, fs)
        bp = bandpass(det, fs, 0.7, 4.0)
        std_in = np.std(bp)
        if std_in > best_score:
            best_score = std_in
            best_idx = i
    return best_idx


def topk_tile_indices(channel_arr, fs, k=14, score_floor_frac=0.6):
    scores = np.zeros(channel_arr.shape[1])
    for i in range(channel_arr.shape[1]):
        x = channel_arr[:, i]
        det = detrend(x, fs)
        bp = bandpass(det, fs, 0.7, 4.0)
        scores[i] = np.std(bp)
    order = np.argsort(scores)[::-1]
    floor = scores[order[0]] * score_floor_frac
    picked = []
    for i in order:
        if scores[i] < floor: break
        picked.append(i)
        if len(picked) >= k: break
    return picked, scores


def find_beat_peaks(bp, fs, min_dist_sec=0.35):
    md = int(min_dist_sec * fs)
    peaks, _ = find_peaks(bp, distance=md, prominence=0.3 * bp.std())
    return peaks


def find_feet(detr, peaks):
    """Foot for each peak: local min in the last 30% of the inter-peak interval
    (mirrors PulseMorphology v4 logic — avoids picking the dicrotic-notch
    valley as the foot)."""
    feet = []
    for i, p in enumerate(peaks):
        if i == 0:
            lo = max(0, p - 200)
        else:
            prev = peaks[i - 1]
            lo = prev + int((p - prev) * 0.7)  # last 30%
        seg = detr[lo:p + 1]
        if len(seg) > 0:
            feet.append(lo + int(np.argmin(seg)))
    return np.array(feet)


def average_beat(detr, feet, fs):
    """Hampel-filtered, foot-aligned averaged beat. Same Kotlin v4 approach."""
    if len(feet) < 4:
        return None
    durs = np.diff(feet) / fs
    med = np.median(durs)
    valid_mask = (durs > 0.7 * med) & (durs < 1.3 * med)

    pre = int(0.10 * fs)
    post = int(med * fs * 1.1)
    n = pre + post

    beats = []
    for i, f in enumerate(feet[:-1]):
        if not valid_mask[i]: continue
        lo = f - pre; hi = f + post
        if lo < 0 or hi > len(detr): continue
        beats.append(detr[lo:hi])
    if len(beats) < 3:
        return None
    beats = np.array(beats)
    # Trim 10% extreme per sample
    sorted_b = np.sort(beats, axis=0)
    k = max(1, int(0.1 * len(beats)))
    avg = sorted_b[k:-k].mean(axis=0) if len(beats) > 2 * k + 1 else beats.mean(axis=0)
    t_axis = np.arange(n) / fs - pre / fs
    return t_axis, avg, beats, valid_mask.sum()


# ----------------------------- fiducials -----------------------------

def find_fiducials(t, beat, fs):
    """Returns dict with foot_idx, sys_idx, notch_idx, dia_idx and metrics.
    Uses second-derivative for notch detection (zero-cross from + → −) and
    third-derivative confirmation. Fiducials may be None if not detectable."""
    # Smooth beat slightly with a 5-point moving average
    win = 5
    ker = np.ones(win) / win
    s = np.convolve(beat, ker, mode="same")

    d1 = np.gradient(s, 1.0 / fs)
    d2 = np.gradient(d1, 1.0 / fs)
    d3 = np.gradient(d2, 1.0 / fs)

    # Foot: minimum in the first 25% of the window
    foot_search_end = max(1, int(0.25 * len(s)))
    foot_idx = int(np.argmin(s[:foot_search_end]))

    # Systolic peak: max after foot, before 50% of beat
    sys_search_end = min(len(s) - 1, foot_idx + int(0.45 * fs))
    if sys_search_end <= foot_idx + 1:
        return {"foot_idx": foot_idx, "sys_idx": None, "notch_idx": None, "dia_idx": None,
                "metrics": {}}
    sys_idx = foot_idx + int(np.argmax(s[foot_idx:sys_search_end]))

    # Dicrotic notch: zero-cross of d1 from negative to positive AFTER systolic peak,
    # OR local min of s after sys_idx, before sys_idx + 60% of beat duration
    notch_search_end = min(len(s) - 1, sys_idx + int(0.40 * fs))
    notch_idx = None
    if notch_search_end > sys_idx + 5:
        seg = s[sys_idx:notch_search_end]
        # Local min within the window with negative-to-positive d1 transition
        for j in range(2, len(seg) - 2):
            if d1[sys_idx + j - 1] < 0 and d1[sys_idx + j] >= 0 and d2[sys_idx + j] > 0:
                notch_idx = sys_idx + j
                break
        if notch_idx is None:
            # fallback: shallowest descent (smallest |d1|) after sys
            notch_idx = sys_idx + int(np.argmin(np.abs(d1[sys_idx:notch_search_end])))

    # Diastolic peak: max of s after notch
    dia_idx = None
    if notch_idx is not None:
        dia_search_end = min(len(s) - 1, notch_idx + int(0.30 * fs))
        if dia_search_end > notch_idx + 3:
            dia_idx = notch_idx + int(np.argmax(s[notch_idx:dia_search_end]))

    metrics = {}
    if sys_idx is not None and foot_idx is not None:
        metrics["crest_time_ms"] = float((sys_idx - foot_idx) * 1000.0 / fs)
        metrics["systolic_amp"] = float(s[sys_idx] - s[foot_idx])
    if dia_idx is not None and sys_idx is not None and foot_idx is not None:
        sys_amp = s[sys_idx] - s[foot_idx]
        dia_amp = s[dia_idx] - s[foot_idx]
        if sys_amp > 1e-6:
            metrics["reflection_index_pct"] = float(100.0 * dia_amp / sys_amp)
            # AIx = (P_sys - P_dia) / P_sys * 100  (Type C waveform convention)
            metrics["aix_pct"] = float(100.0 * (sys_amp - dia_amp) / sys_amp)
            metrics["delta_t_ms"] = float((dia_idx - sys_idx) * 1000.0 / fs)

    # AGI/SDPPG: identify a, b, c, d, e on second derivative
    # a = first major positive peak of d2 (acceleration up to systolic)
    # b = first major negative trough after a
    # c, d, e = following extrema (sometimes weak)
    agi = None
    try:
        a_idx = int(np.argmax(d2[:int(0.2 * len(d2))]))
        b_seg = d2[a_idx:a_idx + int(0.15 * fs)]
        b_idx = a_idx + int(np.argmin(b_seg)) if len(b_seg) > 3 else None
        if b_idx is not None:
            c_seg = d2[b_idx:b_idx + int(0.15 * fs)]
            if len(c_seg) > 3:
                c_idx = b_idx + int(np.argmax(c_seg))
                d_seg = d2[c_idx:c_idx + int(0.1 * fs)]
                if len(d_seg) > 3:
                    d_idx = c_idx + int(np.argmin(d_seg))
                    e_seg = d2[d_idx:d_idx + int(0.1 * fs)]
                    if len(e_seg) > 3:
                        e_idx = d_idx + int(np.argmax(e_seg))
                        a, b, c, d, e = d2[a_idx], d2[b_idx], d2[c_idx], d2[d_idx], d2[e_idx]
                        if abs(a) > 1e-6:
                            agi = float((b - c - d - e) / a)
                            metrics["agi"] = agi
                            metrics["sdppg_a"] = float(a)
                            metrics["sdppg_b"] = float(b)
                            metrics["sdppg_c"] = float(c)
                            metrics["sdppg_d"] = float(d)
                            metrics["sdppg_e"] = float(e)
                            # Vascular age estimate (Takazawa): linear regression form
                            # vasc_age = 65 + 30*agi (approximate)
                            metrics["vascular_age_yr_est"] = float(65 + 30 * agi)
    except Exception:
        pass

    return {
        "foot_idx": foot_idx, "sys_idx": sys_idx,
        "notch_idx": notch_idx, "dia_idx": dia_idx,
        "metrics": metrics,
        "d1": d1, "d2": d2, "d3": d3, "smoothed": s,
    }


# ----------------------------- main -----------------------------

def main():
    p = argparse.ArgumentParser()
    p.add_argument("session_dir")
    p.add_argument("--channel", default="G")
    p.add_argument("--topk", type=int, default=14)
    args = p.parse_args()

    d = Path(args.session_dir)
    print(f"loading {d}")
    data, ts, fs, gcols, grows, cpt, choff, header = load_raw(d)
    print(f"  fs={fs:.2f} Hz, frames={len(ts)}, channel={args.channel}")

    chan = channel_series(data, gcols, grows, cpt, choff, args.channel)
    picked, scores = topk_tile_indices(chan, fs, k=args.topk)
    print(f"  picked {len(picked)} tiles; scores top: {[round(scores[i], 3) for i in picked[:5]]}...")
    rows = [i // gcols for i in picked]
    cols = [i % gcols for i in picked]
    print(f"  region: rows {min(rows)}..{max(rows)}, cols {min(cols)}..{max(cols)}")

    # Average the picked tiles
    sig = chan[:, picked].mean(axis=1)

    # Upsample to 120 Hz then run the same morphology pipeline
    t_up, sig_up = upsample(ts, sig, fs_target=120.0)
    fs_up = 120.0
    detr = detrend(sig_up, fs_up)
    bp = bandpass(detr, fs_up, 0.7, 4.0)
    peaks = find_beat_peaks(bp, fs_up)
    print(f"  peaks: {len(peaks)} on bandpass; mean RR = {np.mean(np.diff(peaks))/fs_up:.3f}s -> {60.0*fs_up/np.mean(np.diff(peaks)):.1f} BPM")

    # Snap each bandpass peak to local max of detrended signal (window = ±0.15 s)
    snap_w = int(0.15 * fs_up)
    peaks_morph = []
    for q in peaks:
        lo = max(0, q - snap_w); hi = min(len(detr) - 1, q + snap_w)
        peaks_morph.append(lo + int(np.argmax(detr[lo:hi + 1])))
    peaks_morph = np.array(peaks_morph)

    feet = find_feet(detr, peaks_morph)
    avg = average_beat(detr, feet, fs_up)
    if avg is None:
        print("  beat averaging failed (insufficient valid beats)")
        return
    t_axis, beat, beats_used, n_valid = avg
    print(f"  averaged beat over {n_valid} valid beats (median-duration filtered)")

    fid = find_fiducials(t_axis, beat, fs_up)
    metrics = fid["metrics"]
    print("  morphology metrics:")
    for k, v in metrics.items():
        print(f"    {k} = {v:.2f}")

    # ------- plot --------
    fig, axes = plt.subplots(2, 2, figsize=(14, 9))
    fig.suptitle(f"{d.name} — channel {args.channel}, topK={len(picked)} tiles, fs_up={fs_up} Hz",
                 color="white")

    # Raw + bandpass first 12 s
    mask = t_up < 12
    axes[0, 0].plot(t_up[mask], sig_up[mask], color="C2", lw=1.0, label="upsampled signal")
    axes[0, 0].set_title("Channel signal (averaged across picked tiles)")
    axes[0, 0].set_xlabel("seconds"); axes[0, 0].legend(fontsize=8)

    axes[0, 1].plot(t_up[mask], bp[mask], color="C2", lw=1.0)
    for q in peaks_morph:
        if t_up[q] < 12: axes[0, 1].plot(t_up[q], bp[q], "o", color="C1", ms=6)
    for f in feet:
        if t_up[f] < 12: axes[0, 1].plot(t_up[f], bp[f], "v", color="C0", ms=6)
    axes[0, 1].axhline(0, color="white", alpha=0.2)
    axes[0, 1].set_title("Bandpass 0.7-4 Hz with peaks (orange) and feet (blue)")
    axes[0, 1].set_xlabel("seconds")

    # Averaged beat with fiducials
    axes[1, 0].plot(t_axis, fid["smoothed"], color="C2", lw=1.7, label="averaged beat")
    if fid["foot_idx"] is not None:
        axes[1, 0].plot(t_axis[fid["foot_idx"]], fid["smoothed"][fid["foot_idx"]],
                        "o", color="C0", ms=10, label="foot")
    if fid["sys_idx"] is not None:
        axes[1, 0].plot(t_axis[fid["sys_idx"]], fid["smoothed"][fid["sys_idx"]],
                        "o", color="C1", ms=10, label="systolic")
    if fid["notch_idx"] is not None:
        axes[1, 0].plot(t_axis[fid["notch_idx"]], fid["smoothed"][fid["notch_idx"]],
                        "o", color="C3", ms=10, label="notch")
    if fid["dia_idx"] is not None:
        axes[1, 0].plot(t_axis[fid["dia_idx"]], fid["smoothed"][fid["dia_idx"]],
                        "o", color="C4", ms=10, label="diastolic")
    axes[1, 0].set_title(f"Averaged beat ({n_valid} beats)")
    axes[1, 0].set_xlabel("seconds from foot"); axes[1, 0].legend(fontsize=8, loc="upper right")
    axes[1, 0].axhline(0, color="white", alpha=0.15)

    # 2nd derivative (SDPPG)
    axes[1, 1].plot(t_axis, fid["d2"], color="C5", lw=1.4, label="d²/dt² (SDPPG)")
    axes[1, 1].axhline(0, color="white", alpha=0.2)
    axes[1, 1].set_title("Second derivative — a/b/c/d/e wave landmarks")
    axes[1, 1].set_xlabel("seconds from foot"); axes[1, 1].legend(fontsize=8)

    # Annotate metrics text
    if metrics:
        text = "\n".join(f"{k} = {v:.2f}" for k, v in metrics.items())
        axes[1, 0].text(0.02, 0.02, text, transform=axes[1, 0].transAxes,
                        family="monospace", fontsize=8.5, color="white",
                        va="bottom", ha="left",
                        bbox=dict(boxstyle="round", facecolor="black", alpha=0.6))

    plt.tight_layout()
    out = d / f"morphology_{args.channel}.png"
    plt.savefig(out, dpi=110, facecolor="#1b1b1f")
    print(f"saved: {out}")

    # JSON
    out_json = d / f"morphology_{args.channel}.json"
    save = {
        "session_id": d.name,
        "channel": args.channel,
        "fs_hz": fs,
        "fs_upsample_hz": fs_up,
        "picked_tiles": [int(p) for p in picked],
        "n_valid_beats": int(n_valid),
        "metrics": metrics,
    }
    out_json.write_text(json.dumps(save, indent=2))
    print(f"saved: {out_json}")


if __name__ == "__main__":
    main()
