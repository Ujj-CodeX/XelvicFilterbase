# Xelvic Camera Engine

**Xelvic** is a native Android camera and face-effects engine. The existing baseline uses CameraX, on-device ML Kit Face Detection, a data-driven `.xfilter` package format, and a Canvas-based 2D sticker renderer. The dedicated research direction is a **mesh-first face-effects engine**: persistent face tracking, UV-mapped 2D textures on a live 3D face surface, GPU rendering, and depth-based face occlusion.

> **Status notice:** This README separates the production baseline from Mesh Engine R&D. R0.2 adds the MediaPipe provider/geometry contract; R1 adds opt-in CameraController routing, per-frame CameraX coordinate mapping, a wireframe diagnostic overlay, and topology tests. Normal startup still uses ML Kit. The MediaPipe route requires `app/src/main/assets/face_landmarker.task` and has not yet been verified on-device. GPU texture mapping, compatible UV data, occlusion, and physical-device performance remain unvalidated.

## Goals

- Keep the production baseline isolated while research is developed on a dedicated branch.
- Use a persistent tracking session: switching an effect must not restart the camera or face tracker.
- Reuse static mesh topology, UV data, validated assets, and cached textures; update live vertex positions as tracking results arrive.
- Render 2D PNG/WebP artwork as a texture on the tracked face surface—not only as a flat rectangle positioned at a few landmarks.
- Establish a correct coordinate/projection contract across camera input, tracked geometry, preview, GL rendering, and still-photo output.
- Design for future depth-based face occlusion and, separately, hand/scene occlusion.
- Measure latency, jitter, memory, frame drops, texture-switch hitches, and thermal behaviour on physical devices.
- Keep face images, landmarks, blendshapes, and mesh data on-device by default. Do not upload or persist face data unless a separately reviewed feature explicitly requires it.

## Technology Baseline

The current repository version catalog/configuration lists the following baseline versions. Verify the checked-out files before changing versions; do not upgrade dependencies as an incidental part of the mesh implementation.

| Component | Version / setting in the current repository |
|---|---|
| Android Gradle Plugin | `9.1.1` |
| Kotlin | `2.2.10` |
| CameraX core / camera2 / lifecycle / view | `1.5.0` |
| ML Kit Face Detection | `16.1.7` |
| `compileSdk` | API 36 (minor API level 1 in the current Gradle configuration) |
| `minSdk` | API 24 (Android 7.0) |
| Application ID | `com.aistudio.xelvic.krvpmz` |
| Namespace | `com.example` |

The production baseline remains ML Kit and is unchanged. The R&D branch pins `com.google.mediapipe:tasks-vision:1.1.1` and includes an opt-in `CameraController` route to `MediaPipeFaceGeometryProvider` for diagnostics; normal startup still uses ML Kit. The model asset itself is not committed. Place `face_landmarker.task` in `app/src/main/assets/` using the instructions in that directory.

## Current Baseline vs. Mesh Engine R&D

| Area | Existing baseline (repository code) | Mesh Engine R&D target |
|---|---|---|
| Camera | CameraX `Preview`, `ImageAnalysis`, and `ImageCapture` | Keep CameraX input; decide preview/GL composition after a focused technical spike |
| Face tracking | ML Kit Face Detection with sparse landmarks and pose angles | MediaPipe Face Landmarker adapter producing dense 3D landmarks and optional blendshapes/transformation matrix |
| Face representation | `FaceTrackingResult` with bounds, named `PointF` landmarks, roll/yaw, timestamp | Immutable timestamped geometry snapshot with vertices, validated topology/UV reference, pose, optional blendshapes, and tracking status |
| 2D effects | Generic Canvas `StickerRenderer`; PNG/WebP layers anchored to face points | GPU mesh-texture renderer for face-surface effects; retain Canvas for legacy/unsupported effects |
| Filter package | `.xfilter` schema v1 | Backward-compatible schema extension after texture/mesh contract is proven |
| Occlusion | No general mesh depth-occlusion pipeline is assumed | Face depth pre-pass first; hand/scene occlusion as a separate later project |
| Performance | Existing frame dropping/adaptive analysis controls and bitmap caches | Reuse persistent tracking state, static geometry, textures and GPU buffers; profile before choosing optimizations |
| Research algorithms | Not present just because they appear in this README | Optical flow and learned dynamic rigidity are separate research items, not automatic MediaPipe features |

`main` is the production baseline. R&D work belongs on `research/face-mesh-texture-occlusion` and should be merged only after validation and review.

## Repository Architecture

### Existing live camera path

