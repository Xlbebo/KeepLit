package ru.keeplit;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PeriodHistoryStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public static class Meta {
        public String currentPeriodStart;
    }

    private final Path reportsDir;
    private final Path metaFile;

    public PeriodHistoryStore(Path keeplitDir) {
        this.reportsDir = keeplitDir.resolve("reports");
        this.metaFile = keeplitDir.resolve("meta.json");
        try { Files.createDirectories(reportsDir); } catch (Exception e) {
            LOGGER.error("[KeepLit] Не удалось создать папку reports", e);
        }
    }

    // --- Мета (с какого числа идёт текущий период) ---

    public String getMetaPeriodStart() {
        if (!Files.exists(metaFile)) return null;
        try {
            Meta m = GSON.fromJson(Files.readString(metaFile, StandardCharsets.UTF_8), Meta.class);
            return m != null ? m.currentPeriodStart : null;
        } catch (Exception e) {
            LOGGER.error("[KeepLit] Ошибка чтения meta.json", e);
            return null;
        }
    }

    public void setMetaPeriodStart(String date) {
        Meta m = new Meta();
        m.currentPeriodStart = date;
        try {
            FileUtils.writeAtomically(metaFile, GSON.toJson(m));
        } catch (IOException e) {
            LOGGER.error("[KeepLit] Ошибка записи meta.json", e);
        }
    }

    // --- Отчёты ---

    public boolean hasReport(String periodStart, String periodEnd) {
        return Files.exists(reportsDir.resolve("report_" + PeriodReport.periodKey(periodStart, periodEnd) + ".json"));
    }

    public PeriodReport loadReport(String periodStart, String periodEnd) {
        Path file = reportsDir.resolve("report_" + PeriodReport.periodKey(periodStart, periodEnd) + ".json");
        if (!Files.exists(file)) return null;
        try {
            return PeriodReport.loadFrom(file);
        } catch (Exception e) {
            LOGGER.error("[KeepLit] Ошибка загрузки отчёта {}", file, e);
            return null;
        }
    }

    public void archivePeriod(BillingPeriod period, StatsCalculator.BillingResult billing,
                              long collectedTotal, List<Session> sessions, List<String> paidUuids) {
        String start = period.start.toLocalDate().toString();
        String end = period.end.toLocalDate().toString();
        if (hasReport(start, end)) return;

        Map<String, Long> lastSeen = new HashMap<>();
        for (Session s : sessions) {
            lastSeen.merge(s.uuid, s.joinAt, Math::max);
        }

        PeriodReport report = new PeriodReport();
        report.periodStart = start;
        report.periodEnd = end;
        report.createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        report.targetCost = billing.totalCost;
        report.currency = billing.currency;
        report.totalCollected = collectedTotal;
        report.totalActiveMs = billing.totalActiveMs;

        for (StatsCalculator.PlayerStats p : billing.activePlayers) {
            PeriodReport.PlayerEntry e = new PeriodReport.PlayerEntry();
            e.uuid = p.uuid; e.name = p.name; e.totalMs = p.totalMs;
            e.sharePercent = p.sharePercent; e.recommendedAmount = p.recommendedAmount;
            e.lastSeenMs = lastSeen.getOrDefault(p.uuid, 0L);
            e.paid = paidUuids.contains(p.uuid);
            report.activePlayers.add(e);
        }
        for (StatsCalculator.PlayerStats p : billing.newbies) {
            PeriodReport.PlayerEntry e = new PeriodReport.PlayerEntry();
            e.uuid = p.uuid; e.name = p.name; e.totalMs = p.totalMs;
            e.sharePercent = 0; e.recommendedAmount = 0;
            e.lastSeenMs = lastSeen.getOrDefault(p.uuid, 0L);
            e.paid = false;
            report.newbies.add(e);
        }

        try {
            report.saveTo(reportsDir);
            LOGGER.info("[KeepLit] Отчёт сохранён: {}", report.getFileName());
        } catch (IOException e) {
            LOGGER.error("[KeepLit] Ошибка сохранения отчёта", e);
        }
    }

    public List<PeriodReport> listAllReports() {
        List<PeriodReport> result = new ArrayList<>();
        if (!Files.isDirectory(reportsDir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "report_*.json")) {
            for (Path file : stream) {
                try { result.add(PeriodReport.loadFrom(file)); }
                catch (Exception e) { LOGGER.warn("[KeepLit] Не удалось загрузить отчёт {}", file); }
            }
        } catch (IOException e) {
            LOGGER.error("[KeepLit] Ошибка чтения папки reports", e);
        }
        result.sort(Comparator.comparing((PeriodReport r) -> r.periodStart).reversed());
        return result;
    }

    public PeriodReport findPrevious(String currentPeriodStart) {
        for (PeriodReport r : listAllReports()) {
            if (r.periodStart.compareTo(currentPeriodStart) < 0) return r;
        }
        return null;
    }

    public PeriodReport findNext(String currentPeriodStart) {
        List<PeriodReport> all = listAllReports();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).periodStart.compareTo(currentPeriodStart) > 0) return all.get(i);
        }
        return null;
    }
}
