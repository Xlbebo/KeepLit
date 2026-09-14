package ru.keeplit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public class BillingPeriod {
    public final LocalDateTime start;
    public final LocalDateTime end;
    public final long startMs;
    public final long endMs;
    private final ZoneId zoneId;

    public BillingPeriod(int billingDay, String timeZone) {
        this.zoneId = ZoneId.of(timeZone);
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        LocalDate today = now.toLocalDate();

        LocalDate startDate;
        // Если сегодня ещё не наступил billingDay, значит период начался в прошлом месяце
        if (today.getDayOfMonth() < billingDay) {
            LocalDate lastMonth = today.minusMonths(1);
            startDate = lastMonth.withDayOfMonth(Math.min(billingDay, lastMonth.lengthOfMonth()));
        } else {
            startDate = today.withDayOfMonth(Math.min(billingDay, today.lengthOfMonth()));
        }

        this.start = startDate.atStartOfDay();
        this.end = startDate.plusMonths(1).atStartOfDay();

        this.startMs = this.start.atZone(zoneId).toInstant().toEpochMilli();
        this.endMs = this.end.atZone(zoneId).toInstant().toEpochMilli();
    }

    public String getFormattedRange() {
        return String.format("%s — %s",
            start.toLocalDate().toString(),
            end.toLocalDate().toString());
    }

    /**
     * Возвращает количество полных дней до конца периода.
     * Если период уже закончился, возвращает 0.
     */
    public long getDaysRemaining() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), end.toLocalDate());
        return Math.max(0, days);
    }

    public boolean isAlmostOver() {
        return getDaysRemaining() <= 5;
    }
}
