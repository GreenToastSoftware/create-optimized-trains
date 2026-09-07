package com.createoptimizedtrains.util;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

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
}
