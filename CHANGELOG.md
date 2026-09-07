# Changelog

## v1.3.2

### Focus

- Fixes the remaining Copycats+ bit lag, a critical world-load freeze, and last-carriage movement stutter
- All fixes are follow-ups discovered while stress-testing v1.3.1 on maps with 40+ trains and Copycats+ bit-heavy contraptions

### Bug Fixes

#### Copycats+ Periodic Lag (Bit Blocks with BlockEntity)
- Contraptions containing Copycats+ "bits" (sub-block sized pieces with a BlockEntity, unlike plain slabs/panels) caused periodic lag spikes every few seconds
- Root cause: `ContraptionWorld` does not override `getBlockEntity()`, so it fell back to `WrappedLevel.getBlockEntity()`, which queried the **real world** using the contraption's **local** coordinates (e.g. 5,1,3) — forcing the server to load/query the chunk at real-world (0,0) on every call
- Fix: `ContraptionWorldBlockEntityMixin` intercepts `getBlockEntity()` on `Level` (filtered to `ContraptionWorld` instances) and always serves the BlockEntity from `contraption.getBlocks()`, with a lazy per-instance cache — never touching the real world with local coordinates

#### World Freeze on Startup (Memory Pressure)
- On some world loads, especially after the server had been running a while, the game would hang completely at the loading screen (no crash, just a full freeze)
- Root cause: heap memory pressure genuinely reaching 90%+ during startup, mainly from Distant Horizons' distant world generation competing with ~40 Create contraptions being assembled simultaneously — triggering long stop-the-world GC pauses (minutes-long in the worst case)
- Fix: `DistantHorizonsThrottle` temporarily reduces DH's distant world generation and worker thread count for the first `startupThrottleTicks` (default 200 ticks / 10s) after the server starts, giving vanilla terrain and contraption assembly priority. DH's original settings are restored automatically afterwards. Uses DH's public API via reflection — no hard dependency

#### Last-Carriage Movement Stutter
- Long trains (10+ carriages) without a player on board showed irregular movement — the stutter would noticeably improve once the rearmost carriage's entity finally loaded
- Root cause: `ChunkLoadManager`'s per-tick chunk load rate limit applied to *every* needed chunk indiscriminately, including the exact chunk each carriage physically occupies. On unoccupied trains, that chunk competed with lookahead/trailing chunks for the same 6-per-tick budget, so the last carriage in line could take several ticks just to get its own chunk loaded — delaying its entity creation
- Fix: chunk requests are now split into **critical** (the exact chunk under each carriage — always loaded immediately, no rate limit, regardless of occupancy) and **soft** (LOD comfort radius, directional lookahead, trailing buffer — still rate-limited). No carriage, including the rearmost one, waits behind empty pre-load chunks anymore

#### Chunks Not Unloading When a Train Stops
- Chunks along a train's previous travel direction could remain force-loaded indefinitely after the train stopped, accumulating over a session and degrading performance over time
- Root cause: the anti-thrash "recently loaded" deque only advances when new chunks are loaded; once a train stops, nothing loads, so old lookahead chunks stayed protected from unloading forever
- Fix: the anti-thrash deque is cleared as soon as a train's speed drops below the movement threshold, letting unneeded chunks unload immediately

### Improvements

#### Chunk Loading Tuning
- Adaptive lookahead margin increased from ~2s to ~5s of travel time, to reliably cover cold-terrain chunk generation (which can take 2-3s) — default cap raised from 10 to 20 chunks
- Per-tick chunk load budget reduced to 6 chunks with a 5ms time budget per train, preventing a single train's chunk update from blocking the whole server tick
- Non-occupied trains now have their chunk updates staggered across ticks (5 trains per batch, every 3 ticks) instead of all being processed every tick, reducing aggregate `setChunkForced` calls per tick on maps with many trains

#### Diagnostics
- Added granular performance logging (`[COT/Perf]`, `[COT] updateTrainChunks detalhe`) to break down slow ticks by train and by phase (chunk calculation, unload, load)
- Added `[COT/ContraptionWorldBE]` activity counter to confirm the Copycats+ BlockEntity fix is intercepting calls as expected

### Configuration

New config section in `create_optimized_trains-common.toml`:
- `[distant_horizons]` — `startupThrottleEnabled` (default `true`), `startupThrottleTicks` (default `200`, range 20-1200)

## v1.3.1

### Focus

- Movement smoothness, simulation distance, LOD chunk radius, and rendering correctness
- Rendering fixes target entity visibility and depth layering with Oculus/Embeddium + Flywheel OIT
- Chunk loading and directional filtering improved for smoother train travel
- Distant Horizons compatibility groundwork added

### New Features

#### Train Simulation Distance
- New `simulation` config section: `simulationDistance` (0–128 chunks) extends the directional chunk lookahead beyond the previous hardcoded 10-chunk limit
- `maxForcedChunks` (20–400): configurable global cap for force-loaded chunks
- Designed for use with Distant Horizons for smooth far-train visibility

