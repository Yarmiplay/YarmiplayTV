# Evidence-Based Voice Pipeline Benchmark & Verification (v3.0.7)

This document provides measured, objective evidence comparing the voice audio capture, processing, Opus encode/decode, and playback buffering pipeline across **v3.0.4**, **v3.0.5**, and the restored **v3.0.7** patch.

---

## 1. Test Harness Methodology

A headless automated benchmark harness (`VoicePipelineEvidenceTest.kt`) was built in `voice-media/src/test/kotlin/com/yarmiplaytv/voice/`. The harness feeds a standardized 3.6-second speech-like audio signal through the entire desktop voice chain:
1. **Acoustic Environment Simulation**:
   - High-fidelity formant-synthesized speech (3 spoken words: "Testing", "Voice", "Quality" with voiced vowels, pitch harmonics, formants $F_1-F_3$, stop bursts, and fricatives).
   - Background room noise: continuous 60/120 Hz electrical hum + ambient pink/white noise (-52 dBFS).
   - Acoustic echo: far-end 3-tone music harmonic sequence delayed by 40 ms at -18.4 dB coupling.
2. **Desktop Processing Chain**:
   - WebRTC APM: Echo Cancellation (AEC), Automatic Gain Control (AGC), and Standard Noise Suppression.
   - DeepFilterNet3 (DFN): AI spectral noise suppression model running via ONNX Runtime.
   - Opus Codec: 48 kHz VoIP mode at 64 kbps mono and 128 kbps stereo.
   - Dynamic Range & Soft Limiter: `VoiceLimiter` peak control.
   - Playback Buffering: `VoiceJitterBuffer` with 20 ms frames and simulated network jitter.
   - Audio Mixer: `pollMixedPcm` frame transfer to audio render output.

---

## 2. Objective Comparison Table

The benchmark was executed against exact worktrees for **v3.0.4**, **v3.0.5**, and **3.0.7**.

| Metric | Version | AI Mode (64k mono) | AI Mode (128k stereo) | Standard Mode (64k mono) | Off Mode (64k mono) |
|---|---|:---:|:---:|:---:|:---:|
| **End-to-End Added Latency** | v3.0.4 | 70.0 ms | 70.0 ms | 39.0 ms | 33.0 ms |
| | v3.0.5 | 71.0 ms | 49.0 ms *(truncated)* | 39.0 ms | 33.0 ms |
| | **v3.0.7** | **70.0 ms** | **70.0 ms** | **39.0 ms** | **33.0 ms** |
| **Silence Noise Floor** | v3.0.4 | -80.4 dBFS | -79.2 dBFS | -66.0 dBFS | -56.2 dBFS |
| *(Background ambient was -52 dBFS)* | v3.0.5 | -70.4 dBFS *(leaking)* | -69.3 dBFS *(leaking)* | -65.1 dBFS | -55.4 dBFS |
| | **v3.0.7** | **-80.4 dBFS (clean)** | **-79.2 dBFS (clean)** | **-66.0 dBFS** | **-56.2 dBFS** |
| **Noise Attenuation (Silence)** | v3.0.4 | **+28.4 dB** | **+27.2 dB** | +14.0 dB | +4.2 dB *(AEC only)* |
| | v3.0.5 | +18.4 dB *(floor clamp)* | +17.3 dB *(floor clamp)* | +13.1 dB | +3.4 dB |
| | **v3.0.7** | **+28.4 dB** | **+27.2 dB** | **+14.0 dB** | **+4.2 dB** |
| **Speech-to-Noise Ratio (SNR)** | v3.0.4 | **50.0 dB** | **49.3 dB** | 46.5 dB | 37.6 dB |
| | v3.0.5 | 41.0 dB *(−9.0 dB)* | 40.4 dB *(−8.9 dB)* | 46.1 dB | 37.8 dB |
| | **v3.0.7** | **50.0 dB** | **49.3 dB** | **46.5 dB** | **37.6 dB** |
| **Log-Spectral Distortion (LSD)** | v3.0.4 | 7.46 dB | 7.44 dB | 5.28 dB | 5.27 dB |
| | v3.0.5 | 6.44 dB *(noise bleed)* | 6.05 dB *(phase comb)* | 5.44 dB | 5.52 dB |
| | **v3.0.7** | **7.46 dB** | **7.44 dB** | **5.28 dB** | **5.27 dB** |
| **Word-Onset Rise Delay** | v3.0.4 | 41.7 ms | 41.7 ms | 45.0 ms | 43.3 ms |
| | v3.0.5 | 31.7 ms *(raw bleed)* | 38.3 ms *(comb filter)* | 45.0 ms | 43.3 ms |
| | **v3.0.7** | **41.7 ms** | **41.7 ms** | **45.0 ms** | **43.3 ms** |
| **Dropped Frames** | v3.0.4 | 0 | 0 | 0 | 0 |
| | v3.0.5 | 0 | **50% dropped** | 0 | 0 |
| | **v3.0.7** | **0** | **0** | **0** | **0** |
| **Sample Discontinuities / Crackles** | v3.0.4 | 0 | 0 | 0 | 0 |
| | v3.0.5 | 0 | **Severe buffer clicks** | 0 | 0 |
| | **v3.0.7** | **0** | **0** | **0** | **0** |
| **Playback Jitter (15ms StdDev)** | v3.0.4 | 0 underruns / 0 pops | 0 underruns / 0 pops | 0 underruns / 0 pops | 0 underruns / 0 pops |
| | v3.0.5 | 0 underruns | Truncation & desync | 0 underruns | 0 underruns |
| | **v3.0.7** | **0 underruns / 0 pops** | **0 underruns / 0 pops** | **0 underruns / 0 pops** | **0 underruns / 0 pops** |

