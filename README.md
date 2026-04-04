# Create Optimized Trains

Performance optimization addon for **Create Mod** trains (Forge 1.20.1).

## Features

### 1. LOD (Level of Detail) for Trains
- **FULL** — Real carriages with full physics (player nearby)
- **MEDIUM** — Simplified physics, normal rendering
- **LOW** — No physics, no collisions, minimal contraption updates
- **GHOST** — Ghost entity, no rendering, abstract data only

### 2. Carriage Grouping / Abstraction
- Merges multiple carriages into a single logical entity (`TrainGroup`)
- Stores only: length, speed, position, carriage list as NBT
- Expands back when the player approaches

### 3. Safe Multi-threading
**Secondary thread (safe):**
- Route calculation
- Collision prediction
- Schedule optimization
- Priority management
- LOD decisions
- Predictive simulation

**Main thread (required):**
- Actual entity movement
- Create physics
- Rendering
- Block interaction

### 4. Tick Throttling (Partial Updates)
- FULL: every tick
- MEDIUM: every 3 ticks (configurable)
- LOW: every 8 ticks
- GHOST: every 20 ticks

### 5. Render Optimization
- Disables animations for distant trains
- Skips full rendering for GHOST LOD
- Reduces particles and visual effects
- Hides internal details when not visible

### 6. Proxy Entities
- Lightweight entity representing the entire train
- Replaces dozens of contraptions
- Keeps position, speed and direction
- Recreates real carriages when needed

### 7. Smart Chunk Loading Management
- Loads only essential chunks
- Unloads chunks behind the train earlier
- Pre-loads chunks ahead
- Anti-thrashing (prevents repeated load/unload cycles)

### 8. Physics Optimization
- Simplified physics for distant carriages
- Disables collisions between carriages at low LOD
- Reduces bogie checks
- Disables internal contraption physics when far away

### 9. Networking Optimization
- Sends updates only when speed/direction changes
- Batches packets (delta compression)
- Reduces sync frequency for distant trains

### 10. Priority System
- Priority by type (EXPRESS > PASSENGER > FREIGHT > LOW)
- Conflict resolution at junctions
- Track segment reservation
- Async conflict analysis

### 11. Dynamic Performance Monitor
- **NORMAL** (TPS >= 18): full fidelity
- **DEGRADED** (15 <= TPS < 18): light optimizations
- **CRITICAL** (TPS < 15): aggressive optimizations, reduced LOD distances

## Configuration

File: `create_optimized_trains-common.toml`

All features are individually configurable with distances, intervals and toggles.

## Dependencies

- Minecraft 1.20.1
- Forge 47.1.33+
- Create Mod 6.0.8+
- Flywheel 1.0.6+ (bundled with Create)

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
