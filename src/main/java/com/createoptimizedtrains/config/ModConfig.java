package com.createoptimizedtrains.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.DoubleValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;

public class ModConfig {

    public static final ForgeConfigSpec SPEC;

    // --- LOD ---
    public static final IntValue LOD_FULL_DISTANCE;
    public static final IntValue LOD_MEDIUM_DISTANCE;
    public static final IntValue LOD_LOW_DISTANCE;
    public static final IntValue GHOST_DISTANCE;

    // --- Agrupamento ---
    public static final BooleanValue GROUPING_ENABLED;
    public static final IntValue GROUPING_MIN_CARRIAGES;

    // --- Threading ---
    public static final IntValue THREAD_POOL_SIZE;

    // --- Tick Throttling ---
    public static final BooleanValue THROTTLING_ENABLED;
    public static final IntValue THROTTLE_MEDIUM_INTERVAL;
    public static final IntValue THROTTLE_LOW_INTERVAL;
    public static final IntValue THROTTLE_GHOST_INTERVAL;

    // --- Renderização ---
    public static final BooleanValue RENDER_OPTIMIZATION_ENABLED;
    public static final BooleanValue DISABLE_DISTANT_ANIMATIONS;
    public static final BooleanValue DISABLE_DISTANT_PARTICLES;
    public static final IntValue RENDER_WARMUP_FRAMES;

    // --- Proxy Entities ---
    public static final BooleanValue PROXY_ENABLED;

    // --- Chunk Loading ---
    public static final BooleanValue SMART_CHUNK_LOADING;
    public static final IntValue CHUNK_LOOKAHEAD;
    public static final IntValue CHUNK_TRAIL_KEEP;

    // --- Directional Chunk Loading ---
    public static final BooleanValue DIRECTIONAL_CHUNK_LOADING;
    public static final IntValue DIRECTIONAL_FORWARD_CHUNKS;
    public static final IntValue DIRECTIONAL_BACKWARD_CHUNKS;
    public static final IntValue DIRECTIONAL_SIDE_CHUNKS;

    // --- Física ---
    public static final BooleanValue PHYSICS_OPTIMIZATION_ENABLED;
    public static final BooleanValue DISABLE_DISTANT_COLLISIONS;

    // --- Networking ---
    public static final BooleanValue NETWORK_OPTIMIZATION_ENABLED;
    public static final IntValue DISTANT_SYNC_INTERVAL;

    // --- Prioridades ---
    public static final BooleanValue PRIORITY_SYSTEM_ENABLED;

    // --- Performance Monitor ---
    public static final BooleanValue PERFORMANCE_MONITOR_ENABLED;
    public static final IntValue TPS_LOW_THRESHOLD;
    public static final IntValue TPS_HIGH_THRESHOLD;

    // --- Debug Overlay ---
    public static final BooleanValue DEBUG_OVERLAY_ENABLED;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("=== Create Optimized Trains - Configuração ===").push("general");

        // LOD
        builder.comment("Sistema LOD (Level of Detail)").push("lod");
        LOD_FULL_DISTANCE = builder
                .comment("Distância máxima (em blocos) para detail completo")
                .defineInRange("fullDistance", 48, 16, 256);
        LOD_MEDIUM_DISTANCE = builder
                .comment("Distância máxima (em blocos) para detail médio")
                .defineInRange("mediumDistance", 96, 32, 512);
        LOD_LOW_DISTANCE = builder
                .comment("Distância máxima (em blocos) para detail baixo")
                .defineInRange("lowDistance", 192, 64, 1024);
        GHOST_DISTANCE = builder
                .comment("Distância (em blocos) a partir da qual comboios entram em modo fantasma",
                         "Modo fantasma reduz significativamente o render para poupar FPS",
                         "Aumenta este valor se queres ver comboios mais longe antes de ficarem fantasma")
                .defineInRange("ghostDistance", 256, 32, 2048);
        builder.pop();

        // Agrupamento
        builder.comment("Agrupamento / Abstração de Carruagens").push("grouping");
        GROUPING_ENABLED = builder
                .comment("Ativar agrupamento de carruagens em entidades lógicas")
                .define("enabled", true);
        GROUPING_MIN_CARRIAGES = builder
                .comment("Número mínimo de carruagens para agrupar")
                .defineInRange("minCarriages", 5, 2, 100);
        builder.pop();

        // Threading
        builder.comment("Multi-threading").push("threading");
        THREAD_POOL_SIZE = builder
                .comment("Número de threads auxiliares para cálculos pesados")
                .defineInRange("poolSize", 2, 1, 8);
        builder.pop();

        // Tick Throttling
        builder.comment("Tick Throttling - Atualização Parcial").push("throttling");
        THROTTLING_ENABLED = builder
                .comment("Ativar atualização parcial de carruagens")
                .define("enabled", true);
        THROTTLE_MEDIUM_INTERVAL = builder
                .comment("Intervalo de ticks para carruagens em LOD médio")
                .defineInRange("mediumInterval", 3, 1, 20);
        THROTTLE_LOW_INTERVAL = builder
                .comment("Intervalo de ticks para carruagens em LOD baixo")
                .defineInRange("lowInterval", 8, 2, 40);
        THROTTLE_GHOST_INTERVAL = builder
                .comment("Intervalo de ticks para carruagens fantasma")
                .defineInRange("ghostInterval", 20, 5, 100);
        builder.pop();