```text
CameraX
  ├─ Preview ───────────────────────────────────> PreviewView
  ├─ ImageAnalysis (KEEP_ONLY_LATEST)
  │    └─ FaceAnalyzer (background executor)
  │         └─ ML Kit Face Detection
  │              └─ CoordinateMapper
  │                   └─ FaceTrackingResult
  │                        └─ TrackingPoseHolder (latest tracking state)
  │                             └─ TrackingOverlayView (VSYNC invalidation)
  │                                  └─ StickerRenderer (Android Canvas)
  └─ ImageCapture
       └─ PhotoProcessor
            └─ MediaStoreSaver
                 └─ Pictures/Xelvic
```

The actual source should always be checked before assuming a particular lifecycle, viewport, cancellation, or transform fix exists in a newly cloned checkout. In particular, camera use-case crop alignment, transform access thread, and image-resource ownership must be verified in code and on a device.

### Intended Mesh Engine path

```text
CameraX frame input
  ├─ Native PreviewView path (baseline / fallback)
  └─ GL frame path (preferred only if a reusable, validated camera-to-GL path exists)
       │
       ▼
FaceGeometryProvider
  └─ MediaPipe Face Landmarker adapter (LIVE_STREAM for live input)
       │  timestamps; vertices/landmarks; optional blendshapes and pose matrix
       ▼
Immutable FaceGeometrySnapshot ──> latest-snapshot handoff
       │
       ├─ Coordinate + projection module
       │    sensor/buffer → oriented analysis image → normalized landmarks
       │    → canonical face space / tracked face space → camera projection
       │    → preview crop/display space or GL Normalized Device Coordinates (NDC)
       │
       └─ EffectDefinition + validated assets
            └─ Renderer capability/tier router
                 ├─ FaceMeshRenderer (primary R&D target)
                 │    ├─ static topology + UVs reused
                 │    ├─ live vertices updated from tracking
                 │    ├─ PNG/WebP texture sampling and mask/alpha handling
                 │    └─ optional face-depth pre-pass for virtual-effect occlusion
                 └─ Legacy Canvas StickerRenderer (compatibility fallback)
```

The GL composition approach is a decision gate, not an already completed implementation:

- **Path A — transparent GL surface above `PreviewView`:** simplest way to prototype mesh graphics while preserving CameraX's native preview. It can have some preview/overlay timing mismatch and does not provide a single pixel-processing scene for blur or colour-grade effects.
- **Path B — camera frame and effects in one GL scene:** potentially better synchronisation and a reusable path for pixel effects/video, but it adds camera-texture import, synchronisation, lifecycle, GPU-resource and device-compatibility work.

Investigate whether a proven camera-to-GL path can be reused. If not, start with Path A for wireframe/texture proof-of-concept, keep the renderer interface independent of the surface, and measure synchronisation before choosing a production composition path.

## Component Responsibilities

### Existing code

- **`MainActivity`** — Activity/Compose host, lifecycle, permissions and user-facing flow.
- **`CameraController`** — CameraX lifecycle binding, front/rear camera, flash, and photo capture.
- **`FaceAnalyzer`** — current ML Kit `ImageAnalysis.Analyzer`; protects against overlapping inference and closes every `ImageProxy` exactly once.
- **`CoordinateMapper`** — current face-coordinate mapping for the preview or captured-photo coordinate system.
- **`FaceTrackingResult`** — current sparse face bounds, named landmarks, orientation and timestamp.
- **`TrackingPoseHolder`** — keeps the latest tracking result available to the overlay.
- **`StickerRenderer`** — current Canvas renderer for generic `.xfilter` layers and per-layer smoothing.
- **`FilterPackageManager`** — imports/validates `.xfilter` archives.
- **`FilterRepository` / `InstalledFilter`** — active filter selection, installed filter metadata and decoded-asset access.
- **`PhotoProcessor`** — processes the captured photograph with the active effect.
- **`MediaStoreSaver`** — stores processed photos in the gallery.

### New R&D components (current implementation status)

- **`FaceGeometryProvider`** — interface separating the renderer from a specific tracking SDK.
- **`MediaPipeFaceGeometryProvider`** — MediaPipe implementation with explicit lifecycle and live-stream timestamp handling.
- **`FaceGeometrySnapshot`** — immutable snapshot of one coherent result; no arrays/maps should be mutated after publication.
- **`CoordinateProjection`** — owns all named-space transforms, mirroring rules, crop handling and GL projection assumptions.
- **`EffectDefinition`** — validated, renderer-independent effect description derived from a package manifest.
- **`OverlayRenderer`** — common renderer interface for Canvas and mesh-based backends.
- **`FaceMeshRenderer`** — GL-based mesh, UV texture, masks and depth pass.
- **`RendererTierRouter`** — chooses a compatible renderer for a filter and device capabilities; the mesh engine remains the target for mesh effects, while Canvas is a legacy/compatibility path.
- **`TextureCache`** — bounded LRU cache with background decode and GL-thread-only texture upload/deletion.
- **`ReplayHarness`** — local test-only frame/result playback for deterministic regression tests.

Names above are suggested architecture names; they should be aligned with existing conventions before adding files.

