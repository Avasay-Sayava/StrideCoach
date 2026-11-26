# StrideCoach — Local-First Guided Running Tracker & Training Plan Builder

A **$0-forever, offline-first, English-only** native Android running app built with Kotlin, Jetpack Compose, Room SQLite, and a Foreground GPS Service.

## Highlights
- **Reusable Training Plan Builder:** Generate plans for 5K, 10K, Half Marathon, Marathon, or Base Building with custom weekday roles (`Long Run`, `Speed / Tempo`, `Easy Run`, `Full Rest`, `Walk-Only`, `Cross-Train`), cutback weeks, taper, 5K benchmark auto-recalibration, and a built-in **Tel Aviv 10K 92-Day Preset**.
- **Custom Structured Workouts:** Typed segment runner (`Warm-Up`, `Walk`, `Run`, `RepeatBlock`, `Rest`, `Cool-Down`) plus unstructured free runs.
- **Walking-Load Auto-Regulation:** Configurable daily walking threshold (`> 7 km` default) that offers 1-tap downgrade or rest-day swap when heavy walking precedes a fast session.
- **Pocket & Lock-Screen Proofing:** Foreground GPS Service with persistent lock-screen controls (`Pause` / `Resume` / `Next` / `Stop`), English `TextToSpeech` + haptic cues, smart auto-pause, WakeLock toggle, and a **Post-Run Tail Trimmer**.
- **Replay-Ready SQLite Schema (`track_points`):** Stores time-series GPS points (`seqIndex`, `elapsedMillis`, `latitude`, `longitude`, `speedMps`, `bearingDegrees`, `cumulativeDistanceMeters`, `segmentIndex`, `isPaused`) powering the **Animated Route Replay Player** (even for imported polyline runs via split-interpolated timing).
- **Lossless Data Portability:** Import & export `running-tracker-*.backup` (`v4.1.0`), import Google Maps `Timeline.json` (with `> 7.5 km/h` GPS glitch filtering and run-overlap deduplication), and export GPX 1.1.

## Architecture
- `:core:domain` — Pure Kotlin models, `TrainingPlanGenerator`, `WorkoutStateMachine`, `WalkingLoadRegulator`, `RunTailTrimmer`, `EncodedPolylineCodec`, `RunAnalyticsEngine`.
- `:core:data` — Room database (`runs`, `track_points`, `run_splits`, `walking_logs`, `training_plans`, `planned_days`, `workout_templates`), DataStore preferences, and backup/Timeline/GPX adapters.
- `:core:tracking` — `RunTrackingService`, `GpsSignalProcessor`, `VoiceCoachEngine`, `StepCounterTracker`.
- `:app` — Jetpack Compose Material 3 UI and animated route replay player.

## Build & CI
Push to `main` (or run the GitHub Actions workflow manually) to run unit tests and download the built debug APK from Actions artifacts.
