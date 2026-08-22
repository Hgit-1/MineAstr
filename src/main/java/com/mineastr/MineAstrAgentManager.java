package com.mineastr;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryComponent;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

/** Owns the isolated Node/Mineflayer process. The control API is loopback-only and token authenticated. */
public final class MineAstrAgentManager implements AutoCloseable {
    private static final String RUNTIME_RESOURCE = "/mineastr-agent/runtime.zip";
    private static final String NODE_VERSION = "22.19.0";
    private static final int MIN_NODE_MAJOR = 22;
    private static final int MAX_CONTROL_BODY_CHARS = 512 * 1024;
    private static final Set<String> NEOFORGE_CONFIGURATION_CHANNELS = Set.of(
            "neoforge:extensible_enum_data", "neoforge:extensible_enum_ack",
            "neoforge:feature_flags", "neoforge:feature_flags_ack");
    private static final Map<String, NodeArtifact> NODE_ARTIFACTS = Map.of(
            "linux-x86_64", new NodeArtifact(
                    "https://nodejs.org/dist/v" + NODE_VERSION + "/node-v" + NODE_VERSION + "-linux-x64.tar.gz",
                    "d36e56998220085782c0ca965f9d51b7726335aed2f5fc7321c6c0ad233aa96d", "tar.gz", "bin/node"),
            "linux-aarch64", new NodeArtifact(
                    "https://nodejs.org/dist/v" + NODE_VERSION + "/node-v" + NODE_VERSION + "-linux-arm64.tar.gz",
                    "d32817b937219b8f131a28546035183d79e7fd17a86e38ccb8772901a7cd9009", "tar.gz", "bin/node"),
            "windows-x86_64", new NodeArtifact(
                    "https://nodejs.org/dist/v" + NODE_VERSION + "/node-v" + NODE_VERSION + "-win-x64.zip",
                    "ea3fad0e67a991d8477d8c01344b56e69c676ccb733f065b22436994b1253f86", "zip", "node.exe"));

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile ExecutorService ioExecutor = newIoExecutor();
    private final AtomicReference<State> state = new AtomicReference<>(State.DISABLED);
    private final AtomicInteger controlPort = new AtomicInteger();
    private final AtomicInteger restartCount = new AtomicInteger();
    private final AtomicInteger humanPlayerCount = new AtomicInteger();

    private volatile MinecraftServer server;
    private volatile Process process;
    private volatile String token = "";
    private volatile String nodeVersion = "";
    private volatile String lastError = "";
    private volatile String preferredAgentUsername = "MineAstrBot";
    private volatile String previousAgentUsername = "";
    private volatile long startedAtMs;
    private volatile JsonObject lastAgentStatus = new JsonObject();
    private volatile boolean stopping;
    private volatile int neoForgeComponentCount;

