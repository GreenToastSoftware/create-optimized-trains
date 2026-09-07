package com.createoptimizedtrains.diagnostics;

/**
 * Master switch para logging verboso/periódico do mod (diagnóstico, rendering pipeline,
 * chunk loading). Mensagens de arranque/encerramento e erros reais NÃO são controladas
 * por este switch — continuam sempre visíveis.
 *
 * Builds de release devem ter ENABLED=false (evita sobrecarregar o log de jogadores
 * com dezenas de comboios). Durante desenvolvimento, mudar para true.
 */
public final class DebugLog {

    public static final boolean ENABLED = true;

    private DebugLog() {
    }
}
