# POLI-based Scan Merging for MoGe3DScanner — Design

Date: 2026-07-13
Status: Approved for planning
Target app: `MoGe3DScanner/` (native Kotlin, Jetpack Compose, CameraX, OpenGL ES 2.0)

## Problem

MoGe3DScanner already saves individual scans (`moge_scan_<timestamp>.glb`) to the
device's public Downloads folder via `MediaStore`, and its existing "Multi Mode"
does live rotation-only panorama stitching during capture
(`P_aligned = R0ᵀ × Ri × Pi`, from stored sensor rotation vectors). There is no
way to go back and merge several *already-saved* scans that were taken from
different positions (not just different angles in place) — that requires real
point-to-point registration, which rotation-only stitching cannot do, and no
per-scan pose is persisted in the GLB to seed it anyway (only optional GPS
lat/long in `asset.extras`).

This feature adds a **post-hoc merge**: pick 2+ previously-saved GLBs, align
them via a ported subset of the POLI (Point-to-Ellipsoid, RSS 2026) pipeline,
and write a new combined GLB, without touching the existing capture flow.

## What POLI actually contributes (and what it doesn't)

POLI is not a registration algorithm — it's a per-point covariance predictor
(PointNet++ encoder/decoder outputting a 3×3 Cholesky factor per point, trained
self-supervised via Mahalanobis correspondence loss) that plugs into *classical*
registration as a better uncertainty weight than local-PCA covariance. The
actual alignment math — FPFH, ROBIN, GNC, GICP — is unchanged classical code
that POLI's outputs feed into.

Confirmed by reading POLI's inference code directly (not assumed): the
`sdprlayer` SDP solver in POLI's `environment.yml` is a **training-only**
dependency — none of the inference apps (`POLI_FPFH_ROBIN_GNC.py`,
`registration_utils.py`) import it. At inference:
- ROBIN's `maximum_consensus` builds a pairwise-distance compatibility graph
  and extracts inliers via **max-core** (polynomial-time k-core decomposition,
  not full max-clique).
- GNC (`gnc_registration`, "tls" or "gm" variant) is **iteratively reweighted
  weighted Horn's method** — each iteration re-solves rotation+translation in
  closed form via a 3×3 SVD (Kabsch/Umeyama), reweighted by a robust-loss
  schedule.

So the Kotlin port needs: FPFH descriptor computation, a compatibility-graph +
max-core inlier filter, and an IRLS loop around a 3×3-SVD closed-form solve.
**No SDP solver port is required.**

## Domain mismatch (accepted tradeoff for v1)

POLI's pretrained checkpoints (`weights/HeLiPR/vlp_helipr_0.2m.pth`, etc.) were
trained on sparse Velodyne/Ouster LiDAR spacing. MoGe3DScanner's points are
dense monocular-depth output (up to 150k points/scan). The nearest-matching
checkpoint (`vlp_helipr_0.2m`, closest point spacing) will be used as-is for
v1; no retraining. If merge quality proves poor in practice, retraining POLI
on MoGe's own point distribution is a follow-up, out of scope here.

## Scope decisions from brainstorming

| Decision | Choice |
|---|---|
| App framework | Stays native Kotlin/Compose (no Flutter — confirmed no `pubspec.yaml` exists; "flutter" in the original ask was a misnomer) |
| POLI weights | Pretrained `vlp_helipr_0.2m` as-is, converted to TFLite. No retraining in v1. |
| Background execution | Kotlin coroutine via `viewModelScope`, no foreground service/notification. Merge is lost if the app is killed mid-job; user must stay in the app. |
| Registration approach | Full FPFH + ROBIN + GNC coarse alignment (no pose-prior dependency — necessary, since no rotation/pose metadata is persisted per GLB) + POLI-weighted GICP refinement |
| Gesture model | Port `model-viewer`'s `SmoothControls.ts` math (damped spherical orbit, pinch-zoom, 2-finger pan, tap-to-focus) natively into a new `SphericalCameraController.kt`, scoped only to the new merge-preview screen. Existing `MainScreen.kt`/`GLPointRenderer.kt` capture-preview gestures (simpler turntable spin/tilt) are untouched. |
| Output | Non-destructive: write new `merged_<timestamp>.glb` to Downloads via `MediaStore`. Original scans are kept. Auto-opens in the new preview screen. |

## Corrections to the original design sketch (from reading real source)

