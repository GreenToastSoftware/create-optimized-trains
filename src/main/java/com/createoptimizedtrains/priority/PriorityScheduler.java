package com.createoptimizedtrains.priority;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.threading.AsyncTaskManager;
import com.simibubi.create.content.trains.entity.Train;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PriorityScheduler {

    private final AsyncTaskManager asyncTaskManager;

    // Prioridade por comboio
    private final Map<UUID, TrainPriority> trainPriorities = new ConcurrentHashMap<>();

    // Conflitos detetados: pares de comboios que convergem para o mesmo ponto
    private final Map<UUID, Set<UUID>> activeConflicts = new ConcurrentHashMap<>();

    // Comboios que devem parar/ceder passagem
    private final Set<UUID> yieldingTrains = ConcurrentHashMap.newKeySet();

    // Janelas de passagem: segmentos de via reservados por comboio
    private final Map<UUID, Set<String>> reservedSegments = new ConcurrentHashMap<>();

    public PriorityScheduler(AsyncTaskManager asyncTaskManager) {
        this.asyncTaskManager = asyncTaskManager;
    }

    /**
     * Definir prioridade de um comboio.
     */
    public void setTrainPriority(UUID trainId, TrainPriority priority) {
        trainPriorities.put(trainId, priority);
    }

    /**
     * Obter prioridade de um comboio.
     */
    public TrainPriority getTrainPriority(UUID trainId) {
        return trainPriorities.getOrDefault(trainId, TrainPriority.FREIGHT);
    }

    /**
     * Verificar se um comboio deve ceder passagem.
     */
    public boolean shouldYield(UUID trainId) {
        if (!ModConfig.PRIORITY_SYSTEM_ENABLED.get()) {
            return false;
        }
        return yieldingTrains.contains(trainId);
    }

    /**
     * Analisar conflitos entre comboios assíncronamente.
     * Isto corre numa thread secundária e aplica resultados no main thread.
     */
    public void analyzeConflictsAsync(Collection<Train> trains) {
        if (!ModConfig.PRIORITY_SYSTEM_ENABLED.get()) {
            return;
        }

        // Copiar dados necessários (thread-safe)
        List<TrainSnapshot> snapshots = new ArrayList<>();
        for (Train train : trains) {
            if (!train.carriages.isEmpty()) {
                // Create 6.x: usar anyAvailableEntity() para posição
                var entity = train.carriages.get(0).anyAvailableEntity();
                if (entity != null) {
                    snapshots.add(new TrainSnapshot(
                            train.id,
                            entity.getX(), entity.getY(), entity.getZ(),
                            train.speed,
                            getTrainPriority(train.id)
                    ));
                }
            }
        }

        asyncTaskManager.submitAsync(
                () -> detectConflicts(snapshots),
                this::applyConflictResolutions
        );
    }

    /**
     * Detectar conflitos entre comboios (thread secundária).
     */
    private List<ConflictResolution> detectConflicts(List<TrainSnapshot> snapshots) {
        List<ConflictResolution> resolutions = new ArrayList<>();
        double conflictRadiusSq = 64 * 64; // 64 blocos

        for (int i = 0; i < snapshots.size(); i++) {
            for (int j = i + 1; j < snapshots.size(); j++) {
                TrainSnapshot a = snapshots.get(i);
                TrainSnapshot b = snapshots.get(j);

                double dx = a.posX - b.posX;
                double dz = a.posZ - b.posZ;
                double distSq = dx * dx + dz * dz;

                // Comboios próximos a convergir = conflito potencial
                if (distSq < conflictRadiusSq && a.speed != 0 && b.speed != 0) {
                    // Comboio com menor prioridade cede
                    UUID yielder;
                    if (a.priority.isHigherThan(b.priority)) {
                        yielder = b.trainId;
                    } else if (b.priority.isHigherThan(a.priority)) {
                        yielder = a.trainId;
                    } else {
                        // Mesma prioridade: o mais lento cede
                        yielder = Math.abs(a.speed) < Math.abs(b.speed) ? a.trainId : b.trainId;
                    }

                    resolutions.add(new ConflictResolution(a.trainId, b.trainId, yielder));
                }
            }
        }

        return resolutions;
    }

    /**
     * Aplicar resoluções de conflitos (main thread).
     */
    private void applyConflictResolutions(List<ConflictResolution> resolutions) {
        // Limpar conflitos antigos
        yieldingTrains.clear();
        activeConflicts.clear();

        for (ConflictResolution resolution : resolutions) {
            yieldingTrains.add(resolution.yielder);

            activeConflicts.computeIfAbsent(resolution.trainA, k -> new HashSet<>())
                    .add(resolution.trainB);
            activeConflicts.computeIfAbsent(resolution.trainB, k -> new HashSet<>())
                    .add(resolution.trainA);
        }

        if (!resolutions.isEmpty()) {
            CreateOptimizedTrains.LOGGER.debug("{} conflitos de via resolvidos, {} comboios a ceder",
                    resolutions.size(), yieldingTrains.size());
        }
    }

    /**
     * Tentar reservar um segmento de via para um comboio.
     */
    public boolean reserveSegment(UUID trainId, String segmentId) {
        // Verificar se outro comboio já reservou
        for (var entry : reservedSegments.entrySet()) {
            if (!entry.getKey().equals(trainId) && entry.getValue().contains(segmentId)) {
                TrainPriority ourPriority = getTrainPriority(trainId);
                TrainPriority theirPriority = getTrainPriority(entry.getKey());

                if (ourPriority.isHigherThan(theirPriority)) {
                    // Nós temos prioridade: revogar a reserva deles
                    entry.getValue().remove(segmentId);
                } else {
                    return false; // Não podemos reservar
                }
            }
        }

        reservedSegments.computeIfAbsent(trainId, k -> new HashSet<>()).add(segmentId);
        return true;
    }

    /**
     * Libertar segmentos reservados por um comboio.
     */
    public void releaseSegments(UUID trainId) {
        reservedSegments.remove(trainId);
    }

    public void removeTrain(UUID trainId) {
        trainPriorities.remove(trainId);
        activeConflicts.remove(trainId);
        yieldingTrains.remove(trainId);
        reservedSegments.remove(trainId);
    }

    // --- Tipos internos ---

    private record TrainSnapshot(UUID trainId, double posX, double posY, double posZ,
                                  double speed, TrainPriority priority) {}

    private record ConflictResolution(UUID trainA, UUID trainB, UUID yielder) {}
}
