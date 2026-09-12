package ru.keeplit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class BillingPeriod {
    public final LocalDateTime start;
    public final LocalDateTime end;
    public final long startMs;
    public final long endMs;

    public BillingPeriod(int billingDay) {
        LocalDate today = LocalDate.now();
        LocalDate startDate;

        // Логика: если сегодня ещё не наступил billingDay, значит период начался в прошлом месяце
        if (today.getDayOfMonth() < billingDay) {
            LocalDate lastMonth = today.minusMonths(1);
            // Страховка от месяцев, где меньше дней (например, февраль и 30-е число)
            startDate = lastMonth.withDayOfMonth(Math.min(billingDay, lastMonth.lengthOfMonth()));
        } else {
            startDate = today.withDayOfMonth(Math.min(billingDay, today.lengthOfMonth()));
        }

        this.start = startDate.atStartOfDay();
        this.end = startDate.plusMonths(1).atStartOfDay();

        ZoneId zone = ZoneId.systemDefault();
        this.startMs = this.start.atZone(zone).toInstant().toEpochMilli();
        this.endMs = this.end.atZone(zone).toInstant().toEpochMilli();
    }

    public String getFormattedRange() {
        return String.format("%s — %s",
            start.toLocalDate().toString(),
            end.toLocalDate().toString());
    }
}