#### Per-LOD Carriage Chunk Radius
- New `lod_radius` config section: `radiusFull`, `radiusMedium`, `radiusLow`, `radiusGhost`
- Load a larger or smaller chunk area around carriages depending on their LOD level
- Replaces the single `CARRIAGE_CHUNK_RADIUS` option with fine-grained per-LOD control

#### Distant Horizons Compatibility
- Added `DistantHorizonsCompat` to detect DH and coordinate chunk loading so the two systems do not conflict
- Added `ShaderCompat` for lightweight detection of active shader packs (used by LOD and render decisions)

#### Player Train Tracker
- Added `PlayerTrainTracker` utility to track which players are currently riding which trains
- Used by chunk loading and LOD systems for proximity-aware decisions

### Improvements

#### Movement Smoothness
- Chunk boundary grace period is now adaptive: 5 ticks while moving, 20 ticks while stopped
- Eliminates JourneyMap position lag and entity freeze at chunk boundaries
- `ContraptionTickThrottleMixin` added to throttle `tickContraption()` safely without visual desync

#### Chunk Loading
- `ChunkMapMixin`: added a 5-chunk minimum radius — directional filtering is now disabled entirely when the player is close to the train, preventing nearby chunks from being skipped
- `ChunkLoadManager`: global forced-chunk cap increased from 30 to 60; lookahead cap increased from 6 to 10
- `TrainEventHandler`: startup delay reduced from 100 to 40 ticks; ramp-up batch increased from 2 to 5
- `DirectionalChunkShaper`: direction response made faster (smooth factor 0.3 → 0.5)
- Default `sideChunks` increased from 3 to 5 in `ModConfig`

#### LOD System
- LOD distances restored to v1.2.0 baseline (`shaderLodShift` default back to 0)
- Fixed `resolveCarriagePosition` to use `positionAnchor` as a fallback when a carriage entity is not yet available
- `shouldAnimate` now allows `LOW` LOD to animate; only `GHOST` skips animation
- `shouldSkipRender` threshold lowered from 40 to 20 FPS

#### Rendering Performance
- Removed Flywheel frame skipping from `RenderOptimizer` — positions now update every frame, eliminating position drift under load
- `CarriageRendererMixin`: culling distance increased from 256 to 512 blocks

### Bug Fixes

#### Camera Shake
- Removed the `isActiveChunkOrLoadedInManage` Redirect that was causing camera shake when sitting in a moving carriage

#### Visual Throttle
- Added `NEAR_VISUAL_SKIP_RADIUS_SQ` proximity guard so the visual throttle never fires for trains near the player/camera

#### Collision Throttle Intervals
- `TrainMixin`: reduced collision throttle tick intervals from 4/8/12 to 2/4/8, making collisions more responsive

#### Chunk Grace Periods
- `CarriageMixin`: chunk grace period reduced from 60 to 20 ticks
- `CarriageEntityMixin`: bind grace period reduced from 40 to 15 ticks

