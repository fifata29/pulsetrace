# PulseTrace — fingertip PPG / HRV for Android

A native Android app that measures heart rate and time-domain HRV (RMSSD, SDNN, pNN50) from
the rear camera + flash. Cover the lens with your fingertip; the app reads the tiny color
changes that happen as your capillaries fill and empty with each heartbeat.

Built for OnePlus 11 but works on any Android 8+ device with a back camera and flash.

## Architecture

```
PpgAnalyzer (CameraX ImageAnalyzer, YUV_420_888 → red-channel mean over centered ROI)
   │
   ▼
SignalProcessor (rolling 30 s buffer → uniform resample → moving-avg detrend
                 → Butterworth bandpass 0.7–4 Hz, zero-phase → adaptive peak detect)
   │
   ▼
HrvCalculator (RR outlier filter → BPM, SDNN, RMSSD, pNN50)
   │
   ▼
MeasurementViewModel (StateFlow, 10 Hz UI refresh)
   │
   ▼
Jetpack Compose UI (BPM hero, live PPG chart with peak markers, RR tachogram, metrics row)
```

The "traceability" you asked for: the live chart overlays the **filtered PPG** (red line) on
the **raw detrended signal** (faint blue), with **detected peaks** drawn as dots. This lets
you see at a glance whether the algorithm is locking onto real systolic peaks. The RR
tachogram below shows beat-to-beat intervals — flat = low HRV, jagged = high HRV.

## How to build

1. Install **Android Studio Hedgehog (2023.1.1)** or newer.
2. Open this folder (`F:\Vibe Coding\HRV App`) in Android Studio. It will sync Gradle and
   download the wrapper automatically.
3. On your OnePlus 11: enable **Developer Options** and **USB debugging**
   (Settings → About → tap Build number 7 times; then Settings → System → Developer options).
4. Plug the phone in, accept the RSA prompt, click **Run** (or `Shift+F10`).
5. Grant camera permission on first launch.

## How to use

1. Tap **Start measurement**.
2. Cover the **rear camera lens AND the flash** with the pad of your index finger. Press
   firmly enough that the lens is fully blocked but not so hard you cut off blood flow.
3. Hold still for 20–30 seconds. The signal-quality bar should turn green; the BPM number
   stabilizes after ~10 s and HRV metrics need ~30 s to be meaningful.
4. The chart shows your live waveform — every dot is a detected heartbeat.

## Signal-processing notes

- **Sample rate** — CameraX delivers ~30 fps on the OnePlus 11; the processor resamples
  to a uniform grid before filtering, so jitter doesn't matter.
- **Filter** — 2nd-order Butterworth bandpass 0.7–4 Hz (≈42–240 BPM), applied bidirectionally
  for zero-phase response.
- **Peak detection** — adaptive threshold at 30% of positive RMS, minimum 300 ms separation.
- **HRV outlier rule** — RR intervals are rejected if they're outside 300–2000 ms or differ
  by more than 20% from the previous valid interval. Keeps motion artifacts out of RMSSD.

## Pulling raw data off the phone

Each measurement writes a CSV (raw samples) and a JSON summary to the app's external
files dir. The path is shown at the bottom of the report card after a recording ends,
e.g. `/storage/emulated/0/Android/data/dk.nst.hrvmonitor.debug/files/sessions/2026-05-09T10-23-15/`.

To pull all sessions to your PC over USB (with USB debugging on):

```powershell
C:\Android\adb.exe pull /sdcard/Android/data/dk.nst.hrvmonitor.debug/files/sessions/ .\sessions\
```

Each session folder contains:
- `samples.csv` — `timestamp_ns, red, luma, coverage` (one row per camera frame)
- `summary.json` — sample rate, metrics, RR intervals, peak times, device info

## Disclaimer

This is a personal/wellness tool, not a medical device. The measurements aren't validated
for clinical use. Don't rely on them for diagnosis.

## File map

```
app/src/main/
├── java/dk/nst/hrvmonitor/
│   ├── MainActivity.kt
│   ├── ppg/
│   │   ├── PpgAnalyzer.kt          # camera frame → red-channel sample
│   │   ├── SignalProcessor.kt      # filter + peak detect, snapshot for UI
│   │   └── HrvCalculator.kt        # SDNN, RMSSD, pNN50
│   ├── viewmodel/MeasurementViewModel.kt
│   └── ui/
│       ├── MeasurementScreen.kt    # main screen + camera binding
│       ├── theme/                  # colors, typography, Material 3 theme
│       └── components/
│           ├── SignalChart.kt      # PPG + RR tachogram
│           └── MetricsPanel.kt     # BPM hero, HRV cards, quality bar
└── res/                            # strings, themes, launcher icon
```
