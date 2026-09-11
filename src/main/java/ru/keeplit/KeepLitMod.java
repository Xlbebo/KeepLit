package ru.keeplit;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mod(KeepLitMod.MODID)
public class KeepLitMod {

    public static final String MODID = "keeplit";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path configPath;
    private KeepLitConfig config;

    private final Path keeplitDir;
    private SessionStore sessionStore;

    private HttpServer httpServer;

    public KeepLitMod() {
        // 1. Конфиг
        configPath = FMLPaths.CONFIGDIR.get().resolve("keeplit.json");
        config = KeepLitConfig.loadOrCreate(configPath);

        // 2. Папка для данных мода
        keeplitDir = FMLPaths.GAMEDIR.get().resolve("keeplit");
        try { Files.createDirectories(keeplitDir); } catch (Exception ignored) {}

        sessionStore = new SessionStore(keeplitDir);

        // 3. Слушатели
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);

        LOGGER.info("[KeepLit] Мод инициализирован.");
    }

    // --- События сервера ---

    private void onServerStarted(ServerStartedEvent event) {
        if (!config.enabled || !config.web.enabled) return;
        startWebServer();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (sessionStore != null) {
            sessionStore.closeAllActiveSessions();
        }
        stopWebServer();
    }

    // --- События игроков ---

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sessionStore.startSession(player.getUUID(), player.getGameProfile().getName());
            LOGGER.info("[KeepLit] Вход: {}", player.getGameProfile().getName());
        }
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sessionStore.closeActiveSession(player.getUUID());
            LOGGER.info("[KeepLit] Выход: {}", player.getGameProfile().getName());
        }
    }

    // --- Веб-сервер ---

    private void startWebServer() {
        try {
            InetSocketAddress address = new InetSocketAddress(config.web.host, config.web.port);
            httpServer = HttpServer.create(address, 0);

            httpServer.createContext("/", exchange -> {
                List<Session> sessions = sessionStore.getSessions();
                List<StatsCalculator.PlayerStats> stats = StatsCalculator.calculate(sessions);

                long serverTotalMs = stats.stream().mapToLong(s -> s.totalMs).sum();
                long serverMinutes = serverTotalMs / (1000 * 60);
                String serverFormatted = String.format("%d ч %d мин", serverMinutes / 60, serverMinutes % 60);

                StringBuilder tableRows = new StringBuilder();
                for (StatsCalculator.PlayerStats p : stats) {
                    tableRows.append(String.format(
                        "<tr><td>%s</td><td>%s</td></tr>",
                        escapeHtml(p.name),
                        p.getFormattedTime()
                    ));
                }

                String html = """
                        <!DOCTYPE html>
                        <html lang="ru">
                        <head>
                            <meta charset="UTF-8">
                            <title>KeepLit - Статистика</title>
                            <style>
                                body { background: #202020; color: #e8e8e8; font-family: monospace; padding: 48px; max-width: 800px; margin: 0 auto; }
                                h1 { color: #ff9900; text-align: center; }
                                .card { background: #2d2d2d; border: 1px solid #444; padding: 24px; margin-bottom: 20px; }
                                .total { font-size: 24px; color: #4caf50; text-align: center; margin-bottom: 10px; }
                                table { width: 100%%; border-collapse: collapse; }
                                th, td { padding: 8px 12px; text-align: left; border-bottom: 1px solid #444; }
                                th { color: #ff9900; }
                            </style>
                        </head>
                        <body>
                            <h1>🔥 KeepLit</h1>

                            <div class="card">
                                <div class="total">Общее время сервера: %s</div>
                            </div>

                            <div class="card">
                                <h2>Игроки</h2>
                                <table>
                                    <thead>
                                        <tr>
                                            <th>Ник</th>
                                            <th>Время за период</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        %s
                                    </tbody>
                                </table>
                            </div>
                        </body>
                        </html>
                        """.formatted(serverFormatted, tableRows.toString());

                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            });

            httpServer.setExecutor(null);
            httpServer.start();
            LOGGER.info("[KeepLit] ✅ Веб-сервер запущен: http://localhost:{}", config.web.port);
        } catch (IOException e) {
            LOGGER.error("[KeepLit] ❌ Не удалось запустить веб-сервер", e);
        }
    }

    private void stopWebServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOGGER.info("[KeepLit] Веб-сервер остановлен.");
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
