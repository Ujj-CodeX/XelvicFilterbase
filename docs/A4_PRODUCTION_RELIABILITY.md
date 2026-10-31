# A4 — production reliability hardening

## Working-tree baseline and scope

- Branch: `research/face-mesh-texture-occlusion`.
- HEAD inspected before implementation: `7234bca5d55ba1c705a975bdc938d0fc1a2612ec`.
- The starting tree was dirty: A3 rotation tests/fixture and partial preview/session lifecycle fixes were already present. They were preserved, not reset or replaced.
- `CameraXPreviewTransformFixture.kt`, `CameraXImageProxyTransformRotationTest.kt`, `MediaPipeCoordinateTransformTest.kt`, `CaptureCropTransformTest.kt` and `FaceGeometryDisplayTransformTest.kt` are byte-identical to the pre-task versions (SHA-256 checked).
- `CameraPreview.kt`'s existing Compose update hook and `FaceMeshGlFrameBuilder.kt`'s existing retired-frame check were retained without additional changes in this task.
- No dependencies, topology/UV changes, new runtime/coordinator, photo-processing changes, commits or pushes.

## Exact files changed during A4

Production (all below `app/src/main/java/com/example/xelvic/`):

- `camera/CameraController.kt`
- `geometry/FaceGeometrySnapshotHolder.kt`
- `geometry/MediaPipeFaceGeometryProvider.kt`
- `perf/DiagnosticRateLimiter.kt` (new)
- `renderer/FaceMeshGlOverlayView.kt`
- `ui/CameraScreen.kt`
- `ui/FaceMeshGlTextureOverlay.kt`

Tests (all below `app/src/test/java/com/example/xelvic/`):

- `camera/CameraPreviewConfigurationTest.kt` (extended pre-existing untracked test)
- `geometry/GeometrySessionPublicationTest.kt` (extended pre-existing untracked test)
- `geometry/MediaPipeInferenceWatchdogTest.kt` (new)
- `perf/DiagnosticRateLimiterTest.kt` (new)
- `renderer/FaceMeshGlLifecycleTest.kt` (new)

Documentation: `docs/A4_PRODUCTION_RELIABILITY.md` (this file, new).

Other dirty files shown by Git pre-date this task and were preserved. In particular, `CameraPreview.kt` and `FaceMeshGlFrameBuilder.kt` were reviewed/retained unchanged relative to the pre-task tree, although both differ from HEAD.

## Evidence and fixes

### Preview freshness / resize

CameraX **1.5.0**, as shipped in the cached dependency source JARs:

- `PreviewView.getOutputTransform()` requires the main thread and calculates from its current transformation metadata/layout. It can retain metadata while a replacement request is being established.
- `PreviewView` owns `SurfaceRequest`'s **single** transformation-info listener. Replacing that listener would break CameraX's preview updates.
- Transformation updates redraw/transform its child view; a main-thread pre-draw observer detects changed matrices without reading PreviewView on the analyzer thread or polling on each analysis frame.
- `PreviewView.getViewPort()` uses width/height **aspect ratio**, display rotation, layout direction and scale type. All `FIT_*` types map to `ViewPort.FIT`. Pixel-size-only proportional resizing does not require a new ViewPort.
- Changing implementation mode requires setting Preview's SurfaceProvider again, not rebinding unchanged analysis/capture use cases.
- `ImageAnalysis.setTargetRotation()` changes `ImageInfo.rotationDegrees`; use-case defaults alone do not update every display-rotation change.

The controller now invalidates geometry before configuration/transform replacement, compares both matrix values and viewport-size metadata, copies CameraX's mutable Matrix into a read-only volatile snapshot, and captures the holder token **before** the cached target. The target is nulled before token retirement, so an old matrix cannot gain new-session publication authority.

A binding generation rejects old queued analysis frames/surface requests. A surface-request/main-executor post fence prevents recaching retained old PreviewView metadata before the current request has been applied. CameraX's listener is left intact. Layout/display/pre-draw/attach events refresh or invalidate; unchanged events do not rebind. Actual aspect/rotation/crop configuration changes rebuild the shared `UseCaseGroup` with the matching target rotation on Preview, ImageAnalysis and ImageCapture. Proportional resize only refreshes mapping; implementation-mode changes refresh only Preview's SurfaceProvider.

The attached ViewTreeObserver is tracked explicitly and removed on detach/release; the floating pre-attach observer is not used as a cleanup handle after attachment. Preview detach retires geometry before AndroidView's later release callback.

