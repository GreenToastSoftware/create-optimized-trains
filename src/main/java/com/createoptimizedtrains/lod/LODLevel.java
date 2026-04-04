package com.createoptimizedtrains.lod;

public enum LODLevel {
    FULL(1),
    MEDIUM(2),
    LOW(3),
    GHOST(4);

    private final int tier;

    LODLevel(int tier) {
        this.tier = tier;
    }

    public int getTier() {
        return tier;
    }

    public boolean isAtLeast(LODLevel other) {
        return this.tier >= other.tier;
    }

    public boolean shouldUpdatePhysics() {
        return this == FULL || this == MEDIUM;
    }

    public boolean shouldRenderDetailed() {
        return this == FULL;
    }

    public boolean shouldCheckCollisions() {
        return this == FULL;
    }

    public boolean shouldSyncFrequently() {
        return this == FULL || this == MEDIUM;
    }

    public boolean isGhost() {
        return this == GHOST;
    }
}
