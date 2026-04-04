package com.createoptimizedtrains.grouping;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.nbt.CompoundTag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TrainGroupManager {

    private final Map<UUID, TrainGroup> groups = new ConcurrentHashMap<>();
    private final LODSystem lodSystem;

    public TrainGroupManager(LODSystem lodSystem) {
        this.lodSystem = lodSystem;
    }

    public boolean shouldCollapse(Train train) {
        if (!ModConfig.GROUPING_ENABLED.get()) {
            return false;
        }

        LODLevel lod = lodSystem.getTrainLOD(train.id);
        int minCarriages = ModConfig.GROUPING_MIN_CARRIAGES.get();

        return train.carriages.size() >= minCarriages && lod.isAtLeast(LODLevel.LOW);
    }

    public void collapseTrain(Train train) {
        if (groups.containsKey(train.id) && groups.get(train.id).isCollapsed()) {
            return; // Já colapsado
        }

        List<CompoundTag> carriageSnapshots = new ArrayList<>();
        double totalLength = 0;

        for (Carriage carriage : train.carriages) {
            CompoundTag snapshot = new CompoundTag();
            // Guardar dados essenciais de cada carruagem
            snapshot.putInt("Index", train.carriages.indexOf(carriage));
            snapshot.putBoolean("IsLeading", carriage == train.carriages.get(0));
            carriageSnapshots.add(snapshot);
        }

        // Obter posição e direção do comboio (Create 6.x API)
        double posX = 0, posY = 0, posZ = 0;
        double dirX = 0, dirZ = 0;

        if (!train.carriages.isEmpty()) {
            var firstCarriage = train.carriages.get(0);
            var entity = firstCarriage.anyAvailableEntity();
            if (entity != null) {
                posX = entity.getX();
                posY = entity.getY();
                posZ = entity.getZ();
            }
        }

        TrainGroup group = new TrainGroup(train.id);
        group.collapse(carriageSnapshots, totalLength, train.speed, posX, posY, posZ, dirX, dirZ);
        groups.put(train.id, group);

        // Remover entidades das carruagens do mundo (exceto a proxy)
        removeCarriageEntities(train);

        CreateOptimizedTrains.LOGGER.debug("Comboio {} colapsado: {} carruagens -> 1 grupo",
                train.id, train.carriages.size());
    }

    public void expandTrain(Train train) {
        TrainGroup group = groups.get(train.id);
        if (group == null || !group.isCollapsed()) {
            return;
        }

        group.setCollapsed(false);

        // Recriar entidades de carruagens
        restoreCarriageEntities(train, group);

        CreateOptimizedTrains.LOGGER.debug("Comboio {} expandido: {} carruagens restauradas",
                train.id, group.getCarriageCount());
    }

    private void removeCarriageEntities(Train train) {
        for (int i = 1; i < train.carriages.size(); i++) {
            Carriage carriage = train.carriages.get(i);
            var entity = carriage.anyAvailableEntity();
            if (entity != null) {
                entity.discard();
            }
        }
    }

    private void restoreCarriageEntities(Train train, TrainGroup group) {
        // Forçar recriação das entidades solicitando reassemblagem
        for (Carriage carriage : train.carriages) {
            var entity = carriage.anyAvailableEntity();
            if (entity != null) {
                entity.setPos(entity.position());
            }
        }
    }

    public void updateGroupedTrains(long currentTick) {
        for (TrainGroup group : groups.values()) {
            if (group.isCollapsed()) {
                group.updatePosition(group.getSpeed(), currentTick);
            }
        }
    }

    public void removeTrain(UUID trainId) {
        groups.remove(trainId);
    }

    public TrainGroup getGroup(UUID trainId) {
        return groups.get(trainId);
    }

    public boolean isCollapsed(UUID trainId) {
        TrainGroup group = groups.get(trainId);
        return group != null && group.isCollapsed();
    }

    public Map<UUID, TrainGroup> getAllGroups() {
        return groups;
    }
}