    private static ExecutorService newIoExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "MineAstr-Agent-IO");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start(MinecraftServer currentServer) {
        this.server = currentServer;
        this.stopping = false;
        this.lastError = "";
        this.lastAgentStatus = new JsonObject();
        this.controlPort.set(0);
        this.previousAgentUsername = this.preferredAgentUsername;
        this.preferredAgentUsername = resolveAgentUsername(MineAstrConfig.BOT_DISPLAY_NAME.get());
        if (!currentServer.isDedicatedServer()) {
            disable("Agent 只在独立服务器中运行。");
            return;
        }
        if (!MineAstrConfig.ENABLE_AGENT.getAsBoolean()) {
            state.set(State.DISABLED);
            return;
        }
        if (process != null && process.isAlive()) return;
        if (ioExecutor == null || ioExecutor.isShutdown()) ioExecutor = newIoExecutor();
        restartCount.set(0);
        state.set(State.STARTING);
        CompletableFuture.runAsync(this::startProcess, ioExecutor);
    }

    private void startProcess() {
        try {
            MinecraftServer activeServer = server;
            ExecutorService executor = ioExecutor;
            if (stopping || activeServer == null || executor == null || executor.isShutdown()) return;
            Path runtime = prepareRuntime();
            Path executable = resolveNodeExecutable();
            nodeVersion = validateNode(executable);
            token = UUID.randomUUID().toString() + UUID.randomUUID();
            Path dataDir = activeServer.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("data").resolve("mineastr").resolve("agent").toAbsolutePath().normalize();
            Files.createDirectories(dataDir);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), runtime.resolve("index.js").toString());
            builder.directory(runtime.toFile());
            builder.redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("MINEASTR_AGENT_TOKEN", token);
            environment.put("MINEASTR_AGENT_DATA_DIR", dataDir.toString());
            environment.put("MINEASTR_MC_HOST", MineAstrConfig.AGENT_SERVER_HOST.get());
            environment.put("MINEASTR_MC_PORT", Integer.toString(MineAstrConfig.AGENT_SERVER_PORT.getAsInt()));
            environment.put("MINEASTR_MC_VERSION", SharedConstants.getCurrentVersion().getName());
            environment.put("MINEASTR_AGENT_USERNAME", preferredAgentUsername);
            environment.put("MINEASTR_AGENT_AUTH", MineAstrConfig.AGENT_ACCOUNT_MODE.get().toLowerCase(Locale.ROOT));
            environment.put("MINEASTR_AGENT_SESSION_POLICY",
                    MineAstrConfig.AGENT_SESSION_POLICY.get().toLowerCase(Locale.ROOT));
            environment.put("MINEASTR_AGENT_IDLE_DISCONNECT_SECONDS",
                    Integer.toString(MineAstrConfig.AGENT_IDLE_DISCONNECT_SECONDS.getAsInt()));
            environment.put("MINEASTR_NAV_ALLOW_DIGGING",
                    Boolean.toString(MineAstrConfig.AGENT_NAVIGATION_ALLOW_DIGGING.getAsBoolean()));
            environment.put("MINEASTR_NAV_ALLOW_PLACING",
                    Boolean.toString(MineAstrConfig.AGENT_NAVIGATION_ALLOW_PLACING.getAsBoolean()));
            environment.put("MINEASTR_NAV_DIG_COST",
                    Integer.toString(MineAstrConfig.AGENT_NAVIGATION_DIG_COST.getAsInt()));
            environment.put("MINEASTR_NAV_PLACE_COST",
                    Integer.toString(MineAstrConfig.AGENT_NAVIGATION_PLACE_COST.getAsInt()));
            environment.put("MINEASTR_NAV_LIQUID_COST",
                    Integer.toString(MineAstrConfig.AGENT_NAVIGATION_LIQUID_COST.getAsInt()));
            environment.put("MINEASTR_COMBAT_ENABLED",
                    Boolean.toString(MineAstrConfig.AGENT_COMBAT_ENABLED.getAsBoolean()));
            environment.put("MINEASTR_COMBAT_RADIUS",
                    Integer.toString(MineAstrConfig.AGENT_COMBAT_RADIUS.getAsInt()));
            environment.put("MINEASTR_COMBAT_MIN_HEALTH",
                    Integer.toString(MineAstrConfig.AGENT_COMBAT_MIN_HEALTH.getAsInt()));
            environment.put("MINEASTR_COMBAT_ATTACK_COOLDOWN_MS",
                    Integer.toString(MineAstrConfig.AGENT_COMBAT_ATTACK_COOLDOWN_MS.getAsInt()));
            environment.put("MINEASTR_NAV_CACHE_MAX_CHUNKS",
                    Integer.toString(MineAstrConfig.AGENT_NAVIGATION_CACHE_MAX_CHUNKS.getAsInt()));
            if (!MineAstrConfig.AGENT_JOIN_COMMANDS.get().isEmpty()) {
                environment.put("MINEASTR_AGENT_JOIN_COMMANDS", String.join(
                        "\n", MineAstrConfig.AGENT_JOIN_COMMANDS.get().stream().limit(5).toList()));
            }
            environment.put("MINEASTR_AGENT_JOIN_COMMAND_DELAY_MS",
                    Integer.toString(MineAstrConfig.AGENT_JOIN_COMMAND_DELAY_MS.getAsInt()));
            environment.put("MINEASTR_AGENT_JOIN_COMMAND_SETTLE_MS",
                    Integer.toString(MineAstrConfig.AGENT_JOIN_COMMAND_SETTLE_MS.getAsInt()));
            environment.put("MINEASTR_FORBIDDEN_REGIONS", String.join("\n", MineAstrConfig.AGENT_FORBIDDEN_REGIONS.get()));
            if (MineAstrConfig.AGENT_PROXY_PROTOCOL.getAsBoolean()
                    && isLoopbackHost(MineAstrConfig.AGENT_SERVER_HOST.get())) {
                environment.put("MINEASTR_PROXY_PROTOCOL", "true");
            }
            if (MineAstrConfig.AGENT_NEOFORGE_COMPATIBILITY.getAsBoolean()
                    && isLoopbackHost(MineAstrConfig.AGENT_SERVER_HOST.get())) {
                NeoForgeManifest manifest = buildNeoForgeManifest();
                environment.put("MINEASTR_NEOFORGE_QUERY_B64", manifest.encodedQuery());
                environment.put("MINEASTR_NEOFORGE_COMPONENT_COUNT", Integer.toString(manifest.componentCount()));
                neoForgeComponentCount = manifest.componentCount();
            } else {
                neoForgeComponentCount = 0;
            }
            if (stopping || server != activeServer) return;
            Process started = builder.start();
            synchronized (this) {
                if (stopping || server != activeServer) {
                    started.destroyForcibly();
                    return;
                }
                process = started;
                startedAtMs = System.currentTimeMillis();
                state.set(State.WAITING_FOR_CONTROL);
                executor.execute(() -> readOutput(started));
                started.onExit().thenAcceptAsync(this::onProcessExit, executor);
            }
        } catch (Exception exc) {
            if (!stopping) fail("Agent 启动失败：" + safeMessage(exc));
        }
    }

    private Path prepareRuntime() throws IOException {
        String resourceHash;
        try (InputStream raw = MineAstrAgentManager.class.getResourceAsStream(RUNTIME_RESOURCE)) {
            if (raw == null) throw new IOException("JAR 中缺少内嵌 Agent runtime.zip");
            resourceHash = sha256(raw);
        }
        Path root = FMLPaths.GAMEDIR.get().resolve("config").resolve("mineastr")
                .resolve("agent-runtime").resolve(MineAstr.MOD_VERSION + "-" + resourceHash.substring(0, 12))
                .toAbsolutePath().normalize();
        Path marker = root.resolve(".complete");
        if (Files.isRegularFile(marker) && Files.isRegularFile(root.resolve("index.js"))) return root;
        Path temporary = root.resolveSibling(root.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectories(temporary);
        try (InputStream raw = MineAstrAgentManager.class.getResourceAsStream(RUNTIME_RESOURCE)) {
            if (raw == null) throw new IOException("JAR 中缺少内嵌 Agent runtime.zip");
            unzip(raw, temporary);
        } catch (Exception exc) {
            deleteTree(temporary);
            throw exc;
        }
        Files.writeString(temporary.resolve(".complete"), resourceHash, StandardCharsets.UTF_8);
        Files.createDirectories(root.getParent());
        if (!Files.exists(root)) {
            Files.move(temporary, root, StandardCopyOption.ATOMIC_MOVE);
        } else {
            deleteTree(temporary);
        }
        return root;
    }

    private Path resolveNodeExecutable() throws Exception {
        String configured = MineAstrConfig.AGENT_NODE_EXECUTABLE.get().strip();
        Path candidate = Path.of(configured);
        try {
            validateNode(candidate);
            return candidate;
        } catch (Exception configuredFailure) {
            if (!MineAstrConfig.AGENT_AUTO_DOWNLOAD_NODE.getAsBoolean()) {
                throw new IOException("未找到 Node.js 22+：" + configured
                        + "。请安装 Node、设置 agentNodeExecutable，或显式启用 agentAutoDownloadNode。", configuredFailure);
            }
        }
        return downloadNode();
    }

    private String validateNode(Path executable) throws Exception {
        Process validation = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start();
        String output;
        try (InputStream stream = validation.getInputStream()) {
            output = new String(stream.readNBytes(128), StandardCharsets.UTF_8).strip();
        }
        if (!validation.waitFor(5, TimeUnit.SECONDS)) {
            validation.destroyForcibly();
            throw new IOException("Node 版本检查超时");
        }
        if (validation.exitValue() != 0 || !output.matches("v\\d+(?:\\.\\d+){1,2}.*")) {
            throw new IOException("无法识别 Node 版本：" + output);
        }
        int major = Integer.parseInt(output.substring(1, output.indexOf('.')));
        if (major < MIN_NODE_MAJOR) throw new IOException("MineAstr Agent 要求 Node.js 22+，当前为 " + output);
        return output;
    }

    private Path downloadNode() throws Exception {
        String platform = platformKey();
        NodeArtifact artifact = NODE_ARTIFACTS.get(platform);
        if (artifact == null) throw new IOException("自动下载不支持当前平台：" + platform);
        Path nodeRoot = FMLPaths.GAMEDIR.get().resolve("config").resolve("mineastr")
                .resolve("node").resolve(NODE_VERSION + "-" + platform).toAbsolutePath().normalize();
        Path executable = nodeRoot.resolve(artifact.executablePath);
        if (Files.isRegularFile(executable)) {
            validateNode(executable);
            return executable;
        }
        Files.createDirectories(nodeRoot.getParent());
        Path archive = Files.createTempFile(nodeRoot.getParent(), "node-", ".download");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(artifact.url))
                    .timeout(Duration.ofMinutes(3)).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(archive));
            if (response.statusCode() != 200) throw new IOException("Node 下载返回 HTTP " + response.statusCode());
            String actualHash = sha256(archive);
            if (!artifact.sha256.equals(actualHash)) throw new IOException("Node 下载文件 SHA-256 不匹配");
            Path temporary = nodeRoot.resolveSibling(nodeRoot.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.createDirectories(temporary);
            if ("zip".equals(artifact.format)) {
                try (InputStream input = Files.newInputStream(archive)) {
                    unzipStrippingFirstDirectory(input, temporary);
                }
            } else {
                try (InputStream input = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
                    untarStrippingFirstDirectory(input, temporary);
                }
            }
            if (!Files.exists(nodeRoot)) Files.move(temporary, nodeRoot, StandardCopyOption.ATOMIC_MOVE);
            else deleteTree(temporary);
            executable.toFile().setExecutable(true, true);
            validateNode(executable);
            return executable;
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private void readOutput(Process activeProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(activeProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String safeLine = line.length() > 2000 ? line.substring(0, 2000) : line;
                try {
                    JsonObject payload = JsonParser.parseString(safeLine).getAsJsonObject();
                    String type = payload.has("type") ? payload.get("type").getAsString() : "";
                    if ("ready".equals(type) && payload.has("port")) {
                        controlPort.set(payload.get("port").getAsInt());
                        state.set(State.RUNNING);
                        MineAstr.LOGGER.info("MineAstr Agent 控制端已就绪：127.0.0.1:{}", controlPort.get());
                        syncSessionPresence();
                    } else if ("bot_error".equals(type) || "bot_incompatible".equals(type)
                            || "uncaught_exception".equals(type)) {
                        lastError = payload.has("error") ? payload.get("error").getAsString() : type;
                        MineAstr.LOGGER.warn("MineAstr Agent：{}", lastError);
                    } else if ("bot_offline".equals(type) && payload.has("session_exit")
                            && payload.get("session_exit").isJsonObject()) {
                        JsonObject exit = payload.getAsJsonObject("session_exit");
                        String code = exit.has("code") ? exit.get("code").getAsString() : "unknown";
                        boolean expected = exit.has("expected") && exit.get("expected").getAsBoolean();
                        String detail = exit.has("detail") ? safeLogText(exit.get("detail").getAsString()) : "";
                        MineAstr.LOGGER.info("MineAstr Agent 游戏会话结束：code={} expected={} detail={}",
                                code, expected, detail);
                    } else if ("bot_death".equals(type)) {
                        MineAstr.LOGGER.warn("MineAstr Agent 在游戏中死亡；寻路任务将由生命保护安全中止。");
                    } else if ("navigation_watchdog_triggered".equals(type)) {
                        JsonObject diagnostics = payload.has("diagnostics")
                                && payload.get("diagnostics").isJsonObject()
                                ? payload.getAsJsonObject("diagnostics") : null;
                        String pathUpdate = diagnostics != null && diagnostics.has("last_path_update")
                                && diagnostics.get("last_path_update").isJsonObject()
                                ? safeLogText(diagnostics.getAsJsonObject("last_path_update").toString()) : "none";
                        MineAstr.LOGGER.warn(
                                "MineAstr Agent 寻路看门狗触发：attempt={} code={} position={} moving={} mining={} building={} reset={} path={}",
                                sanitizeAudit(jsonString(payload, "attempt", "?")),
                                sanitizeAudit(jsonString(payload, "code", "unknown")),
                                safeLogText(payload.has("position") ? payload.get("position").toString() : "unknown"),
                                jsonString(diagnostics, "moving", "false"),
                                jsonString(diagnostics, "mining", "false"),
                                jsonString(diagnostics, "building", "false"),
                                safeLogText(jsonString(diagnostics, "last_path_reset", "none")),
                                pathUpdate);
                    } else if ("navigation_pathfinder_early_resolve".equals(type)) {
                        String pathUpdate = payload.has("path_update")
                                && payload.get("path_update").isJsonObject()
                                ? safeLogText(payload.getAsJsonObject("path_update").toString()) : "none";
                        MineAstr.LOGGER.info(
                                "MineAstr Agent 局部寻路提前返回但尚未到达，继续等待区块与路径更新：attempt={} elapsed_ms={} position={} path={}",
                                sanitizeAudit(jsonString(payload, "attempt", "?")),
                                sanitizeAudit(jsonString(payload, "elapsed_ms", "?")),
                                safeLogText(payload.has("position") ? payload.get("position").toString() : "unknown"),
                                pathUpdate);
                    } else if ("combat_started".equals(type) && payload.has("target")
                            && payload.get("target").isJsonObject()) {
                        JsonObject target = payload.getAsJsonObject("target");
                        MineAstr.LOGGER.info("MineAstr Agent 开始自主防卫：target={} id={} distance={}",
                                sanitizeAudit(jsonString(target, "name", "unknown")),
                                sanitizeAudit(jsonString(target, "id", "?")),
                                sanitizeAudit(jsonString(payload, "distance", "?")));
                    } else if ("combat_error".equals(type)) {
                        MineAstr.LOGGER.warn("MineAstr Agent 自主防卫失败：{}",
                                safeLogText(jsonString(payload, "error", "unknown")));
                    } else if ("autonomous_retreat".equals(type)) {
                        MineAstr.LOGGER.warn("MineAstr Agent 自主撤退：threat={} reason={} target={}",
                                sanitizeAudit(jsonString(payload, "threat", "unknown")),
                                sanitizeAudit(jsonString(payload, "reason", "threat")),
                                safeLogText(payload.has("target") ? payload.get("target").toString() : "unknown"));
                    } else if ("task_started".equals(type) && payload.has("task")
                            && payload.get("task").isJsonObject()) {
                        JsonObject task = payload.getAsJsonObject("task");
                        MineAstr.LOGGER.info("MineAstr Agent 任务开始：id={} type={}",
                                sanitizeAudit(jsonString(task, "task_id", "unknown")),
                                sanitizeAudit(jsonString(task, "task_type", "unknown")));
                    } else if ("task_finished".equals(type) && payload.has("task")
                            && payload.get("task").isJsonObject()) {
                        JsonObject task = payload.getAsJsonObject("task");
                        MineAstr.LOGGER.info("MineAstr Agent 任务结束：id={} type={} state={} detail={}",
                                sanitizeAudit(jsonString(task, "task_id", "unknown")),
                                sanitizeAudit(jsonString(task, "task_type", "unknown")),
                                sanitizeAudit(jsonString(task, "state", "unknown")),
                                safeLogText(jsonString(task, "message", "")));
                    } else if ("bot_idle_disconnect".equals(type)) {
                        String lastTask = "none";
                        if (payload.has("last_task") && payload.get("last_task").isJsonObject()) {
                            JsonObject task = payload.getAsJsonObject("last_task");
                            lastTask = sanitizeAudit(jsonString(task, "task_id", "unknown")) + "/"
                                    + sanitizeAudit(jsonString(task, "state", "unknown"));
                        }
                        MineAstr.LOGGER.info("MineAstr Agent 闲置断开已触发：idle_seconds={} last_task={}",
                                payload.has("idle_seconds") ? payload.get("idle_seconds").getAsInt() : -1,
                                lastTask);
                    } else {
                        MineAstr.LOGGER.debug("MineAstr Agent：{}", safeLine);
                    }
                } catch (RuntimeException ignored) {
                    if (state.get() == State.STARTING || state.get() == State.WAITING_FOR_CONTROL) {
                        MineAstr.LOGGER.warn("MineAstr Agent 启动输出：{}", safeLogText(safeLine));
                    } else {
                        MineAstr.LOGGER.debug("MineAstr Agent 输出：{}", safeLine.replaceAll("[\\r\\n]", " "));
                    }
                }
            }
        } catch (IOException exc) {
            if (!stopping) MineAstr.LOGGER.debug("MineAstr Agent 输出流已关闭：{}", safeMessage(exc));
        }
    }

    private void onProcessExit(Process exited) {
        if (process != exited) return;
        process = null;
        controlPort.set(0);
        if (stopping) {
            state.set(State.STOPPED);
            return;
        }
        int exitCode = exited.exitValue();
        fail("Agent 进程意外退出，代码 " + exitCode);
        if (System.currentTimeMillis() - startedAtMs >= 60_000L) restartCount.set(0);
        if (restartCount.incrementAndGet() <= 3 && server != null && MineAstrConfig.ENABLE_AGENT.getAsBoolean()) {
            try {
                TimeUnit.SECONDS.sleep(5L * restartCount.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!stopping) startProcess();
        } else if (restartCount.get() > 3) {
            lastError += "；连续重启超过上限，已熔断至下次服务端重启。";
        }
    }

    public JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("enabled", MineAstrConfig.ENABLE_AGENT.getAsBoolean());
        result.addProperty("state", state.get().name().toLowerCase(Locale.ROOT));
        result.addProperty("node_version", nodeVersion);
        result.addProperty("control_ready", controlPort.get() > 0);
        result.addProperty("started_at_ms", startedAtMs);
        result.addProperty("restart_count", restartCount.get());
        result.addProperty("neoforge_compatibility_enabled", MineAstrConfig.AGENT_NEOFORGE_COMPATIBILITY.getAsBoolean());
        result.addProperty("neoforge_manifest_components", neoForgeComponentCount);
        result.addProperty("proxy_protocol_enabled", MineAstrConfig.AGENT_PROXY_PROTOCOL.getAsBoolean()
                && isLoopbackHost(MineAstrConfig.AGENT_SERVER_HOST.get()));
        result.addProperty("session_policy", MineAstrConfig.AGENT_SESSION_POLICY.get());
        result.addProperty("human_player_count", humanPlayerCount.get());
        result.addProperty("idle_disconnect_seconds", MineAstrConfig.AGENT_IDLE_DISCONNECT_SECONDS.getAsInt());
        result.addProperty("preferred_username", preferredAgentUsername);
        JsonObject joinCommands = new JsonObject();
        joinCommands.addProperty("configured_count", Math.min(5, MineAstrConfig.AGENT_JOIN_COMMANDS.get().size()));
        joinCommands.addProperty("command_delay_ms", MineAstrConfig.AGENT_JOIN_COMMAND_DELAY_MS.getAsInt());
        joinCommands.addProperty("settle_delay_ms", MineAstrConfig.AGENT_JOIN_COMMAND_SETTLE_MS.getAsInt());
        result.add("join_command_config", joinCommands);
        JsonObject navigation = new JsonObject();
        navigation.addProperty("allow_digging", MineAstrConfig.AGENT_NAVIGATION_ALLOW_DIGGING.getAsBoolean());
        navigation.addProperty("allow_placing", MineAstrConfig.AGENT_NAVIGATION_ALLOW_PLACING.getAsBoolean());
        navigation.addProperty("dig_cost", MineAstrConfig.AGENT_NAVIGATION_DIG_COST.getAsInt());
        navigation.addProperty("place_cost", MineAstrConfig.AGENT_NAVIGATION_PLACE_COST.getAsInt());
        navigation.addProperty("liquid_cost", MineAstrConfig.AGENT_NAVIGATION_LIQUID_COST.getAsInt());
        navigation.addProperty("cache_max_chunks", MineAstrConfig.AGENT_NAVIGATION_CACHE_MAX_CHUNKS.getAsInt());
        result.add("navigation_config", navigation);
        JsonObject combat = new JsonObject();
        combat.addProperty("enabled", MineAstrConfig.AGENT_COMBAT_ENABLED.getAsBoolean());
        combat.addProperty("radius", MineAstrConfig.AGENT_COMBAT_RADIUS.getAsInt());
        combat.addProperty("minimum_health", MineAstrConfig.AGENT_COMBAT_MIN_HEALTH.getAsInt());
        combat.addProperty("attack_cooldown_ms", MineAstrConfig.AGENT_COMBAT_ATTACK_COOLDOWN_MS.getAsInt());
        result.add("combat_config", combat);
        Process current = process;
        if (current != null) result.addProperty("pid", current.pid());
        if (!lastError.isBlank()) result.addProperty("last_error", lastError);
        if (lastAgentStatus != null && !lastAgentStatus.isEmpty()) result.add("agent", lastAgentStatus.deepCopy());
        result.addProperty("renderer_configured", !MineAstrConfig.AGENT_CLIENT_INSTANCE_PATH.get().isBlank());
        result.addProperty("renderer_min_free_memory_mb", MineAstrConfig.AGENT_RENDERER_MIN_FREE_MEMORY_MB.getAsInt());
        JsonObject rendererGate = new JsonObject();
        long freeMemoryMb = freePhysicalMemoryMb();
        double averageMspt = averageMspt();
        boolean pathReady = rendererInstanceReady();
        boolean memoryReady = freeMemoryMb < 0
                || freeMemoryMb >= MineAstrConfig.AGENT_RENDERER_MIN_FREE_MEMORY_MB.getAsInt();
        boolean tickReady = averageMspt < 0 || averageMspt < 47.5;
        rendererGate.addProperty("eligible", pathReady && memoryReady && tickReady);
        rendererGate.addProperty("instance_ready", pathReady);
        rendererGate.addProperty("free_physical_memory_mb", freeMemoryMb);
        rendererGate.addProperty("average_mspt", averageMspt);
        rendererGate.addProperty("memory_ready", memoryReady);
        rendererGate.addProperty("tick_ready", tickReady);
        rendererGate.addProperty("max_session_minutes", MineAstrConfig.AGENT_RENDERER_MAX_MINUTES.getAsInt());
        if (!pathReady) rendererGate.addProperty("reason", "未配置有效的独立客户端实例目录");
        else if (!memoryReady) rendererGate.addProperty("reason", "可用内存低于渲染熔断阈值");
        else if (!tickReady) rendererGate.addProperty("reason", "服务端 MSPT 接近过载阈值");
        result.add("renderer_gate", rendererGate);
        return result;
    }

    private boolean rendererInstanceReady() {
        String configured = MineAstrConfig.AGENT_CLIENT_INSTANCE_PATH.get().strip();
        if (configured.isBlank()) return false;
        try {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isDirectory(path) && Files.isDirectory(path.resolve("mods"));
        } catch (RuntimeException exc) {
            return false;
        }
    }

    private long freePhysicalMemoryMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean) {
                return bean.getFreeMemorySize() / (1024L * 1024L);
            }
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    private double averageMspt() {
        MinecraftServer current = server;
        if (current == null) return -1;
        try {
            return Math.round((current.getAverageTickTimeNanos() / 1_000_000.0) * 100.0) / 100.0;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    public CompletableFuture<JsonObject> request(String endpoint, JsonObject body, Duration timeout) {
        if (!endpoint.matches("/(?:status|observe|task|cancel|waypoints|session)")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("不支持的 Agent 端点"));
        }
        int port = controlPort.get();
        if (state.get() != State.RUNNING || port <= 0) {
            return CompletableFuture.failedFuture(new IllegalStateException(lastError.isBlank() ? "Agent 尚未就绪" : lastError));
        }
        try {
            validateRequest(endpoint, body == null ? new JsonObject() : body);
        } catch (RuntimeException exc) {
            return CompletableFuture.failedFuture(exc);
        }
        if ("/task".equals(endpoint)) {
            String taskId = jsonString(body, "task_id", "auto");
            String taskType = jsonString(body, "task_type", "unknown");
            String requester = jsonString(body, "requester_name", jsonString(body, "requester_id", "unknown"));
            MineAstr.LOGGER.info("MineAstr Agent 任务审计：id={} type={} requester={}",
                    sanitizeAudit(taskId), sanitizeAudit(taskType), sanitizeAudit(requester));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.body().length() > MAX_CONTROL_BODY_CHARS) throw new IllegalStateException("Agent 响应过大");
                    JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (response.statusCode() >= 400) {
                        String error = parsed.has("error") ? parsed.get("error").getAsString() : "HTTP " + response.statusCode();
                        throw new IllegalStateException(error);
                    }
                    if ("/status".equals(endpoint) || "/observe".equals(endpoint)) lastAgentStatus = parsed.deepCopy();
                    return parsed;
                });
    }

    public void updateHumanPlayerCount(int count) {
        humanPlayerCount.set(Math.max(0, count));
        syncSessionPresence();
    }

    public void updateBotDisplayName(String displayName) {
        String resolved = resolveAgentUsername(displayName);
        if (!resolved.equals(preferredAgentUsername)) {
            previousAgentUsername = preferredAgentUsername;
            preferredAgentUsername = resolved;
            MineAstr.LOGGER.info("MineAstr Agent 玩家名已同步为 {}，下次登录生效。", resolved);
        }
        syncSessionPresence();
    }

    public boolean isAgentUsername(String playerName) {
        return playerName != null && (playerName.equalsIgnoreCase(preferredAgentUsername)
                || (!previousAgentUsername.isBlank() && playerName.equalsIgnoreCase(previousAgentUsername)));
    }

    private static String resolveAgentUsername(String displayName) {
        return MineAstrAgentIdentity.resolve(
                MineAstrConfig.AGENT_USE_BOT_DISPLAY_NAME_AS_USERNAME.getAsBoolean(),
                displayName,
                MineAstrConfig.SERVER_NAME.get(),
                MineAstrConfig.AGENT_USERNAME.get());
    }

    private void syncSessionPresence() {
        if (state.get() != State.RUNNING || controlPort.get() <= 0 || stopping) return;
        JsonObject body = new JsonObject();
        body.addProperty("human_player_count", humanPlayerCount.get());
        body.addProperty("preferred_username", preferredAgentUsername);
        request("/session", body, Duration.ofSeconds(3)).whenComplete((ignored, throwable) -> {
            if (throwable != null && !stopping) {
                Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                MineAstr.LOGGER.debug("MineAstr Agent 玩家会话状态同步失败：{}", safeMessage(cause));
            }
        });
    }

    private static void validateRequest(String endpoint, JsonObject body) {
        if (!"/task".equals(endpoint)) return;
        if (!MineAstrConfig.AGENT_FULL_AUTONOMY.getAsBoolean()
                && (!body.has("approved_by_admin") || !body.get("approved_by_admin").getAsBoolean())) {
            throw new IllegalStateException("服务端已关闭 Agent 完全自主模式；任务需要管理员审批。");
        }
        String type = body.has("task_type") ? body.get("task_type").getAsString().toLowerCase(Locale.ROOT) : "";
        if (!Set.of("chat", "crouch_greet", "goto", "goto_waypoint", "follow_player", "look_at",
                "wait", "eat", "interact_block", "use_item").contains(type)) {
            throw new IllegalArgumentException("服务端不允许任务类型：" + type);
        }
        JsonObject args = body.has("args") && body.get("args").isJsonObject()
                ? body.getAsJsonObject("args") : new JsonObject();
        if ("chat".equals(type) && args.has("message") && args.get("message").getAsString().length() > 256) {
            throw new IllegalArgumentException("Agent 聊天内容超过 256 字符");
        }
        if (Set.of("goto", "look_at", "interact_block").contains(type)) {
            int x = requiredCoordinate(args, "x");
            int y = requiredCoordinate(args, "y");
            int z = requiredCoordinate(args, "z");
            String dimension = args.has("dimension") ? args.get("dimension").getAsString() : "minecraft:overworld";
            if (insideForbiddenRegion(dimension, x, y, z)) throw new IllegalStateException("目标坐标位于 Agent 禁区内");
        }
    }

    private static int requiredCoordinate(JsonObject args, String name) {
        if (!args.has(name)) throw new IllegalArgumentException("任务缺少坐标 " + name);
        int value = args.get(name).getAsInt();
        if (Math.abs((long) value) > 30_000_000L) throw new IllegalArgumentException("坐标超出世界边界：" + name);
        return value;
    }

    private static boolean insideForbiddenRegion(String dimension, int x, int y, int z) {
        for (String configured : MineAstrConfig.AGENT_FORBIDDEN_REGIONS.get()) {
            String[] fields = configured.split(",");
            if (fields.length != 7) continue;
            try {
                if (!fields[0].strip().equals(dimension)) continue;
                int minX = Math.min(Integer.parseInt(fields[1].strip()), Integer.parseInt(fields[4].strip()));
                int minY = Math.min(Integer.parseInt(fields[2].strip()), Integer.parseInt(fields[5].strip()));
                int minZ = Math.min(Integer.parseInt(fields[3].strip()), Integer.parseInt(fields[6].strip()));
                int maxX = Math.max(Integer.parseInt(fields[1].strip()), Integer.parseInt(fields[4].strip()));
                int maxY = Math.max(Integer.parseInt(fields[2].strip()), Integer.parseInt(fields[5].strip()));
                int maxZ = Math.max(Integer.parseInt(fields[3].strip()), Integer.parseInt(fields[6].strip()));
                if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) return true;
            } catch (NumberFormatException ignored) {
                // Invalid administrator entries are reported in status by the configuration UI; they never grant access.
            }
        }
        return false;
    }

    private void disable(String error) {
        lastError = error;
        state.set(State.DISABLED);
        MineAstr.LOGGER.info("MineAstr Agent 已禁用：{}", error);
    }

    private void fail(String error) {
        lastError = error;
        state.set(State.ERROR);
        MineAstr.LOGGER.warn("{}", error);
    }

    @Override
    public synchronized void close() {
        stopping = true;
        state.set(State.STOPPING);
        Process active = process;
        process = null;
        controlPort.set(0);
        if (active != null && active.isAlive()) {
            active.destroy();
            try {
                if (!active.waitFor(3, TimeUnit.SECONDS)) active.destroyForcibly();
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                active.destroyForcibly();
            }
        }
        state.set(State.STOPPED);
        server = null;
        ExecutorService executor = ioExecutor;
        ioExecutor = null;
        if (executor != null) executor.shutdownNow();
    }

    private static boolean isLoopbackHost(String value) {
        String host = value.strip().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)
                || "[::1]".equals(host);
    }

    @SuppressWarnings("unchecked")
    private static NeoForgeManifest buildNeoForgeManifest() throws Exception {
        Field registrationsField = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        registrationsField.setAccessible(true);
        Map<ConnectionProtocol, Map<?, PayloadRegistration<?>>> registrations =
                (Map<ConnectionProtocol, Map<?, PayloadRegistration<?>>>) registrationsField.get(null);
        Map<ConnectionProtocol, Set<ModdedNetworkQueryComponent>> selected = new IdentityHashMap<>();
        int count = 0;
        for (Map.Entry<ConnectionProtocol, Map<?, PayloadRegistration<?>>> protocol : registrations.entrySet()) {
            Set<ModdedNetworkQueryComponent> components = new HashSet<>();
            for (PayloadRegistration<?> registration : protocol.getValue().values()) {
                // Some mods register a payload as optional but still send it
                // unconditionally when a player joins. NeoForge rejects that
                // send unless the peer advertised the optional channel. Only
                // advertise optional PLAY clientbound channels: optional
                // CONFIGURATION channels can start mod-specific configuration
                // tasks that Mineflayer cannot acknowledge and would stall login.
                boolean supportedConfigurationChannel = NEOFORGE_CONFIGURATION_CHANNELS.contains(
                        registration.id().toString());
                boolean optionalPlayToClient = protocol.getKey() == ConnectionProtocol.PLAY
                        && registration.matchesFlow(PacketFlow.CLIENTBOUND);
                if (registration.optional() && !supportedConfigurationChannel && !optionalPlayToClient) {
                    continue;
                }
                components.add(new ModdedNetworkQueryComponent(registration));
                count++;
            }
            selected.put(protocol.getKey(), components);
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ModdedNetworkQueryPayload.STREAM_CODEC.encode(buffer, new ModdedNetworkQueryPayload(selected));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return new NeoForgeManifest(Base64.getEncoder().encodeToString(bytes), count);
        } finally {
            buffer.release();
        }
    }

    private static String jsonString(JsonObject body, String name, String fallback) {
        try {
            return body != null && body.has(name) ? body.get(name).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String sanitizeAudit(String value) {
        String sanitized = value == null ? "unknown" : value.replaceAll("[\\r\\n\\t]", " ");
        return sanitized.substring(0, Math.min(100, sanitized.length()));
    }

    private static void unzip(InputStream input, Path target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = safeResolve(target, entry.getName());
                if (entry.isDirectory()) Files.createDirectories(resolved);
                else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void unzipStrippingFirstDirectory(InputStream input, Path target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = stripFirstDirectory(entry.getName());
                if (name.isBlank()) continue;
                Path resolved = safeResolve(target, name);
                if (entry.isDirectory()) Files.createDirectories(resolved);
                else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void untarStrippingFirstDirectory(InputStream input, Path target) throws IOException {
        byte[] header = new byte[512];
        String pendingLongName = null;
        while (true) {
            int read = input.readNBytes(header, 0, header.length);
            if (read == 0) return;
            if (read != header.length) throw new IOException("Node tar 文件被截断");
            boolean empty = true;
            for (byte value : header) if (value != 0) { empty = false; break; }
            if (empty) return;
            String entryName = tarString(header, 0, 100);
            String prefix = tarString(header, 345, 155);
            long size = parseTarOctal(header, 124, 12);
            int type = header[156] & 0xff;
            if (type == 'L' || type == 'x') {
                if (size < 0 || size > 1024 * 1024) throw new IOException("tar 长路径元数据过大");
                byte[] metadata = input.readNBytes((int) size);
                if (metadata.length != size) throw new IOException("tar 长路径元数据被截断");
                String decoded = type == 'L'
                        ? new String(metadata, StandardCharsets.UTF_8).replace("\0", "").strip()
                        : paxPath(metadata);
                if (!decoded.isBlank()) pendingLongName = decoded;
                long metadataPadding = (512 - (size % 512)) % 512;
                if (metadataPadding > 0) input.skipNBytes(metadataPadding);
                continue;
            }
            String originalName = pendingLongName != null
                    ? pendingLongName
                    : prefix.isBlank() ? entryName : prefix + "/" + entryName;
            pendingLongName = null;
            String name = stripFirstDirectory(originalName);
            if (!name.isBlank()) {
                Path resolved = safeResolve(target, name);
                if (type == '5') Files.createDirectories(resolved);
                else if (type == 0 || type == '0') {
                    Files.createDirectories(resolved.getParent());
                    try (var output = Files.newOutputStream(resolved)) {
                        copyExactly(input, output, size);
                    }
                } else {
                    input.skipNBytes(size);
                }
            } else input.skipNBytes(size);
            long padding = (512 - (size % 512)) % 512;
            if (padding > 0) input.skipNBytes(padding);
        }
    }

    private static String paxPath(byte[] metadata) throws IOException {
        int offset = 0;
        while (offset < metadata.length) {
            int space = offset;
            while (space < metadata.length && metadata[space] != ' ') space++;
            if (space >= metadata.length) throw new IOException("无效的 PAX tar 元数据");
            int recordLength;
            try {
                recordLength = Integer.parseInt(
                        new String(metadata, offset, space - offset, StandardCharsets.US_ASCII));
            } catch (NumberFormatException exc) {
                throw new IOException("无效的 PAX tar 记录长度", exc);
            }
            if (recordLength <= 0 || offset + recordLength > metadata.length) {
                throw new IOException("PAX tar 记录被截断");
            }
            String record = new String(
                    metadata,
                    space + 1,
                    recordLength - (space + 1 - offset),
                    StandardCharsets.UTF_8).strip();
            int equals = record.indexOf('=');
            if (equals > 0 && "path".equals(record.substring(0, equals))) {
                return record.substring(equals + 1);
            }
            offset += recordLength;
        }
        return "";
    }

    private static Path safeResolve(Path root, String entryName) throws IOException {
        Path resolved = root.resolve(entryName.replace('\\', '/')).normalize();
        if (!resolved.startsWith(root)) throw new IOException("压缩包包含不安全路径");
        return resolved;
    }

    private static String stripFirstDirectory(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.indexOf('/');
        return slash < 0 ? "" : normalized.substring(slash + 1);
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static long parseTarOctal(byte[] header, int offset, int length) {
        String value = tarString(header, offset, length).strip();
        return value.isEmpty() ? 0 : Long.parseLong(value, 8);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            updateDigest(digest, input);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, input);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("当前 Java 运行时缺少 SHA-256", impossible);
        }
    }

    private static void updateDigest(MessageDigest digest, InputStream input) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
    }

    private static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osPart = os.contains("win") ? "windows" : os.contains("linux") ? "linux" : os.replaceAll("[^a-z0-9]", "");
        String archPart = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
        return osPart + "-" + archPart;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) value = throwable.getClass().getSimpleName();
        return safeLogText(value);
    }

    private static String safeLogText(String value) {
        if (value == null) return "";
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(token|password|secret)\\s*[:=]\\s*\\S+", "$1=[redacted]");
        return sanitized.substring(0, Math.min(300, sanitized.length()));
    }

    private static void copyExactly(InputStream input, java.io.OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("tar 条目被截断");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private enum State { DISABLED, STARTING, WAITING_FOR_CONTROL, RUNNING, ERROR, STOPPING, STOPPED }

    private record NodeArtifact(String url, String sha256, String format, String executablePath) {}

    private record NeoForgeManifest(String encodedQuery, int componentCount) {}

}
