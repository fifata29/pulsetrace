"""Plot the averaged-beat waveforms from sessions and compare against raw signals."""
import json, sys
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

if len(sys.argv) < 2:
    print("usage: plot_morphology.py <sessions-root>")
    sys.exit(1)

root = Path(sys.argv[1])
fig, axes = plt.subplots(len(list(root.iterdir())), 2, figsize=(14, 4 * len(list(root.iterdir()))), squeeze=False)
i = 0
for d in sorted(root.iterdir()):
    summary = d / "summary.json"
    csv = d / "samples.csv"
    if not summary.exists() or not csv.exists():
        continue
    s = json.loads(summary.read_text())
    morph = s.get("morphology")
    if morph is None:
        continue
    beat = np.array(morph["averaged_beat"])
    t = np.array(morph["averaged_beat_time_sec"])
    foot = morph["foot_idx"]
    sysp = morph["systolic_peak_idx"]
    notch = morph["dicrotic_notch_idx"]
    dia = morph["diastolic_peak_idx"]

    ax = axes[i, 0]
    ax.plot(t, beat, color="C3", lw=1.5)
    for idx, name, c in [(foot, "foot", "C0"), (sysp, "systolic", "C1"),
                          (notch, "notch", "C2"), (dia, "diastolic", "C4")]:
        if 0 <= idx < len(beat):
            ax.plot(t[idx], beat[idx], "o", ms=8, color=c, label=f"{name} (idx {idx})")
    ax.set_title(f"{d.name} averaged beat (app's fiducials)")
    ax.set_xlabel("seconds"); ax.set_ylabel("filtered red")
    ax.legend(loc="upper right", fontsize=7)

    # Now load samples.csv and plot raw + filtered for the first 5 seconds
    n_skip = 0
    with csv.open() as f:
        for line in f:
            n_skip += 1
            if not line.startswith("#"): break
    data = np.loadtxt(csv, delimiter=",", skiprows=n_skip)
    ts = (data[:, 0] - data[0, 0]) / 1e9
    red = data[:, 1]
    mask = ts < 5
    ax = axes[i, 1]
    ax.plot(ts[mask], red[mask], color="C3", lw=1.0, label="raw red")
    ax.set_title(f"{d.name} raw red (first 5 s)")
    ax.set_xlabel("seconds"); ax.set_ylabel("red")
    ax.legend(loc="upper right", fontsize=7)

    print(f"=== {d.name} ===")
    print(f"  duration_sec: {s['duration_sec']:.1f}")
    print(f"  fs: {s['sample_rate_hz']:.1f}")
    print(f"  bpm peak: {s['metrics']['bpm']:.1f}, spectral: {s['metrics']['spectral_bpm']:.1f}")
    print(f"  morph: crest={morph.get('crest_time_ms')}, RI={morph.get('reflection_index_pct')}, "
          f"AIx={morph.get('augmentation_index_pct')}, AGI={morph.get('aging_index')}, "
          f"vAge={morph.get('vascular_age_years')}")
    print(f"  fiducials: foot={foot}, sys={sysp}, notch={notch}, dia={dia}, beat_len={len(beat)}")
    print(f"  beat amp range: {beat.min():.3f}..{beat.max():.3f} at idx {beat.argmin()}/{beat.argmax()}")
    i += 1

plt.tight_layout()
out = root / "morphology_diag.png"
plt.savefig(out, dpi=110)
print(f"saved: {out}")
