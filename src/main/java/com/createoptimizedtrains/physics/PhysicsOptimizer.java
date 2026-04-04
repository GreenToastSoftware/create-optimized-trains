package com.createoptimizedtrains.physics;

import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;

import java.util.UUID;

public class PhysicsOptimizer {

    private final LODSystem lodSystem;

    public PhysicsOptimizer(LODSystem lodSystem) {
        this.lodSystem = lodSystem;
    }

    /**
     * Verifica se a física completa deve ser processada para este comboio.
     */
    public boolean shouldProcessFullPhysics(UUID trainId) {
        if (!ModConfig.PHYSICS_OPTIMIZATION_ENABLED.get()) {
            return true;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod == LODLevel.FULL;
    }

    /**
     * Verifica se a física simplificada deve ser usada.
     * Física simplificada: movimento linear sem colisões detalhadas.
     */
    public boolean shouldUseSimplifiedPhysics(UUID trainId) {
        if (!ModConfig.PHYSICS_OPTIMIZATION_ENABLED.get()) {
            return false;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod == LODLevel.MEDIUM;
    }

    /**
     * Verifica se toda a física deve ser desativada (modo fantasma).
     */
    public boolean shouldDisablePhysics(UUID trainId) {
        if (!ModConfig.PHYSICS_OPTIMIZATION_ENABLED.get()) {
            return false;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod == LODLevel.LOW || lod == LODLevel.GHOST;
    }

    /**
     * Verifica se colisões entre carruagens devem ser verificadas.
     */
    public boolean shouldCheckCollisions(UUID trainId) {
        if (!ModConfig.DISABLE_DISTANT_COLLISIONS.get()) {
            return true;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod.shouldCheckCollisions();
    }

    /**
     * Verifica se os bogies devem ser atualizados.
     * Bogies são custosos — reduzir checks para comboios distantes.
     */
    public boolean shouldUpdateBogies(UUID trainId) {
        if (!ModConfig.PHYSICS_OPTIMIZATION_ENABLED.get()) {
            return true;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod == LODLevel.FULL || lod == LODLevel.MEDIUM;
    }

    /**
     * Verifica se a física interna da contraption deve ser processada.
     */
    public boolean shouldProcessContraptionPhysics(UUID trainId) {
        if (!ModConfig.PHYSICS_OPTIMIZATION_ENABLED.get()) {
            return true;
        }
        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return lod == LODLevel.FULL;
    }

    /**
     * Obter o fator de simplificação da física (1.0 = completo, 0.0 = desativado).
     */
    public float getPhysicsFidelity(UUID trainId) {
        if (!ModConfig.PHYSICS_OPTIMIZATION_ENABLED.get()) {
            return 1.0f;
        }

        LODLevel lod = lodSystem.getTrainLOD(trainId);
        return switch (lod) {
            case FULL -> 1.0f;
            case MEDIUM -> 0.5f;
            case LOW -> 0.1f;
            case GHOST -> 0.0f;
        };
    }
}