## Face Geometry, Pose, and Temporal Stability

### Do not rebuild the whole mesh on every frame

The topology (triangle index list), compatible UV layout, canonical reference data, and validated texture assets are static per supported model/effect. Cache these. For each accepted tracking result, update the live vertex positions and per-frame pose/expression state. Reuse GPU buffers where supported and update only the data that actually changed.

This does **not** mean freezing the face mesh: live vertices must continue to follow head motion and expressions. It means avoiding repeated allocation/reconstruction of immutable mesh data.

### Track continuously and switch effects independently

- Keep the camera session and face-tracking provider alive during ordinary filter changes.
- Replace the active `EffectDefinition` and required material/texture state without reinitialising the tracker.
- Preload/decode candidate textures off the UI/GL thread; upload them on the GL render thread.
- Cache static topology, UV layout and recently used textures under explicit memory limits.
- On a topology/model mismatch, reject the incompatible texture/effect or route to a compatible renderer; never silently use incorrect vertex indices.
- A lost face is different from a filter switch. Face-loss recovery may require relocalisation/re-detection, but selecting a new filter should not trigger it by itself.

### Smoothing, pose prediction, and expression handling

1. Keep strict monotonic timestamps and reject stale results before publishing them to the renderer.
2. Filter translation, rotation and scale as distinct quantities. Do not independently low-pass all 4×4 matrix entries; it can introduce invalid shear or non-rigid transformations.
3. Represent rotation appropriately (for example, a quaternion) and use spherical interpolation where suitable. Smooth local expression/blendshape values independently from rigid head pose.
4. If render-time prediction is added, use a measured, bounded prediction horizon and a validated timestamp clock contract. Do not compare camera timestamps to unrelated wall-clock timestamps.
5. Test expression motion separately from filter switching.

**Learned dynamic rigidity is a separate R&D item.** Snap's 2018 paper describes a region-based dynamic rigidity prior, rigid-pose/expression optimisation, landmarks and dense optical flow to reduce ambiguity between head rotation and facial deformation. MediaPipe does not automatically provide Snap's learned prior. First benchmark the built-in Face Landmarker temporal tracking, transformation matrix and blendshapes; investigate optical flow and learned rigidity only when measured pose instability remains and the extra compute/data/training cost is justified.

## Coordinate and Projection Contract (P0)

Coordinate bugs are a high-risk part of this project. Every geometry snapshot and renderer API must state which coordinate space it uses.

Required stages to document and validate:

1. Camera sensor / `ImageProxy` buffer pixels.
2. Rotation metadata and the oriented analysis-image space used by the tracker.
3. Tracker output normalised landmark coordinates (and the coordinate convention used for Z).
4. Canonical face geometry and its UV/topology version.
5. Runtime face geometry and the returned facial transformation matrix.
6. Camera projection: its assumptions, aspect ratio, units, handedness and matrix convention must match the geometry being rendered.
7. PreviewView crop/scale/rotation and layout mapping, or the single-scene GL projection.
8. GL clip space / NDC and final viewport mapping.
9. Front-camera mirroring: apply it in one explicitly owned stage, never twice.
10. Still-photo coordinate space: computed independently from the captured image and mapped to its final output dimensions.

Do not assume that a model's transformation matrix is automatically a correct projection matrix. Verify the canonical-to-runtime transform and the camera projection separately. Occlusion depends on consistent depth scale/direction and correct depth-buffer conventions; XY alignment alone is insufficient.

For CameraX, prefer a shared `ViewPort`/`UseCaseGroup` where the chosen use cases and lifecycle allow it, but verify actual API behaviour, `PreviewView` transforms and physical-device results. UI view properties must only be read from the correct thread; do not query `PreviewView.outputTransform` directly on the camera analysis executor.

## Mesh Topology and Texture Mapping

MediaPipe Face Landmarker currently documents 478 3D landmarks and can optionally return 52 blendshape scores and a facial transformation matrix. These are model outputs, not a guarantee that any arbitrary 478-index triangle list/UV map is valid. The R1 wireframe uses the current Tasks API's own `face_landmarks_connections.h` tessellation, which indexes the 468-point face surface (indices 0–467); the additional iris landmarks are not part of this face-surface tessellation.

Before mesh rendering:

- Select and pin a specific compatible model/task version for the R&D branch.
- R1 has pinned the Tasks API wireframe connectivity from `face_landmarks_connections.h`: 852 triangles / 1322 unique edges over indices 0–467. Unit tests validate index ranges, unique edge coverage, and expected counts. This validates the static connectivity asset, not physical-device alignment.
- Before texturing, still verify the UV coordinate table and vertex/UV correspondence against the exact Face Landmarker task model. The legacy `canonical_face_model.obj` is a separate legacy Face Geometry asset; its topology/UV correspondence must not be assumed compatible with newer Tasks API output.
- Iris indices 468–477 in the current 478-landmark result are rendered as points in R1 diagnostics but are intentionally excluded from the 468-point face-surface wireframe.
- Keep topology and UV data versioned and cached; update live vertex buffers per tracking result.
- Use a test texture/wireframe to validate every transform before enabling production effects.

