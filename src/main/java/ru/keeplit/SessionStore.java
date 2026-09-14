package ru.keeplit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SessionStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final List<Session> sessions = new ArrayList<>();

    public SessionStore(Path dir) {
        this.file = dir.resolve("sessions.json");
        load();
    }

    private void load() {
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<Session>>() {}.getType();
                List<Session> loaded = GSON.fromJson(reader, listType);
                if (loaded != null) {
                    sessions.addAll(loaded);
                }
            } catch (Exception e) {
                LOGGER.error("[KeepLit] Ошибка чтения сессий", e);
            }
        }
    }

    public void save() {
        try {
            String json = GSON.toJson(sessions);
            FileUtils.writeAtomically(file, json);
        } catch (Exception e) {
            LOGGER.error("[KeepLit] Ошибка записи сессий", e);
        }
    }

    public void startSession(UUID uuid, String name) {
        // На всякий случай закрываем предыдущую активную сессию этого игрока
        closeActiveSession(uuid);

        Session s = new Session(uuid, name, System.currentTimeMillis());
        sessions.add(s);
        save();
    }

    public void closeActiveSession(UUID uuid) {
        String uuidStr = uuid.toString();
        boolean changed = false;
        for (Session s : sessions) {
            if (s.uuid.equals(uuidStr) && s.isActive()) {
                s.leaveAt = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) save();
    }

    public void closeAllActiveSessions() {
        boolean changed = false;
        for (Session s : sessions) {
            if (s.isActive()) {
                s.leaveAt = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) save();
    }

    public List<Session> getSessions() {
        return new ArrayList<>(sessions);
    }
}
