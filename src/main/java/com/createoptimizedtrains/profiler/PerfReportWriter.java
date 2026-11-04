package com.createoptimizedtrains.profiler;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Escreve o relatório do Analisador de Desempenho em .minecraft/cot-reports/.
 *
 * Formato: JSON com "schema" (versão do formato), "meta" (ambiente), "summary",
 * "serverSamples", "clientSamples" e "events". Comprimido em .json.gz quando passa
 * de ~1 MB. Não inclui nome/UUID do jogador, IP, seed nem coordenadas.
 */
public final class PerfReportWriter {

    public static final String GITHUB_NEW_ISSUE =
        "https://github.com/GreenToastSoftware/create-optimized-trains/issues/new";
    public static final String DISCORD_INVITE = "https://discord.com/invite/QFBFJPJ2YD";

    private static final int SCHEMA_VERSION = 1;
    private static final int GZIP_THRESHOLD_BYTES = 1024 * 1024;
    private static final int MAX_ISSUE_BODY_CHARS = 1800;
    private static final String ISSUE_TEMPLATE = "performance.yml";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new Gson();

    private PerfReportWriter() {
    }

    /** Resultado mostrado no ecrã de resumo. */
    public record Report(Path file, List<String> summaryLines, String githubUrl) {
    }

    public static Path reportsDir() {
        return FMLPaths.GAMEDIR.get().resolve("cot-reports");
    }

    /**
     * @param environment dados extra do ambiente (GPU, opções gráficas), ou null
     */
    public static Report write(PerfRecorder.RecordingData data, JsonObject environment) throws IOException {
        JsonObject summary = buildSummary(data);

        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        root.add("meta", buildMeta(data, environment));
        root.add("summary", summary);
        root.add("serverSamples", toArray(data.serverSamples()));
        root.add("clientSamples", toArray(data.clientSamples()));
        root.add("events", toArray(data.events()));

        Path dir = reportsDir();
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

        String compact = GSON_COMPACT.toJson(root);
        Path file;
        if (compact.length() > GZIP_THRESHOLD_BYTES) {
            file = dir.resolve("cot-report-" + stamp + ".json.gz");
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
                 Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                w.write(compact);
            }
        } else {
            file = dir.resolve("cot-report-" + stamp + ".json");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        }

