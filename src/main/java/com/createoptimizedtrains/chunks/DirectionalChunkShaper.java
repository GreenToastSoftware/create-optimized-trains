package com.createoptimizedtrains.chunks;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rastreia jogadores que estão em comboios Create e calcula a direção de movimento.
 * Usado pelo ChunkMapMixin para redistribuir chunks visuais no sentido do comboio.
 *
 * Quando o jogador está num comboio em movimento, as chunks novas são carregadas
 * preferencialmente à frente e atrás do sentido de movimento, com menos chunks
 * para os lados. Isto melhora a performance sem afetar a experiência visual
 * (especialmente com Distant Horizons).
 */
public class DirectionalChunkShaper {

    private static final Map<UUID, PlayerDirection> playerDirections = new ConcurrentHashMap<>();

    // Smoothing factor: 0.3 dá ~3 ticks de suavização (responde rápido o suficiente para comboios)
    private static final double SMOOTH_FACTOR = 0.3;
    // Velocidade mínima (blocos/tick) para considerar que o jogador está em movimento
    private static final double MIN_SPEED = 0.15;

    /**
     * Atualizar estado de direção de um jogador. Chamar a cada tick no server.
     */
    public static void updatePlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        boolean onTrain = isPlayerOnTrain(player);

        if (!onTrain) {
            playerDirections.remove(uuid);
            return;
        }

        PlayerDirection dir = playerDirections.computeIfAbsent(uuid, k -> new PlayerDirection());
        dir.update(player.getX(), player.getZ());
    }

    /**
     * Obter a direção atual do jogador, ou null se não estiver num comboio.
     */
    public static PlayerDirection getDirection(ServerPlayer player) {
        PlayerDirection dir = playerDirections.get(player.getUUID());
        if (dir == null || !dir.hasDirection) return null;
        return dir;
    }

    /**
     * Limpar dados de um jogador (quando sai).
     */
    public static void removePlayer(UUID uuid) {
        playerDirections.remove(uuid);
    }

    /**
     * Limpar todos os dados.
     */
    public static void clear() {
        playerDirections.clear();
    }

    /**
     * Verificar se o jogador está sentado num comboio Create.
     * Percorre a cadeia de veículos para encontrar CarriageContraptionEntity.
     */
    private static boolean isPlayerOnTrain(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        // Percorrer cadeia de veículos (Player → SeatEntity → CarriageContraptionEntity)
        int depth = 0;
        while (vehicle != null && depth < 5) {
            if (vehicle instanceof CarriageContraptionEntity) {
                return true;
            }
            vehicle = vehicle.getVehicle();
            depth++;
        }
        return false;
    }

    /**
     * Dados de direção por jogador.
     */
    public static class PlayerDirection {
        private double prevX, prevZ;
        private double smoothDirX, smoothDirZ;
        boolean hasDirection;
        private boolean hasPrevPos;

        void update(double x, double z) {
            if (!hasPrevPos) {
                prevX = x;
                prevZ = z;
                hasPrevPos = true;
                return;
            }

            double dx = x - prevX;
            double dz = z - prevZ;
            prevX = x;
            prevZ = z;

            double speed = Math.sqrt(dx * dx + dz * dz);
            if (speed < MIN_SPEED) {
                // Muito lento — manter direção anterior ou não aplicar
                return;
            }

            // Normalizar
            double ndx = dx / speed;
            double ndz = dz / speed;

            // Suavizar com exponential moving average
            smoothDirX = smoothDirX * (1 - SMOOTH_FACTOR) + ndx * SMOOTH_FACTOR;
            smoothDirZ = smoothDirZ * (1 - SMOOTH_FACTOR) + ndz * SMOOTH_FACTOR;

            // Re-normalizar
            double len = Math.sqrt(smoothDirX * smoothDirX + smoothDirZ * smoothDirZ);
            if (len > 0.01) {
                smoothDirX /= len;
                smoothDirZ /= len;
                hasDirection = true;
            }
        }

        /**
         * Verificar se uma chunk está dentro da área direcional.
         *
         * @param chunkX     coordenada X da chunk
         * @param chunkZ     coordenada Z da chunk
         * @param centerX    coordenada X da chunk do jogador
         * @param centerZ    coordenada Z da chunk do jogador
         * @param forward    chunks à frente
         * @param backward   chunks atrás
         * @param side       chunks para os lados
         * @return true se a chunk deve ser carregada
         */
        public boolean isInRange(int chunkX, int chunkZ, int centerX, int centerZ,
                                 int forward, int backward, int side) {
            int dx = chunkX - centerX;
            int dz = chunkZ - centerZ;

            // Projetar no eixo do comboio (direção de movimento)
            double along = dx * smoothDirX + dz * smoothDirZ;
            // Distância perpendicular ao eixo do comboio
            double across = Math.abs(-dx * smoothDirZ + dz * smoothDirX);

            if (along >= 0) {
                // À frente: within forward distance AND within side distance
                return along <= forward && across <= side;
            } else {
                // Atrás: within backward distance AND within side distance
                return (-along) <= backward && across <= side;
            }
        }
    }
}
