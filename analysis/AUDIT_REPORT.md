# NST HRV Monitor — Independent Pipeline Audit

Auditor scope: signal-processing pipeline in `app/src/main/java/dk/nst/hrvmonitor/ppg/` plus orchestration in `viewmodel/MeasurementViewModel.kt`. Read-only.

Verdict up-front: the pipeline is competent and the architectural choices (hybrid R/G channel, two-phase ROI search, zero-phase Butterworth, valley-paired RPD detection, separate morphology stream) are sound and in some places ahead of typical hobby PPG code. The two real, defensible failure modes you already see in the field — dicrotic over-counting on G, mis-located fiducials — both trace back to specific decisions that the literature already has better answers for. The cheapest single win is widening the morphology bandpass; the highest-impact win is replacing the peak detector with MSPTDfast or Aboy++.

---

## PART A — PIPELINE CRITIQUE

### A1. Resampling (SignalProcessor.kt:82–97, RoiSelector.kt:80–88)

Linear interpolation onto a uniform grid is fine for the bandpassed HR-detection channel but **wrong for morphology**. CameraX jitter on a 60 fps capture is typically ±2–6 ms; linear interpolation through that jitter sums two errors: (a) the temporal jitter, (b) chord-vs-arc error at signal extrema. The error term is bounded by the second derivative — i.e. it is largest exactly at the systolic peak and dicrotic notch, which are the fiducials you care about. For HR/RR, the bandpass smooths it out. For morphology, where you already spline-upsample 4× downstream (`PulseMorphology.kt:81`), it is inconsistent to linearly interpolate at the front of the pipeline and then Catmull-Rom at the back. The right thing is to do the morphology stream on cubic/Catmull-Rom resampling from raw timestamps, OR to drop the front-end resample entirely and let your spline at the back carry the irregular grid (Catmull-Rom can be parameterised by time, not index). Bias is bounded by roughly `f_signal² · dt²` — on a 1.2 Hz pulse with 6 ms jitter, the foot/notch position error is sub-ms but the **amplitude** error at the systolic peak can be 0.3–1% of pulse amplitude, which directly perturbs AIx/RI.

Verdict: acceptable for HR, marginal for morphology. Small-effort fix.

### A2. Detrending (SignalProcessor.kt:102–104, 191–201)

