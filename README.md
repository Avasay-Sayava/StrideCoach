# StrideCoach — Local-First Guided Running Tracker & Training Plan Builder

A **local-first, English-only** native Android running app built with Kotlin, Jetpack Compose, Room SQLite, MapLibre vector maps, and a Foreground GPS Service.

## Highlights
- **Crisp Vector Maps & Preloaded Route Thumbnails:** Hardware-accelerated OpenFreeMap Dark vector tiles via MapLibre OpenGL with disk-cached Web Mercator route previews in History.
- **Smooth Replay, Park-Safe Street Snapping & Route Planner:** VSYNC-interpolated animated route replay, 1-tap OSM street snapping that preserves off-road park/greenery sections, and an interactive route planner mixing snapped street segments with off-road paths.
- **Reusable Training Plan Builder:** Generate plans for 5K, 10K, Half Marathon, Marathon, or Base Building with custom weekday roles, cutback weeks, taper, 5K benchmark auto-recalibration, and a built-in **Tel Aviv 10K 92-Day Preset**.
- **Custom Structured Workouts & Health Automation:** Typed segment runner (`Warm-Up`, `Walk`, `Run`, `RepeatBlock`, `Rest`, `Cool-Down`), open runs, step cadence & segment auto-classification, Android Health Connect sync, and smart running transition detection.
- **Pocket & Lock-Screen Proofing:** Foreground GPS Service with persistent lock-screen controls (`Pause` / `Resume` / `Next` / `Stop`), English `TextToSpeech` + haptic cues, smart auto-pause, WakeLock toggle, and a **Post-Run Tail Trimmer**.
- **Lossless Data Portability:** Import & export `running-tracker-*.backup` (`v4.1.0`), import Google Maps `Timeline.json` (with `> 7.5 km/h` GPS glitch filtering and run-overlap deduplication), and export GPX 1.1.

## Architecture
- `:core:domain` — Pure Kotlin models, `TrainingPlanGenerator`, `WorkoutStateMachine`, `WalkingLoadRegulator`, `RunTailTrimmer`, `EncodedPolylineCodec`, `TrackInterpolator`, `ParkSafeSnapMerger`, `ActivitySegmentClassifier`, `RunAnalyticsEngine`.
- `:core:data` — Room database (`runs`, `track_points`, `run_splits`, `detected_segments`, `walking_logs`, `training_plans`, `planned_days`, `workout_templates`), DataStore preferences, Health Connect sync, OSM foot router, and backup/Timeline/GPX adapters.
- `:core:tracking` — `RunTrackingService`, `GpsSignalProcessor`, `VoiceCoachEngine`, `StepCounterTracker`, `AutoActivityDetector`.
- `:app` — Jetpack Compose Material 3 UI, MapLibre vector engine, fullscreen map sheets, and route planner.

## Build & CI
Push to `main` (or run the GitHub Actions workflow manually) to run unit tests and download the built debug APK from Actions artifacts.
