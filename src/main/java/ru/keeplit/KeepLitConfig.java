package ru.keeplit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class KeepLitConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public boolean enabled = true;

    public Web web = new Web();

    public static class Web {
        public boolean enabled = true;
        public String host = "0.0.0.0";
        public int port = 25580;
        public String publicUrl = "";
    }

    public static KeepLitConfig loadOrCreate(Path path) {
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    KeepLitConfig config = GSON.fromJson(reader, KeepLitConfig.class);

                    if (config == null) {
                        config = new KeepLitConfig();
                    }

                    if (config.web == null) {
                        config.web = new Web();
                    }

                    return config;
                }
            }

            KeepLitConfig config = new KeepLitConfig();
            config.save(path);
            return config;

        } catch (Exception e) {
            LOGGER.error("[KeepLit] Не удалось прочитать конфиг {}. Использую стандартный.", path, e);
            return new KeepLitConfig();
        }
    }

    public void save(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }

        } catch (Exception e) {
            LOGGER.error("[KeepLit] Не удалось сохранить конфиг {}", path, e);
        }
    }

    public String getPublicUrl() {
        if (web.publicUrl != null && !web.publicUrl.isBlank()) {
            return web.publicUrl;
        }

        return "http://localhost:" + web.port + "/";
    }
}
