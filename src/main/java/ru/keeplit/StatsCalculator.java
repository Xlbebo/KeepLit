package ru.keeplit;

import java.util.*;

public class StatsCalculator {

    public static class PlayerStats {
        public String uuid;
        public String name;
        public long totalMs;
        public double sharePercent;
        public long recommendedAmount;
        public boolean isNewbie;

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

        public String getFormattedShare() {
            return String.format("%.1f%%", sharePercent);
        }
    }

    public static class BillingResult {
        public List<PlayerStats> activePlayers = new ArrayList<>();
        public List<PlayerStats> newbies = new ArrayList<>();
        public int totalCost;
        public long totalActiveMs;
        public String currency;
    }

    public static BillingResult calculate(List<Session> sessions, BillingPeriod period, KeepLitConfig config) {
        BillingResult result = new BillingResult();
        result.totalCost = config.billing.cost;
        result.currency = config.billing.currency;

        // 1. Сначала просто суммируем время всех игроков за период
        Map<String, PlayerStats> tempMap = new HashMap<>();
        long minMs = config.billing.minHours * 3600L * 1000L;

        for (Session s : sessions) {
            if (s.isActive()) continue;

            long effectiveStart = Math.max(s.joinAt, period.startMs);
            long effectiveEnd = Math.min(s.leaveAt, period.endMs);
            long duration = effectiveEnd - effectiveStart;

            if (duration > 0) {
                tempMap.computeIfAbsent(s.uuid, k -> new PlayerStats(s.uuid, s.name, 0))
                       .totalMs += duration;
            }
        }

        // 2. Разделяем на активных и новичков
        long totalActiveMs = 0;
        for (PlayerStats p : tempMap.values()) {
            if (p.totalMs >= minMs) {
                result.activePlayers.add(p);
                totalActiveMs += p.totalMs;
            } else {
                p.isNewbie = true;
                result.newbies.add(p);
            }
        }
        result.totalActiveMs = totalActiveMs;

        // 3. Считаем доли и рекомендации для активных
        if (totalActiveMs > 0) {
            long sumRecommended = 0;

            for (PlayerStats p : result.activePlayers) {
                p.sharePercent = (double) p.totalMs / totalActiveMs * 100.0;

                // Пропорция: (Cost * PlayerTime) / TotalTime
                long rawAmount = (long) config.billing.cost * p.totalMs / totalActiveMs;

                // Округление вверх до roundStep
                int step = config.billing.roundStep;
                long rounded = ((rawAmount + step - 1) / step) * step;

                // Минимальный порог
                if (rounded < config.billing.minAmount) {
                    rounded = config.billing.minAmount;
                }

                p.recommendedAmount = rounded;
                sumRecommended += rounded;
            }

            // 4. Страховка: если из-за округлений или минимумов сумма не покрывает аренду,
            // накидываем остаток самому активному игроку (первому в списке, так как он отсортирован).
            if (sumRecommended < config.billing.cost && !result.activePlayers.isEmpty()) {
                // Сортируем по убыванию времени, чтобы "богатый" платил
                result.activePlayers.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));
                long diff = config.billing.cost - sumRecommended;
                result.activePlayers.get(0).recommendedAmount += diff;
            } else {
                // Иначе просто сортируем для красоты
                result.activePlayers.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));
            }
        }

        // Новичков тоже сортируем
        result.newbies.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));

        return result;
    }
}
