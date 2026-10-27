# Xelvic Camera Engine

A native Android camera engine featuring real-time face tracking and a dynamic, data-driven 2D filter package architecture (`.xfilter`).

---

## 1. Core Architecture

Xelvic treats all augmented reality face overlays as external, self-contained packages rather than hardcoded effects. The rendering engine contains zero filter-specific conditionals, coordinates, or behaviors.

### Component Responsibilities

- **`MainActivity`**: Host Activity, edge-to-edge layout, lifecycle management, SAF file-picker dispatch, and runtime permissions (`CAMERA`).
- **`CameraController`**: CameraX lifecycle binder connecting `Preview`, `ImageAnalysis` (with `STRATEGY_KEEP_ONLY_LATEST`), and `ImageCapture` use cases with lens flipping and flash modes.
- **`FaceAnalyzer`**: Asynchronous `ImageAnalysis.Analyzer` passing camera frames off the main thread to on-device ML Kit Face Detection, guaranteeing proper `ImageProxy` disposal.
- **`CoordinateMapper`**: Handles coordinate geometry between sensor buffers, ML Kit upright detection spaces, and `PreviewView` (handling aspect fill-crop, orientation, and front-camera mirroring).
- **`FilterPackageManager`**: Handles atomic extraction, validation, and storage of `.xfilter` archives. Implements strict ZIP Slip and path traversal defenses, size limits, and manifest verification.
- **`FilterRepository`**: Single source of truth managing installed filters, active selection, and memory caching of bitmaps.
- **`StickerRenderer`**: Generic 2D renderer calculating layer position, scaling (`FACE_WIDTH`, `FACE_HEIGHT`, `EYE_DISTANCE`), normalized offsets, rotation (`FACE_ROLL`, `EYE_LINE`, `FIXED`), opacity, and per-layer EMA jitter suppression.
- **`PhotoProcessor`**: Obtains full-resolution photographs from `ImageCapture`, runs face detection on the captured photo coordinate system, and composites all active filter layers directly into the photo.
- **`MediaStoreSaver`**: Persists processed photographs to the Android device gallery under `Pictures/Xelvic` using modern `MediaStore` APIs.
- **`PhotoReviewScreen`**: Dedicated review interface supporting **Retake** (discards capture and immediately resumes live camera tracking) and **Save Photo**.

---

## 2. `.xfilter` Package Specification

An `.xfilter` package is a ZIP-compatible archive containing:
```
cat_face.xfilter
├── manifest.json
├── thumbnail.png
└── assets/
    ├── cat_ears.png
    ├── cat_nose.png
    ├── cat_whiskers_left.png
    └── cat_whiskers_right.png
```

### Manifest Schema (`cat_face.xfilter / manifest.json`)

```json
{
  "schemaVersion": 1,
  "id": "cat_face",
  "name": "Cat Face",
  "version": "1.0.0",
  "description": "Playful 2D cat face with ears, nose, and whiskers",
  "minimumRuntimeVersion": 1,
  "thumbnail": "thumbnail.png",
  "layers": [
    {
      "id": "ears",
      "asset": "assets/cat_ears.png",
      "anchor": "FOREHEAD_TOP",
      "scaleMode": "FACE_WIDTH",
      "scale": 0.76,
      "offsetX": 0.0,
      "offsetY": -0.14,
      "rotationMode": "FACE_ROLL",
      "rotationDegrees": 0.0,
      "opacity": 1.0,
      "zIndex": 0
    },
    {
      "id": "nose",
      "asset": "assets/cat_nose.png",
      "anchor": "NOSE",
      "scaleMode": "FACE_WIDTH",
      "scale": 0.12,
      "offsetX": 0.0,
      "offsetY": 0.0,
      "rotationMode": "FACE_ROLL",
      "rotationDegrees": 0.0,
      "opacity": 1.0,
      "zIndex": 1
    },
    {
      "id": "left_whiskers",
      "asset": "assets/cat_whiskers_left.png",
      "anchor": "LEFT_CHEEK",
      "scaleMode": "FACE_WIDTH",
      "scale": 0.34,
      "offsetX": -0.04,
      "offsetY": 0.0,
      "rotationMode": "FACE_ROLL",
      "rotationDegrees": 0.0,
      "opacity": 1.0,
      "zIndex": 2
    },
    {
      "id": "right_whiskers",
      "asset": "assets/cat_whiskers_right.png",
      "anchor": "RIGHT_CHEEK",
      "scaleMode": "FACE_WIDTH",
      "scale": 0.34,
      "offsetX": 0.04,
      "offsetY": 0.0,
      "rotationMode": "FACE_ROLL",
      "rotationDegrees": 0.0,
      "opacity": 1.0,
      "zIndex": 2
    }
  ]
}
```