**A3 contract unchanged:** `setUsingCropRect(false)`, `setUsingRotationDegrees(true)`, physical upright bitmap rotation, no manual landmark rotation, added mirroring or coordinate sign changes.

### Atomic geometry publication

The sole frame-publication authority is `FaceGeometrySnapshotHolder`'s `GeometrySessionToken`. `publishIfCurrent`, `beginSession` and conditional `beginSessionIfCurrent` share an atomic state/CAS ordering point. Concurrent retirements cannot install an earlier generation after a later one; delayed retirement cannot clear a replacement session. Snapshot, timestamp and exact display transform are validated/published together. Older no-face results cannot erase newer geometry.

The controller's numeric generation is explicitly only a provider-initialization/error lifecycle guard, not publication authority; the provider's separate result-generation mechanism was removed. A lifecycle pre-check is only an optimization: the holder CAS always rejects retired tokens. External listeners run outside locks, and one failed Canvas listener cannot prevent GL/tracking listeners. Tracking bookkeeping reads the current holder on the main thread rather than replaying a captured callback boolean.

### Inference watchdog / ownership

`INFERENCE_COMPLETION_TIMEOUT_MS = 3_000L` is a named safety policy: 12 times the renderer's 250ms freshness cutoff, allowing a generous startup/scheduling/CPU margin without leaving the one-in-flight gate closed forever. It is **not** a measured device latency claim.

The watchdog captures the exact pending-frame object, timestamp and geometry token. Completion/timeout/shutdown have a state-lock ordering point; stale/duplicate callbacks and cancelled old timers cannot consume a newer pending input. Timeout permanently retires that task and reports through the existing controller error fallback to ML Kit; it does not reopen the gate on the same landmarker.

MediaPipe **1.1.1** packaged bytecode was inspected (`javap -c -p`):

- `BaseVisionTaskApi.close()` delegates to `TaskRunner.close()`.
- `TaskRunner.close()` closes packet sources, calls `Graph.waitUntilGraphDone()`, and tears down the graph. There is no bounded shutdown timeout.
- Shutdown failures can throw/report through error callbacks. A failed shutdown is not treated as a safe input-release fence.
- `MPImage.close()` decrements its reference count; the bitmap-backed container recycles its bitmap at zero.
- `FaceLandmarker$1.convertToTaskInput()` creates a **separate new bitmap-backed MPImage** for the result callback; `OutputHandler.run()` does not close it. The provider now closes that callback image in `finally`, including late/mismatched results.

Native shutdown runs on a provider-owned background thread, not CameraX's analyzer executor or the UI thread. Input release requires both submission returning and exact completion or successful native shutdown. Error/timeout alone does not recycle an input still potentially used by native code. The atomic release guard protects MPImage/bitmap cleanup from timeout/completion/shutdown races and synchronous callbacks. At most one retired task may drain per controller; mesh re-entry is rejected until shutdown is confirmed, preventing repeated creation of stuck graphs.

**Remaining limitation:** a native close, detect submission, or provider initialization that itself never returns cannot be forcibly interrupted safely through the public FaceLandmarker API. The completion watchdog detects a missing result and restores legacy analysis when the camera executor is responsive, but does not guarantee bounded native reclamation or automatic mesh recreation. A permanently stuck task retains its one input rather than risking use-after-recycle; recovery may require process restart. Watchdog execution also depends on OS scheduling (not a hard real-time guarantee).

### Runtime diagnostics

Debug builds only, preserving `CameraController`, `MediaPipeFaceGeometry` and `FaceMeshGlOverlay` tags and the existing bug-report UI:

- Actual ImageProxy dimensions/crop/rotation, even when the analyzer rejects a frame for unavailable transforms.
- Actual upright inference bitmap size; zero means inference was not submitted for that sampled input.
- Transform readiness, configuration generation and frame's geometry-session generation.
- Holder snapshot timestamp/age, inference gate, dropped-frame count, watchdog status and native shutdown completion.
- Actual GL `onSurfaceChanged` dimensions versus PreviewView dimensions and both window bounds.
- Actual resolver block reason when GL is `NOT_MOUNTED`, instead of an old renderer backend reason.

Metadata is immutable/atomic; low-frequency UI sampling runs only while the debug panel is visible (500ms). Logs are throttled to at most once per second per tag source. Diagnostic samples/booleans do not select a rendering backend or decide whether geometry can draw.

### GL lifecycle

Concrete fixes only:

