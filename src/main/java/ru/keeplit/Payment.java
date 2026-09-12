package ru.keeplit;

import java.util.concurrent.atomic.AtomicInteger;

public class Payment {
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(1);

    public int id;
    public String playerUuid;
    public long amount;
    public long timestamp;

    public Payment() {}

    public Payment(String playerUuid, long amount) {
        this.id = ID_COUNTER.getAndIncrement();
        this.playerUuid = playerUuid;
        this.amount = amount;
        this.timestamp = System.currentTimeMillis();
    }

    public static void updateCounter(int maxId) {
        if (maxId >= ID_COUNTER.get()) {
            ID_COUNTER.set(maxId + 1);
        }
    }
}