1. **No app-private scans folder exists.** All scans are written to the public
   Downloads collection via `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with
   `DISPLAY_NAME = "moge_scan_<timestamp>.glb"`, `MIME_TYPE =
   "model/gltf-binary"`. The merge screen's file list will **query MediaStore**
   for GLBs the app itself inserted (filter by MIME type + display-name
   prefix `moge_scan_`), not list a directory. Under scoped storage, an app can
   always read back `MediaStore` entries it created itself — no
   `READ_EXTERNAL_STORAGE`/`READ_MEDIA_*` permission needed, preserving the
   "no extra permission dialogs" goal from brainstorming.
2. **No GLB reader/parser exists yet** — only `exportGlb()` (write) in
   `MainScreen.kt`. A new `GlbReader.kt` is needed to invert it.
3. **Confirmed exact GLB layout to parse** (from `exportGlb()`,
   `MainScreen.kt:895-994`): mesh `mode 0` (POINTS), `POSITION` accessor
   (component type 5126 = float32, VEC3) and `COLOR_0` accessor (float32 VEC3,
   0–1 range, no alpha), stored as two contiguous blocks in one BIN chunk
   (all positions, then all colors — not interleaved), standard glTF 4-byte
   chunk padding. No normals, no indices. `asset.extras.gps_latitude`/
   `gps_longitude` present only if GPS was available at capture.

## New components

All under `com.example.moge3dscanner.ui.merge` (mirrors the existing
`ui.main` package), following `MogeInterpreter.kt`'s TFLite wrapper pattern
(GPU delegate with CPU/XNNPACK fallback, direct NIO `ByteBuffer`s, model
loaded from `assets/`):

```
ui/merge/
├── GlbReader.kt              # GLB bytes -> (positions: FloatArray, colors: FloatArray, gps?) — inverse of exportGlb()
├── PoliInterpreter.kt        # TFLite: points -> per-point Cholesky 6-vec -> Σ (3x3 covariance), modeled on MogeInterpreter.kt
├── FpfhFeatures.kt           # FPFH descriptor computation, normals derived from POLI covariances
├── RobinConsensus.kt         # pairwise-distance compatibility graph + max-core inlier extraction
├── GncSolver.kt              # GNC-TLS/GM: IRLS with weighted Horn's method (3x3 SVD closed-form Kabsch/Umeyama)
├── GicpRefiner.kt            # POLI-covariance-weighted Generalized-ICP, refines GNC's coarse pose
├── ScanMerger.kt             # orchestrates N-way merge: pairwise coarse+refine, pose chaining, point concatenation, writes merged.glb
├── MergeViewModel.kt         # new ViewModel; viewModelScope coroutine; StateFlow<MergeUiState>
├── MergeScreen.kt            # Compose: MediaStore-backed checklist, "Merge Selected" button, progress state
└── SphericalCameraController.kt  # ported model-viewer gesture math; used only by the merge-preview renderer
```

`MainScreen.kt` gets one addition: a toggle/button entering `MergeScreen`,
navigated via the existing `Navigation.kt`/`NavigationKeys.kt` routing.

## Data flow

1. User taps "Merge" toggle on the main screen → navigates to `MergeScreen`.
2. `MergeViewModel` queries `MediaStore` for the app's own `moge_scan_*.glb`
   entries, displays as a checklist.
3. User multi-selects 2+ scans, taps "Merge Selected".
4. `viewModelScope.launch(Dispatchers.Default)`:
   a. `GlbReader` decodes each selected GLB to `(positions, colors)`.
   b. `PoliInterpreter` predicts per-point covariance for each scan.
   c. For each adjacent pair, chained in ascending capture-timestamp order
      (parsed from each file's `moge_scan_<timestamp>.glb` name — not UI
      click/selection order, which is undefined once multiple items are
      checked): `FpfhFeatures` (using
      POLI-derived normals) → `RobinConsensus` (prune correspondence
      outliers) → `GncSolver` (coarse pose) → `GicpRefiner` (POLI-weighted
      GICP refinement) → chain the resulting transform onto the running base
      pose.
   d. All scans transformed into scan[0]'s frame, points concatenated.
   e. Result serialized via a new `writeGlb()` (reuse `exportGlb`'s binary
      logic) to `merged_<timestamp>.glb` in Downloads via `MediaStore`.
5. On success, `MergeViewModel` emits the new URI; `MergeScreen` navigates to
   a preview route rendering it through `GLPointRenderer` wired to the new
   `SphericalCameraController` (damped orbit/pinch/pan, tap-to-focus).

## Error handling

If GNC or GICP fails to converge for a given pair (e.g., insufficient overlap
between two scans), that pair is skipped: a Snackbar names the skipped file,
and the merge continues chaining the remaining pairs. The batch never fails
as a whole because of one bad pair. If registration fails for *every* pair
(no two scans overlap at all), the merge is reported as failed with no file
written.

## Risks

- **PointNet++ → TFLite conversion.** Unlike MoGe's fixed-size 518×518 dense
  grid (a straightforward CNN/ViT conversion), POLI's `pointnetpp_scene.py`
  uses Set Abstraction layers with farthest-point sampling and ball-query
  grouping — dynamic-shape, data-dependent ops that commonly fail standard
  TFLite conversion (this is why the original PyTorch implementation ships
  custom CUDA ops via `pointnet2_ops`). This is the single largest technical
  risk in the plan. If direct conversion fails, fallback options (ONNX Runtime
  Mobile, or a hand-written fixed-topology re-implementation of just the
  layers actually needed) must be evaluated before the rest of the pipeline
  can be built on top of it.
- **No existing reference implementation in Kotlin** for FPFH/ROBIN/GNC —
  correctness can only be checked by numeric parity against POLI's own Python
  code on shared synthetic inputs (see Testing).
- **Unproven merge quality on dense monocular point clouds** — mitigated by
  the non-destructive output (originals kept) and per-pair skip-on-failure
  behavior already specified above.

## Testing

- Unit tests for `RobinConsensus` and `GncSolver`: run the same synthetic
  point-set pairs through both the Kotlin port and POLI's Python reference
  (`registration_utils.py`), assert numeric parity on inlier sets and
  estimated pose within tolerance.
- Manual on-device verification: capture several real overlapping scans,
  merge them, visually inspect alignment quality in the new preview screen.
- No claim of merge correctness ships without this manual on-device check —
  unit parity tests alone don't validate real-world point cloud behavior.