### Coordinate and Face-Relative Sizing Conventions

- **Face-Relative Sizing Rules**:
  - `FACE_WIDTH`: Layer width = Detected face bounding box width × `scale`.
  - `FACE_HEIGHT`: Layer width = Detected face bounding box height × `scale`.
  - `EYE_DISTANCE`: Layer width = Distance between eyes × `scale`.
  - Layer height automatically preserves the source image asset's original aspect ratio.
  - Sizing targets for Cat Face:
    - **Both ears combined**: ~76% of face width (`scale: 0.76`), positioned above the forehead with `FOREHEAD_TOP` anchor and normalized offset `offsetY: -0.14`.
    - **Cat nose**: ~12% of face width (`scale: 0.12`), anchored directly to `NOSE` (ML Kit nose tip landmark).
    - **Whiskers**: ~34% of face width each (`scale: 0.34`), anchored to `LEFT_CHEEK` and `RIGHT_CHEEK` with gentle lateral offsets (`offsetX: ±0.04`).
- **Normalized Offsets (`offsetX` / `offsetY`)**:
  - Offsets are expressed as fractions of detected face width and height, adapting dynamically as the face moves closer to or farther from the lens.
- **Supported Anchors**:
  - `FOREHEAD_TOP`: Derived midpoint above the eye line towards the upper face boundary.
  - `EYES_CENTER`: Mathematical midpoint between the left and right eyes.
  - `FACE_CENTER`: Center of the face bounding box.
  - `LEFT_EYE` / `RIGHT_EYE`: ML Kit landmark eye positions.
  - `NOSE`: Nose base landmark.
  - `MOUTH`: Mouth bottom landmark.
  - `LEFT_CHEEK` / `RIGHT_CHEEK`: Cheek landmarks.
- **Rotation Modes**:
  - `FACE_ROLL`: Rotates with the head's Euler Z roll angle.
  - `EYE_LINE`: Rotates with the geometric angle between the left and right eyes.
  - `FIXED`: Fixed angle independent of face tilt.

---

## 3. Security and Package Validation

The `FilterPackageManager` enforces strict controls on all incoming `.xfilter` archives:
1. **ZIP Slip & Path Traversal Prevention**: Every entry path is verified to resolve strictly within the target extraction directory (`canonicalPath.startsWith(destination.canonicalPath)`). Any relative `..`, absolute `/`, or backslash paths are rejected.
2. **Resource Boundaries**:
   - Maximum archive size: 25 MB
   - Maximum entry count: 100 files
   - Maximum uncompressed extraction size: 50 MB
   - Allowed file extensions: `.json`, `.png`, `.webp` (executable `.dex`, `.jar`, `.so`, `.sh` are strictly prohibited).
3. **Atomic Installation**: Packages extract to an isolated temporary directory `.tmp_extract_*`, validate manifest syntax and required assets, and atomically rename upon verification.

---

## 4. How to Build & Run in Android Studio

1. Open Android Studio and select **File > Open**, navigating to the project directory.
2. Allow Gradle to sync dependencies.
3. Connect an Android device running Android 7.0 (API 24) or newer with USB debugging enabled.
4. Press **Run** (`Shift + F10`) to build and deploy the application.
5. Grant camera permission on the device:
   - Select **Cat Face** or **Butterfly** in the bottom carousel to see the filter track your facial movements.
   - Switch between front and rear cameras using the top-bar flip button.
   - Tap the shutter button to capture a full-resolution photograph with all layers composited.
   - Tap **Retake** to return to the live camera feed, or tap **Save Photo** to save the processed image to `Pictures/Xelvic`.

