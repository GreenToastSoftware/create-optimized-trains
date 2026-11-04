package com.createoptimizedtrains.profiler;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;

/**
 * /cot profiler start|stop|discard|status — Analisador de Desempenho para servidores
 * (dedicados ou LAN), só para operadores (nível 2).
 *
 * Grava só dados do servidor (MSPT, comboios, chunks); o relatório fica em
 * &lt;pasta do servidor&gt;/cot-reports/. Mensagens em inglês, como o resto da UI.
 */
public final class PerfCommand {

    // Gravação iniciada por comando: este handler trata da paragem automática
    private static boolean startedByCommand = false;

    private PerfCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cot")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("profiler")
                .then(Commands.literal("start").executes(PerfCommand::start))
                .then(Commands.literal("stop").executes(PerfCommand::stop))
                .then(Commands.literal("discard").executes(PerfCommand::discard))
                .then(Commands.literal("status").executes(PerfCommand::status))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        if (PerfRecorder.isRecording()) {
            ctx.getSource().sendFailure(Component.literal("A recording is already running. Use /cot profiler stop."));
            return 0;
        }
        PerfRecorder.start();
        startedByCommand = true;
        long max = PerfRecorder.MAX_DURATION_MS / 60000;
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal(
            "Recording started. Play normally, then run /cot profiler stop (auto-stops after " + max + " min).")),
            true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        if (!PerfRecorder.isRecording()) {
            ctx.getSource().sendFailure(Component.literal("No recording is running. Use /cot profiler start."));
            return 0;
        }
        return saveAndReport(ctx.getSource()) ? 1 : 0;
    }

    private static int discard(CommandContext<CommandSourceStack> ctx) {
        if (!PerfRecorder.isRecording()) {
            ctx.getSource().sendFailure(Component.literal("No recording is running."));
            return 0;
        }
        PerfRecorder.stop();
        startedByCommand = false;
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Recording discarded.")), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        if (!PerfRecorder.isRecording()) {
            ctx.getSource().sendSuccess(() -> prefix().append(Component.literal("Not recording.")), false);
            return 1;
        }
        long s = PerfRecorder.elapsedMillis() / 1000;
        var latest = PerfRecorder.getLatestServer();
        String extra = latest == null ? "" : String.format(" | MSPT %.1f | trains %d active / %d ghost | %d forced chunks",
            latest.get("msptAvg").getAsDouble(), latest.get("trainsActive").getAsInt(),
            latest.get("trainsGhost").getAsInt(), latest.get("forcedChunks").getAsInt());
        ctx.getSource().sendSuccess(() -> prefix().append(Component.literal(
            String.format("Recording for %02d:%02d%s", s / 60, s % 60, extra))), false);
        return 1;
    }

    /** Paragem automática de gravações iniciadas por comando (o cliente trata das suas). */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !startedByCommand) return;
        if (!PerfRecorder.isRecording()) {
            // Parada por outro caminho (ex: ecrã do cliente em singleplayer)
            startedByCommand = false;
            return;
        }
        if (PerfRecorder.elapsedMillis() < PerfRecorder.MAX_DURATION_MS) return;
        MinecraftServer server = event.getServer();
        if (server != null) {
            saveAndReport(server.createCommandSourceStack());
        }
    }

    private static boolean saveAndReport(CommandSourceStack source) {
        PerfRecorder.RecordingData data = PerfRecorder.stop();
        startedByCommand = false;
        try {
            PerfReportWriter.Report report = PerfReportWriter.write(data, null);
            MutableComponent msg = prefix()
                .append(Component.literal("Report saved: " + report.file().toAbsolutePath() + "\n"));
            for (String line : report.summaryLines()) {
                msg.append(Component.literal("  " + line + "\n").withStyle(ChatFormatting.GRAY));
            }
            msg.append(link("[Report on GitHub]", report.githubUrl()))
                .append(Component.literal("  "))
                .append(link("[Discord]", PerfReportWriter.DISCORD_INVITE));
            source.sendSuccess(() -> msg, true);
            return true;
        } catch (IOException | RuntimeException e) {
            CreateOptimizedTrains.LOGGER.error("COT: falha ao gravar o relatório do Performance Analyzer", e);
            source.sendFailure(Component.literal("Could not save the report: " + e.getMessage()));
            return false;
        }
    }

    private static MutableComponent prefix() {
        return Component.literal("[Create Optimized Trains] ").withStyle(ChatFormatting.GOLD);
    }

    private static MutableComponent link(String label, String url) {
        return Component.literal(label).withStyle(Style.EMPTY
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
    }
}
