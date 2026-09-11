package ru.keeplit;

import java.util.UUID;

public class Session {
    public String uuid;
    public String name;
    public long joinAt;
    public long leaveAt; // 0, если сессия активна

    public Session() {} // Нужно для Gson

    public Session(UUID uuid, String name, long joinAt) {
        this.uuid = uuid.toString();
        this.name = name;
        this.joinAt = joinAt;
        this.leaveAt = 0;
    }

    public boolean isActive() {
        return leaveAt == 0;
    }

    public long getDurationMs() {
        if (isActive()) return 0;
        return leaveAt - joinAt;
    }
}
