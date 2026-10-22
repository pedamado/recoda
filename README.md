# recoda

> Minimalist native Android audio monitor, field recorder, and synchronized DSP playback engine built for hardware groovebox jamming, sample studies, and portable audio recording.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg?logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0+%20(API%2026+)-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![License](https://img.shields.io/badge/License-MIT-black.svg)](#license)

---

## motivation

**recoda** (slang for *recorder*) was conceived as a no-nonsense, ultra-responsive audio monitoring and capture companion for recording beat sessions and sample experiments with portable hardware setups—specifically the **Teenage Engineering EP-133 K.O. II**, **Pocket Operators (PO series)**, and compact synths. 

Whether tracking quick sample chops during studio sessions or capturing portable jams on the move, standard recording apps often introduce aggressive OS-level automatic gain control (AGC), noise suppression, excessive latency, or bloated multi-screen workflows. 

**recoda** prioritizes raw input fidelity, instant access, tactile flat-primitive UI ergonomics, and immediate DSP feedback.

---

## design philosophy

The interface follows a strict **Dieter Rams & Teenage Engineering inspired minimalist aesthetic**:

* **Geometry & Primitives**: Built entirely from flat, non-rounded geometric primitives—sharp rectangular tracks, circular flat knobs with radius value indicators, and clean action polygons (triangles, squares, bars).
* **Analogous Grayscale Palette**: Matte off-white (`#F6F6F4`), pure white (`#FFFFFF`), neutral light grays, dark charcoal (`#1A1A1A`), and solid black (`#000000`).
* **Sparing Accent**: Signal red (`#E60000`) is reserved strictly for true peak clipping LEDs and recording status indicators.
* **Typographic Hierarchy**: Set in the condensed monospace variable font **[Martian Mono](https://github.com/evilmartians/mono)** with strict lowercase labels and headings (`recoda`, `recorder`, `gallery`, `playback`).
* **Non-Intrusive Feedback**: Native toast popups are eliminated; system state and status updates stream inline directly beside the header title (`recoda :: recording started`).
* **Ergonomics & Layout**: Zero splash screen latency—launches directly into the live engine across a strict `100% dvh` single-activity layout with gesture navigation (horizontal swipe between tabs).

---

## core features & architecture

### 1. real-time audio engine (pure kotlin dsp)
* **Zero NDK / C++ Footprint**: Built purely in native Kotlin, minimizing compilation complexity and architecture overhead.
* **Low-Latency Ingest**: Configured via `AudioRecord` utilizing `MediaRecorder.AudioSource.UNPROCESSED` (with graceful fallback to `MIC` for unsupported hardware/emulators) to preserve full dynamic range without unwanted system DSP.
* **Ultra-Low Latency Passthrough**: Direct monitoring routed through `AudioTrack` configured with `FLAG_LOW_LATENCY` and `PERFORMANCE_MODE_LOW_LATENCY` on a dedicated high-priority audio thread (`THREAD_PRIORITY_URGENT_AUDIO`).
* **Audible Dynamic Noise Suppression**: Downward expansion envelope follower with fast attack and smooth decay, eliminating background hum in live monitoring.

### 2. screen 1: recorder (`recorder`)
* **Dual-Channel VU Strips**: Full-height, borderless Left/Right channel level meters with peak-hold decay, calibrated dB scaling ($0\text{ dB}$ to $-60\text{ dB}$), and independent channel tap-to-mute.
* **Rolling Waveform Monitor**: Continuously listens to input amplitude, drawing vertical dB lines moving right-to-left from the VU origin. Displays in neutral gray while idling and switches to solid black upon recording.
* **Input Gain Calibration**: Wide $-30\text{ dB}$ to $+30\text{ dB}$ range featuring sample-interpolated smoothing to prevent zipper noise. Double-tap background reset to unity ($0\text{ dB}$).
* **Passthrough & Output Level**: Toggleable live monitor with master volume slider (default 50%).
* **Noise Gate Threshold**: Horizontal dB threshold slider with double-tap reset.
* **Screen Awake Control & Lockscreen Recording**: Toggleable `screen awake` mode (`stays awake during rec`). Uses an Android Foreground Service with partial CPU wake-lock and microphone capture policy, guaranteeing uninterrupted background recording even if the phone screen locks or turns off.
* **Status Favicon & Notification**: A Dieter Rams light gray square favicon with a slowly blinking white ball indicator in the app header and camera notch area, paired with a persistent status bar notification displaying the custom white ball icon during recording.
* **Primary CTA**: Prominent solid black rectangular trigger for record/stop toggling with real-time timer.

### 3. screen 2: recordings archive (`gallery`)
* **Minimalist Archive Cards**: Two-line clean white cards displaying file title, creation timestamp, duration, and file size in MB.
* **Inline Actions**: Quick actions for playback loading, file renaming, exporting, system sharing via `FileProvider`, and deletion.
* **Direct Interaction**: Double-tapping the card text triggers an instant rename dialog.

### 4. screen 3: synchronized playback (`playback`)
* **Full-Length Waveform Overview**: Interactive full-audio waveform overview with touch-and-drag playhead scrubbing.
* **Synchronized 3-Band Biquad EQ (Cookbook Direct Form II Transposed)**:
  * Low Shelf: $100\text{ Hz}$
  * Mid Peaking: $1\text{ kHz}$
  * High Shelf: $8\text{ kHz}$
  * Stacked horizontal sliders ($-30\text{ dB}$ to $+30\text{ dB}$) with individual double-tap reset and master `enabled / disabled` toggle.
* **Variable Transport & Scrubbing**:
  * **Transport Bar**: Normalized full-width bar featuring `go to start`, `reverse speed`, `play/pause` (2fr), `stop`, `forward speed`, `loop toggle`, and `go to end`.
  * **Stepped Scrub Speeds**: Reverse down-stepping ($-25\%$, $-50\%$, $-100\%$, $-200\% \to 100\%$) and forward up-stepping ($200\%$, $25\%$, $50\% \to 100\%$).
* **Playback-Linked VU Meters**: Meter strips animate strictly to active audio file output with per-channel muting.

### 5. audio formats & mobile storage efficiency
* **Native AAC/M4A Capture**: Option to compress directly via Android's hardware `MediaRecorder` using AAC-LC at $44.1\text{ kHz}$ / $192\text{ kbps}$ inside an MPEG-4 container, achieving up to a 90% reduction in file size compared to raw PCM while retaining transient punch.
* **Uncompressed WAV**: 16-bit $44.1\text{ kHz}$ / $48\text{ kHz}$ stereo PCM audio directly written with clean 44-byte RIFF headers.
* **Multi-Preset Export**:
  * `fast sharing`: High compression, ultra-fast transfers (MP3 64 kbps / WAV 22.05 kHz mono)
  * `normal audio`: Balanced standard quality (MP3 128 kbps / WAV 44.1 kHz stereo)
  * `medium compression`: Moderate size, high fidelity (MP3 192 kbps / WAV 32 kHz stereo)
  * `high studio quality`: Master uncompressed stream (MP3 320 kbps / WAV 48 kHz 16-bit stereo)

---

## interface & interaction overview

┌─────────────────────────────────────────────────────────────┐
│ recoda :: engine ready                          [tabs: 1 2 3]│
├─────────────────────────────────────────────────┬───────────┤
│                                                 │ [CH1][CH2]│
│  LIVE ROLLING WAVEFORM / FULL AUDIO WAVEFORM    │  VU METERS│
│  (idle: light gray | recording/playing: black)  │  (tap to  │
│                                                 │   mute)   │
├─────────────────────────────────────────────────┴───────────┤
│ input gain [-30dB .. 0dB .. +30dB]      (dbl-tap resets 0dB)│
│ monitor / passthrough [on/off]    volume [0% .. 50% .. 100%]│
│ noise removal filter [on/off]     threshold [-60dB .. 0dB]  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│                      [  ●  REC  ]                           │
│                                                             │
└─────────────────────────────────────────────────────────────┘

---

## tech stack & dependencies

* **Language**: [Kotlin](https://kotlinlang.org/)
* **UI Toolkit**: [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material3 & Foundation primitives)
* **Audio Framework**: Android Low Latency Audio API (`AudioRecord`, `AudioTrack`, `MediaRecorder`)
* **Persistence**: `SharedPreferences` (AppSettings for gain, EQ, passthrough, noise threshold, and source preferences)
* **File Sharing**: Android `FileProvider` (Scoped App Storage)

---

## getting started

### prerequisites
* Android Studio Ladybug / Koala or later
* Android SDK 34+
* Physical device recommended (low-latency audio performance and microphone dynamic ranges vary on emulators)

### build & install
```bash
# Clone the repository
git clone [https://github.com/your-username/recoda.git](https://github.com/your-username/recoda.git)
cd recoda

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

## credits & acknowledgments
Concept, Design Direction & Research: Pedro Amado (FBAUP / i2ADS)

# Application Architecture & Development:
Built with Google AI Studio

# Typography:
Martian Mono — Designed by Roman Shamin & Evil Martians, licensed under the SIL Open Font License (OFL).

# Hardware & Aesthetic Inspiration:
Teenage Engineering (EP-133 K.O. II, PO Series)
Design principles of Dieter Rams (Braun)
