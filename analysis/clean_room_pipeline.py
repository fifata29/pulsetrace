"""Clean-room PPG pipeline for raw-tiles.csv recordings.

The premise: every metric the app produces from a measurement is also
computable offline from the raw RGB tile grid. If a "best-practice" Python
pipeline using scipy's well-tested filters and derivative-based fiducials
produces cleaner numbers than the app's pipeline on the SAME raw data, then
the signal processing is the bottleneck. If both struggle, the hardware /
signal acquisition is the bottleneck.

What this pipeline does, per recording:

  1. Load raw-tiles.csv (per-frame R/G/B mean for 192 tiles).
  2. ROI selection on each channel by AC×coherence (top-K, score-floor).
  3. Cubic-spline resample to a uniform time grid.
  4. Butterworth HPF at 0.3 Hz (zero-phase via filtfilt) — detrend.
  5. 4th-order Butterworth bandpass at 0.5-3 Hz via SOS (proper higher-order
     Butterworth, NOT identical-Q cascade — the trap the app fell into).
  6. Peak detection via scipy.signal.find_peaks (well-validated).
  7. RR outlier filter (median ± 20 %, successive-pair RMSSD).
  8. Spectral cross-check: scipy.signal.welch (PSD) AND autocorrelation at
     lag = median RR (more robust than DFT bin power on noisy data).
  9. Morphology: pyPPG-style derivative-based fiducials on the averaged beat.

Usage:
  python clean_room_pipeline.py <raw-session-dir>           # single
  python clean_room_pipeline.py F:/.../raw_sessions         # all under root
  python clean_room_pipeline.py --csv-out report.csv ...    # table output
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Optional

import numpy as np
from scipy.interpolate import CubicSpline
from scipy.signal import (
    butter, sosfiltfilt, filtfilt, find_peaks, welch
)


# ============================================================ IO ===

def load_raw(d: Path):
    """Returns header dict, ts_sec array, dict[chan -> 2D array of shape (n_frames, n_tiles)]."""
    csv = d / "raw-tiles.csv"
    if not csv.exists():
        raise FileNotFoundError(csv)
    header = {}
    n_skip = 0
    with csv.open() as f:
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
    n_tiles = grid_cols * grid_rows
    data = np.loadtxt(csv, delimiter=",", skiprows=n_skip)
    ts = (data[:, 0] - data[0, 0]) / 1e9
    chans = {}
    chan_offsets = {"R": 0, "G": 1, "B": 2}
    for c, off in chan_offsets.items():
        arr = np.empty((data.shape[0], n_tiles), dtype=np.float32)
        for r in range(grid_rows):
            for cc in range(grid_cols):
                base = 2 + (r * grid_cols + cc) * 6 + off
                arr[:, r * grid_cols + cc] = data[:, base]
        chans[c] = arr
    fs = (len(ts) - 1) / (ts[-1] - ts[0])
    return header, ts, fs, grid_cols, grid_rows, chans


# ============================================================ ROI ===

def bandpass_each_tile(arr_ntiles, fs, lo=0.5, hi=3.0, order=4):
    """Bandpass each tile in arr (frames × tiles). Returns same shape."""
    nyq = fs / 2
    sos = butter(order, [lo / nyq, hi / nyq], btype="bandpass", output="sos")
    out = np.empty_like(arr_ntiles)
    for i in range(arr_ntiles.shape[1]):
        try:
            out[:, i] = sosfiltfilt(sos, arr_ntiles[:, i])
        except Exception:
            out[:, i] = 0
    return out


def score_tiles_for_roi(channel_arr, fs, grid_cols, grid_rows, top_k=14, floor_frac=0.6):
    """Mirror RoiSelector: AC RMS × neighbour correlation, top-K with score floor.
    Returns indices of picked tiles, sorted by score descending."""
    bp = bandpass_each_tile(channel_arr, fs)
    n_tiles = channel_arr.shape[1]

    # AC RMS per tile
    ac = np.sqrt((bp ** 2).mean(axis=0))

    # Saturation guard
    sat = (channel_arr.max(axis=0) >= 254.0)

    # Neighbour correlation
    nbr_corr = np.zeros(n_tiles)
    for r in range(grid_rows):
        for c in range(grid_cols):
            idx = r * grid_cols + c
            if sat[idx]:
                continue
            corrs, n = [], 0
            for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                rr, cc = r + dr, c + dc
                if not (0 <= rr < grid_rows and 0 <= cc < grid_cols):
                    continue
                nidx = rr * grid_cols + cc
                if sat[nidx]:
                    continue
                if bp[:, idx].std() < 1e-6 or bp[:, nidx].std() < 1e-6:
                    continue
                corrs.append(np.corrcoef(bp[:, idx], bp[:, nidx])[0, 1])
                n += 1
            nbr_corr[idx] = np.mean(corrs) if corrs else 0.0

    score = np.where(sat, 0, ac * np.clip(nbr_corr, 0, None))
    order = np.argsort(score)[::-1]
    best = score[order[0]]
    floor = best * floor_frac
    picked = []
    for i in order:
        if score[i] <= 0 or score[i] < floor:
            break
        picked.append(int(i))
        if len(picked) >= top_k:
            break
    return picked, score


# ============================================================ pipeline ===

def cubic_resample(ts, x, target_fs):
    """Resample x at non-uniform ts to a uniform grid at target_fs via Catmull-Rom-like spline."""
    cs = CubicSpline(ts, x, extrapolate=False)
    t_uniform = np.arange(ts[0], ts[-1], 1.0 / target_fs)
    return t_uniform, cs(t_uniform)


def hpf_detrend(x, fs, fc=0.3):
    """Zero-phase 2nd-order Butterworth highpass."""
    nyq = fs / 2
    b, a = butter(2, fc / nyq, btype="highpass")
    return filtfilt(b, a, x)


def bandpass_sos(x, fs, lo=0.5, hi=3.0, order=4):
    """4th-order Butterworth bandpass via SOS (proper, not biquad cascade)."""
    nyq = fs / 2
    sos = butter(order, [lo / nyq, hi / nyq], btype="bandpass", output="sos")
    return sosfiltfilt(sos, x)


def detect_peaks_scipy(filtered, fs):
    """scipy.signal.find_peaks with prominence + minimum distance."""
    min_dist = int(0.35 * fs)
    # prominence threshold: 0.4 × std of filtered
    prom = 0.4 * filtered.std()
    peaks, _ = find_peaks(filtered, distance=min_dist, prominence=prom)
    return peaks


def filter_rr_outliers(rr_ms, reject_frac=0.20):
    """Return per-RR acceptance mask. Same logic as HrvCalculator (revised)."""
    n = len(rr_ms)
    mask = np.zeros(n, dtype=bool)
    in_range = (rr_ms >= 300) & (rr_ms <= 2000)
    rr_ok = rr_ms[in_range]
    if len(rr_ok) < 3:
        mask[in_range] = True
        return mask
    ref = np.median(rr_ok)
    kept = np.abs(rr_ms - ref) / ref <= reject_frac
    if kept.sum() >= 3:
        ref = np.median(rr_ms[kept])
        kept = np.abs(rr_ms - ref) / ref <= reject_frac
    mask[in_range] = True
    mask &= kept
    return mask


def hrv_metrics(rr_ms, mask):
    """RMSSD/SDNN/pNN50 using successive-pair-accepted-only."""
    nn = rr_ms[mask]
    if len(nn) < 3:
        return None
    bpm = 60000.0 / np.median(nn)
    sdnn = nn.std(ddof=0)
    diffs, nn50, pairs = [], 0, 0
    for i in range(1, len(rr_ms)):
        if not (mask[i] and mask[i - 1]):
            continue
        d = rr_ms[i] - rr_ms[i - 1]
        diffs.append(d * d)
        if abs(d) > 50:
            nn50 += 1
        pairs += 1
    rmssd = float(np.sqrt(np.mean(diffs))) if diffs else None
    pnn50 = 100.0 * nn50 / pairs if pairs else None
    return dict(
        bpm=float(bpm), sdnn=float(sdnn), rmssd=rmssd, pnn50=pnn50,
        valid_beats=int(mask.sum()), total_beats=int(len(rr_ms)),
    )


def spectral_bpm_welch(x, fs, lo_hz=0.7, hi_hz=2.5):
    """Welch PSD peak in [lo_hz, hi_hz]. More robust than DFT bin scan."""
    f, psd = welch(x, fs=fs, nperseg=min(len(x), int(8 * fs)))
    mask = (f >= lo_hz) & (f <= hi_hz)
    if not mask.any():
        return None
    return float(f[mask][psd[mask].argmax()] * 60.0)


def autocorr_bpm(x, fs, min_rr_s=0.4, max_rr_s=1.5):
    """Autocorrelation peak between min_rr_s and max_rr_s lag = beat period."""
    x = x - x.mean()
    n = len(x)
    if n < int(max_rr_s * fs) * 2:
        return None
    ac = np.correlate(x, x, mode="full")[n - 1:]
    ac /= ac[0] + 1e-12
    lo, hi = int(min_rr_s * fs), int(max_rr_s * fs)
    if hi >= len(ac):
        return None
    peak = lo + int(np.argmax(ac[lo:hi]))
    return float(60.0 * fs / peak)


# ============================================================ morphology ===

def averaged_beat(detr, peaks, fs):
    """Beat segmentation foot→foot, Hampel-filter median duration, trim+average."""
    if len(peaks) < 4:
        return None
    # Foot = local min in last 30 % of the previous-to-current peak interval.
    feet = []
    for i, p in enumerate(peaks):
        if i == 0:
            lo = max(0, p - 200)
        else:
            prev = peaks[i - 1]
            lo = prev + int((p - prev) * 0.7)
        seg = detr[lo:p + 1]
        if len(seg) > 0:
            feet.append(lo + int(np.argmin(seg)))
    feet = np.array(feet)
    if len(feet) < 4:
        return None

    durs_s = np.diff(feet) / fs
    med = float(np.median(durs_s))
    valid = (durs_s > 0.7 * med) & (durs_s < 1.3 * med)

    BEAT_LEN = 250
    pre = int(0.10 * fs)
    post = int(med * fs * 1.1)
    n = pre + post

    beats = []
    for i, f in enumerate(feet[:-1]):
        if not valid[i]:
            continue
        a, b = f - pre, f + post
        if a < 0 or b > len(detr):
            continue
        seg = detr[a:b]
        # Resample to fixed length
        x_new = np.linspace(0, 1, BEAT_LEN)
        x_old = np.linspace(0, 1, len(seg))
        beats.append(np.interp(x_new, x_old, seg))
    if len(beats) < 3:
        return None
    beats = np.array(beats)
    sorted_b = np.sort(beats, axis=0)
    k = max(1, int(0.1 * len(beats)))
    avg = sorted_b[k:-k].mean(axis=0) if len(beats) > 2 * k + 1 else beats.mean(axis=0)
    t_axis = np.linspace(0, med * 1.1, BEAT_LEN)
    return dict(avg=avg, t=t_axis, n_beats=int(valid.sum()), med_dur_s=med)


def derivative_fiducials(beat, t_axis):
    """pyPPG-style fiducials. Returns (foot_idx, sys_idx, notch_idx, dia_idx,
    notch_confident)."""
    n = len(beat)
    # Smooth lightly
    win = 5
    s = np.convolve(beat, np.ones(win) / win, mode="same")
    d1 = np.gradient(s)
    d2 = np.gradient(d1)

    # Max upslope: argmax of d1 in first 40 % of beat
    upslope_end = int(0.4 * n)
    ms_idx = int(np.argmax(d1[:upslope_end]))
    ms_val = d1[ms_idx]

    # Tangent intersection foot, with local-min sanity floor
    baseline = s[0]
    tangent_foot = max(0, int(ms_idx - (s[ms_idx] - baseline) / (ms_val + 1e-12)))
    tangent_foot = min(tangent_foot, ms_idx - 1)
    foot_search = int(0.25 * n)
    local_min_foot = int(np.argmin(s[:foot_search]))
    foot_idx = max(tangent_foot, local_min_foot, 0)

    # Systolic peak: first d1 zero crossing (+→−) after ms_idx
    sys_idx = None
    for i in range(ms_idx + 1, n - 1):
        if d1[i] >= 0 and d1[i + 1] < 0:
            sys_idx = i
            break
    if sys_idx is None:
        sys_idx = int(np.argmax(s))

    # Notch via d2 positive peak after sys_idx
    search_lo = sys_idx + int(0.06 * n)
    search_hi = int(n - 0.05 * n)
    d2_seg = d2[search_lo:search_hi]
    d2_noise = d2_seg.std() if len(d2_seg) > 1 else 0
    notch_idx, best_prom = None, -np.inf
    for i in range(search_lo + 1, search_hi - 1):
        if d2[i] > d2[i - 1] and d2[i] > d2[i + 1] and d2[i] > 0:
            if d2[i] > best_prom:
                best_prom = d2[i]
                notch_idx = i
    notch_confident = notch_idx is not None and best_prom > 2.0 * d2_noise
    if notch_idx is None:
        notch_idx = sys_idx + (n - sys_idx) // 3

    # Diastolic: first d1 zero crossing after notch
    dia_idx = None
    for i in range(notch_idx + 1, search_hi - 1):
        if d1[i] >= 0 and d1[i + 1] < 0:
            dia_idx = i
            break
    if dia_idx is None:
        dia_idx = notch_idx + int(np.argmax(s[notch_idx:search_hi]))
    if dia_idx <= notch_idx:
        dia_idx = notch_idx

    return dict(
        foot=foot_idx, sys=sys_idx, notch=notch_idx, dia=dia_idx,
        notch_confident=notch_confident, d2_noise=float(d2_noise),
        d1=d1, d2=d2, smoothed=s,
    )


def morphology(detr, peaks, fs):
    """Returns dict of crest_time_ms, ri, aix, lvet_ms, upk_per_sec, ampvar_pct."""
    avg = averaged_beat(detr, peaks, fs)
    if not avg:
        return None
    fid = derivative_fiducials(avg["avg"], avg["t"])
    s = fid["smoothed"]
    foot, sys, notch, dia = fid["foot"], fid["sys"], fid["notch"], fid["dia"]
    t = avg["t"]
    sys_amp = s[sys] - s[foot]

    crest = (t[sys] - t[foot]) * 1000 if sys > foot else None
    if crest is not None and not (80 <= crest <= 400):
        crest = None

    lvet = None
    if fid["notch_confident"] and notch > foot:
        v = (t[notch] - t[foot]) * 1000
        if 150 <= v <= 500:
            lvet = v

    ri = aix = None
    if fid["notch_confident"] and dia > sys and sys_amp > 1e-6:
        dia_amp = s[dia] - s[foot]
        v = 100 * dia_amp / sys_amp
        if 5 <= v <= 120:
            ri = float(v)
        if sys + 1 <= notch < dia:
            v = 100 * (dia_amp - sys_amp) / sys_amp
            if -50 <= v <= 50:
                aix = float(v)

    upk = None
    if sys > foot and sys_amp > 1e-6:
        dt = t[1] - t[0] if len(t) > 1 else 1.0 / fs
        max_slope = float(max(0, np.max(np.diff(s[foot:sys + 1])) / dt))
        upk = max_slope / sys_amp

    # Amplitude variability across raw (un-averaged) beats — use approximate
    # peak heights from the time-series at the peak indices.
    return dict(
        crest_time_ms=crest, reflection_index_pct=ri,
        augmentation_index_pct=aix, lvet_ms=lvet,
        max_upstroke_per_sec=float(upk) if upk is not None else None,
        n_beats_averaged=avg["n_beats"], med_beat_duration_s=avg["med_dur_s"],
        notch_confident=fid["notch_confident"],
    )


def amp_variability_pct(detr, peaks, fs):
    """Per-beat (max-min in a 0.7×median_RR window centred on peak) coefficient of variation."""
    if len(peaks) < 5:
        return None
    rrs = np.diff(peaks) / fs
    med = float(np.median(rrs))
    half = int(0.5 * med * fs)
    amps = []
    for p in peaks:
        lo = max(0, p - half)
        hi = min(len(detr), p + half)
        if hi - lo < 4:
            continue
        amps.append(detr[lo:hi].max() - detr[lo:hi].min())
    amps = np.array(amps)
    if len(amps) < 5 or amps.mean() < 1e-6:
        return None
    return float(100 * amps.std() / amps.mean())


# ============================================================ main ===

def analyse_one(d: Path, channel="G"):
    header, ts, fs, gc, gr, chans = load_raw(d)
    arr = chans[channel]

    picked, score = score_tiles_for_roi(arr, fs, gc, gr)
    if not picked:
        return dict(session_id=d.name, channel=channel, error="no ROI")
    # Average picked tiles
    sig = arr[:, picked].mean(axis=1)

    # Cubic resample to a uniform grid at the measured fs
    target_fs = round(fs * 2)  # upsample 2× for sub-sample fiducials
    t_u, sig_u = cubic_resample(ts, sig, target_fs)
    fs_u = float(target_fs)

    # Detrend + bandpass
    det = hpf_detrend(sig_u, fs_u, fc=0.3)
    bp = bandpass_sos(det, fs_u, lo=0.5, hi=3.0, order=4)

    # Peak detection
    peaks = detect_peaks_scipy(bp, fs_u)
    if len(peaks) < 4:
        return dict(session_id=d.name, channel=channel, error="too few peaks", n_peaks=len(peaks))

    # RR + HRV
    rr_ms = np.diff(peaks) / fs_u * 1000
    mask = filter_rr_outliers(rr_ms)
    hrv = hrv_metrics(rr_ms, mask)

    # Spectral cross-checks
    bpm_welch = spectral_bpm_welch(bp, fs_u)
    bpm_autocorr = autocorr_bpm(bp, fs_u)

    # Morphology on the detrended (un-bandpassed) signal, peaks from bandpass
    morph = morphology(det, peaks, fs_u)
    ampvar = amp_variability_pct(det, peaks, fs_u)

    # Best tile location (for diagnostics)
    best_tile = picked[0]
    best_r, best_c = best_tile // gc, best_tile % gc
    best_ac = float(arr[:, picked].std(axis=0).mean())
    best_dc = float(arr[:, picked].mean())
    perfusion = 100 * best_ac / best_dc if best_dc > 1 else 0

    return dict(
        session_id=d.name,
        site=header.get("site", "?"),
        channel=channel,
        fs=float(fs),
        n_peaks=int(len(peaks)),
        valid_beats=hrv["valid_beats"] if hrv else 0,
        bpm_peak=hrv["bpm"] if hrv else None,
        bpm_welch=bpm_welch,
        bpm_autocorr=bpm_autocorr,
        rmssd_ms=hrv["rmssd"] if hrv else None,
        sdnn_ms=hrv["sdnn"] if hrv else None,
        pnn50=hrv["pnn50"] if hrv else None,
        crest_time_ms=morph.get("crest_time_ms") if morph else None,
        reflection_index_pct=morph.get("reflection_index_pct") if morph else None,
        augmentation_index_pct=morph.get("augmentation_index_pct") if morph else None,
        lvet_ms=morph.get("lvet_ms") if morph else None,
        max_upstroke_per_sec=morph.get("max_upstroke_per_sec") if morph else None,
        amp_variability_pct=ampvar,
        notch_confident=morph.get("notch_confident") if morph else False,
        n_beats_averaged=morph.get("n_beats_averaged") if morph else 0,
        roi_best_tile=f"({best_r},{best_c})",
        roi_ac=round(best_ac, 3),
        roi_dc=round(best_dc, 1),
        perfusion_pct=round(perfusion, 2),
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target", help="raw-session dir, or root containing many")
    ap.add_argument("--channel", default="G")
    ap.add_argument("--csv-out", default=None)
    args = ap.parse_args()

    target = Path(args.target)
    if (target / "raw-tiles.csv").exists():
        sessions = [target]
    else:
        sessions = sorted([p for p in target.iterdir()
                           if p.is_dir() and (p / "raw-tiles.csv").exists()])
    if not sessions:
        print(f"no raw sessions under {target}")
        sys.exit(1)

    results = []
    for s in sessions:
        for ch in ["R", "G", "B"]:
            try:
                r = analyse_one(s, channel=ch)
                results.append(r)
                if "error" in r:
                    print(f"{s.name} [{ch}] ERROR: {r['error']}")
                else:
                    print(f"{s.name} site={r['site']} ch={ch} "
                          f"BPM peak={fmt(r['bpm_peak'])} welch={fmt(r['bpm_welch'])} "
                          f"ac={fmt(r['bpm_autocorr'])}  "
                          f"RMSSD={fmt(r['rmssd_ms'])} ms  "
                          f"PI={fmt(r['perfusion_pct'])}% "
                          f"beats={r['valid_beats']}/{r['n_peaks']}")
                    if r["crest_time_ms"] or r["lvet_ms"]:
                        print(f"  crest={fmt(r['crest_time_ms'])} ms  "
                              f"LVET={fmt(r['lvet_ms'])} ms  "
                              f"UPK={fmt(r['max_upstroke_per_sec'])}/s  "
                              f"AMPVAR={fmt(r['amp_variability_pct'])}%  "
                              f"RI={fmt(r['reflection_index_pct'])} AIx={fmt(r['augmentation_index_pct'])} "
                              f"notch_conf={r['notch_confident']}")
            except Exception as e:
                print(f"{s.name} [{ch}] FAILED: {e}")

    if args.csv_out:
        import csv
        keys = sorted({k for r in results for k in r.keys()})
        with open(args.csv_out, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=keys)
            w.writeheader()
            for r in results:
                w.writerow(r)
        print(f"\ntable saved to {args.csv_out}")


def fmt(v, n=1):
    if v is None: return "—"
    if isinstance(v, bool): return str(v)
    return f"{v:.{n}f}"


if __name__ == "__main__":
    main()
