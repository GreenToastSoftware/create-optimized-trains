package com.createoptimizedtrains.rendering;

import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnóstico para o fix de vidros em contraptions.
 * Escreve log para ficheiro: .minecraft/logs/glass_diagnostics.log
 *
 * Corre em DUAS fases:
 * 1. runStartupDiagnostics() — chamado em onClientSetup, corre SEMPRE
 * 2. recordGetBufferCall() — chamado pelo @Redirect, só se o redirect funcionar
 */
@OnlyIn(Dist.CLIENT)
public class GlassDiagnostics {

    private static PrintWriter logWriter;
    private static final AtomicInteger redirectCalls = new AtomicInteger(0);
    private static final AtomicInteger translucentSwaps = new AtomicInteger(0);
    private static volatile boolean firstCallLogged = false;

    /**
     * Chamado no onClientSetup — GARANTIDO que corre.
     */
    public static void runStartupDiagnostics() {
        try {
            Path logPath = Paths.get("logs", "glass_diagnostics.log");
            logWriter = new PrintWriter(new BufferedWriter(new FileWriter(logPath.toFile(), false)), true);
            log("=== Glass Diagnostics Log ===");
            log("Hora: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            log("");

            // 1. Verificar TRANSLUCENT_NO_DEPTH
            try {
                RenderType custom = ContraptionRenderTypes.TRANSLUCENT_NO_DEPTH;
                log("[OK] TRANSLUCENT_NO_DEPTH criado: " + custom);
            } catch (Throwable t) {
                log("[ERRO] TRANSLUCENT_NO_DEPTH falhou: " + t);
                t.printStackTrace(logWriter);
            }

            // 2. Verificar ContraptionEntityRenderer — TODOS os métodos
            log("");
            log("=== ContraptionEntityRenderer ===");
            try {
                Class<?> cerClass = Class.forName("com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer");
                log("[OK] Classe encontrada: " + cerClass.getName());
                log("Superclass: " + cerClass.getSuperclass().getName());

                log("");
                log("--- Métodos DECLARADOS (inclui private) ---");
                for (Method m : cerClass.getDeclaredMethods()) {
                    String mods = Modifier.toString(m.getModifiers());
                    log("  " + mods + " " + m.getReturnType().getSimpleName() + " " + m.getName() + describeParams(m));
                }

                log("");
                log("--- Métodos PÚBLICOS (inclui herdados) ---");
                for (Method m : cerClass.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    String mods = Modifier.toString(m.getModifiers());
                    log("  [" + m.getDeclaringClass().getSimpleName() + "] " + mods + " " + m.getReturnType().getSimpleName() + " " + m.getName() + describeParams(m));
                }
            } catch (ClassNotFoundException e) {
                log("[ERRO] ContraptionEntityRenderer NÃO encontrado!");
            }

            // 3. Verificar Flywheel / VisualizationManager
            log("");
            log("=== Flywheel ===");
            try {
                Class<?> vizMgr = Class.forName("dev.engine_room.flywheel.api.visualization.VisualizationManager");
                log("[OK] VisualizationManager presente: " + vizMgr.getName());

                // Verificar se supportsVisualization existe
                for (Method m : vizMgr.getDeclaredMethods()) {
                    if (m.getName().contains("support") || m.getName().contains("Visualization")) {
                        log("  método: " + m.getName() + describeParams(m));
                    }
                }
            } catch (ClassNotFoundException e) {
                log("[INFO] VisualizationManager NÃO encontrada — Flywheel ausente ou diferente");
            }

            // 4. Verificar se o mixin foi aplicado
            log("");
            log("=== Mixin Check ===");
            try {
                Class<?> cerClass = Class.forName("com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer");
                // Procurar campos/métodos injetados pelo nosso mixin
                boolean foundMixinTrace = false;
                for (Method m : cerClass.getDeclaredMethods()) {
                    // Mixins injetados pelo Redirect criam métodos auxiliares com prefixo handler$
                    if (m.getName().contains("handler$") || m.getName().contains("redirect$") ||
                        m.getName().contains("useNoDepthForTranslucent") ||
                        m.getName().contains("optimizedtrains")) {
                        log("[MIXIN DETECTADO] " + m.getName() + describeParams(m));
                        foundMixinTrace = true;
                    }
                }
                if (!foundMixinTrace) {
                    log("[AVISO] Nenhum método do mixin detectado na classe!");
                    log("Isto significa que o @Redirect NÃO foi aplicado.");
                    log("Possíveis causas:");
                    log("  - O nome do método 'render'/'m_7392_' não corresponde ao nome real em runtime");
                    log("  - O mixin JSON não registou o mixin correctamente");
                    log("  - O target class foi carregado antes do mixin ser aplicado");
                }
            } catch (Exception e) {
                log("[ERRO] Falha ao verificar mixin: " + e);
            }

            // 5. Verificar CarriageContraptionEntityRenderer
            log("");
            log("=== CarriageContraptionEntityRenderer ===");
            try {
                Class<?> ccerClass = Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer");
                log("[OK] Classe encontrada: " + ccerClass.getName());
                log("Superclass: " + ccerClass.getSuperclass().getName());
                for (Method m : ccerClass.getDeclaredMethods()) {
                    String mods = Modifier.toString(m.getModifiers());
                    log("  " + mods + " " + m.getReturnType().getSimpleName() + " " + m.getName() + describeParams(m));
                }
            } catch (ClassNotFoundException e) {
                log("[ERRO] CarriageContraptionEntityRenderer NÃO encontrado!");
            }

            log("");
            log("=== Fim dos diagnósticos de startup ===");
            log("A aguardar chamadas do @Redirect (aparecerão abaixo)...");
            log("");
            logWriter.flush();

        } catch (Exception e) {
            // Não crashar o jogo
            e.printStackTrace();
        }
    }

    /**
     * Chamado pelo @Redirect — cada vez que getBuffer() é interceptado.
     */
    public static void recordGetBufferCall(RenderType type) {
        int count = redirectCalls.incrementAndGet();

        if (!firstCallLogged && logWriter != null) {
            firstCallLogged = true;
            synchronized (GlassDiagnostics.class) {
                log("");
                log("[REDIRECT ATIVO!] Primeira chamada: " + LocalDateTime.now());
                log("  RenderType: " + type);
                log("  Stack trace:");
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (int i = 2; i < Math.min(stack.length, 15); i++) {
                    log("    " + stack[i]);
                }
                logWriter.flush();
            }
        }

        // Resumo a cada 1000 chamadas
        if (count % 1000 == 0 && logWriter != null) {
            synchronized (GlassDiagnostics.class) {
                log("[RESUMO] Chamadas: " + count + " | Substituições translucent: " + translucentSwaps.get());
                logWriter.flush();
            }
        }
    }

    /**
     * Chamado quando substituímos translucent → TRANSLUCENT_NO_DEPTH.
     */
    public static void recordTranslucentRedirect() {
        translucentSwaps.incrementAndGet();
    }

    private static String describeParams(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        sb.append(")");
        return sb.toString();
    }

    private static void log(String msg) {
        if (logWriter != null) {
            logWriter.println(msg);
        }
    }
}
