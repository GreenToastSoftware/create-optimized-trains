package com.createoptimizedtrains.profiler.client;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.profiler.PerfReportWriter;
import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Ecrã de config do mod com o visual do Create (BaseConfigScreen da Catnip).
 *
 * O mod só tem config COMMON: "Client" e "Server" aparecem desativados, como no Create
 * quando uma spec não existe. Por baixo, botões para o Analisador de Desempenho e para
 * reportar problemas (GitHub / Discord).
 */
@OnlyIn(Dist.CLIENT)
public class CotConfigScreen extends BaseConfigScreen {

    private static final int BUTTON_WIDTH = 200;

    public CotConfigScreen(Screen parent) {
        super(parent, CreateOptimizedTrains.MOD_ID);
        withSpecs(null, ModConfig.SPEC, null);
        withButtonLabels("Client Settings", "Mod Settings", "Server Settings");
    }

    @Override
    protected void init() {
        super.init();
        int x = width / 2 - BUTTON_WIDTH / 2;
        // BaseConfigScreen põe os 3 botões em centro-45, centro-15 e centro+15
        int y = height / 2 - 15 + 60;

        addRenderableWidget(CotWidgets.box(font, "Performance Analyzer", x, y, BUTTON_WIDTH,
            () -> minecraft.setScreen(new PerfAnalyzerScreen(this))));

        int half = (BUTTON_WIDTH - 4) / 2;
        addRenderableWidget(CotWidgets.box(font, "Report a problem", x, y + 30, half,
            () -> ConfirmLinkScreen.confirmLinkNow(PerfReportWriter.GITHUB_NEW_ISSUE, this, true)));
        addRenderableWidget(CotWidgets.box(font, "Discord", x + half + 4, y + 30, half,
            () -> ConfirmLinkScreen.confirmLinkNow(PerfReportWriter.DISCORD_INVITE, this, true)));
    }
}