        // Renderização
        builder.comment("Otimização de Renderização (cliente)").push("rendering");
        RENDER_OPTIMIZATION_ENABLED = builder
                .comment("Ativar otimização de renderização")
                .define("enabled", true);
        DISABLE_DISTANT_ANIMATIONS = builder
                .comment("Desativar animações para comboios distantes")
                .define("disableDistantAnimations", true);
        DISABLE_DISTANT_PARTICLES = builder
                .comment("Reduzir partículas para comboios distantes")
                .define("disableDistantParticles", true);
        RENDER_WARMUP_FRAMES = builder
                .comment("Frames de atraso antes de comboios começarem a renderizar ao entrar em chunks",
                         "Reduz spike de FPS quando comboios aparecem pela primeira vez",
                         "0 = sem warmup, 1 = mínimo recomendado, 3+ = mais suave para PCs fracos")
                .defineInRange("warmupFrames", 1, 0, 10);
        builder.pop();

        // Proxy Entities
        builder.comment("Proxy Entities").push("proxy");
        PROXY_ENABLED = builder
                .comment("Usar entidades proxy leves para comboios distantes")
                .define("enabled", true);
        builder.pop();

        // Chunk Loading
        builder.comment("Gestão Inteligente de Chunk Loading").push("chunks");
        SMART_CHUNK_LOADING = builder
                .comment("Ativar gestão inteligente de chunks")
                .define("enabled", true);
        CHUNK_LOOKAHEAD = builder
                .comment("Chunks mínimos a pré-carregar à frente do comboio (adaptativo por velocidade)")
                .defineInRange("lookahead", 4, 0, 16);
        CHUNK_TRAIL_KEEP = builder
                .comment("Chunks a manter atrás do comboio antes de descarregar")
                .defineInRange("trailKeep", 1, 0, 4);
        builder.pop();

        // Directional Chunk Loading
        builder.comment("Carregamento Direcional de Chunks",
                "Quando o jogador está num comboio, redistribui as chunks visuais",
                "para carregar mais no sentido do movimento e menos para os lados.",
                "Ideal para quem usa Distant Horizons.").push("directional_chunks");
        DIRECTIONAL_CHUNK_LOADING = builder
                .comment("Ativar carregamento direcional de chunks quando num comboio")
                .define("enabled", true);
        DIRECTIONAL_FORWARD_CHUNKS = builder
                .comment("Chunks a carregar à frente do comboio (no sentido do movimento)")
                .defineInRange("forwardChunks", 8, 2, 32);
        DIRECTIONAL_BACKWARD_CHUNKS = builder
                .comment("Chunks a carregar atrás do comboio")
                .defineInRange("backwardChunks", 6, 2, 32);
        DIRECTIONAL_SIDE_CHUNKS = builder
                .comment("Chunks a carregar para os lados do comboio")
                .defineInRange("sideChunks", 3, 1, 16);
        builder.pop();

        // Física
        builder.comment("Otimização de Física").push("physics");
        PHYSICS_OPTIMIZATION_ENABLED = builder
                .comment("Ativar otimização de física")
                .define("enabled", true);
        DISABLE_DISTANT_COLLISIONS = builder
                .comment("Desativar colisões entre carruagens distantes")
                .define("disableDistantCollisions", true);
        builder.pop();

        // Networking
        builder.comment("Otimização de Sincronização/Networking").push("networking");
        NETWORK_OPTIMIZATION_ENABLED = builder
                .comment("Ativar otimização de networking")
                .define("enabled", true);
        DISTANT_SYNC_INTERVAL = builder
                .comment("Intervalo de sync (ticks) para comboios distantes")
                .defineInRange("distantSyncInterval", 20, 5, 100);
        builder.pop();

        // Prioridades
        builder.comment("Sistema de Prioridades e Horários").push("priority");
        PRIORITY_SYSTEM_ENABLED = builder
                .comment("Ativar sistema de prioridades para comboios")
                .define("enabled", true);
        builder.pop();

        // Performance Monitor
        builder.comment("Monitor de Performance Dinâmico").push("monitor");
        PERFORMANCE_MONITOR_ENABLED = builder
                .comment("Ativar monitor de performance adaptativo")
                .define("enabled", true);
        TPS_LOW_THRESHOLD = builder
                .comment("TPS abaixo deste valor = reduzir fidelidade")
                .defineInRange("tpsLowThreshold", 15, 5, 20);
        TPS_HIGH_THRESHOLD = builder
                .comment("TPS acima deste valor = restaurar fidelidade normal")
                .defineInRange("tpsHighThreshold", 18, 10, 20);
        builder.pop();

        // Debug Overlay
        builder.comment("Overlay de debug no ecrã F3").push("debug_overlay");
        DEBUG_OVERLAY_ENABLED = builder
                .comment("Mostrar informações do mod no ecrã de debug (F3)",
                         "Inclui memória, threads, TPS/MSPT, FPS, chunks forçados")
                .define("enabled", false);
        builder.pop();

        builder.pop(); // general

        SPEC = builder.build();
    }
}
