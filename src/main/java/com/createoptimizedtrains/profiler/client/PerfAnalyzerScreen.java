package com.createoptimizedtrains.profiler.client;

import com.createoptimizedtrains.profiler.PerfRecorder;
import com.createoptimizedtrains.profiler.PerfReportWriter;
import net.createmod.catnip.config.ui.ConfigScreen;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Ecrã do Analisador de Desempenho (em inglês, para todos os jogadores), com o visual
 * do ecrã de config do Create (fundo com engrenagem + botões BoxWidget da Catnip).
 *
 * Três estados: pronto a gravar → a gravar → relatório gravado (resumo + botões para
 * abrir a pasta, copiar para Transferências, reportar no GitHub e entrar no Discord).
 * Abre a partir do ecrã de config do mod, do botão no menu de pausa e da tecla de atalho.
 */
@OnlyIn(Dist.CLIENT)
public class PerfAnalyzerScreen extends ConfigScreen {

    private static final int BUTTON_WIDTH = 150;
    private static final int GAP = 6;
    private static final int ROW = CotWidgets.HEIGHT + GAP;

    private static final int COLOR_TITLE = 0xFFFFFF;
    private static final int COLOR_TEXT = 0xDDDDDD;
    private static final int COLOR_RECORDING = 0xFF5555;
    private static final int COLOR_OK = 0x55FF55;
    private static final int COLOR_ERROR = 0xFF5555;

    private String status;
    private int statusColor = 0xAAAAAA;
    private boolean wasRecording;

    public PerfAnalyzerScreen(Screen parent) {
        super(parent);
    }

    @Override
    protected void init() {
        super.init();
        wasRecording = PerfRecorder.isRecording();

        int cx = width / 2;
        int left = cx - BUTTON_WIDTH - GAP / 2;
        int right = cx + GAP / 2;
        int full = BUTTON_WIDTH * 2 + GAP;
        int bottom = height - 30;

        if (PerfRecorder.isRecording()) {
            addRenderableWidget(CotWidgets.box(font, "Stop & save report", left, bottom - ROW, BUTTON_WIDTH, () -> {
                PerfReportWriter.Report report = PerfClientHooks.stopAndSave();
                setStatus(report != null ? null : "Could not save the report: " + PerfClientHooks.getLastError(),
                    COLOR_ERROR);
                refresh();
            }));
            addRenderableWidget(CotWidgets.box(font, "Discard recording", right, bottom - ROW, BUTTON_WIDTH, () -> {
                PerfClientHooks.cancelRecording();
                setStatus("Recording discarded.", 0xAAAAAA);
                refresh();
            }));
            addRenderableWidget(CotWidgets.box(font, "Back to game (keep recording)", left, bottom, full,
                () -> minecraft.setScreen(null)));
            return;
        }

        PerfReportWriter.Report report = PerfClientHooks.getLastReport();
        if (report != null) {
            addRenderableWidget(CotWidgets.box(font, "Open reports folder", left, bottom - ROW * 2, BUTTON_WIDTH,
                this::openReportsFolder));
            addRenderableWidget(CotWidgets.box(font, "Copy to Downloads", right, bottom - ROW * 2, BUTTON_WIDTH,
                () -> copyToDownloads(report)));
            addRenderableWidget(CotWidgets.box(font, "Report on GitHub", left, bottom - ROW, BUTTON_WIDTH,
                () -> ConfirmLinkScreen.confirmLinkNow(report.githubUrl(), this, true)));
            addRenderableWidget(CotWidgets.box(font, "Join our Discord", right, bottom - ROW, BUTTON_WIDTH,
                () -> ConfirmLinkScreen.confirmLinkNow(PerfReportWriter.DISCORD_INVITE, this, true)));
            addRenderableWidget(CotWidgets.box(font, "New recording", left, bottom, BUTTON_WIDTH,
                this::startRecording, PerfClientHooks.canRecord()));
            addRenderableWidget(CotWidgets.box(font, "Done", right, bottom, BUTTON_WIDTH, this::onClose));
            return;
        }

        addRenderableWidget(CotWidgets.box(font, "Start recording", left, bottom - ROW, full,
            this::startRecording, PerfClientHooks.canRecord()));
        addRenderableWidget(CotWidgets.box(font, "Open reports folder", left, bottom, BUTTON_WIDTH,
            this::openReportsFolder));
        addRenderableWidget(CotWidgets.box(font, "Done", right, bottom, BUTTON_WIDTH, this::onClose));
    }

