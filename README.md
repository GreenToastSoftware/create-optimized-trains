# 🚅⚡Create Optimized Trains

Performance optimization addon for **Create Mod** trains (Forge 1.20.1).

<p>
  <a href="https://discord.gg/QFBFJPJ2YD">
    <img src="https://raw.githubusercontent.com/GreenToastSoftware/greentoastsoftware.github.io/refs/heads/main/projects/greentoastsoftware-games/discord-badge.svg" width="200">
  </a>


  <a href="https://greentoastsoftware.github.io/">
    <img src="https://raw.githubusercontent.com/GreenToastSoftware/greentoastsoftware.github.io/refs/heads/main/projects/greentoastsoftware-games/greentoast-badge.svg" width="200">
  </a>
</p>

[![Used in 11 modpacks on Modpack Index](https://www.modpackindex.com/badge/mod/104478/create-optimized-trains/modpacks.svg)](https://www.modpackindex.com/mod/104478/create-optimized-trains)
[![7,359 downloads across all platforms](https://www.modpackindex.com/badge/mod/104478/create-optimized-trains/downloads.svg)](https://www.modpackindex.com/mod/104478/create-optimized-trains)
[![Updated 1mo ago](https://www.modpackindex.com/badge/mod/104478/create-optimized-trains/updated.svg)](https://www.modpackindex.com/mod/104478/create-optimized-trains)

## 🔗Dependencies

This mod requires Create or Create Fabric.

*   Minecraft 1.20.1
*   Forge 47.1.33+
*   Create Mod 6.0.8+
*   Flywheel 1.0.6+ (bundled with Create)

<p>
 <a href="https://www.curseforge.com/minecraft/mc-mods/create">
    <img src="https://raw.githubusercontent.com/GreenToastSoftware/greentoastsoftware.github.io/refs/heads/main/projects/greentoastsoftware-games/create-badge.svg" width="200">
  </a>
</p>


## 📦Features

### 1\. LOD (Level of Detail) for Trains

*   **FULL** — Real carriages with full physics (player nearby)
*   **MEDIUM** — Simplified physics, normal rendering
*   **LOW** — No physics, no collisions, minimal contraption updates
*   **GHOST** — Ghost entity, no rendering, abstract data only

### 2\. Carriage Grouping / Abstraction

*   Merges multiple carriages into a single logical entity (`TrainGroup`)
*   Stores only: length, speed, position, carriage list as NBT
*   Expands back when the player approaches

### 3\. Safe Multi-threading

**Secondary thread (safe):**

*   Route calculation
*   Collision prediction
*   Schedule optimization
*   Priority management
*   LOD decisions
*   Predictive simulation

**Main thread (required):**

*   Actual entity movement
*   Create physics
*   Rendering
*   Block interaction

### 4\. Tick Throttling (Partial Updates)

*   FULL: every tick
*   MEDIUM: every 3 ticks (configurable)
*   LOW: every 8 ticks
*   GHOST: every 20 ticks

### 5\. Render Optimization

*   Disables animations for distant trains
*   Skips full rendering for GHOST LOD
*   Reduces particles and visual effects
*   Hides internal details when not visible

### 6\. Proxy Entities

*   Lightweight entity representing the entire train
*   Replaces dozens of contraptions
*   Keeps position, speed and direction
*   Recreates real carriages when needed

### 7\. Smart Chunk Loading Management

*   Loads only essential chunks
*   Unloads chunks behind the train earlier
*   Pre-loads chunks ahead
*   Anti-thrashing (prevents repeated load/unload cycles)

### 8\. Physics Optimization

*   Simplified physics for distant carriages
*   Disables collisions between carriages at low LOD
*   Reduces bogie checks
*   Disables internal contraption physics when far away

### 9\. Networking Optimization

*   Sends updates only when speed/direction changes
*   Batches packets (delta compression)
*   Reduces sync frequency for distant trains

### 10\. Priority System

*   Priority by type (EXPRESS > PASSENGER > FREIGHT > LOW)
*   Conflict resolution at junctions
*   Track segment reservation
*   Async conflict analysis

### 11\. Dynamic Performance Monitor

*   **NORMAL** (TPS >= 18): full fidelity
*   **DEGRADED** (15 <= TPS < 18): light optimizations
*   **CRITICAL** (TPS < 15): aggressive optimizations, reduced LOD distances

<p>
 <a>
    <img src="https://raw.githubusercontent.com/GreenToastSoftware/greentoastsoftware.github.io/refs/heads/main/projects/greentoastsoftware-games/warning_testmod-badge.svg" width="680">
  </a>
</p>

## 🏅 Technical Performance Certificate
Create Optimized Trains has undergone a series of internal and community tests, including:

### Stress Tests

*   Trains over 300 blocks long
*   Simultaneous moving contraptions
*   Rendering with hundreds of entities
*   Worlds with aggressive preloading

### Compatibility Tests

*   Create
*   Steam ’n’ Rails
*   Embeddium
*   Radium
*   Copycats+
*   Distant Horizons

### Community Testing
Pre-release versions were made available on the official Discord, ensuring that every public update is stable and free of regressions.


## ⚙️Configuration

File: `create_optimized_trains-common.toml`

All features are individually configurable with distances, intervals and toggles.

## 🪄Backported Fixes from Create 6.0.9+

Create 6.0.9 (for Minecraft 1.21.1) introduced several important fixes that were **never backported to 1.20.1**. Since 1.20.1 is no longer receiving updates from the Create team, this mod backports the following fixes for users staying on 1.20.1:

### Contraption Collision Box GC Pressure ([#6902](https://github.com/Creators-of-Create/Create/issues/6902))

**Original problem:** When train doors open/close at a station, `gatherBBsOffThread()` combines ALL block collision shapes into a single `VoxelShape` using `Shapes.joinUnoptimized()` — an O(n²) operation that creates massive temporary objects. The garbage collector can't keep up, causing **lag spikes of 1-30+ seconds** depending on train complexity.

**Our fix:** `ContraptionCollisionMixin` replaces the collision gathering algorithm with the same O(n) approach used in Create 6.0.9 ([commit 8f30c2c](https://github.com/Creators-of-Create/Create/commit/8f30c2cccce4724ddae1067e4789f56dc3ee5eda)). Instead of joining VoxelShapes, it collects AABBs directly from each block's collision shape — no intermediate allocations, no GC pressure. Also includes related duplicates: [#4607](https://github.com/Creators-of-Create/Create/issues/4607), [#5685](https://github.com/Creators-of-Create/Create/issues/5685), [#9026](https://github.com/Creators-of-Create/Create/issues/9026), [#9389](https://github.com/Creators-of-Create/Create/issues/9389).

### Train Stutter from carriageWaitingForChunks

**Original problem:** In `Train.tick()`, when a carriage enters an unloaded chunk, Create sets `carriageWaitingForChunks` which forces `speed = 0` until the chunk finishes loading. On maps with many trains and limited view distance, this causes **0.5-1.5 second freezes** every time a train crosses a chunk boundary.

**Our fix:** `TrainMixin` redirects all reads of `carriageWaitingForChunks` in `tick()` to always return -1, so speed is never zeroed. Combined with the Smart Chunk Loading system (pre-loading chunks ahead of the train), trains move continuously without stutter.

### Collision Check Performance

**Original problem:** `collideWithOtherTrains()` runs every tick for every train, which becomes expensive on maps with 20+ trains.

**Our fix:** `TrainMixin` throttles collision checks adaptively based on server performance — every 4 ticks normally, every 8 when degraded, every 12 when critical. Each train's check is offset by its UUID hash to distribute the load evenly across ticks.

<p>
 <a href="https://github.com/GreenToastSoftware/create-optimized-trains">
    <img src="https://raw.githubusercontent.com/GreenToastSoftware/greentoastsoftware.github.io/refs/heads/main/projects/greentoastsoftware-games/github-badge.svg" width="200">
  </a>
</p>

## 💚About us

Publisher: GreenToastSoftware Games® (C)GreenToastSoftware Corporation. All rights reserved 2020-2026.

## Build

```bash
./gradlew build
```

The resulting JAR will be in `build/libs/`.

## Project Structure

```
src/main/java/com/createoptimizedtrains/
├── CreateOptimizedTrains.java     # Main mod class
├── config/ModConfig.java          # TOML configuration
├── lod/                           # LOD system
│   ├── LODLevel.java
│   └── LODSystem.java
├── grouping/                      # Carriage grouping
│   ├── TrainGroup.java
│   └── TrainGroupManager.java
├── threading/                     # Safe multi-threading
│   └── AsyncTaskManager.java
├── throttling/                    # Tick throttling
│   └── TickThrottler.java
├── rendering/                     # Render optimization
│   └── RenderOptimizer.java
├── proxy/                         # Proxy entities
│   ├── ProxyTrainEntity.java
│   └── ProxyEntityManager.java
├── chunks/                        # Chunk loading management
│   └── ChunkLoadManager.java
├── physics/                       # Physics optimization
│   └── PhysicsOptimizer.java
├── networking/                    # Network optimization
│   ├── NetworkOptimizer.java
│   └── TrainSyncPacket.java
├── priority/                      # Priority system
│   ├── TrainPriority.java
│   └── PriorityScheduler.java
├── monitor/                       # Performance monitor
│   └── PerformanceMonitor.java
├── events/                        # Forge event handlers
│   └── TrainEventHandler.java
└── mixin/                         # Create mod mixins
    ├── TrainMixin.java
    ├── CarriageMixin.java
    ├── CarriageEntityMixin.java
    └── client/
        └── CarriageRendererMixin.java
```

## License

MIT