### Renderer-specific geometry

- **Face-paint / skin texture:** canonical face UV mapping with region masks and live deforming surface vertices.
- **Local 2D decoration:** map onto a defined mesh region/patch or use a face-attached sprite/geometry; a random transparent PNG is not automatically a full-face UV texture.
- **Rigid 3D prop (hat, glasses frame, horns, etc.):** attach to validated face pose/anchors; use the proper model transform rather than deforming the whole model like skin.
- **Face occluder:** draw a live face surface to the depth buffer with a consistent projection/depth convention.

### Texture boundary requirements

- Define UV layout and region-mask semantics.
- Handle eye sockets, eyelids/iris, lips, and mouth interior to avoid texture spilling over eye whites or into the mouth when blinking/speaking.
- Support alpha correctly, including premultiplied-alpha conventions where the renderer uses them.
- Evaluate mipmaps/minification and edge feathering to reduce visible seams.
- Validate texture dimensions, decoded pixel count, file type and total package size before allocation/upload.
- Avoid unbounded decoded-bitmap and GPU-texture caches. Define an LRU budget and release GL resources on the GL thread.

## Effect Definition and `.xfilter` Compatibility

### Existing `.xfilter` schema v1

The current schema describes 2D layers with fields such as:

- `id`, `name`, `version`, `description`, `thumbnail`
- `layers[]`: `id`, `asset`, `anchor`, `scaleMode`, `scale`, `offsetX`, `offsetY`, `rotationMode`, `rotationDegrees`, `opacity`, and `zIndex`
- Current anchors include face center, forehead top, eye centre, left/right eyes, nose, mouth, and left/right cheek.
- Current scale modes include `FACE_WIDTH`, `FACE_HEIGHT`, and `EYE_DISTANCE`.
- Current rotation modes include `FACE_ROLL`, `EYE_LINE`, and `FIXED`.

Existing schema v1 packages should remain readable and continue to render through the Canvas compatibility renderer unless they explicitly meet the newer mesh-effect contract.

### Schema v2 extension (R2 preparation; GL renderer still blocked)

The R2-prep slice now parses and validates this schema-v2 metadata shape. It does not assert that a declared UV layout is compatible with the active model. The package installer deliberately blocks mesh packages until the pinned UV registry and live GL renderer are connected and validated.

```json
{
  "schemaVersion": 2,
  "id": "sample_face_paint",
  "name": "Sample Face Paint",
  "version": "1.0.0",
  "thumbnail": "thumbnail.png",
  "minimumRuntimeVersion": 2,
  "renderer": "FACE_MESH_TEXTURE",
  "meshTexture": {
    "asset": "assets/face_paint.png",
    "uvLayout": "pinned-canonical-face-layout-id",
    "blendMode": "NORMAL",
    "opacity": 1.0,
    "regionMask": "assets/face_region_mask.png"
  }
}
```

The future package-to-render flow should be:

```text
.xfilter archive
  → secure extraction to temporary directory
  → schema/version validation
  → asset path + file signature + dimension/pixel + package-budget validation
  → UV/topology/effect compatibility validation
  → immutable EffectDefinition
  → renderer capability/tier selection
  → background decode + GL-thread texture upload
  → active effect swap (tracker remains running)
```

Keep ZIP Slip/path traversal prevention, strict archive/extraction limits, allow-listed asset types, and transactional installation. If adding texture/image types or higher texture sizes, update the security limits/tests deliberately; do not weaken validation as a side effect of supporting meshes.

### Schema-v2 implementation status (R2 preparation)

The parser and package validator now define an additive `schemaVersion: 2` contract for `FACE_MESH_TEXTURE`, including the texture asset, a UV-layout identifier, `NORMAL` blend mode, bounded opacity, and optional region mask. The existing schema-v1 layer manifest still defaults to Canvas. Schema-v2 image assets are checked with the same canonical-path, image-signature, dimension and pixel-budget rules as legacy assets.

**Mesh packages are deliberately not installable yet.** The installation path fails closed after validation until a model-compatible UV registry and the actual GL renderer are connected to the live camera path. `uvLayout` is metadata only; the placeholder identifier in tests is not a validated UV asset, and no face texture rendering or occlusion is claimed by this task.

## Preview vs. Still Photo

### Live preview

- CameraX owns camera acquisition.
- Decide between native `PreviewView` plus transparent GL overlay and a single GL camera/effects scene using the composition decision gate above.
- Keep rendering asynchronous from UI recomposition and pass complete, immutable tracking snapshots to the render loop.
- Use the latest valid snapshot; drop stale frames rather than building an unbounded queue.