        List<String> lines = summaryLines(summary);
        CreateOptimizedTrains.LOGGER.info("COT: Performance Analyzer — relatório gravado em {}", file);
        return new Report(file, lines, buildGithubUrl(summary, root.getAsJsonObject("meta"), file));
    }

    // ======================== Meta ========================

    private static JsonObject buildMeta(PerfRecorder.RecordingData data, JsonObject environment) {
        JsonObject meta = new JsonObject();
        meta.addProperty("createdAt", LocalDateTime.now().toString());
        meta.addProperty("durationSeconds", (data.endMillis() - data.startMillis()) / 1000);
        meta.addProperty("modVersion", modVersion(CreateOptimizedTrains.MOD_ID));
        meta.addProperty("createVersion", modVersion("create"));
        meta.addProperty("forgeVersion", modVersion("forge"));
        meta.addProperty("minecraftVersion", modVersion("minecraft"));
        meta.addProperty("serverData", !data.serverSamples().isEmpty());

        JsonObject system = new JsonObject();
        system.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        system.addProperty("java", System.getProperty("java.version"));
        system.addProperty("cpuCores", Runtime.getRuntime().availableProcessors());
        system.addProperty("maxMemoryMB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        meta.add("system", system);

        if (environment != null) {
            meta.add("client", environment);
        }

        JsonArray mods = new JsonArray();
        List<IModInfo> infos = new ArrayList<>(ModList.get().getMods());
        infos.sort(Comparator.comparing(IModInfo::getModId));
        for (IModInfo info : infos) {
            mods.add(info.getModId() + " " + info.getVersion());
        }
        meta.add("mods", mods);

        Path config = FMLPaths.CONFIGDIR.get().resolve(CreateOptimizedTrains.MOD_ID + "-common.toml");
        try {
            meta.addProperty("config", Files.readString(config));
        } catch (IOException e) {
            meta.addProperty("config", "unavailable");
        }
        return meta;
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
            .map(c -> c.getModInfo().getVersion().toString())
            .orElse("not installed");
    }

    // ======================== Resumo ========================

    private static JsonObject buildSummary(PerfRecorder.RecordingData data) {
        JsonObject s = new JsonObject();
        s.addProperty("durationSeconds", (data.endMillis() - data.startMillis()) / 1000);

        List<JsonObject> server = data.serverSamples();
        if (!server.isEmpty()) {
            double msptWeighted = 0, msptMax = 0, modMs = 0;
            int ticks = 0, slow = 0, maxActive = 0, maxGhost = 0, maxTrains = 0, maxForced = 0;
            long stutter = 0, outside = 0, missing = 0, trainTicks = 0;
            for (JsonObject o : server) {
                int t = num(o, "ticks").intValue();
                ticks += t;
                msptWeighted += num(o, "msptAvg").doubleValue() * t;
                modMs += num(o, "modMsAvg").doubleValue() * t;
                msptMax = Math.max(msptMax, num(o, "msptMax").doubleValue());
                slow += num(o, "slowTicks").intValue();
                maxTrains = Math.max(maxTrains, num(o, "trains").intValue());
                maxActive = Math.max(maxActive, num(o, "trainsActive").intValue());
                maxGhost = Math.max(maxGhost, num(o, "trainsGhost").intValue());
                maxForced = Math.max(maxForced, num(o, "forcedChunks").intValue());
                trainTicks += num(o, "trainTicks").longValue();
                stutter += num(o, "stutterTicks").longValue();
                outside += num(o, "outsideChunkTicks").longValue();
                missing += num(o, "missingEntityTicks").longValue();
            }
            s.addProperty("msptAvg", PerfRecorder.round(ticks > 0 ? msptWeighted / ticks : 0));
            s.addProperty("msptMax", PerfRecorder.round(msptMax));
            s.addProperty("tpsAvg", PerfRecorder.round(ticks > 0 ? Math.min(20.0, 1000.0 / Math.max(50.0, msptWeighted / ticks)) : 0));
            s.addProperty("slowTicks", slow);
            s.addProperty("modMsAvg", PerfRecorder.round(ticks > 0 ? modMs / ticks : 0));
            s.addProperty("trainsMax", maxTrains);
            s.addProperty("trainsActiveMax", maxActive);
            s.addProperty("trainsGhostMax", maxGhost);
            s.addProperty("forcedChunksMax", maxForced);
            s.addProperty("stutterPercent", PerfRecorder.round(trainTicks > 0 ? 100.0 * stutter / trainTicks : 0));
            s.addProperty("outsideChunkTicks", outside);
            s.addProperty("missingEntityTicks", missing);

            JsonArray top = new JsonArray();
            data.stutterTicksByTrain().entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("train", e.getKey().toString().substring(0, 8));
                    o.addProperty("stutterTicks", e.getValue());
                    top.add(o);
                });
            s.add("topStutterTrains", top);
        }

        List<JsonObject> client = data.clientSamples();
        if (!client.isEmpty()) {
            double fpsSum = 0, fpsMin = Double.MAX_VALUE, worstP95 = 0, maxMem = 0;
            for (JsonObject o : client) {
                double fps = num(o, "fps").doubleValue();
                fpsSum += fps;
                fpsMin = Math.min(fpsMin, fps);
                worstP95 = Math.max(worstP95, num(o, "frameP95Ms").doubleValue());
                maxMem = Math.max(maxMem, num(o, "memUsedMB").doubleValue());
            }
            s.addProperty("fpsAvg", PerfRecorder.round(fpsSum / client.size()));
            s.addProperty("fpsMin", PerfRecorder.round(fpsMin));
            s.addProperty("frameP95WorstMs", PerfRecorder.round(worstP95));
            s.addProperty("memUsedMaxMB", (long) maxMem);
        }

        s.addProperty("events", data.events().size());
        return s;
    }

    private static List<String> summaryLines(JsonObject s) {
        List<String> lines = new ArrayList<>();
        lines.add("Duration: " + s.get("durationSeconds").getAsLong() + " s");
        if (s.has("fpsAvg")) {
            lines.add(String.format("FPS: %.0f avg, %.0f min  |  worst frame p95: %.1f ms",
                d(s, "fpsAvg"), d(s, "fpsMin"), d(s, "frameP95WorstMs")));
        }
        if (s.has("msptAvg")) {
            lines.add(String.format("Server: %.1f MSPT avg, %.1f max  |  %d slow ticks",
                d(s, "msptAvg"), d(s, "msptMax"), s.get("slowTicks").getAsInt()));
            lines.add(String.format("Trains: %d (max %d active, %d ghost)  |  forced chunks max: %d",
                s.get("trainsMax").getAsInt(), s.get("trainsActiveMax").getAsInt(),
                s.get("trainsGhostMax").getAsInt(), s.get("forcedChunksMax").getAsInt()));
            lines.add(String.format("Train stutter: %.1f%% of train ticks  |  mod time: %.2f ms/tick",
                d(s, "stutterPercent"), d(s, "modMsAvg")));
        } else {
            lines.add("Server data: not available (multiplayer — client data only)");
        }
        return lines;
    }

    // ======================== GitHub ========================

    private static String buildGithubUrl(JsonObject summary, JsonObject meta, Path file) {
        StringBuilder body = new StringBuilder();
        body.append("**Describe the problem:**\n\n(what happened, where, when)\n\n");
        body.append("**Report file:** please drag `").append(file.getFileName())
            .append("` (from `.minecraft/cot-reports/`) into this issue.\n\n");
        body.append("**Summary**\n");
        for (String line : summaryLines(summary)) {
            body.append("- ").append(line).append('\n');
        }
        body.append("\n**Versions**\n");
        body.append("- Create Optimized Trains: ").append(str(meta, "modVersion")).append('\n');
        body.append("- Create: ").append(str(meta, "createVersion")).append('\n');
        body.append("- Forge: ").append(str(meta, "forgeVersion")).append('\n');
        body.append("- Minecraft: ").append(str(meta, "minecraftVersion")).append('\n');

        String text = body.length() > MAX_ISSUE_BODY_CHARS
            ? body.substring(0, MAX_ISSUE_BODY_CHARS) : body.toString();

        // Campos do formulário .github/ISSUE_TEMPLATE/performance.yml (preenchidos pelo id).
        // "body" fica como alternativa: o GitHub ignora-o quando o template existe.
        StringBuilder summaryField = new StringBuilder();
        for (String line : summaryLines(summary)) {
            summaryField.append("- ").append(line).append('\n');
        }
        String versionsField = "- Create Optimized Trains: " + str(meta, "modVersion")
            + "\n- Create: " + str(meta, "createVersion")
            + "\n- Forge: " + str(meta, "forgeVersion")
            + "\n- Minecraft: " + str(meta, "minecraftVersion");

        return GITHUB_NEW_ISSUE
            + "?template=" + ISSUE_TEMPLATE
            + "&title=" + encode("[Performance] ")
            + "&summary=" + encode(summaryField.toString())
            + "&versions=" + encode(versionsField)
            + "&report=" + encode(file.getFileName().toString())
            + "&body=" + encode(text);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ======================== Utilitários ========================

    private static JsonArray toArray(List<JsonObject> list) {
        JsonArray arr = new JsonArray();
        list.forEach(arr::add);
        return arr;
    }

    private static Number num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsNumber() : 0;
    }

    private static double d(JsonObject o, String key) {
        return num(o, key).doubleValue();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null ? e.getAsString() : "?";
    }
}
