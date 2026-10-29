package com.createoptimizedtrains.monitor;

import com.createoptimizedtrains.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Boss bar vanilla (barra de progresso + texto) mostrada aos jogadores enquanto o
 * carregamento faseado do mundo (WorldLoadPhaseManager) ainda não chegou a FULL.
 * Usa só API vanilla de BossEvent — sem mixins no cliente, sem risco de soft-lock.
 */
public class LoadingProgressBar {

    private static final int UPDATE_INTERVAL_TICKS = 5;

    private static final ServerBossEvent BAR = new ServerBossEvent(
            Component.literal("Loading world..."),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS);

    private static boolean shown = false;
    private static long tickCounter = 0;

    static {
        BAR.setVisible(false);
    }

    private LoadingProgressBar() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ModConfig.STAGED_LOADING_ENABLED.get()) return;
        if (WorldLoadPhaseManager.getPhase() == WorldLoadPhaseManager.Phase.FULL) return;

        BAR.addPlayer(player);
        shown = true;
        update();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BAR.removePlayer(player);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!shown) return;

        tickCounter++;
        if (tickCounter % UPDATE_INTERVAL_TICKS != 0) return;

        if (WorldLoadPhaseManager.getPhase() == WorldLoadPhaseManager.Phase.FULL) {
            hide();
            return;
        }
        update();
    }

    private static void update() {
        WorldLoadPhaseManager.Phase phase = WorldLoadPhaseManager.getPhase();
        float progress = WorldLoadPhaseManager.getPhaseProgress();

        String text;
        BossEvent.BossBarColor color;
        if (phase == WorldLoadPhaseManager.Phase.CORE) {
            text = String.format("Loading world (your train first) - %.0f%%", progress * 100);
            color = BossEvent.BossBarColor.RED;
        } else {
            text = String.format("Loading remaining trains - %.0f%%", progress * 100);
            color = BossEvent.BossBarColor.YELLOW;
        }

        BAR.setName(Component.literal(text));
        BAR.setColor(color);
        BAR.setProgress(Math.max(0.02f, progress));
        BAR.setVisible(true);
    }

    private static void hide() {
        BAR.setVisible(false);
        BAR.removeAllPlayers();
        shown = false;
    }
}