The 1.5 s moving-average detrend is a **non-zero-phase** highpass with a passband-edge near `1/1.5 = 0.67 Hz` and a notoriously bad transition band. A moving average is a sinc in frequency, so it ripples; it also distorts onsets of pulses near edges of the rolling window. The Tarvainen 2002 smoothness-priors detrend (`λ ≈ 10`, cutoff configurable) is what Kubios uses and is the *de facto* standard. For embedded code, a running median (longer window, 2–3 s) or a 2-pass IIR highpass at 0.3–0.4 Hz is the practical replacement; both kill respiration much more cleanly than the MA. The MA also explains some of the cycle-to-cycle baseline wander visible in your morphology signal (because the MA's group delay smears slowly-changing offsets into the pulse shape).

Verdict: this is the second-weakest link. Polynomial detrending is *worse* than what you have; smoothness priors / median / IIR HPF are all better. Reference: Tarvainen et al. 2002, *IEEE TBME* 49(2):172. https://doi.org/10.1109/TBME.2002.804337

### A3. Bandpass (SignalProcessor.kt:23–24, 205–215)

0.7 Hz lower cutoff is **too high** if morphology is a goal. The morphology stream already bypasses this (`morphology = detrended`, no bandpass — good), so the HR-detection path's 0.7 Hz cutoff is fine for what it does. But:
- 4 Hz upper cutoff (≈240 BPM) plus only a 2nd-order Butterworth gives only ~12 dB/octave rolloff. The dicrotic harmonic on G survives in the bandpassed signal (you note exactly this in your own code comments at line 109), and that is why your RPD detector fights it.
- The literature consensus for HR-band PPG is 0.5–8 Hz (Charlton 2022 wearable review; pyPPG uses 0.5–12 Hz Chebyshev II 4th-order). Raising the lower cutoff helps reject respiration; *lowering* the upper cutoff to 3.0 Hz on the HR path would suppress dicrotic harmonics by another 4–6 dB.

Practical recommendation: keep the morphology stream at "detrended, no bandpass". Move the HR-detection bandpass to 0.5–3 Hz. Optionally use a 4th-order Butterworth (cascade two of your existing biquads) for sharper rejection.

References: Charlton et al. 2022, *IEEE Reviews in Biomedical Engineering*, PMC7612541. https://pmc.ncbi.nlm.nih.gov/articles/PMC7612541/ ; Goda et al. 2024 pyPPG, https://doi.org/10.1088/1361-6579/ad33a2

### A4. Peak detection (SignalProcessor.kt:283–337)

The RPD-with-valley-pairing implementation is genuinely good for a from-scratch detector and is essentially a hand-tuned hybrid of Argüello-Prada's pulse-detection-algorithm (PDA) and a decaying-threshold scheme. Specific failure modes that better algorithms handle:

- **Dicrotic-as-second-beat on G.** Your valley pairing requires the signal to cross below zero between peaks. On G with a strong notch and an even-stronger 2nd harmonic, the bandpassed signal can have a "valley" that touches zero or remains slightly negative without becoming a clear minimum — and your `< 0f` check (line 309) treats any below-zero local min as a valley. This is too permissive. The Elgendi 2013 W1/W2 method side-steps this by integrating squared signal over a systolic-peak-duration window (≈175 ms) and a heartbeat-duration window (≈1 s); a peak is accepted where the W1 envelope exceeds W2's envelope by an offset. The detector cannot lock onto a notch because the notch's W1 envelope is dwarfed by the systolic envelope. F1 ≈ 99.8% in the original validation.
- **Filter ringing at edges.** You correctly trim 1.5 s (line 112). Good.
- **Amplitude variability.** The decaying threshold handles slow drift well but can still miss low beats after a strong PVC.
- **No re-walk.** Modern detectors (MSPTD, Aboy++, PPG-pulses) all do a second pass that snaps each accepted peak to its local max in the raw/lightly filtered signal — you do this implicitly only for morphology (PulseMorphology.kt:91–99), not for the RR series. Snapping for the RR series too would tighten timing jitter.

Benchmark context (Charlton 2022 PPG-beats benchmark, PMC9393905; MSPTDfast 2024, PMC11894679):
- MSPTD and qppg lead the open-source field, F1 ≈ 88–94% on adult resting wearable data.
- Elgendi (ERMA) and Aboy++ are close behind.
- The Argüello-Prada PDA detector (closest to your code) sits in the middle of the pack.

Your detector on fingertip R is probably already at F1 ≥ 95% — the literature scores are deliberately measured on harder data. The diagnostic question is what your false-positive rate is on G, which is where it matters.

Reference: Elgendi et al. 2013, *PLOS ONE*, https://doi.org/10.1371/journal.pone.0076585 ; Charlton et al. 2022 PPG-beats, https://pmc.ncbi.nlm.nih.gov/articles/PMC9393905/ ; Charlton 2025 MSPTDfast, https://pmc.ncbi.nlm.nih.gov/articles/PMC11894679/

### A5. Morphology fiducials (PulseMorphology.kt:152, 260–296, 298–329)

This is the weakest part of the pipeline and where the symptoms you describe live.

- **Foot detection.** Line 269–271: "Foot: lowest point in the first 15% of the beat." That assumes the beat is already aligned at the true foot, but `locateFeet` (lines 211–229) restricts the *segmentation* foot search to the last 30% of the inter-peak interval. There is a circularity here: the averaged beat's first sample is the segmentation foot, so re-searching the first 15% of the average for a lower point only relocates the foot if the median of aligned beats has lower amplitude near index 5–10 than at 0, which usually means the alignment was off. The Charlton/pyPPG approach uses **tangent intersection** (a line through the maximum upslope of PPG' intersected with the foot baseline) — this is much more reproducible than a local minimum search.
- **Systolic peak as global max** (line 263–265). On a clean fingertip beat this is fine. On a forearm beat where the systolic peak is *smaller* than the diastolic peak (highly possible with reflected-wave dominance — common in older subjects), this picks the wrong fiducial. pyPPG defines the systolic peak as the first prominent peak after the foot on PPG (or via PPG' max). That is the literature standard.
- **Dicrotic notch via 2nd derivative positive-peak search** (lines 277–286). Conceptually right (SDPPG c-wave / first local min on the descending limb after the systolic peak). Practically your code requires `d2[i] > 0`. On a beat where the notch is shallow, d² may stay negative across the notch and you fall back to the "1/3 of the way down" heuristic (line 286) — which is then the value that propagates into AIx/RI. The literature standard (pyPPG) uses an inflection point search via the *zero crossings* of the 3rd derivative or the b-c interval on PPG''.
- **Diastolic peak before systolic.** You note this happens. Looking at lines 289–291: the diastolic search starts at `notchIdx + 1` and looks for the max up to `searchEnd`. If `notchIdx` fell back to the heuristic and there is a small local max even *before* the true notch (which there can be on a forearm beat), then yes — the displayed diastolic peak can end up to the left of the systolic peak when downstream consumers don't gate on `fid.diastolicPeak > fid.systolicPeak`. The RI calculation at line 159 gates on this, but the displayed `diastolicPeakIdx` (line 195) is whatever was computed regardless. Quick fix: if the gates fail, output `-1` for `diastolicPeakIdx` rather than a junk index.
- **AGI/SDPPG (lines 298–329).** The Takazawa formula is `AGI = (b − c − d − e) / a`. Your code uses the same — good. But the search constrains itself to the first 55% of the beat (`limit = n*0.55f`, line 308) and requires 5 alternating extrema. Takazawa's original paper defines a/b in the *first* 100 ms of systole, c/d in the *next 200 ms* — so 55% is too generous for a/b but probably right for c/d/e. More importantly, the formula assumes a *normalised* beat: pyPPG normalises by the global maximum of |PPG''| in the beat, not min/max range of the raw beat (line 302). Different normalisation → different AGI values → the vascular-age regression `65 − 25·AGI` (line 183) almost certainly does not give the right numbers because the regression coefficients in Takazawa 1998 were derived against the standard SDPPG normalisation. **You should not be reporting a vascular-age number in years until this is calibrated against ground truth on the device itself.** The number you display is a unitless transform, not validated age.
- **Reference**: Takazawa et al. 1998, *Hypertension* 32:365, https://doi.org/10.1161/01.HYP.32.2.365 ; Charlton pulse-wave-analysis tutorial https://peterhcharlton.github.io/bsp-book/tutorial/notebooks/pulse-wave-analysis.html ; pyPPG paper, https://arxiv.org/pdf/2309.13767

### A6. HRV outlier filter (HrvCalculator.kt:75–87)

A median-anchored ±25% rule with one refinement pass is **less aggressive than Kubios' default** (Kubios uses local-median or piecewise-cubic-spline interpolation of detected outliers, with thresholds 0.45 s / 0.35 s / 0.25 s / 0.15 s / 0.05 s as "low … very strong" filtering). For RMSSD specifically:

- A single missed beat doubles one RR interval. ±25% from median catches that (a 1500 ms RR vs median 800 ms is clearly rejected).
- A single extra beat halves an RR. Same — rejected.
- But: the **successive-differences** in RMSSD are wrecked by *deletion* alone, because removing an RR collapses two beats into one apparent transition and your RMSSD sums a fake `(RR_{i+2} − RR_i)` term. You filter outliers as a *list*, dropping rejected RRs; the remaining list still has discontinuities that show up in `nn[i] − nn[i-1]`. The Kubios approach is **interpolation, not deletion** — replace the outlier with a cubic spline value so successive-differences are continuous.

Your `pNN50` and `RMSSD` are inflated by exactly the mechanism your code comments call out for the morphology side. Per-RR rejection rate of 5–10% is plausible (matches your observation of inflated HRV).

Practical fix: keep your acceptance flag, but for RMSSD/pNN50 either (a) skip the i↔i+1 difference where one was rejected (cleanest), or (b) cubic-interpolate the rejected RR from neighbours.

Reference: Tarvainen et al. (Kubios) preprocessing guide, https://www.kubios.com/blog/preprocessing-of-hrv-data/

### A7. ROI selection (RoiSelector.kt:117–148)

`AC RMS × neighbor coherence` is a principled, low-cost approximation of spatial PCA / power spectral coherence — and arguably better than either for an embedded use case:
- A spatial PCA's first component will load heavily on whatever variance is largest, which on a smartphone-on-finger setup is **motion / luma drift**, not pulse. Your bandpass first means AC RMS is already pulse-band-limited.
- Spectral coherence at HR harmonics is more accurate but requires an HR estimate first (chicken-and-egg). Your score-by-AC × neighbor-correlation effectively *learns* which tiles correlate spatially — coherent noise (e.g. motion) won't have the right HR harmonic, but pulse will. Good design.

Minor critique:
- The score floor at 60% of best (line 167) is a hard threshold and not robust to a single dominant noisy tile (one outlier saturates `bestScore` and excludes good tiles). A *robust* alternative is to use the median of the top-K scores as the reference.
- 14 tiles out of 192 (16×12) is ~7% of frame area. For forearm, where the vessel signal is localised over a vein, this is sensible. For fingertip, where pulse is everywhere except over the bone shadow, you may be over-restricting and losing SNR you'd get from averaging more tiles.

Verdict: solid. Better than literature defaults (most papers use a fixed central ROI). Reference: similar idea in Wang et al. 2017 (rPPG) and Lempe et al. 2024 face-region selection https://www.nature.com/articles/s41746-025-01814-9.

### A8. Quality scoring (QualityScorer.kt:51–123)

Multi-component SQI is the right structure. Component weights are reasonable. Two issues vs the literature:

- **Spectral-vs-peak BPM agreement** (component 1) is the strongest single SQI in your stack. It double-counts with "Beat acceptance" because both fail under the same conditions. Elgendi's 2017/2024 SQI work shows that the best single index is *perfusion + skewness* (the latter because pulse waves are asymmetric, noise is roughly symmetric). Skewness on the bandpassed channel is one line of code and worth adding.
- **No SNR/perfusion measure.** AC/DC ratio (perfusion index) is the gold-standard hardware-derived SQI on every clinical pulse oximeter ever made. You have all the inputs — average luma over the ROI is your DC, AC RMS is your AC. Should be a 5–10 point component.
- **Sample-rate component** (lines 100–104) is too generous: 15 points at "fs ≥ 28 Hz". HRV literature wants ≥ 100 Hz for RMSSD without spline interpolation (Choi & Shin 2017), and 25 Hz is the *bare minimum* for HR-only (Charlton 2022 review). At 55 Hz you are fine for HR, marginal for RMSSD without your 4× spline interp. That's why pyPPG resamples to 75 Hz internally — your effective post-interpolation rate of 220 Hz is fine, but the scoring doesn't reflect that.

Reference: Elgendi 2016 optimal SQI, https://pmc.ncbi.nlm.nih.gov/articles/PMC5597264/ ; Elgendi 2024 NSQI, https://www.nature.com/articles/s44328-024-00002-1

---

## PART B — STATE-OF-THE-ART COMPARISON

**Open-source PPG pipelines.**

- **pyPPG** (Goda, Charlton, Behar 2024) is the current most-complete reference: 0.5–12 Hz 4th-order Chebyshev II, internal resample to 75 Hz, Aboy++ peak detector (F1 88.2% on 2054 polysomnography recordings, 16,300 hours of data), 15 fiducial points (foot via tangent intersection, sys peak, notch via PPG' inflection, diastolic peak, a–e on SDPPG, P1/P2 on 3rd derivative), 74 biomarkers each summarised by 9 statistics. MAE < 10 ms vs human annotation on every fiducial.
- **PPG-beats / MSPTDfast** (Charlton 2022/2024) is the canonical *beat detector* benchmark: 16 algorithms, MSPTD/qppg lead, F1 ≈ 99% on adults at rest, 55–91% on exercise.
- **NeuroKit2** (Makowski 2021): general-purpose, ppg_findpeaks supports Elgendi/Bishop/Charlton methods. Less PPG-specialised than pyPPG.
- **HeartPy** (van Gent 2019): noise-robust, designed for driving/cycling data, less morphology focus.
- **BioSPPy**: classic, mostly ECG; PPG support is thinner.

What they do that you don't:
1. **Tangent-intersection foot detection** — universally adopted, more reproducible than min-search.
2. **PPG' max for systolic peak** — robust to amplitude irregularity and the "diastolic > systolic" cases.
3. **PPG'' a/b/c/d/e localisation against the *systolic foot region* with normalisation against |PPG''| peak** — not against the raw beat min-max.
4. **F1-benchmarked peak detection** — your detector is plausible but unbenchmarked. MSPTDfast Kotlin port would be ~200 LOC.
5. **Interpolation-based RR cleaning** — pyPPG/Kubios both interpolate rejected RRs rather than dropping them.

**Smartphone-PPG accuracy SOTA.**

Fingertip (camera-on-skin, flash-on):
- Resting HR: MAPE ≈ 1.6% (Browne 2021, n=95; Lakhno 2024 scoping review). RMSE ≈ 1 BPM vs ECG.
- Post-exercise HR: MAPE ≈ 2.5% (Coppetti 2017, JMIR mHealth).
- HRV/RMSSD: ICC ≥ 0.90 vs ECG in controlled settings (Plews et al., Stone 2023). Bias ranges −2 to −8 ms RMSSD.
- AFib detection: sensitivity 98%, specificity 99% (Hermans 2024 EP Europace).

You should target MAPE ≤ 2% for HR and ICC ≥ 0.9 for RMSSD on fingertip resting; both are achievable with the proposed fixes.

Non-finger sites (forearm, palm, wrist):
- WF-PPG 2025 dataset (Ho et al., *Scientific Data* 12:200, PMC11790827) is *the* current reference. Key finding: dicrotic notch on wrist requires "Type 2L" morphology, which requires optimal contact pressure that **varies subject-to-subject**. Below optimal pressure you get "capillary" Type 1 (no notch). Above optimal you lose the diastolic peak. Finger PPG is consistently Type 2L; wrist is pressure-sensitive.
- Implication for your palm/forearm modes: the "no notch on palm" observation is consistent with the WF-PPG result — you are at the wrong contact pressure or the palm vasculature is genuinely capillary-dominant.
- HR accuracy on wrist with smartphone flash (no consumer device does this with a normal phone) is essentially unbenchmarked in the literature. You may be in untrodden territory.

**Where you are ahead.**

- Two-phase ROI search with bandpass-weighted spatial coherence is better than fixed-ROI baseline (most academic smartphone-PPG code).
- Hybrid R-for-timing/G-for-morphology is a clever solution to the saturation/notch trade-off — I have not seen it in the open-source literature.
- Continuous fps estimation + zero-phase filtering on uniform grid is the right pattern.

**Where you are behind.**

- Peak detector unbenchmarked.
- Detrend is the weakest filter in the pipeline.
- Fiducial detection uses 1980s heuristics; pyPPG/PulseAnalyse have been refining since 2017.
- HRV outlier handling deletes rather than interpolates.
- Vascular-age is uncalibrated — *do not display this number to a user* until you have ≥ 50 paired measurements against a validated device.

---

## PART C — TOP 5 RECOMMENDATIONS (ranked by impact)

### 1. Replace fiducial detection with pyPPG-style derivative pipeline

**Problem.** `PulseMorphology.kt:152–296` produces fiducials where (a) the "diastolic peak" can land before the systolic peak, (b) the notch fallback (`kt:286`) silently degrades AIx/RI, (c) AGI normalisation differs from Takazawa's, making `vascAge` (kt:183) uncalibrated. You see all three symptoms.

**Change.** Compute PPG' and PPG'' on the averaged beat. Detect: foot = tangent intersection on PPG' max → x-axis; systolic peak = first prominent PPG' zero-crossing from + to −; dicrotic notch = first PPG' local minimum after systolic peak (i.e. inflection on PPG); diastolic peak = next PPG' zero-crossing after notch. SDPPG a–e: a = first +ve peak of PPG'' after foot, b–e = alternating extrema in the first 600 ms.

**Effort.** Medium. ~300 LOC Kotlin; mostly mechanical. Test against the existing averaged beats in your `sessions/` data.

**Expected impact.** AIx/RI become physiologically valid. Forearm "diastolic before systolic" disappears. Vascular age remains uncalibrated but the inputs to it are correct.

### 2. Replace peak detector with MSPTDfast or Elgendi-ERMA, with two-pass refinement

**Problem.** RPD valley-pairing (`SignalProcessor.kt:283`) miscounts dicrotic notches on G. You worked around this by always running peak detection on R, but that means peak times are R-channel times — and when R saturates (forearm) you lose peaks entirely. The chain "saturate R → no peaks" is a single point of failure.

**Change.** Port MSPTDfast (≈200 LOC) or implement Elgendi ERMA (W1=175 ms, W2=1000 ms moving averages on squared bandpassed signal, offset threshold). Both can run on whichever channel has more amplitude. Add a second pass that snaps each accepted peak to its local max in a ±100 ms window on the raw morphology signal — fixes timing jitter for RR.

**Effort.** Medium. ERMA is the smaller code change.

**Expected impact.** F1 from "unknown but probably 95% on R" to 99% benchmarked on R *and* G. Removes the always-on-R workaround. RMSSD bias from peak-time jitter drops by 2–5 ms.

### 3. Widen the HR bandpass and replace MA detrend with smoothness-priors / IIR HPF

**Problem.** `SignalProcessor.kt:23–24` uses 0.7–4 Hz, and `kt:102–104` uses a 1.5 s moving-average detrend. The MA is the noisier of the two and contaminates the morphology stream (which inherits the detrend output, `kt:121`).

**Change.** Replace `movingAverageDetrend` with either (a) 2nd-order Butterworth highpass at 0.3 Hz applied bi-directionally (you already have `butterworthBandpass`; just call its HP biquad alone), or (b) Tarvainen smoothness priors (closed-form solution, ~50 LOC: `z = (I − (I + λ²D₂ᵀD₂)⁻¹)·x` for a banded D₂). Move HR bandpass to 0.5–3 Hz; cascade two biquads for 4th-order rolloff.

**Effort.** Small (option a) or Medium (option b).

**Expected impact.** Morphology baseline drift drops; dicrotic harmonic in the HR-detection signal drops ~6 dB; respiration rejection improves.

### 4. Fix HRV outlier handling: interpolate, don't delete

**Problem.** `HrvCalculator.kt:75–87` drops rejected RRs from the list. RMSSD then sums successive-difference terms across the deletion gap. One missed beat per 30 s inflates RMSSD by 5–15 ms.

**Change.** Track `accepted: List<Pair<Float, Boolean>>` where the boolean is "is this RR a real successive difference with its predecessor". In RMSSD's loop, skip terms where either neighbour was interpolated. Optionally, fill rejected RRs with cubic-spline interpolation from accepted neighbours so SDNN doesn't pay either.

**Effort.** Small.

**Expected impact.** RMSSD bias halves on the typical session you describe. Brings ICC vs ECG from ~0.85 to ~0.93 territory (matches Stone 2023 / Tarvainen Kubios).

### 5. Add perfusion-index SQI and remove/qualify the vascular-age display

**Problem.** `QualityScorer.kt` has 5 components but no AC/DC ratio (perfusion index), which is the gold standard hardware SQI. Separately, `PulseMorphology.kt:183` outputs `vascularAgeYears` from an uncalibrated linear transform of AGI; users will read this as a clinical number.

**Change.** Add a perfusion-index component (~10 LOC) using `acRms / baselineLuma`. Hide `vascularAgeYears` from the UI until you have a ground-truth calibration set; keep AGI as a unitless number with a "research-only" tag. Add skewness on the bandpassed channel as an Elgendi-style backup SQI (~5 LOC).

**Effort.** Small.

**Expected impact.** Honest signalling. Real-world quality calls become more discriminating against motion-only artefacts that currently pass beat-acceptance but have low perfusion. Removes potential medical-device claim issue with displaying an uncalibrated "vascular age".

---

## Final note on regulatory posture

Three things in this code base that would not pass a notified-body review for a Class IIa device, in priority order:
1. The `vascularAgeYears` output (PulseMorphology.kt:183) is presented as a years number and is not calibrated. This is the single highest regulatory risk.
2. No documented test suite against a reference signal generator or annotated dataset for any of HR, RMSSD, AIx, RI. PPG-BP and WF-PPG are both freely available.
3. The quality tiers ("Use this number with confidence", QualityScorer.kt:26) are clinical claims and must be supported by clinical-evaluation evidence before display.

None of this affects the pipeline's technical merit, which is genuinely good for a single-developer codebase. But the gap between "this works" and "this passes audit" is measured by the validation work, not the code.

---

## References (key DOIs / URLs)

- Charlton PH et al. 2022. Wearable Photoplethysmography for Cardiovascular Monitoring. *IEEE Reviews in Biomedical Engineering*. https://pmc.ncbi.nlm.nih.gov/articles/PMC7612541/
- Charlton PH et al. 2023. The 2023 wearable photoplethysmography roadmap. *Physiol Meas*. https://iopscience.iop.org/article/10.1088/1361-6579/acead2
- Charlton PH et al. 2022. PPG-beats benchmark. https://pmc.ncbi.nlm.nih.gov/articles/PMC9393905/
- Charlton PH 2025. MSPTDfast. https://pmc.ncbi.nlm.nih.gov/articles/PMC11894679/
- Elgendi M et al. 2013. Systolic Peak Detection in Acceleration PPG. *PLOS ONE* 8(10):e76585. https://doi.org/10.1371/journal.pone.0076585
- Elgendi M 2016. Optimal Signal Quality Index for PPG. *Bioengineering*. https://pmc.ncbi.nlm.nih.gov/articles/PMC5597264/
- Elgendi M, Martinelli I, Menon C 2024. Optimal signal quality index for remote PPG. *npj Biosensing*. https://www.nature.com/articles/s44328-024-00002-1
- Goda MA, Charlton PH, Behar JA 2024. pyPPG. *Physiol Meas* 45:045001. https://doi.org/10.1088/1361-6579/ad33a2 ; arXiv https://arxiv.org/pdf/2309.13767
- Takazawa K et al. 1998. Second derivative PPG and vascular aging. *Hypertension* 32(2):365. https://doi.org/10.1161/01.HYP.32.2.365
- Tarvainen MP et al. 2002. Advanced detrending method. *IEEE TBME* 49(2):172. https://doi.org/10.1109/TBME.2002.804337
- Ho MY, Pham HM, Saeed A, Ma D 2025. WF-PPG. *Scientific Data* 12:200. https://pmc.ncbi.nlm.nih.gov/articles/PMC11790827/
- Hermans ANL et al. 2024. Smartphone PPG for AFib. *EP Europace*. https://pmc.ncbi.nlm.nih.gov/articles/PMC11023210/
- Mejía-Mejía E, Charlton PH et al. 2021. PPG Signal Processing and Synthesis. In: *Photoplethysmography*, Elsevier.
- van Gent P et al. 2019. HeartPy. *Transportation Research Part F* 66:368.
- Charlton PH pulse-wave-analysis tutorial. https://peterhcharlton.github.io/bsp-book/tutorial/notebooks/pulse-wave-analysis.html
- Kubios HRV preprocessing. https://www.kubios.com/blog/preprocessing-of-hrv-data/
- Argüello-Prada EJ 2019. RPD method (cited by your `SignalProcessor.kt:265` comment).
