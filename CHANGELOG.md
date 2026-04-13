# Changelog

## v1.2.0

### New Features

#### Route Chunk Preloader
- New chunk preloading system along the railway route using Create's track graph
- Uses `getChunkFuture(FULL)` to load chunks into memory **without** force-loading (no entity-ticking)
- Dedicated `COT-RouteChunkPreloader` thread to avoid blocking the server tick
- ~256 block lookahead, maximum of 24 chunks per train

#### Directional Chunk Loading
- `ChunkMapMixin` filters new chunks sent to the client when the player is riding a train
- Lateral chunks outside the directional area are not loaded (front/back have priority)
- `DirectionalChunkShaper` tracks players on trains and calculates direction with EMA smoothing
- Compatible with Distant Horizons — DH uses its own LOD system independently

#### Player Proximity Buffer
- `updatePlayerProximityBuffer()` preloads chunks in the buffer zone (view distance + 1~3 chunks)
- Carriage entities are created and positioned BEFORE the player can see them
- Result: trains appear fully positioned with no ghost/stutter

#### Position Snap (Client)
- `CarriageEntityClientMixin` forces `xo=x, yo=y, zo=z` for the first 5 ticks after spawn
- Prevents visual interpolation/sliding when carriage entities are created

#### F3 Debug Overlay
- COT information on the F3 screen: memory, threads (pool/active/queued), TPS/MSPT/Peak, state/factor, FPS, chunks forced/route-cached
- JVM thread count cached every ~1s to prevent flickering
- Can be disabled via `DEBUG_OVERLAY_ENABLED` config

#### Safe Train Tick Protection
- `SafeTrainTickMixin` wraps `train.earlyTick()` in a try-catch inside `GlobalRailwayManager.tickTrains()`
- If an external mod (e.g. RailX) crashes during a train's tick, the train skips the tick instead of crashing the server
- Logging limited to 3 warnings per train to avoid spam
- Prevents crash loops that block worlds from loading

### Improvements

#### Chunk Load Manager
- Directional lookahead based on real train movement (deltaMovement + fallback to position delta)
- Adaptive lookahead capped at 6 chunks (entity-ticking is CPU-expensive)
- Side chunks (for curves) only for the 3 nearest chunks
- `getCarriagePosition()` uses `DimensionalCarriageEntity.positionAnchor` as fallback for carriages without entities
- Anti-thrashing with 12-chunk recent history

#### Render Optimization
- LOD-based render skipping by distance (>256 blocks = culling)
- Flywheel update skipping for distant trains (LOD > MEDIUM)
- Per-train visibility tracking for LOD decisions
- `getClientFPS()` cached every 30 frames

#### LOD System
- Buffer zone: trains approaching the player forced to LOD FULL
- Temporal hysteresis (500ms minimum between LOD changes) to prevent flip-flop
- Player position caching for thread-safe calculations
- Distances adjustable by the Performance Monitor

### Bug Fixes

#### Invisible Trains (Critical)
- **Cause:** Fade-out system marked all distant trains as "departing" on world load → `getDepartureScale()` returned -1 after 12 frames → renderer permanently skipped them
- **Fix:** Fade-out system completely removed. `getDepartureScale()` always returns 1.0f, `startFadeOut()` is a no-op, `isFadingOut()` returns false

#### Inflated Chunk Tracking (Critical)
- **Cause:** `trainChunks.put(trainId, needed)` stored ALL "needed" chunks even when the global cap (30) blocked force-loading → `getLoadedChunkCount()` inflated (reached 196) → cap blocked everything → trains without entity-ticking → movement stuttering
- **Fix:** `trainChunks` now only stores chunks **actually forced** on the server. Cap calculation uses exact count: `otherTrainsCount + actuallyForced.size()`

#### OOM from Forced Chunk Leak
- **Cause:** `RouteChunkPreloader` used `setChunkForced()` instead of just loading into memory → forced chunks accumulated without limit
- **Fix:** Replaced with `getChunkFuture(FULL)` — loads chunks into memory without force-loading

#### RejectedExecutionException
- Added `isShutdown()` guards in `AsyncTaskManager` before submitting tasks
- Prevents crash during server shutdown

#### World Stuck at 100%
- **Cause:** Global startup delay blocked all Create systems
- **Fix:** Delay now applies only to chunk operations, not to the general tick

### Configuration

New options in `create_optimized_trains-common.toml`:
- `DEBUG_OVERLAY_ENABLED` — Show F3 overlay (default: true)
- `DIRECTIONAL_CHUNK_LOADING` — Directional chunk loading (default: true)
- `DIRECTIONAL_FORWARD_CHUNKS` — Forward chunks (default: 12)
- `DIRECTIONAL_BACKWARD_CHUNKS` — Backward chunks (default: 4)
- `DIRECTIONAL_SIDE_CHUNKS` — Side chunks (default: 5)
- `CHUNK_LOOKAHEAD` — Base lookahead chunks (default: 3)

### Mixins

#### Server (7)
- `TrainMixin` — carriageWaitingForChunks bypass + collision throttling
- `SafeTrainTickMixin` — try-catch protection on earlyTick
- `CarriageMixin` — 60-tick grace period for chunks
- `CarriageEntityMixin` — isActiveChunk always true
- `DimensionalCarriageEntityMixin` — isActiveChunk always true
- `ContraptionCollisionMixin` — Collision throttling
- `ChunkMapMixin` — Directional chunk filtering

#### Client (7)
- `CarriageRendererMixin` — Render skipping by LOD/distance
- `CarriageContraptionVisualMixin` — Flywheel update skipping by LOD
- `CarriageEntityClientMixin` — Position snap for first 5 ticks
- `ContraptionEntityRendererMixin` — Render optimization
- `ContraptionVisualMaterialMixin` — Material optimization
- `LevelRendererFlushMixin` — Flush optimization
- `OitDepthMixin` — Depth sorting fix

### Dependencies

- Minecraft 1.20.1
- Forge 47.4.16+
- Create Mod 6.0.8+
- Flywheel 1.0.5 (JiJ'd inside Create)
- Mixin 0.8.5
