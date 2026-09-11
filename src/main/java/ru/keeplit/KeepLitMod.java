package ru.keeplit;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Mod(KeepLitMod.MODID)
public class KeepLitMod {

    public static final String MODID = "keeplit";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path configPath;

    private KeepLitConfig config;

    private HttpServer httpServer;

    public KeepLitMod() {
        configPath = FMLPaths.CONFIGDIR.get().resolve("keeplit.json");
        config = KeepLitConfig.loadOrCreate(configPath);

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);

        LOGGER.info("[KeepLit] Мод инициализирован.");
        LOGGER.info("[KeepLit] Файл конфига: {}", configPath);
        LOGGER.info("[KeepLit] Веб-порт: {}", config.web.port);
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (!config.enabled) {
            LOGGER.info("[KeepLit] Мод отключён в конфиге.");
            return;
        }

        if (!config.web.enabled) {
            LOGGER.info("[KeepLit] Веб-сервер отключён в конфиге.");
            return;
        }

        startWebServer();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        stopWebServer();
    }

    private void startWebServer() {
        try {
            InetSocketAddress address = new InetSocketAddress(config.web.host, config.web.port);
            httpServer = HttpServer.create(address, 0);

            httpServer.createContext("/", exchange -> {
                String html = """
                        <!DOCTYPE html>
                        <html lang="ru">
                        <head>
                            <meta charset="UTF-8">
                            <title>KeepLit</title>
                            <style>
                                body {
                                    background: #202020;
                                    color: #e8e8e8;
                                    font-family: monospace;
                                    text-align: center;
                                    padding: 48px;
                                }

                                h1 {
                                    color: #ff9900;
                                }

                                .card {
                                    background: #2d2d2d;
                                    border: 1px solid #444;
                                    padding: 24px;
                                    display: inline-block;
                                }

                                .ok {
                                    color: #4caf50;
                                    font-size: 18px;
                                }
                            </style>
                        </head>
                        <body>
                            <h1>🔥 KeepLit</h1>
                            <div class="card">
                                <p class="ok">Веб-сервер работает</p>
                                <p>Порт: %d</p>
                                <p>Публичный адрес: %s</p>
                            </div>
                        </body>
                        </html>
                        """.formatted(config.web.port, escapeHtml(config.getPublicUrl()));

                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });

            httpServer.setExecutor(null);
            httpServer.start();

            LOGGER.info("[KeepLit] ✅ Веб-сервер запущен: http://localhost:{}", config.web.port);

        } catch (IOException e) {
            LOGGER.error("[KeepLit] ❌ Не удалось запустить веб-сервер на порту {}", config.web.port, e);
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
        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