### Photo capture

Do not blindly reuse the preview pose/mesh as the authoritative geometry for a still. The shutter captures at a different time and resolution.

1. Capture the photo through `ImageCapture`.
2. Run a fresh still-image face inference using the image/still mode on a background worker. A downsampled copy may be used for inference, but retain the transform needed to map detected coordinates back to the full-resolution image.
3. Apply the same validated `EffectDefinition` and matching mesh/UV convention to the newly detected geometry.
4. Use an offscreen rendering path that is designed and tested for still images; if GL is used, define EGL/context sharing and resource lifecycle explicitly. A CPU fallback may be needed for incompatible devices or a simpler effect.
5. Define no-face behaviour: save without effect, ask to retake, or expose a controlled retry. Do not leave this unspecified.
6. Match camera crop/aspect and front-camera mirror policy deliberately; do not assume preview and capture have identical pixels solely because they share a lifecycle.
7. Bound photo bitmap and texture memory. A large RGBA texture can consume tens of megabytes; downsample/limit texture assets as appropriate and verify peak memory on real devices.

Preview and still rendering should share the same effect definition and geometry conventions, but they do not have to reuse the same per-frame tracking result.

## Threads and Resource Ownership

Thread ownership is part of the architecture, not an implementation afterthought.

| Work | Owner / rule |
|---|---|
| View lifecycle, `PreviewView` properties and UI state | Android main thread |
| CameraX `ImageAnalysis` delivery | Dedicated analysis executor; close every `ImageProxy` exactly once |
| MediaPipe live-stream inference | Dedicated/background execution as required by the SDK; supply valid monotonic frame timestamps |
| Result publication | Atomic reference or another bounded, safe latest-snapshot handoff; publish complete immutable snapshots |
| OpenGL calls, texture upload/deletion and mesh-buffer updates | GL render thread/context only |
| Image/texture decoding and archive validation | Background worker; do not block UI or GL thread |
| Photo inference and offscreen compositing | Background worker plus a correctly owned offscreen rendering context when required |

Additional rules:

- Avoid concurrent mutation of vertex arrays after publication.
- Preload/cache before switching where possible, but never perform texture upload on the UI thread.
- Handle activity pause/resume, camera unbind, surface recreation and GL context loss. GPU resources may need recreation after context loss.
- If offscreen photo rendering shares GL resources with preview, define and test EGL context sharing; do not assume it works automatically.
- Ensure the old effect's texture/mesh resources are released safely without invalidating work currently queued on the GL thread.

## Low-End Device Strategy

Low-end support must be measured throughout development rather than added at the end.

- Start with one face and a single tracking provider.
- Use MediaPipe's live-stream tracking behaviour and a latest-frame policy where appropriate; don't run ML Kit and MediaPipe on every frame simultaneously in the production path.
- Cache topology, UVs and textures; reuse buffers and avoid per-frame bitmap conversion/allocation where possible.
- Keep face analysis resolution independent of preview resolution and choose it using benchmark results.
- Consider reducing inference cadence, texture resolution, shader complexity and nonessential effects under load. Keep the camera display path responsive.
- Separate measurement of inference latency, render GPU time, frame age, dropped frames, memory and thermal behaviour.
- Treat RGBA conversion as a benchmark candidate, not an assumed optimisation: CameraX documents extra conversion overhead for `RGBA_8888` and `NV21` output compared with the default YUV path. Investigate `PRIVATE`/GPU-oriented buffers only if the CameraX version, device support and model input path make that feasible.
- Do not promise fixed FPS or zero latency before physical-device profiling.

Suggested reporting fields (no result should be marked verified until measured):

- device model, Android version, app/build variant, model asset version
- tracking inference p50/p95, render GPU p50/p95, result age at render
- dropped/stale frames, filter-switch latency, texture-upload duration
- peak Java/native/GPU memory, ANR/crash count, thermal status over a sustained run
- front/back camera, lighting condition, head pose and expression test case

## Occlusion Roadmap

1. **Face-surface depth occlusion:** render the live face mesh in a depth-only pass, then draw virtual effects using a consistent depth convention. This can hide virtual geometry behind the visible face surface where the tracked geometry represents it correctly.
2. **Expression/texture regions:** validate eyes, eyelids, lips and mouth masks; a face depth mesh alone does not provide perfect interior-mouth/eye segmentation.
3. **Hand/scene occlusion:** separate feature requiring hand or scene segmentation and/or usable depth information. A face mesh by itself cannot identify a hand crossing in front of the face.

Occlusion correctness depends on model topology, mesh projection, z mapping, depth-buffer conventions, and the chosen camera composition path. It must be tested with explicit edge cases and golden images.

## Research Notes: Snap-Inspired Ideas