- Holder detach/replacement and callback handoff share a local ordering point, rejecting an old callback even if it passed its earlier subscription check.
- Diagnostic posts are main-thread delivered and listener/lifetime-generation fenced; queued posts cannot update a released/replaced view. Listener exceptions cannot abort GL rendering.
- An overlay mounted below RESUMED is paused. Lifecycle-owner replacement pauses/resumes the same view without permanently clearing its holder/effect/listeners. Final AndroidView release is separate and idempotent.
- Transient window detach drops frame/subscription and reattach resubscribes, preserves selected caller-owned texture data, and reapplies host pause state to GLSurfaceView's new thread.
- Final release drops strong selections/listeners and disables EGL-context preservation. A dirty draw is **not** falsely claimed to run before pause/detach. GLSurfaceView owns context destruction, which releases programs/textures; effect clearing while a context is active deletes its texture exactly once.
- Context recreation forgets old IDs/attribute handles/upload attempts and retries the same effect's upload; old IDs are never deleted in the new context.

The transparent RGBA GLES overlay, media-overlay Z order and normal `PreviewView.ImplementationMode.PERFORMANCE` composition are unchanged. No forced COMPATIBLE mode/device workaround was introduced.

## Tests and validation boundary

Pre-task full suite: **124 tests, 0 failures**.

New/extended deterministic tests:

| File | Current tests | Added during A4 | Coverage |
| --- | ---: | ---: | --- |
| `camera/CameraPreviewConfigurationTest.kt` | 23 | 18 | Resize/invalidation/no-op events; shared ViewPort and target rotations; proportional resize; surface-only mode changes; posted/stale requests; attach observer cleanup; camera switch/re-entry; main-thread cache; fallback/shutdown gating; current-state tracking/age diagnostics |
| `geometry/GeometrySessionPublicationTest.kt` | 12 | 5 | Latch-controlled check/publication gap and listener completion; concurrent CAS retirement; stale retirement; exact session/timestamp pairing; listener isolation |
| `geometry/MediaPipeInferenceWatchdogTest.kt` | 19 | 19 | Manual timer; completion/timeout/shutdown/close-error races; exact-once resource release; synchronous submission callbacks; stuck close; retired results; callback MPImage ownership; unavailable-transform diagnostics |
| `perf/DiagnosticRateLimiterTest.kt` | 2 | 2 | Interval/clock rollback and barrier-controlled concurrent throttling |
| `renderer/FaceMeshGlLifecycleTest.kt` | 10 | 10 | Detached callback/publication and diagnostics races; pause/final release/transient reattach; surface dimensions; context reset/texture re-upload; texture deletion and retired-frame rejection |

Targeted tests were run and diffs checked after each phase. Tests replace only native task/timer/GL or camera-hardware boundaries; CameraX transform calculations and provider bitmap/MPImage ownership run as production code. The GL tests use shadowed IDs/limits and therefore verify lifecycle/control flow, **not** a GPU driver.

Final acceptance commands: `:app:testDebugUnitTest` **passed: 178 tests across 25 classes, 0 failures, 0 errors, 0 skipped** (54 additional tests versus baseline); `git diff --check` **passed**. No physical-device test was performed.

## Required physical-device checks

1. Front/rear at 0/90/180/270 display rotations, including 180-degree changes without Activity recreation; verify Preview/analysis crop agreement and fresh transform generation.
2. Resize in place (split-screen/window resizing), including proportional and aspect-changing sizes; confirm no old mesh remains over the new viewport and no repeated binds for unchanged events.
3. Rapid camera switching, review-screen detach/re-entry, app background/resume and lifecycle-owner recreation during inference.
4. Compare PreviewView/GL actual dimensions and window bounds. Validate transparent overlay, Compose controls, SurfaceView Z ordering and premultiplied alpha on physical GPU/OEM devices. CameraX's PERFORMANCE stream-state signal is not a guarantee that preview pixels are already visible.
5. Exercise device stalls/cold-start/thermal throttling: watchdog fallback, responsiveness of legacy Canvas tracking, eventual native shutdown and safe manual mesh re-entry. The 3s policy may require evidence-based tuning.
6. Validate context loss/recreation and texture re-upload on a real GLES driver. Robolectric success is not proof of SurfaceFlinger composition, native MediaPipe execution or GPU resource reclamation timing.

CameraX documents that PERFORMANCE output transforms may not respect a non-identity app-applied View matrix; this screen does not add such transforms. Do not force COMPATIBLE without device evidence. Future device-specific composition corrections require separate evidence.