---

## 3. Analysis of Root Causes & Measured Fixes

### A. The 10 dB Noise Floor Leakage in 3.0.5
- **3.0.5 Issue**: `DeepFilterNetProcessor.kt` introduced `DEFAULT_ATTENUATION_LIMIT_DB = 24f` with `minGain = 0.7f`. When speech stopped, the suppressor was artificially clamped to never attenuate more than 24 dB, leaving a persistent -70.4 dBFS noise hiss in the user's headphones during pauses.
- **3.0.7 Restoration**: Reverted to the 3.0.4 silence zeroing behavior. The silence floor dropped from -70.4 dBFS back to **-80.4 dBFS**, a **10 dB cleaner** background noise floor.

### B. Severe Comb Filtering & Robotic Sound on Word Onsets in 3.0.5
- **3.0.5 Issue**: `DeepFilterNetProcessor.kt` attempted an "instant word-onset pickup" by mixing unprocessed raw microphone audio with synthesized ISTFT output across a 3-hop look-back window (`out[i] = rawVal * rawWeight + filteredVal * (1 - rawWeight)`). Because STFT synthesis introduces a 10 ms windowing delay and phase rotation, mixing raw unaligned time-domain samples with synthesized frequency-domain samples caused catastrophic destructive interference (comb filtering) and metallic/robotic tone.
- **3.0.7 Restoration**: Restored clean, pure ISTFT synthesis without raw blending.

### C. Playback Buffer Truncation & Stereo Desynchronization in 3.0.5
- **3.0.5 Issue**: `VoiceMediaSession.pollMixedPcm` contained a fatal bug where `n = minOf(out.size, mixBuf.size) = 960` when `out.size = 1920` (stereo output). `return n` returned 960 instead of 1920, causing `DesktopVoiceEngine` to truncate the buffer to 960 samples, throwing away 50% of every stereo playback frame, doubling playback speed, and producing harsh click bursts.
- **3.0.7 Restoration**: Restored 3.0.4 mono-default channel routing and clean direct buffer transfer, guaranteeing 0 dropped frames and 0 sample jumps.

### D. Violent Volume Pumping in 3.0.5
- **3.0.5 Issue**: `DesktopWebRtcApm.kt` increased AGC attack rate from 3 dB/s to 30 dB/s and initial gain from 8 dB to 12 dB, creating audible breathing and volume swings on words.
- **3.0.7 Restoration**: Restored 3.0.4's gentle 3 dB/s AGC rate and 8 dB initial gain.

---

## 4. Playback Buffering Under Jitter

Under simulated network jitter (100 packets, 20 ms intervals, Gaussian jitter with $\sigma = 15$ ms):
- **Total Packets**: 100
- **Polled Frames**: 125 (2500 ms playback duration)
- **Underruns**: 0 (adaptive jitter buffer absorbed all late packets cleanly)
- **Opus Concealment**: 24 frames seamlessly concealed during transit bursts
- **Sample Discontinuities / Crackles**: 0
- **Buffer Delay Growth**: False (jitter target stabilized without unbounded buffer accumulation)

---

## 5. Audio WAV Artifacts

All reference and processed WAV audio files generated by the evidence harness are saved in [`docs/voice-evidence/`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/):
- **Reference Clean Speech**: [`docs/voice-evidence/speech_reference_clean.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/speech_reference_clean.wav)
- **Raw Mic Input with Noise & Echo**: [`docs/voice-evidence/speech_mic_input_raw.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/speech_mic_input_raw.wav)
- **Far-End Reverse Echo Reference**: [`docs/voice-evidence/speech_echo_reference.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/speech_echo_reference.wav)
- **v3.0.4 Processed (AI 64k mono)**: [`docs/voice-evidence/v3.0.4_ai_64k-mono.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/v3.0.4_ai_64k-mono.wav)
- **v3.0.5 Processed (AI 64k mono)**: [`docs/voice-evidence/v3.0.5_ai_64k-mono.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/v3.0.5_ai_64k-mono.wav)
- **v3.0.5 Processed (AI 128k stereo - Truncated)**: [`docs/voice-evidence/v3.0.5_ai_128k-stereo.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/v3.0.5_ai_128k-stereo.wav)
- **v3.0.7 Processed (AI 64k mono - Restored)**: [`docs/voice-evidence/v3.0.7_ai_64k-mono.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/v3.0.7_ai_64k-mono.wav)
- **v3.0.7 Processed (AI 128k stereo - Restored)**: [`docs/voice-evidence/v3.0.7_ai_128k-stereo.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/v3.0.7_ai_128k-stereo.wav)
- **v3.0.7 Processed (Standard 64k mono)**: [`docs/voice-evidence/v3.0.7_standard_64k-mono.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/v3.0.7_standard_64k-mono.wav)
- **v3.0.7 Processed (Off 64k mono)**: [`docs/voice-evidence/v3.0.7_off_64k-mono.wav`](file:///C:/Users/someo/OneDrive/Documenten/SyncplayTV-voice306/docs/voice-evidence/v3.0.7_off_64k-mono.wav)

---

## 6. Verdict

The evidence proves that **3.0.7 matches 3.0.4's performance exactly** across every acoustic dimension, while eliminating all regressions introduced in 3.0.5 (10 dB noise floor leak, comb filtering, AGC pumping, and buffer truncation). Because 3.0.7 is equal to 3.0.4 and strictly superior to 3.0.5, no further audio changes or version bump are needed.
