package com.createoptimizedtrains.profiler.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Registo da parte cliente do Analisador de Desempenho. Chamado só no cliente
 * (DistExecutor), para que as classes de ecrã nunca sejam carregadas num servidor dedicado.
 */
@OnlyIn(Dist.CLIENT)
public final class PerfClientSetup {

    private PerfClientSetup() {
    }

    /** Abre o Analisador de Desempenho. Sem tecla por padrão (Options > Controls). */
    public static final KeyMapping OPEN_ANALYZER = new KeyMapping(
        "key.create_optimized_trains.performance_analyzer",
        KeyConflictContext.IN_GAME,
        InputConstants.UNKNOWN,
        "key.categories.create_optimized_trains");

    public static void register(IEventBus modBus) {
        MinecraftForge.EVENT_BUS.register(PerfClientHooks.class);
        modBus.addListener(PerfClientHooks::registerOverlay);
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN_ANALYZER));

        // Botão "Config" na lista de mods → ecrã de config estilo Create (+ Analisador)
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new CotConfigScreen(parent)));
    }
}
