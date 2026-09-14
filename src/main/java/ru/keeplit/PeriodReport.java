package ru.keeplit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PeriodReport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String periodStart;
    public String periodEnd;
    public String createdAt;
    public int targetCost;
    public String currency;
    public long totalCollected;
    public long totalActiveMs;
    public List<PlayerEntry> activePlayers = new ArrayList<>();
    public List<PlayerEntry> newbies = new ArrayList<>();

    public static class PlayerEntry {
        public String uuid;
        public String name;
        public long totalMs;
        public double sharePercent;
        public long recommendedAmount;
        public long lastSeenMs;
        public boolean paid;

        public String getFormattedTime() {
            long minutes = totalMs / (1000 * 60);
            return String.format("%d ч %d мин", minutes / 60, minutes % 60);
        }

        public String getFormattedShare() {
            return String.format("%.1f%%", sharePercent);
        }
    }

    public static String periodKey(String start, String end) {
        return start.replace("-", "_") + "_" + end.replace("-", "_");
    }

    public String getFileName() {
        return "report_" + periodKey(periodStart, periodEnd) + ".json";
    }

    public void saveTo(Path dir) throws IOException {
        FileUtils.writeAtomically(dir.resolve(getFileName()), GSON.toJson(this));
    }

    public static PeriodReport loadFrom(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, PeriodReport.class);
        }
    }
}