Public Snap research describes real-time monocular face tracking techniques including a learned region-based dynamic rigidity prior, separate rigid-pose and expression optimisation, facial landmarks, and dense optical flow. These are useful research references, not claims that Xelvic or MediaPipe implements Snap's private tracker.

Recommended progression:

1. Validate built-in MediaPipe landmarks, transformation matrices, blendshapes and temporal behaviour.
2. Add pose decomposition and bounded smoothing/prediction with a validated timestamp contract.
3. Measure pose jitter during smiles, mouth open, blinks and large head turns.
4. If needed, investigate optical flow as additional motion evidence.
5. Treat learned dynamic rigidity as an advanced research project: it may require a suitable model, training/validation data and a custom optimisation loop. It is not a ready-made checkbox in the MediaPipe task.

**Expression-induced pose jitter and filter-switch jitter are different problems.** Dynamic rigidity targets ambiguity between non-rigid expression and rigid head motion. Fast filter switching instead requires persistent tracking, cached assets and effect replacement without resetting the tracker.

## Replay, Golden Images, and Privacy

### Replay harness

Maintain a test-only harness that can replay a fixed set of consented/local frames and/or timestamped geometry results. Use the same input sequence to compare changes to coordinate mapping, smoothing, mesh rendering and texture mapping. Keep test datasets out of production assets and do not commit private camera footage to the repository.

Golden-image tests should cover:

- frontal face, left/right yaw, roll, near/far position
- smile, mouth open, blink and eyebrow movement
- front-camera mirroring and rear camera
- crop/aspect ratio, device rotation and display sizes
- face loss/re-entry and rapid filter switching
- texture seams, eye/mouth masks, alpha and occlusion edges
- high-resolution photo output and no-face capture behaviour

### Privacy

- Process camera frames, landmarks, blendshapes and face geometry locally by default.
- Do not upload or persist raw frames/face geometry as telemetry.
- If a user consents to share a debugging clip, treat it as test data, store it separately and remove it according to the test policy.
- Production telemetry should be aggregate performance signals only, without raw images, landmarks or unique biometric profiles.

## Development Phases and Acceptance Gates

### R0 — Baseline and contracts

- Confirm clean checkout/build/tests on the R&D branch.
- Record the existing renderer and camera behaviour as baseline.
- Pin the MediaPipe task/model version for the experiment.
- Define `FaceGeometryProvider`, immutable snapshot contract, timestamp rules and topology/UV version.
- Document every coordinate space and decide how the camera image reaches the GL scene.

**Exit gate:** app builds, baseline is repeatable, and output coordinate spaces/topology are documented.

### R1 — Live geometry visualization

- Add MediaPipe behind a distinct R&D adapter or feature flag.
- Confirm 3D landmarks, optional matrix and blendshape outputs.
- Draw a mesh/wireframe with verified vertex ordering and projection.
- Test the camera-to-preview/GL transform chain; don't start texture warping until the wireframe is correct.

**Exit gate:** stable wireframe alignment on physical device for front/rear camera and representative rotations.

### R2 — PNG texture on the 3D face mesh

- Validate canonical mesh topology and UV coordinates against the chosen model.
- Add GPU texture mapping and explicit mask/blend/alpha semantics.
- Cache static mesh data and textures; update live vertices without rebuilding static topology.
- Switch effects without restarting the camera/tracker; profile texture preload and upload hitches.

**Exit gate:** texture remains attached across head motion/expression, no obvious UV topology mismatch, and resource usage is bounded.

### R3 — Face depth occlusion

- Add depth-only face pass with matching projection/depth convention.
- Validate virtual objects/effect surfaces that pass behind face regions.
- Add test coverage for nose, jaw and profile views.

**Exit gate:** repeatable depth occlusion under supported poses; known limitations documented.

### R4 — Photo parity, compatibility and device tuning

- Re-detect the face in captured stills, with resolution/coordinate remapping.
- Design offscreen rendering/GL context lifecycle or an explicit compatible CPU fallback.
- Test legacy `.xfilter` v1 through Canvas and mesh-capable effects through the mesh renderer.
- Profile low-end devices, cache limits, activity lifecycle and context loss.

**Exit gate:** preview/photo behaviour is consistent with the documented effect definition; all tests pass and device measurements are reported.

### Later — advanced tracking and video

- Evaluate optical flow and learned dynamic rigidity only if tracking diagnostics justify them.
- Investigate hand/scene occlusion separately from face occlusion.
- Add video recording only after frame synchronisation, rendering and resource lifecycle are stable.

## Build, Run, and Branch Workflow

### Requirements

- Android Studio compatible with the project's Android Gradle Plugin and Kotlin versions.
- Android SDK/platform and build-tools required by the Gradle configuration.
- Git for Windows.
- A physical Android device is strongly recommended for camera, timing, GPU, thermal and lifecycle validation.

### Clone the repository (Windows PowerShell)

