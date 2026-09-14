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

public class PaymentStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final List<Payment> payments = new ArrayList<>();

    public PaymentStore(Path dir) {
        this.file = dir.resolve("payments.json");
        load();
    }

    private void load() {
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<Payment>>() {}.getType();
                List<Payment> loaded = GSON.fromJson(reader, listType);
                if (loaded != null) {
                    payments.addAll(loaded);
                    int maxId = 0;
                    for (Payment p : payments) {
                        if (p.id > maxId) maxId = p.id;
                    }
                    Payment.updateCounter(maxId);
                }
            } catch (Exception e) {
                LOGGER.error("[KeepLit] Ошибка чтения платежей", e);
            }
        }
    }

    public void save() {
        try {
            String json = GSON.toJson(payments);
            FileUtils.writeAtomically(file, json);
        } catch (Exception e) {
            LOGGER.error("[KeepLit] Ошибка записи платежей", e);
        }
    }

    public void addPayment(String uuid, long amount) {
        payments.add(new Payment(uuid, amount));
        save();
    }

    // Считаем сумму всех платежей за период
    public long getTotal(long sinceMs) {
        long total = 0;
        for (Payment p : payments) {
            if (p.timestamp >= sinceMs) {
                total += p.amount;
            }
        }
        return total;
    }

    public List<Payment> getPayments() {
        return new ArrayList<>(payments);
    }
}