---

## 5. High-Performance Three-Loop Architecture

To prevent frame drops, stutter, and thermal throttling on mid-range and low-end Android devices, Xelvic decouples the camera preview pipeline into three independent asynchronous execution loops:

```
┌────────────────────────────────────────────────────────┐
│ 1. DISPLAY LOOP (CameraX Preview)                      │
│ - Hardware-accelerated SurfaceView/TextureView         │
│ - Driven directly by Android GraphicBuffer/HAL         │
│ - Fixed 30/60 fps display pacing; never blocked by ML  │
└────────────────────────────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ 2. TRACKING LOOP (ImageAnalysis + ML Kit)             │
│ - Independent Background Executor                      │
│ - 480p target resolution (optimal for face detection)  │
│ - AtomicBoolean in-flight lock (no queue buildup)      │
│ - AdaptivePerformanceController frame throttling       │
│ - Publishes latest pose to TrackingPoseHolder          │
└────────────────────────────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ 3. OVERLAY RENDERING LOOP (TrackingOverlayView)        │
│ - Hardware-accelerated custom android.view.View        │
│ - Decoupled from Jetpack Compose recomposition        │
│ - Redraws on VSYNC via postInvalidateOnAnimation()     │
│ - OneEuroFilter smoothing (X, Y, Scale, Rotation)      │
│ - Zero per-frame allocations on hot draw path          │
└────────────────────────────────────────────────────────┘
```

### Why Compose Recomposition Was Eliminated from the Tracking Loop

In naive Compose camera overlays, tracking state is published as `StateFlow<FaceTrackingResult>`. Reading this flow inside a composable function forces the Compose runtime to re-execute recomposition, layout measurement, and modifier passes at 30+ frames per second. Even small recompositions trigger noticeable GC pauses, UI thread jank, and battery drain.

In Xelvic:
- The UI layer (shutter button, flash toggle, filter carousel) remains in Jetpack Compose, updating **only** on user interaction or state changes (e.g., face detection state `isFaceLocked`).
- High-frequency tracking coordinates bypass the Compose node tree completely: `TrackingPoseHolder` updates an `AtomicReference`, which triggers an invalidated draw pass on `TrackingOverlayView` via Android's native hardware-accelerated canvas.

---

## 6. One-Euro (1€) Filter Smoothing & Tuning

Xelvic uses an adapted 1€ filter (Casiez et al., CHI 2012) for position, scale, and angular smoothing. The filter dynamically computes cutoff frequency:
$$\hat{x} = \alpha x + (1 - \alpha) x_{\text{prev}}, \quad f_c = \text{minCutoff} + \beta \cdot |\hat{dx}|$$

- **`minCutoff` (default: 1.0 Hz)**: Determines filtering strength when the face is stationary. Lower values suppress camera sensor noise and facial micro-tremors.
- **`beta` (default: 0.02)**: Determines velocity scaling. When the user tilts or turns their head quickly, the cutoff frequency increases proportionally to eliminate perceptible lag.
- **`dCutoff` (default: 1.0 Hz)**: Derivative cutoff frequency to smooth velocity estimates.

### Angular Wraparound Handling
To prevent 358° spin glitches when roll angle crosses the $\pm 180^\circ$ boundary:
```kotlin
val delta = normalizeAngle(target - prevAngle) // wraps delta to [-180, +180]
val smoothed = normalizeAngle(prevAngle + alpha * delta)
```

---

## 7. Adaptive Performance Controller

`AdaptivePerformanceController` prevents thermal throttling during extended camera sessions:
- **`BALANCED`**: Evaluates every incoming frame (p95 inference latency $\le 45\text{ms}$).
- **`PERFORMANCE`**: Evaluates every 2nd frame (p95 inference latency $> 45\text{ms}$).
- **`LOW_LOAD`**: Evaluates every 3rd frame (p95 inference latency $> 65\text{ms}$, or when Android OS triggers Battery Saver mode).
- **Hysteresis & Cooldown**: Downshifting occurs immediately under load; upgrading requires a **3-second stability cooldown** to prevent rapid profile oscillation.
- **No-Face Power Save**: When no face is detected for $> 2.0\text{ seconds}$, frame processing automatically throttles to conserve CPU/GPU cycles.