#### Entity Visibility Through Contraption Glass (Oculus/Embeddium)
- Entities were invisible when viewed through glass blocks on contraptions (trains with glass windows/doors)
- Root cause: Flywheel's OIT pipeline writes its glass composite to the `itemEntityTarget` FBO. With Oculus, entity rendering is deferred into `FullyBufferedMultiBufferSource` — entities were not in the framebuffer when `composite()` ran
- Fix: inject a flush of the Iris/Oculus batched entity source at `popPush("blockentities")` (priority 500, before Flywheel's priority 1000 hook), so entities are in the framebuffer when OIT composites the glass tint

#### Copycats+ Door Not Following Camera
- Animated doors from Copycats+ were moving with the camera when mounted in a contraption
- Root cause: `translucentMovingBlock` was routed through `bufferSource()`, which Oculus replaces with `FullyBufferedMultiBufferSource`. The Iris source is flushed at a different time, causing the door geometry to be drawn relative to camera movement
- Fix: `ContraptionBufferSourceWrapper` now routes `translucentMovingBlock` to `BufferSourceResolver.getRawMainBufferSource()` — the vanilla raw `BufferSource` not replaced by Oculus — avoiding the infinite recursion that the Oculus getter substitution would otherwise cause

#### Entity Ghost / Wrong Layer Effect
- Entities appeared ghost-like or at the wrong depth layer relative to block entities rendered after them
- Root cause: the early entity flush was using `depthMask(false)`, so entities did not write their own depth to the main FBO. Block entities rendered after Flywheel's OIT hook would pass the depth test where they shouldn't, appearing on top of entities
- Fix: removed `depthMask(false)` from the entity flush — entities now write correct depth, giving proper layering

#### Copycats+ Door Wrong Depth Layer
- With the entity flush corrected, the door remained at the wrong layer relative to contraption solid blocks
- Root cause: the door was flushed with `depthMask(false)` before OIT, then OIT `prepare()` overwrote the item entity FBO depth — the door had no depth information when Iris composited `itemEntityTarget` onto the main FBO
- Fix: the door is now flushed by `OitFramebufferMixin` at `@HEAD` of `composite()` (with `depthMask(true)` by default) and by `LevelRendererPostFlywheelMixin` (priority 1500) as a fallback when no OIT is active

#### Entities Invisible Through Copycats+ Door + Glass Combination
- When a contraption had both Copycats+ doors and glass blocks, all entities seen through the door were invisible
- Root cause: flushing the door to `ITEM_ENTITY_TARGET` before `prepare()` with `depthMask(true)` wrote door depth into the item entity FBO, interfering with the shared depth texture between the OIT FBO and item entity FBO
- Fix: `OitFramebufferMixin @HEAD` flush fires after `prepare()` has already run, so the item entity FBO depth is clean before the door writes its own depth

### Technical Details (Rendering)

- Added `BufferSourceResolver` (reflection-based) to access `RenderBuffers.f_110094_` directly, bypassing the Oculus getter substitution
- Added `ContraptionBufferSourceWrapper` to centralise `translucentMovingBlock` and `translucent` rerouting during contraption render
- Added `FullyBufferedMultiBufferSourceMixin` (`@Pseudo`) targeting both `net.irisshaders` and `net.coderbot` namespaces
- `OitDepthMixin`: cancels `renderDepthFromTransmittance()` to prevent Flywheel OIT from writing synthetic glass depths
- `oit_composite_fix.js` coremod: patches `OitFramebuffer.composite()` from `depthMask(true)` to `depthMask(false)`

### Configuration

New config sections in `create_optimized_trains-common.toml`:
- `[simulation]` — `simulationDistance`, `maxForcedChunks`
- `[lod_radius]` — `radiusFull`, `radiusMedium`, `radiusLow`, `radiusGhost`
- `sideChunks` default changed from 3 to 5
- `shaderLodShift` default restored to 0

##  Thank you so much to all of you for making this mod a success! And let's keep growing!

## v1.3.0

### Focus

- Stability-first release focused on fixing regressions introduced after v1.2.0
- Restores smooth train behavior under real gameplay load (multiple trains, stations, chunk boundaries)
- Keeps most aggressive optimizations optional behind config flags

### Improvements

#### Movement and Physics Stability
- Added player proximity guards so nearby trains keep full physics/collision updates
- Aggressive throttling for other trains is now optional (`AGGRESSIVE_OTHER_TRAINS_THROTTLE`), default OFF
- Client-side `tickContraption()` throttling is blocked to avoid visual desync and “entry-state freeze”

#### Chunk Loading and Spawn Smoothness
- Startup grace period added to chunk filtering in `ChunkMapMixin` to avoid missing chunks on world join
- Chunk force-load pipeline now uses per-train rate limiting to reduce tick spikes when new trains appear
- Prioritizes immediate carriage chunks before lookahead/trailing chunks
- Added trailing buffer logic based on train length so rear carriages stay stable on long consists

#### LOD Consistency
- Fixed LOD classification bug where `lowDistance` was not being applied correctly
- Removed dynamic TPS-based shrinking of LOD distances (distance thresholds are now stable)
- Restored effective LOD behavior to v1.2.0 profile (no extra client-side shader LOD shift)
- Added fallback distance source (`positionAnchor`) when a carriage entity is not yet available

### Bug Fixes

#### Camera Shake While Sitting in Carriages
- Root cause addressed by preventing premature carriage entity creation in non-entity-ticking chunks
- Added priority chunk loading for carriages with player passengers to stabilize seat/camera sync

#### Trains Appearing as Distant LOD While Nearby
- Fixed client visual throttle path to never apply near the player/camera
- Nearby stationary trains no longer look choppy or “far LOD” when standing next to them

#### Visual and Flywheel Regressions
- `CarriageContraptionVisualMixin` begin-frame flow aligned with safer behavior (no incorrect skip path for moving trains)
- Removed problematic skip conditions that could freeze or under-update visuals

### Configuration

New/updated behavior in `create_optimized_trains-common.toml`:
- `AGGRESSIVE_OTHER_TRAINS_THROTTLE` (default: `false`)
- `GROUPING_ENABLED` default changed to `false` (experimental)
- `PROXY_ENABLED` default changed to `false` (experimental)
- `SHADER_BOOST_ENABLED` default changed to `false`
- `SHADER_LOD_SHIFT` default changed to `0`

### Notes

- Recommended migration from older configs: review existing `.toml` values, because existing files keep old values instead of new defaults
- This release is tuned for predictable behavior first; re-enable aggressive options only if needed per world/server

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
