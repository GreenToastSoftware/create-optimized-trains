package com.createoptimizedtrains.util;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rastreia quais comboios têm jogadores dentro.
 * Usado pelo ContraptionColliderMixin e TrainMixin para reduzir física
 * em comboios onde o jogador NÃO está sentado.
 *
 * Atualizado a cada tick pelo TrainEventHandler (server) e pelo
 * ContraptionColliderMixin (client, via entidade).
 */
public class PlayerTrainTracker {

    // UUIDs dos comboios que têm pelo menos um jogador dentro
    private static final Set<UUID> occupiedTrains = ConcurrentHashMap.newKeySet();

    /**
     * Atualizar a lista de comboios ocupados (chamar do server tick).
     */
    public static void setOccupiedTrains(Set<UUID> trainIds) {
        occupiedTrains.clear();
        occupiedTrains.addAll(trainIds);
    }

    /**
     * Verificar se um comboio tem jogador dentro.
     */
    public static boolean isOccupied(UUID trainId) {
        return occupiedTrains.contains(trainId);
    }

    /**
     * Verificar se uma CarriageContraptionEntity pertence a um comboio ocupado.
     * Para uso nos mixins client/server.
     */
    public static boolean isEntityInOccupiedTrain(Entity entity) {
        if (entity instanceof CarriageContraptionEntity cce) {
            var carriage = cce.getCarriage();
            if (carriage != null && carriage.train != null) {
                return occupiedTrains.contains(carriage.train.id);
            }
        }
        return false;
    }

    /**
     * Obter o train ID de uma CarriageContraptionEntity, ou null.
     */
    public static UUID getTrainId(Entity entity) {
        if (entity instanceof CarriageContraptionEntity cce) {
            var carriage = cce.getCarriage();
            if (carriage != null && carriage.train != null) {
                return carriage.train.id;
            }
        }
        return null;
    }

    /**
     * Verificar se existe algum jogador dentro de um raio da entidade.
     *
     * Usado para NÃO degradar física de comboios próximos ao jogador,
     * mesmo quando o jogador não está sentado nesse comboio.
     */
    public static boolean isPlayerNear(Entity entity, double radius) {
        if (entity == null || entity.level() == null) return false;

        double radiusSq = radius * radius;
        var players = entity.level().players();
        for (var player : players) {
            if (player == null || !player.isAlive()) continue;
            if (player.distanceToSqr(entity) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        occupiedTrains.clear();
    }
}