    @Override
    public void tick() {
        super.tick();
        // A gravação pode parar sozinha (limite de 5 min) com o ecrã aberto
        if (wasRecording != PerfRecorder.isRecording()) {
            refresh();
        }
    }

    @Override
    protected void renderWindow(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        super.renderWindow(g, mouseX, mouseY, partialTicks);
        int cx = width / 2;
        g.drawCenteredString(font, Component.literal("Performance Analyzer"), cx, 14, COLOR_TITLE);
        g.drawCenteredString(font, Component.literal("Create Optimized Trains"), cx, 26, 0x999999);

        List<String> lines = new ArrayList<>();
        int firstColor = COLOR_TEXT;
        if (PerfRecorder.isRecording()) {
            long s = PerfRecorder.elapsedMillis() / 1000;
            long max = PerfRecorder.MAX_DURATION_MS / 1000;
            lines.add(String.format("● Recording — %02d:%02d (stops automatically at %02d:%02d)",
                s / 60, s % 60, max / 60, max % 60));
            firstColor = COLOR_RECORDING;
            lines.add("");
            lines.add("Go back to the game and play where the problem happens");
            lines.add("(ride the train, walk around, watch the chunks load).");
            lines.add("Then open this screen again and press \"Stop & save report\".");
        } else if (PerfClientHooks.getLastReport() != null) {
            PerfReportWriter.Report report = PerfClientHooks.getLastReport();
            lines.add("Report saved: " + report.file().getFileName());
            firstColor = COLOR_OK;
            lines.add("");
            lines.addAll(report.summaryLines());
            lines.add("");
            lines.add("To report a problem, open a GitHub issue or post on Discord");
            lines.add("and drag the report file into it. Thank you for helping!");
        } else {
            lines.add("Records performance data while you play: FPS, server ticks,");
            lines.add("trains and chunk loading. Play normally where the problem");
            lines.add("happens, then stop the recording to save a report file.");
            lines.add("");
            lines.add("No personal data is recorded (no name, IP, seed or coordinates).");
            lines.add("Reports are saved in .minecraft/cot-reports/. Max 5 minutes.");
            if (!PerfClientHooks.canRecord()) {
                lines.add("");
                lines.add("Join a world to start recording.");
            }
        }

        int y = 48;
        for (int i = 0; i < lines.size(); i++) {
            g.drawCenteredString(font, lines.get(i), cx, y, i == 0 ? firstColor : COLOR_TEXT);
            y += font.lineHeight + 3;
        }
        if (status != null) {
            g.drawCenteredString(font, status, cx, y + 6, statusColor);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void startRecording() {
        if (!PerfClientHooks.canRecord()) return;
        PerfClientHooks.startRecording();
        setStatus(null, 0);
        // Voltar ao jogo para gravar logo
        minecraft.setScreen(null);
    }

    private void openReportsFolder() {
        Path dir = PerfReportWriter.reportsDir();
        try {
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
        } catch (IOException e) {
            setStatus("Could not open the folder: " + e.getMessage(), COLOR_ERROR);
        }
    }

    private void copyToDownloads(PerfReportWriter.Report report) {
        Path downloads = Paths.get(System.getProperty("user.home"), "Downloads");
        if (!Files.isDirectory(downloads)) {
            setStatus("Downloads folder not found — use \"Open reports folder\" instead.", COLOR_ERROR);
            return;
        }
        try {
            Path target = downloads.resolve(report.file().getFileName());
            Files.copy(report.file(), target, StandardCopyOption.REPLACE_EXISTING);
            setStatus("Copied to " + target, COLOR_OK);
        } catch (IOException e) {
            setStatus("Could not copy the file: " + e.getMessage(), COLOR_ERROR);
        }
    }

    private void setStatus(String text, int color) {
        status = text;
        statusColor = color;
    }

    private void refresh() {
        clearWidgets();
        init();
    }
}
