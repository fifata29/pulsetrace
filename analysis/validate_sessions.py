"""Sanity-check / regression-test scaffolding for all recorded sessions.

Walks F:\\Vibe Coding\\HRV App\\sessions\\ and validates every summary.json
against physiologically plausible ranges and pipeline self-consistency. Flags
sessions whose metrics fall outside expected windows so we can catch regressions
across builds.

This is NOT a calibration test against ECG / clinical-grade PPG — for that we
need an external paired dataset (PPG-BP, WF-PPG, MIMIC-III). It IS a useful
guardrail to catch pipeline-level mistakes (e.g. peak detector goes crazy on a
specific device firmware, or a future code change inflates RMSSD again).

Usage:
  python validate_sessions.py            # summary table + flagged-session list
  python validate_sessions.py --strict   # also fail on warnings (CI mode)
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Optional


# Plausible-range checks. Tight enough to flag real failures, loose enough to
# accept the variability you see across resting / post-exercise / different sites.
RANGES = {
    "bpm":          (40, 200, "BPM"),
    "rmssd_ms":     (5, 250, "RMSSD ms"),
    "sdnn_ms":      (8, 300, "SDNN ms"),
    "pnn50_pct":    (0, 100, "pNN50 %"),
    "sample_rate":  (15, 120, "fs Hz"),
    "crest_time":   (60, 400, "Crest Time ms"),
    "reflection":   (5, 120, "Reflection Index %"),
    "augmentation": (-50, 50, "AIx %"),
}

# Stricter cross-metric consistency checks.
def cross_checks(s: dict) -> list[str]:
    issues: list[str] = []
    m = s.get("metrics") or {}
    bpm = m.get("bpm")
    spectral = m.get("spectral_bpm") or 0
    if bpm and spectral > 1:
        rel = abs(bpm - spectral) / max(bpm, 1.0)
        if rel > 0.20:
            issues.append(f"BPM disagreement {bpm:.1f}/{spectral:.1f} ({rel*100:.0f}%) — peak detection likely wrong")

    valid = m.get("valid_beats") or 0
    total = m.get("total_beats") or 0
    if total > 0:
        acc = valid / total
        if acc < 0.5:
            issues.append(f"Low beat acceptance: {valid}/{total} = {acc*100:.0f}%")

    fs = s.get("sample_rate_hz") or 0
    if fs < 25:
        issues.append(f"Sample rate {fs:.1f} Hz — too low for HRV without aggressive spline interp")

    morph = s.get("morphology")
    if morph and morph.get("isAvailable", True):
        ri = morph.get("reflection_index_pct")
        aix = morph.get("augmentation_index_pct")
        crest = morph.get("crest_time_ms")
        notch = morph.get("dicrotic_notch_idx", -1)
        dia = morph.get("diastolic_peak_idx", -1)
        sys_idx = morph.get("systolic_peak_idx", -1)
        if notch >= 0 and dia >= 0 and dia < notch:
            issues.append("Diastolic peak placed before notch — morphology pipeline error")
        if sys_idx >= 0 and dia >= 0 and dia <= sys_idx and notch > sys_idx:
            issues.append("Diastolic peak before systolic — fiducial detection broken")
        if crest is not None and crest > 400:
            issues.append(f"Crest time {crest:.0f} ms — implausibly long (algorithm picking wrong peak)")
        if ri is not None and (ri < -20 or ri > 120):
            issues.append(f"Reflection Index {ri:.0f}% out of plausible range")
        if aix is not None and abs(aix) > 60:
            issues.append(f"AIx {aix:.0f}% out of plausible range")

    quality = s.get("quality")
    if quality:
        total_q = quality.get("total", 0)
        if total_q < 50:
            issues.append(f"Quality score {total_q}/100 (tier {quality.get('tier', '?')})")

    return issues


def check_ranges(s: dict) -> list[str]:
    out: list[str] = []
    m = s.get("metrics") or {}
    fs = s.get("sample_rate_hz")
    morph = s.get("morphology") or {}

    def chk(name: str, val: Optional[float]):
        if val is None:
            return
        lo, hi, label = RANGES[name]
        if not (lo <= val <= hi):
            out.append(f"{label} = {val:.1f} (outside {lo}..{hi})")

    chk("bpm",         m.get("bpm"))
    chk("rmssd_ms",    m.get("rmssd_ms"))
    chk("sdnn_ms",     m.get("sdnn_ms"))
    chk("pnn50_pct",   m.get("pnn50_pct"))
    chk("sample_rate", fs)
    if morph.get("isAvailable", True):
        chk("crest_time",   morph.get("crest_time_ms"))
        chk("reflection",   morph.get("reflection_index_pct"))
        chk("augmentation", morph.get("augmentation_index_pct"))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="F:/Vibe Coding/HRV App/sessions")
    ap.add_argument("--strict", action="store_true",
                    help="exit 1 on any flagged session (CI mode)")
    args = ap.parse_args()

    root = Path(args.root)
    if not root.exists():
        print(f"sessions root not found: {root}")
        sys.exit(2)

    sessions = sorted([p for p in root.iterdir() if p.is_dir() and (p / "summary.json").exists()])
    if not sessions:
        print(f"no sessions in {root}")
        sys.exit(0)

    failures: list[tuple[str, list[str]]] = []
    print(f"Validating {len(sessions)} sessions in {root}\n")
    header = f"{'session':30s} {'site':10s} {'ch':3s} {'fs':5s} {'BPM':6s} {'RMSSD':7s} {'SDNN':6s} {'Q':4s} {'beats':9s}"
    print(header)
    print("-" * len(header))

    for s_dir in sessions:
        try:
            s = json.loads((s_dir / "summary.json").read_text())
        except Exception as e:
            print(f"{s_dir.name:30s}  failed to parse: {e}")
            continue
        m = s.get("metrics") or {}
        q = s.get("quality") or {}
        site = s.get("site", "?")
        ch = s.get("display_channel", "?")
        fs = s.get("sample_rate_hz", 0)
        bpm = m.get("bpm")
        rmssd = m.get("rmssd_ms")
        sdnn = m.get("sdnn_ms")
        valid = m.get("valid_beats", 0)
        total = m.get("total_beats", 0)
        q_total = q.get("total", 0)

        def fmt(v, fmt_s="6.1f"):
            return "—" if v is None else format(v, fmt_s)

        print(f"{s_dir.name:30s} {site:10s} {ch:3s} {fs:5.1f} "
              f"{fmt(bpm):>6s} {fmt(rmssd, '7.1f'):>7s} {fmt(sdnn):>6s} "
              f"{q_total:>3d}  {valid:>2d}/{total:<2d}")

        issues = check_ranges(s) + cross_checks(s)
        if issues:
            failures.append((s_dir.name, issues))

    print()
    if failures:
        print(f"\n=== {len(failures)} session(s) flagged ===")
        for name, issues in failures:
            print(f"\n{name}:")
            for i in issues:
                print(f"  • {i}")
    else:
        print("\nAll sessions pass sanity checks.")

    if args.strict and failures:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