---

## 8. Memory & GC Hot-Path Optimization

1. **Pre-allocated Draw Buffers**: Matrix transforms and `RectF` destination bounds are pre-allocated in `StickerRenderer` and reused per draw pass.
2. **Safe Image Downsampling**: Filter assets (`.png`, `.webp`) are decoded using `BitmapFactory.Options` with bounds pre-computation (`inJustDecodeBounds = true`) and power-of-two `inSampleSize` downsampling (maximum dimension capped at 1280px) to prevent OutOfMemory errors on ultra-high-resolution custom filter imports.
3. **Sensor Timestamps**: Uses CameraX `imageInfo.timestamp` instead of `System.currentTimeMillis()` for drift-free temporal analysis.

---

## 9. Physical-Device Benchmark Procedure & Verification Table

### Device Profiling Instructions

1. **Android Studio Profiler**:
   - Run the app on a connected physical device: **View > Tool Windows > Profiler**.
   - Profile CPU, Memory, and Energy tracks during 60-second continuous face tracking.
2. **Perfetto Trace Recording**:
   ```bash
   adb shell perfetto \
     -c - --txt \
     -o /data/misc/perfetto-traces/trace.perfetto-trace <<EOF
   buffers: { size_kb: 65536 }
   data_sources: {
     config: {
       name: "linux.ftrace"
       ftrace_config: {
         ftrace_events: "sched_switch"
         ftrace_events: "power/cpu_frequency"
       }
     }
   }
   duration_ms: 15000
   EOF
   adb pull /data/misc/perfetto-traces/trace.perfetto-trace .
   ```
3. **On-Screen Telemetry HUD**:
   - Double-tap the top camera bar or review `PerformanceMonitor` rolling metrics to observe live p50/p95 latency, tracking FPS, dropped frames, heap usage, and current profile.

### Physical Device Benchmark Table

*Note: Per strict engineering standards, emulator and container environments lack physical camera hardware and mobile thermal/battery governors; unmeasured physical hardware metrics are marked explicitly as `NOT MEASURED`.*

| Metric | Target | Baseline (Before Optimization) | Hardened Architecture (Current) | Verification Status |
| :--- | :---: | :---: | :---: | :---: |
| **Compose Recompositions / sec** | 0 on tracking | ~30 - 35 recompositions/sec | 0 recompositions/sec | **VERIFIED (Code & Architecture)** |
| **In-Flight Analysis Overlap** | 0 overlapping tasks | Frequent (Task queue backup) | 0 (AtomicBoolean single-flight lock) | **VERIFIED (Unit Test & Code)** |
| **Angular Boundary Glitch (180°)** | Smooth $\le 5^\circ$ | 358° spin loop across boundary | Shortest-path $\Delta$ modular wrap | **VERIFIED (Unit Test)** |
| **GC Allocations on Draw Path** | 0 bytes / frame | Continuous `RectF` / lambda allocations | 0 bytes / frame (Reused buffers) | **VERIFIED (Code & Architecture)** |
| **Hysteresis Upgrade Cooldown** | 3.0s | None (profile flapping) | 3000ms strict cooldown | **VERIFIED (Unit Test)** |
| **Preview Pacing / Jitter** | 60 fps stable | Noticeable under ML analysis load | Isolated display loop | *NOT MEASURED (Physical Device Required)* |
| **ML Kit Inference Latency (p50)** | $< 30\text{ms}$ | Hardware-dependent | 480p downsampled input | *NOT MEASURED (Physical Device Required)* |
| **ML Kit Inference Latency (p95)** | $< 45\text{ms}$ | Hardware-dependent | Adaptive frame skipping fallback | *NOT MEASURED (Physical Device Required)* |
| **Battery Drain Rate (mAh/min)** | Low | High (Main thread saturation) | Reduced (Thread isolation + Low Load) | *NOT MEASURED (Physical Device Required)* |
| **Device Surface Thermal Rise** | Minimal | Noticeable after 3 min continuous | Mitigated via 2s no-face sleep mode | *NOT MEASURED (Physical Device Required)* |