```powershell
cd "$env:USERPROFILE\StudioProjects"
git clone https://github.com/Ujj-CodeX/XelvicFilterbase.git
cd XelvicFilterbase
```

If the repository already exists locally, do not clone into the same folder again; enter the existing project directory instead.

### Create/use the R&D branch

```powershell
git status
git fetch origin
git switch main
git pull --ff-only origin main
git switch -c research/face-mesh-texture-occlusion
```

If the branch already exists locally:

```powershell
git switch research/face-mesh-texture-occlusion
git status
```

Do not discard uncommitted changes blindly. If `git pull --ff-only` fails, inspect the branch and remote status rather than forcing a reset.

### Build and test

From the repository root:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Open the project in Android Studio, allow Gradle Sync to complete, then run on a connected physical device. Permission, signing, SDK and environment setup can differ after a Windows reinstallation; resolve setup errors separately from face-tracking/runtime crashes.

### Change workflow

1. Confirm branch and clean/understood working tree with `git status`.
2. Make one scoped change set.
3. Run affected tests, `testDebugUnitTest`, and `assembleDebug`.
4. Test the applicable behaviour on a physical device before claiming runtime validation.
5. Commit only intended source/tests/docs; do not commit local signing files, secrets, private `.env` values, generated APKs or personal camera footage.
6. Push the R&D branch only when ready to preserve/share it; do not push unreviewed work to production `main`.

Example commit:

```powershell
git add README.md app/src
git commit -m "docs: define mesh engine R&D architecture"
```

Review `git status` and the staged diff before committing.

## Physical-Device Verification Status

No new performance or accuracy result is claimed by this document. Fill this table from actual runs rather than estimating values.

| Metric | Target/criterion | Measured result | Status |
|---|---|---|---|
| Live wireframe alignment | Correct across supported poses and cameras | NOT MEASURED | Pending R1 |
| Texture stability while smiling/blinking | No obvious region slip/seam in test set | NOT MEASURED | Pending R2 |
| Filter-switch continuity | No tracker restart; bounded texture-switch hitch | NOT MEASURED | Pending R2 |
| Face depth occlusion | Correct depth ordering for test effects | NOT MEASURED | Pending R3 |
| Preview/photo geometry consistency | Same effect definition; separately detected still geometry | NOT MEASURED | Pending R4 |
| Inference/render p50 and p95 | Report per physical device | NOT MEASURED | Pending profiling |
| Peak memory and thermal behaviour | Within tested device budget | NOT MEASURED | Pending profiling |
| GL context loss/resume | Resources recreated without crash | NOT MEASURED | Pending lifecycle tests |

## References

- MediaPipe Face Landmarker for Android: <https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android>
- MediaPipe Face Landmarker overview: <https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker>
- MediaPipe Face Geometry source/proto reference: <https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/modules/face_geometry/protos/face_geometry.proto>
- ARCore canonical face mesh/UV asset authoring reference (related technology, not assumed to share the same topology): <https://developers.google.com/ar/develop/ios/augmented-faces/create-assets>
- CameraX image analysis and image format/backpressure guidance: <https://developer.android.com/media/camera/camerax/analyze>
- CameraX `ImageAnalysis.Builder` API: <https://developer.android.com/reference/androidx/camera/core/ImageAnalysis.Builder>
- Snap Research, *Stabilized Real-time Face Tracking via a Learned Dynamic Rigidity Prior* (SIGGRAPH Asia 2018): <https://research.snap.com/publications/stabilized-real-time-face-tracking-via-a-learned-dynamic-rigidity-prior.html>

---

**Project principle:** first prove geometry and coordinate correctness, then texture mapping, then occlusion, then advanced tracking and device tuning. Persistent tracking and static-resource reuse are requirements from the start; learned dynamic rigidity and other expensive research methods are introduced only when measurements justify them.


## R0.2 Implementation Status — Research Branch

Implemented on `research/face-mesh-texture-occlusion`:

- Pinned `com.google.mediapipe:tasks-vision:1.1.1` through the version catalog.
- Added `FaceGeometrySnapshot`, with defensive copies and validation for image dimensions, rotation, finite coordinates, blendshape scores and 4×4 matrix length.
- Added the `FaceGeometryProvider` abstraction and explicit result/error listeners.
- Added `MediaPipeFaceGeometryProvider` using CPU delegate and `LIVE_STREAM` mode. It converts CameraX `YUV_420_888` frames into owned ARGB bitmaps, applies image rotation before inference without mirroring the pixels, enforces monotonic timestamps, and permits one pending inference at a time to bound memory/latency.
- Added unit tests for snapshot validation and immutability.
- Added `app/src/main/assets/README.md` with the official model asset location.

