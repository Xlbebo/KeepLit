package ru.keeplit;

import java.util.*;
import java.util.stream.Collectors;

public class StatsCalculator {

    public static class PlayerStats {
        public String uuid;
        public String name;
        public long totalMs;

        public PlayerStats(String uuid, String name, long totalMs) {
            this.uuid = uuid;
            this.name = name;
            this.totalMs = totalMs;
        }

        public String getFormattedTime() {
            long minutes = totalMs / (1000 * 60);
            long hours = minutes / 60;
            long mins = minutes % 60;
            return String.format("%d ч %d мин", hours, mins);
        }
    }

    public static List<PlayerStats> calculate(List<Session> sessions) {
        Map<String, PlayerStats> map = new HashMap<>();

        for (Session s : sessions) {
            if (!s.isActive() && s.getDurationMs() > 0) {
                map.computeIfAbsent(s.uuid, k -> new PlayerStats(s.uuid, s.name, 0))
                   .totalMs += s.getDurationMs();
            }
        }

        List<PlayerStats> list = new ArrayList<>(map.values());
        // Сортируем по убыванию времени
        list.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));
        return list;
    }
}
