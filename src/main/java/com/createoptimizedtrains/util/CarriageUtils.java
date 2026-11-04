package com.createoptimizedtrains.util;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ConcurrentModificationException;

/**
 * Utilitários para operações seguras em Carriage.
 */
public final class CarriageUtils {

    private CarriageUtils() {}

    /**
     * Versão segura de {@link Carriage#anyAvailableEntity()}.
     *
     * O mapa interno {@code Carriage.entities} é um {@link java.util.HashMap} não-sincronizado.
     * Quando o servidor força o carregamento de chunks ({@code setChunkForced}), o carregamento
     * pode ocorrer de forma assíncrona, modificando o mapa enquanto o tick principal o itera.
     * Isto provoca {@link ConcurrentModificationException} e um crash do servidor.
     *
     * Esta versão captura a excepção e retorna {@code null} (sem entidade disponível),
     * permitindo que o código continue com o fallback de positionAnchor.
     *
     * @param carriage a carruagem a consultar
     * @return a primeira entidade disponível, ou {@code null} se não houver ou houver
     *         modificação concorrente do mapa
     */
    public static CarriageContraptionEntity safeAnyAvailableEntity(Carriage carriage) {
        try {
            return carriage.anyAvailableEntity();
        } catch (ConcurrentModificationException ignored) {
            // O mapa entities foi modificado durante a iteração (carga async de chunks).
            // Retornar null é seguro: o caller usa positionAnchor do DCE como fallback.
            return null;
        }
    }

    /**
     * Dimensão onde o comboio está, a partir do bogey da frente da primeira carruagem.
     *
     * Comboios do Create podem atravessar portais, por isso não se pode assumir o Overworld.
     * Durante a travessia de um portal, carruagens traseiras podem ainda estar na dimensão
     * anterior; a dimensão da frente é a que interessa para pré-carregar chunks.
     *
     * @return a dimensão, ou {@code null} se o comboio não tiver carruagens/bogeys válidos
     */
    public static ResourceKey<Level> getTrainDimension(Train train) {
        try {
            if (train.carriages.isEmpty()) return null;
            return train.carriages.get(0).leadingBogey().getDimension();
        } catch (Exception e) {
            return null;
        }
    }

    /** Nível do servidor onde o comboio está, ou {@code null} se não for possível resolver. */
    public static ServerLevel getTrainLevel(Train train, MinecraftServer server) {
        ResourceKey<Level> dimension = getTrainDimension(train);
        return dimension != null ? server.getLevel(dimension) : null;
    }

    /**
     * Posição de uma carruagem numa dimensão: entidade viva se estiver nessa dimensão,
     * senão positionAnchor do DimensionalCarriageEntity.
     *
     * Usa {@code getDimensionalIfPresent}: {@code getDimensional} CRIA uma entrada nova no
     * mapa {@code entities} quando não existe, o que corrompe o estado da carruagem se for
     * chamado com a dimensão errada (ou de uma thread secundária).
     */
    public static Vec3 getCarriagePosition(Carriage carriage, ResourceKey<Level> dimension) {
        CarriageContraptionEntity entity = safeAnyAvailableEntity(carriage);
        if (entity != null && entity.level().dimension() == dimension) {
            return entity.position();
        }
        try {
            var dce = carriage.getDimensionalIfPresent(dimension);
            if (dce != null && dce.positionAnchor != null) {
                return dce.positionAnchor;
            }
        } catch (Exception ignored) {
            // API pode diferir entre versões do Create / modificação concorrente
        }
        return null;
    }
}
