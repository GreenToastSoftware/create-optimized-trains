package com.createoptimizedtrains.diagnostics;

import com.createoptimizedtrains.config.ModConfig;

/**
 * Master switch para logging verboso/periódico do mod (rendering pipeline, tempos de
 * chunk loading, contadores). Mensagens de arranque/encerramento e erros reais NÃO são
 * controladas por este switch — continuam sempre visíveis.
 *
 * Controlado por diagnostics.verboseLogging na config (desligado por padrão); atualizado
 * quando a config é carregada ou recarregada.
 */
public final class DebugLog {

    public static volatile boolean ENABLED = false;

    public static void refreshFromConfig() {
        try {
            ENABLED = ModConfig.VERBOSE_LOGGING.get();
        } catch (Exception e) {
            ENABLED = false;
        }
    }

    private DebugLog() {
    }
}
