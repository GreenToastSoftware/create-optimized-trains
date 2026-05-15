package com.createoptimizedtrains.compat;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Compatibilidade com Distant Horizons (soft dependency).
 *
 * Quando DH está instalado, o terreno distante já é renderizado pelo sistema LOD
 * do DH. Isto permite-nos:
 * 1. Reduzir o render distance vanilla enquanto o jogador está num comboio rápido,
 *    libertando CPU/IO para pré-carregar chunks da rota mais rapidamente.
 * 2. Reduzir o force-load lookahead (DH cobre visualmente o terreno distante).
 * 3. Aplicar parâmetros de chunk loading mais agressivos sem impacto visual.
 *
 * Sem DH instalado, esta classe é um no-op — nenhuma otimização é aplicada.
 */
public class DistantHorizonsCompat {

    private static boolean dhPresent;
    private static boolean checked;

    /**
     * Verificar se Distant Horizons está instalado (cacheado).
     */
    public static boolean isDHPresent() {
        if (!checked) {
            checked = true;
            try {
                dhPresent = ModList.get().isLoaded("distanthorizons");
                if (dhPresent) {
                    CreateOptimizedTrains.LOGGER.info(
                        "COT: Distant Horizons detetado — otimizações DH ativadas.");
                }
            } catch (Exception e) {
                dhPresent = false;
            }
        }
        return dhPresent;
    }

    /**
     * Obter render distance reduzido para jogador num comboio rápido.
     * Com DH presente, podemos reduzir o render distance porque DH cobre o terreno distante.
     *
     * @param originalViewDist render distance original do servidor
     * @param speed velocidade do comboio em m/tick (Create internal)
     * @return render distance ajustado (pode ser o original se DH não estiver presente)
     */
    public static int getAdjustedViewDistance(int originalViewDist, double speed) {
        if (!isDHPresent() || !ModConfig.DH_REDUCE_VIEW_DISTANCE.get()) {
            return originalViewDist;
        }

        double speedBps = Math.abs(speed) * 20.0; // blocos por segundo

        // Só reduzir se velocidade > 10 b/s (comboio em marcha)
        if (speedBps < 10.0) {
            return originalViewDist;
        }

        // Redução proporcional à velocidade:
        // 10 b/s → sem redução
        // 20 b/s → -2 chunks
        // 30 b/s → -3 chunks
        // 40+ b/s → -4 chunks (mínimo configurável)
        int reduction = Math.min(4, (int) (speedBps / 10.0) - 1);
        int minViewDist = ModConfig.DH_MIN_VIEW_DISTANCE.get();
        return Math.max(minViewDist, originalViewDist - reduction);
    }

    /**
     * Obter lookahead ajustado para chunk loading.
     * Com DH, podemos confiar mais no pré-carregamento da rota e reduzir
     * o force-load agressivo (DH cobre o visual).
     *
     * @param configLookahead lookahead configurado pelo utilizador
     * @param speed velocidade do comboio
     * @return lookahead efetivo
     */
    public static int getAdjustedLookahead(int configLookahead, double speed) {
        if (!isDHPresent() || !ModConfig.DH_REDUCE_FORCE_LOAD.get()) {
            return configLookahead;
        }

        // Com DH, o visual já está coberto. Podemos reduzir force-load lookahead
        // em 1-2 chunks, libertando cap global para mais comboios.
        // Mas NUNCA abaixo de 2 (mínimo para evitar entity pop-in).
        return Math.max(2, configLookahead - 2);
    }

    /**
     * Obter cap máximo global de chunks forçadas, ajustado para DH.
     * Com DH, podemos ter menos chunks forçadas sem impacto visual.
     */
    public static int getAdjustedGlobalChunkCap(int defaultCap) {
        if (!isDHPresent() || !ModConfig.DH_REDUCE_FORCE_LOAD.get()) {
            return defaultCap;
        }
        // Reduzir cap em 30% — liberta RAM e I/O
        return Math.max(30, (int) (defaultCap * 0.7));
    }
}