R1 implementation now includes an opt-in Face button and diagnostic wireframe overlay. It swaps the single ImageAnalysis analyzer between MediaPipe and the existing ML Kit analyzer; the two trackers are not run simultaneously. Each submitted frame captures a CameraX `CoordinateTransform` from that ImageProxy to PreviewView, then carries the immutable 3×3 matrix with the asynchronous result so a later frame cannot accidentally supply its crop/mirror transform. If the exact transform is not available, the overlay falls back to the explicit `FILL_CENTER` approximation. If MediaPipe initialization or inference fails, the controller restores ML Kit and surfaces an error. The overlay draws the official Tasks API 468-point tessellation only when the snapshot's topology ID matches; it still shows all detected landmarks as points.

Still pending / unverified:

- The binary `face_landmarker.task` model must be downloaded to `app/src/main/assets/face_landmarker.task` locally. It is intentionally not included as a source-text file.
- Coordinate projection now prefers a per-frame CameraX `CoordinateTransform`; the `FILL_CENTER` + manual-mirror mapping is only a fallback. Exact alignment still needs physical-device validation with front/rear camera, rotation and ViewPort crop, especially under PreviewView's configured implementation mode.
- The app has not been built/tested against these new commits in the user's Android Studio environment during this change.
- Device-level image orientation, inference latency, visual coordinate correctness, thermal cost and memory use remain to be measured.
- The 468-point triangle connectivity is pinned from the official Tasks API source for the R1 wireframe. UV coordinates compatible with the pinned Face Landmarker model are still unresolved; do not use the legacy `canonical_face_model.obj` UVs blindly. MediaPipe has an issue noting that the legacy canonical OBJ can be incompatible with newer face-mesh vertex ordering: <https://github.com/google-ai-edge/mediapipe/issues/5760>. Do not render texture-mapped triangles until a model-compatible UV/topology asset is validated.


## Mesh Renderer Gate Hardening — Current Research Status

### UV asset loading contract (next R2 gate)

- `FaceMeshUvAssetParser` parses an app-owned UV JSON asset and computes its SHA-256 from the exact file bytes. The expected digest is supplied by `PinnedFaceMeshUvLayout`, an app-controlled allow-list entry, not by the `.xfilter` package.
- Every asset identifies the UV layout, the exact Face Landmarker task bundle SHA-256, topology ID, UV origin and exactly 468 finite normalized coordinates. Model, topology, vertex-count, digest, layout-ID and coordinate failures return explicit blocked results.
- `FaceMeshUvRegistry` loads only allow-listed app asset paths. Arbitrary filter package paths cannot register a UV layout. No production UV layout is registered by this slice because a model-compatible layout has not yet been verified.
- The renderer router now requires the effect's `uvLayout` to equal the validated map's `uvLayoutId`. A missing/unregistered layout remains unavailable; no shader draw, texture mapping or occlusion is claimed.

The legacy `canonical_face_model.obj` is not accepted on landmark count alone. MediaPipe's issue #5760 describes incompatibility between the legacy canonical OBJ and newer Face Landmarker ordering, and the maintainer response notes that the legacy geometry assets are no longer updated for the newer Task API: https://github.com/google-ai-edge/mediapipe/issues/5760. Use only a source whose point ordering/topology is verified against the pinned task bundle, then pin its exact SHA-256.

The R&D branch now makes model/UV compatibility an explicit, testable contract before a face-mesh texture backend can be selected.

- `MediaPipeFaceGeometryProvider` calculates the SHA-256 fingerprint of the exact local `face_landmarker.task` asset once at initialisation and attaches it to each immutable `FaceGeometrySnapshot`.
- `FaceMeshUvMap` accepts a UV table only when its topology identifier matches the pinned Tasks API tessellation, the UV table has exactly 468 entries, the active task-bundle fingerprint matches, and the UV asset fingerprint matches an independently pinned expected digest.
- `RenderBackendRouter` keeps legacy schema-v1 2D filters on Canvas. It selects the GL mesh backend only when a validated UV map and compatible device capabilities are supplied. Missing UVs, model/topology mismatches, unknown texture limits, unsupported GL, oversized textures or a missing required depth buffer fail closed instead of silently treating a face texture as a flat sticker.
- `FaceMeshUvMapTest` and `RenderBackendRouterTest` cover successful validation and mismatch/capability rejection paths.
- `.github/workflows/android-ci.yml` adds a GitHub Actions unit-test job for the research branch and pull requests into `main`.

**This is validation infrastructure, not a completed GPU renderer.** No compatible UV table or UV asset is bundled, no mesh texture is yet drawn in the preview, and no depth pre-pass has been implemented. The new router is not yet wired into filter-package manifests or `CameraScreen` because the schema/API that advertises and installs a mesh effect still needs a separately reviewed, backward-compatible extension. Until that and the physical-device coordinate checks pass, use the existing Canvas implementation for shipped filters.

The current implementation environment did not run Android Gradle tests or a physical-device session. The CI workflow is intended to provide automated unit-test evidence when GitHub Actions executes; until its run is observed as passing, this branch must not be described as build-verified.
