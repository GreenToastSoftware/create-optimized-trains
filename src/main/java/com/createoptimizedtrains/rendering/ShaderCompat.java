package com.createoptimizedtrains.rendering;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * Deteção leve de shader pack ativo sem dependência direta de Oculus/Iris.
 */
public final class ShaderCompat {

    private static long lastCheckNanos = 0L;
    private static boolean lastState = false;

    private ShaderCompat() {
    }

    public static boolean isShaderPackActive() {
        long now = System.nanoTime();
        // Atualizar no máximo 4x por segundo para reduzir overhead de reflexão
        if (now - lastCheckNanos < 250_000_000L) {
            return lastState;
        }
        lastCheckNanos = now;

        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris")) {
            lastState = false;
            return false;
        }

        // API oficial Iris/Oculus (quando disponível)
        try {
            Class<?> apiClazz = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstance = apiClazz.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            Method inUse = apiClazz.getMethod("isShaderPackInUse");
            Object result = inUse.invoke(api);
            if (result instanceof Boolean b) {
                lastState = b;
                return b;
            }
        } catch (Throwable ignored) {
        }

        // Se o mod está carregado mas API não está acessível, assumir ativo para perfil seguro.
        lastState = true;
        return true;
    }
}
