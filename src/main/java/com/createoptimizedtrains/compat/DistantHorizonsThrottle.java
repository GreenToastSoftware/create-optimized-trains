package com.createoptimizedtrains.compat;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * Reduz temporariamente a geração de terreno distante e threads do Distant Horizons
 * nos primeiros segundos após o mundo carregar, para que o terreno vanilla (chunks
 * reais, entidades, contraptions do Create) tenha prioridade de CPU/IO durante o
 * arranque. Os valores originais do utilizador são restaurados depois.
 *
 * Usa apenas a API pública do DH via reflection (soft dependency) — sem DH instalado
 * esta classe é um no-op.
 */
public class DistantHorizonsThrottle {

    private static final String WORLD_GEN_CFG = "com.seibel.distanthorizons.core.api.external.methods.config.common.DhApiWorldGenerationConfig";
    private static final String THREADING_CFG = "com.seibel.distanthorizons.core.api.external.methods.config.client.DhApiMultiThreadingConfig";

    private static boolean checked;
    private static boolean dhPresent;

    private static long tickCounter = 0;
    private static boolean throttled = false;
    private static boolean restored = true;

    // Valores originais do utilizador, capturados antes de aplicar o throttle
    private static Object originalWorldGenEnabled;
    private static Object originalThreadCount;
    private static Object originalThreadRatio;

    private DistantHorizonsThrottle() {
    }

    private static boolean isDhPresent() {
        if (!checked) {
            checked = true;
            try {
                dhPresent = ModList.get().isLoaded("distanthorizons");
            } catch (Exception e) {
                dhPresent = false;
            }
        }
        return dhPresent;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!isDhPresent() || !ModConfig.DH_STARTUP_THROTTLE_ENABLED.get()) return;

        tickCounter++;

        if (tickCounter == 1) {
            applyThrottle();
            return;
        }

        if (!restored && tickCounter >= ModConfig.DH_STARTUP_THROTTLE_TICKS.get()) {
            restoreOriginal();
        }
    }

    private static void applyThrottle() {
        try {
            Object worldGenValue = getConfigValue(WORLD_GEN_CFG, "enableDistantWorldGeneration");
            if (worldGenValue != null) {
                originalWorldGenEnabled = callGetValue(worldGenValue);
                callSetValue(worldGenValue, Boolean.FALSE);
            }

            Object threadCountValue = getConfigValue(THREADING_CFG, "threadCount");
            if (threadCountValue != null) {
                originalThreadCount = callGetValue(threadCountValue);
                callSetValue(threadCountValue, Integer.valueOf(1));
            }

            Object threadRatioValue = getConfigValue(THREADING_CFG, "threadRuntimeRatio");
            if (threadRatioValue != null) {
                originalThreadRatio = callGetValue(threadRatioValue);
                callSetValue(threadRatioValue, Double.valueOf(0.1));
            }

            throttled = true;
            restored = false;
            CreateOptimizedTrains.LOGGER.info(
                "COT: Distant Horizons reduzido durante arranque ({} ticks) — terreno vanilla tem prioridade.",
                ModConfig.DH_STARTUP_THROTTLE_TICKS.get());
        } catch (Throwable t) {
            CreateOptimizedTrains.LOGGER.warn("COT: falha ao aplicar throttle ao Distant Horizons — a ignorar.", t);
        }
    }

    private static void restoreOriginal() {
        restored = true;
        if (!throttled) return;

        try {
            if (originalWorldGenEnabled != null) {
                Object v = getConfigValue(WORLD_GEN_CFG, "enableDistantWorldGeneration");
                if (v != null) callSetValue(v, originalWorldGenEnabled);
            }
            if (originalThreadCount != null) {
                Object v = getConfigValue(THREADING_CFG, "threadCount");
                if (v != null) callSetValue(v, originalThreadCount);
            }
            if (originalThreadRatio != null) {
                Object v = getConfigValue(THREADING_CFG, "threadRuntimeRatio");
                if (v != null) callSetValue(v, originalThreadRatio);
            }
            CreateOptimizedTrains.LOGGER.info("COT: Distant Horizons restaurado à configuração original.");
        } catch (Throwable t) {
            CreateOptimizedTrains.LOGGER.warn("COT: falha ao restaurar configuração do Distant Horizons.", t);
        }
    }

    private static Object getConfigValue(String configClassName, String accessorMethod) throws Exception {
        Class<?> clazz = Class.forName(configClassName);
        Object instance = clazz.getField("INSTANCE").get(null);
        Method accessor = clazz.getMethod(accessorMethod);
        return accessor.invoke(instance);
    }

    private static Object callGetValue(Object configValue) throws Exception {
        Method getValue = configValue.getClass().getMethod("getValue");
        return getValue.invoke(configValue);
    }

    private static void callSetValue(Object configValue, Object value) throws Exception {
        for (Method m : configValue.getClass().getMethods()) {
            if (m.getName().equals("setValue") && m.getParameterCount() == 1) {
                m.invoke(configValue, value);
                return;
            }
        }
    }
}
