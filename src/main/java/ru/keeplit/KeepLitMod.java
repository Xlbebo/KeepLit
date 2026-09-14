package ru.keeplit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod(KeepLitMod.MODID)
public class KeepLitMod {

    public static final String MODID = "keeplit";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path configPath;
    private KeepLitConfig config;

    private final Path keeplitDir;
    private SessionStore sessionStore;
    private PaymentStore paymentStore;
    private PeriodHistoryStore historyStore;

    private HttpServer httpServer;

    public KeepLitMod() {
        // 1. Конфиг
        configPath = FMLPaths.CONFIGDIR.get().resolve("keeplit.json");
        config = KeepLitConfig.loadOrCreate(configPath);

        // 2. Папка для данных мода
        keeplitDir = FMLPaths.GAMEDIR.get().resolve("keeplit");
        try { Files.createDirectories(keeplitDir); } catch (Exception ignored) {}

        sessionStore = new SessionStore(keeplitDir);
        paymentStore = new PaymentStore(keeplitDir);
        historyStore = new PeriodHistoryStore(keeplitDir);

        // 3. Слушатели
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        LOGGER.info("[KeepLit] Мод инициализирован.");
    }

    // --- События сервера ---

    private void onServerStarted(ServerStartedEvent event) {
        checkAndArchivePreviousPeriod();
        if (!config.enabled || !config.web.enabled) return;
        startWebServer();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (sessionStore != null) {
            sessionStore.closeAllActiveSessions();
        }
        stopWebServer();
    }

    // --- Автоархивация периода ---

    private void checkAndArchivePreviousPeriod() {
        try {
            BillingPeriod current = new BillingPeriod(config.billingDay, config.timeZone);
            String currentStart = current.start.toLocalDate().toString();
            String metaStart = historyStore.getMetaPeriodStart();

            if (metaStart == null) {
                historyStore.setMetaPeriodStart(currentStart);
                LOGGER.info("[KeepLit] Создана мета: текущий период с {}", currentStart);
                return;
            }
            if (metaStart.equals(currentStart)) return;

            LOGGER.info("[KeepLit] Граница периода сместилась: {} -> {}. Архивирую прошедший отрезок.", metaStart, currentStart);
            archiveSpan(metaStart, currentStart);
            historyStore.setMetaPeriodStart(currentStart);
        } catch (Exception e) {
            LOGGER.error("[KeepLit] Ошибка автоархивации периода", e);
        }
    }

    private void archiveSpan(String startDate, String endDate) {
        java.time.LocalDate start = java.time.LocalDate.parse(startDate);
        java.time.LocalDate end = java.time.LocalDate.parse(endDate);
        if (!end.isAfter(start)) return;

        BillingPeriod period = BillingPeriod.span(start, end, config.timeZone);
        List<Session> sessions = sessionStore.getSessions();
        StatsCalculator.BillingResult billing = StatsCalculator.calculate(sessions, period, config);
        long collected = paymentStore.getTotal(period.startMs);

        List<String> paidUuids = new ArrayList<>();
        for (Payment p : paymentStore.getPayments()) {
            if (p.timestamp >= period.startMs && p.timestamp < period.endMs) {
                paidUuids.add(p.playerUuid);
            }
        }

        historyStore.archivePeriod(period, billing, collected, sessions, paidUuids);
    }

    // --- События игроков ---

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sessionStore.startSession(player.getUUID(), player.getGameProfile().getName());
            LOGGER.info("[KeepLit] Вход: {}", player.getGameProfile().getName());
        }
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sessionStore.closeActiveSession(player.getUUID());
            LOGGER.info("[KeepLit] Выход: {}", player.getGameProfile().getName());
        }
    }

    // --- Команды ---

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("keeplit")
            // /keeplit link - доступна всем игрокам
            .then(Commands.literal("link")
                .executes(ctx -> {
                    String url = (config.web.publicUrl != null && !config.web.publicUrl.isEmpty())
                        ? config.web.publicUrl
                        : "http://localhost:" + config.web.port;

                    Component message = Component.literal("§6[KeepLit] §fСсылка на статистику: ")
                        .append(Component.literal(url)
                            .withStyle(style -> style
                                .withColor(ChatFormatting.YELLOW)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Нажми, чтобы открыть в браузере")))));

                    ctx.getSource().sendSuccess(() -> message, false);
                    return 1;
                })
            )
            // /keeplit stats - только для OP
            .then(Commands.literal("stats")
                .executes(ctx -> {
                    BillingPeriod period = new BillingPeriod(config.billingDay, config.timeZone);
                    List<Session> sessions = sessionStore.getSessions();
                    StatsCalculator.BillingResult billing = StatsCalculator.calculate(sessions, period, config);
                    long collectedTotal = paymentStore.getTotal(period.startMs);

                    ctx.getSource().sendSuccess(() -> Component.literal("§6[KeepLit] Статистика периода: §f" + period.getFormattedRange()), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§eАктивных игроков: §f" + billing.activePlayers.size()), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§eНовичков: §f" + billing.newbies.size()), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§eСобрано: §f" + collectedTotal + " " + config.billing.currency), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§eЦель: §f" + config.billing.cost + " " + config.billing.currency), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§eДней до конца: §f" + period.getDaysRemaining()), false);

                    List<StatsCalculator.PlayerStats> allPlayers = new ArrayList<>();
                    allPlayers.addAll(billing.activePlayers);
                    allPlayers.addAll(billing.newbies);

                    if (!allPlayers.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.literal("§6[KeepLit] Игроки за период:"), false);
                        int rank = 1;
                        for (StatsCalculator.PlayerStats p : allPlayers) {
                            final int r = rank;
                            final String name = p.name;
                            final String time = p.getFormattedTime();
                            final boolean newbie = p.isNewbie;
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                String.format("§e%d. §f%s §7(§f%s§7)%s", r, name, time, newbie ? " §8[новичок]" : "")
                            ), false);
                            rank++;
                        }
                    }
                    return 1;
                })
            )
            // /keeplit reload - только для OP
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermission(3))
                .executes(ctx -> {
                    config = KeepLitConfig.loadOrCreate(configPath);
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[KeepLit] Конфиг перезагружен."), false);
                    return 1;
                })
            )
            // /keeplit export - только для OP
            .then(Commands.literal("export")
                .requires(source -> source.hasPermission(3))
                .executes(ctx -> {
                    try {
                        Path exportDir = keeplitDir.resolve("exports");
                        Files.createDirectories(exportDir);

                        BillingPeriod period = new BillingPeriod(config.billingDay, config.timeZone);
                        List<Session> sessions = sessionStore.getSessions();
                        StatsCalculator.BillingResult billing = StatsCalculator.calculate(sessions, period, config);

                        StringBuilder csv = new StringBuilder();
                        csv.append("UUID,Name,Category,TimeMinutes,Time,SharePercent,RecommendedAmount\n");

                        for (StatsCalculator.PlayerStats p : billing.activePlayers) {
                            csv.append(String.format("%s,%s,active,%d,%s,%.2f,%d\n",
                                p.uuid, escapeCsv(p.name), p.totalMs / 60000,
                                p.getFormattedTime(), p.sharePercent, p.recommendedAmount));
                        }
                        for (StatsCalculator.PlayerStats p : billing.newbies) {
                            csv.append(String.format("%s,%s,newbie,%d,%s,0.00,0\n",
                                p.uuid, escapeCsv(p.name), p.totalMs / 60000, p.getFormattedTime()));
                        }

                        String fileName = String.format("period_%s.csv",
                            period.start.toLocalDate().toString().replace("-", "_"));
                        Path exportFile = exportDir.resolve(fileName);
                        FileUtils.writeAtomically(exportFile, csv.toString());

                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a[KeepLit] Экспорт сохранён: §f" + exportFile.toAbsolutePath()), false);
                    } catch (Exception e) {
                        LOGGER.error("[KeepLit] Ошибка экспорта", e);
                        ctx.getSource().sendFailure(Component.literal("§c[KeepLit] Ошибка экспорта: " + e.getMessage()));
                    }
                    return 1;
                })
            )
            // /keeplit clear <name> - только для OP
            .then(Commands.literal("clear")
                .requires(source -> source.hasPermission(3))
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        List<String> uuids = findUuidsByName(name);

                        if (uuids.isEmpty()) {
                            ctx.getSource().sendFailure(Component.literal(
                                "§c[KeepLit] Игрок с ником '" + name + "' не найден."));
                            return 0;
                        }

                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "§e[KeepLit] Найдено UUID для " + name + ": " + uuids.size()), false);
                        for (String uuid : uuids) {
                            ctx.getSource().sendSuccess(() -> Component.literal("§7  - " + uuid), false);
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "§e[KeepLit] Очистка записей (в разработке)"), false);
                        return 1;
                    })
                )
            )
            // /keeplit merge <name1> <name2> - только для OP
            .then(Commands.literal("merge")
                .requires(source -> source.hasPermission(3))
                .then(Commands.argument("name1", StringArgumentType.string())
                    .then(Commands.argument("name2", StringArgumentType.string())
                        .executes(ctx -> {
                            String name1 = StringArgumentType.getString(ctx, "name1");
                            String name2 = StringArgumentType.getString(ctx, "name2");

                            List<String> uuids1 = findUuidsByName(name1);
                            List<String> uuids2 = findUuidsByName(name2);

                            if (uuids1.isEmpty() || uuids2.isEmpty()) {
                                ctx.getSource().sendFailure(Component.literal(
                                    "§c[KeepLit] Один из игроков не найден."));
                                return 0;
                            }

                            ctx.getSource().sendSuccess(() -> Component.literal(
                                "§e[KeepLit] Слияние " + name1 + " (" + uuids1.size() + " UUID) -> " +
                                name2 + " (" + uuids2.size() + " UUID)"), false);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                "§e[KeepLit] (в разработке)"), false);
                            return 1;
                        })
                    )
                )
            )
        );
    }

    private List<String> findUuidsByName(String name) {
        List<String> uuids = new ArrayList<>();
        for (Session s : sessionStore.getSessions()) {
            if (s.name.equalsIgnoreCase(name) && !uuids.contains(s.uuid)) {
                uuids.add(s.uuid);
            }
        }
        return uuids;
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // --- Веб-сервер ---

    private void startWebServer() {
        try {
            InetSocketAddress address = new InetSocketAddress(config.web.host, config.web.port);
            httpServer = HttpServer.create(address, 0);

            // Главная страница (текущий период)
            httpServer.createContext("/", exchange -> {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().equals("/api/mark-paid")) {
                    handleMarkPaid(exchange);
                    return;
                }

                BillingPeriod period = new BillingPeriod(config.billingDay, config.timeZone);
                List<Session> sessions = sessionStore.getSessions();
                StatsCalculator.BillingResult billing = StatsCalculator.calculate(sessions, period, config);
                List<Payment> payments = paymentStore.getPayments();

                Map<String, Long> lastSeen = new HashMap<>();
                for (Session s : sessions) {
                    lastSeen.merge(s.uuid, s.joinAt, Math::max);
                }

                long collectedTotal = paymentStore.getTotal(period.startMs);
                int progressPercent = billing.totalCost > 0 ? (int) Math.min(100, (collectedTotal * 100) / billing.totalCost) : 0;

                List<String> paidUuids = new ArrayList<>();
                for (Payment p : payments) {
                    if (p.timestamp >= period.startMs) {
                        paidUuids.add(p.playerUuid);
                    }
                }

                StringBuilder activeRows = new StringBuilder();
                for (StatsCalculator.PlayerStats p : billing.activePlayers) {
                    boolean isPaid = paidUuids.contains(p.uuid);
                    String lastSeenStr = lastSeen.containsKey(p.uuid) ? formatDate(lastSeen.get(p.uuid)) : "-";

                    String actionCell;
                    if (isPaid) {
                        actionCell = "<span class='paid-badge'>✔ Оплачено</span>";
                    } else {
                        actionCell = String.format(
                            "<button class='btn' onclick=\"markPaid('%s', %d)\">Я оплатил</button>",
                            p.uuid, p.recommendedAmount
                        );
                    }

                    activeRows.append(String.format(
                        "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class='amount'>%d %s</td><td>%s</td></tr>",
                        escapeHtml(p.name),
                        lastSeenStr,
                        p.getFormattedTime(),
                        p.getFormattedShare(),
                        p.recommendedAmount,
                        escapeHtml(billing.currency),
                        actionCell
                    ));
                }
                if (activeRows.length() == 0) {
                    activeRows.append("<tr><td colspan='6' class='empty'>Нет активных игроков</td></tr>");
                }

                StringBuilder newbieRows = new StringBuilder();
                for (StatsCalculator.PlayerStats p : billing.newbies) {
                    String lastSeenStr = lastSeen.containsKey(p.uuid) ? formatDate(lastSeen.get(p.uuid)) : "-";
                    newbieRows.append(String.format(
                        "<tr><td>%s</td><td>%s</td><td>%s</td><td class='newbie-note'>Платить не обязательно</td></tr>",
                        escapeHtml(p.name),
                        lastSeenStr,
                        p.getFormattedTime()
                    ));
                }

                String html = """
                        <!DOCTYPE html>
                        <html lang="ru">
                        <head>
                            <meta charset="UTF-8">
                            <title>KeepLit - Биллинг</title>
                            <style>
                                body { background: #202020; color: #e8e8e8; font-family: monospace; padding: 48px; max-width: 900px; margin: 0 auto; }
                                h1 { color: #ff9900; text-align: center; }
                                h2 { color: #ff9900; border-bottom: 1px solid #444; padding-bottom: 5px; }
                                .period { text-align: center; color: #aaa; margin-bottom: 20px; }
                                .card { background: #2d2d2d; border: 1px solid #444; padding: 24px; margin-bottom: 30px; border-radius: 8px; }
                                .target { font-size: 28px; color: #4caf50; text-align: center; margin-bottom: 10px; }
                                .progress-bar { background: #444; border-radius: 4px; height: 20px; margin: 15px 0; overflow: hidden; }
                                .progress-fill { background: #4caf50; height: 100%%; text-align: center; color: #fff; font-weight: bold; line-height: 20px; transition: width 0.5s; }
                                table { width: 100%%; border-collapse: collapse; }
                                th, td { padding: 12px; text-align: left; border-bottom: 1px solid #444; }
                                th { color: #ff9900; }
                                .amount { font-weight: bold; color: #fff; text-align: right; }
                                .newbie-note { color: #888; font-style: italic; }
                                .empty { text-align: center; color: #666; }
                                .btn { background: #ff9900; color: #000; border: none; padding: 6px 12px; cursor: pointer; font-weight: bold; border-radius: 4px; }
                                .btn:hover { background: #ffb84d; }
                                .paid-badge { color: #4caf50; font-weight: bold; }
                                .pay-link { display: block; text-align: center; margin: 20px 0; }
                                .pay-link a { background: #4caf50; color: #fff; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-size: 18px; }
                                .days-remaining { text-align: center; font-size: 16px; margin-bottom: 15px; }
                                .days-remaining.warning { color: #ff4444; font-weight: bold; }
                            </style>
                        </head>
                        <body>
                            <h1>🔥 KeepLit</h1>
                            <div style="text-align:center; margin-bottom:15px;">
                                <a href="/history" style="color:#ff9900; text-decoration:none;">📚 История периодов</a>
                            </div>
                            <div class="period">Расчётный период: <b>%s</b></div>
                            <div class="days-remaining %s">До конца периода: <b>%d</b> дн.</div>
                            <div class="card">
                                <div class="target">Цель: %d %s</div>
                                <div style="text-align:center; font-size: 18px;">Собрано: <b>%d %s</b></div>
                                <div class="progress-bar">
                                    <div class="progress-fill" style="width: %d%%;">%d%%</div>
                                </div>
                                <div class="pay-link">
                                    <a href="%s" target="_blank">💳 Перейти к оплате</a>
                                </div>
                                <p style="text-align:center; font-size:12px; color:#888;">После перевода нажмите "Я оплатил" в таблице.</p>
                            </div>
                            <div class="card">
                                <h2>Участники сбора</h2>
                                <table>
                                    <thead>
                                        <tr>
                                            <th>Игрок</th>
                                            <th>Последний вход</th>
                                            <th>Время</th>
                                            <th>Доля</th>
                                            <th style="text-align:right;">Рекомендация</th>
                                            <th>Статус</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        %s
                                    </tbody>
                                </table>
                            </div>
                            <div class="card" style="background: #252525;">
                                <h2 style="color: #aaa;">Новички</h2>
                                <table>
                                    <thead>
                                        <tr>
                                            <th>Игрок</th>
                                            <th>Последний вход</th>
                                            <th>Время</th>
                                            <th>Статус</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        %s
                                    </tbody>
                                </table>
                            </div>
                            <script>
                                function markPaid(uuid, amount) {
                                    if (!confirm('Вы действительно оплатили ' + amount + ' ' + '%s?')) return;
                                    fetch('/api/mark-paid', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({ uuid: uuid, amount: amount })
                                    }).then(r => { if(r.ok) location.reload(); else alert('Ошибка'); });
                                }
                            </script>
                        </body>
                        </html>
                        """.formatted(
                            period.getFormattedRange(),
                            period.isAlmostOver() ? "warning" : "",
                            period.getDaysRemaining(),
                            billing.totalCost, escapeHtml(billing.currency),
                            collectedTotal, escapeHtml(billing.currency),
                            progressPercent, progressPercent,
                            config.web.paymentUrl != null ? config.web.paymentUrl : "#",
                            activeRows.toString(),
                            newbieRows.toString(),
                            escapeHtml(billing.currency)
                        );

                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            });

            // История периодов
            httpServer.createContext("/history", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String periodParam = null;
                if (query != null) {
                    for (String part : query.split("&")) {
                        if (part.startsWith("period=")) {
                            periodParam = java.net.URLDecoder.decode(part.substring(7), StandardCharsets.UTF_8);
                        }
                    }
                }

                String html;
                if (periodParam != null) {
                    PeriodReport report = findReportByKey(periodParam);
                    html = report != null ? renderReportHtml(report) : renderHistoryIndexHtml(historyStore.listAllReports());
                } else {
                    html = renderHistoryIndexHtml(historyStore.listAllReports());
                }

                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            });

            httpServer.setExecutor(null);
            httpServer.start();
            LOGGER.info("[KeepLit] ✅ Веб-сервер запущен: http://localhost:{}", config.web.port);
        } catch (IOException e) {
            LOGGER.error("[KeepLit] ❌ Не удалось запустить веб-сервер", e);
        }
    }

    private void handleMarkPaid(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

            String uuid = json.get("uuid").getAsString();
            long amount = json.get("amount").getAsLong();

            paymentStore.addPayment(uuid, amount);
            LOGGER.info("[KeepLit] Игрок {} отметил оплату: {} {}", uuid, amount, config.billing.currency);

            exchange.sendResponseHeaders(200, 0);
        } catch (Exception e) {
            LOGGER.error("[KeepLit] Ошибка обработки отметки оплаты", e);
            exchange.sendResponseHeaders(500, 0);
        } finally {
            exchange.close();
        }
    }

    private PeriodReport findReportByKey(String key) {
        for (PeriodReport r : historyStore.listAllReports()) {
            if (PeriodReport.periodKey(r.periodStart, r.periodEnd).equals(key)) return r;
        }
        return null;
    }

    private String renderHistoryIndexHtml(List<PeriodReport> reports) {
        StringBuilder rows = new StringBuilder();
        if (reports.isEmpty()) {
            rows.append("<tr><td colspan='4' class='empty'>Закрытых периодов пока нет</td></tr>");
        } else {
            for (PeriodReport r : reports) {
                int progress = r.targetCost > 0 ? (int) Math.min(100, (r.totalCollected * 100) / r.targetCost) : 0;
                rows.append(String.format(
                    "<tr><td><a href='/history?period=%s'>%s — %s</a></td><td>%d / %d %s</td><td>%d%%</td><td>%d активных</td></tr>",
                    PeriodReport.periodKey(r.periodStart, r.periodEnd),
                    r.periodStart, r.periodEnd,
                    r.totalCollected, r.targetCost, escapeHtml(r.currency),
                    progress, r.activePlayers.size()));
            }
        }

        return String.format("""
            <!DOCTYPE html>
            <html lang="ru">
            <head>
                <meta charset="UTF-8">
                <title>KeepLit - История периодов</title>
                <style>
                    body { background: #202020; color: #e8e8e8; font-family: monospace; padding: 48px; max-width: 900px; margin: 0 auto; }
                    h1 { color: #ff9900; text-align: center; }
                    .back { text-align: center; margin-bottom: 20px; }
                    .back a { color: #ff9900; text-decoration: none; }
                    .card { background: #2d2d2d; border: 1px solid #444; padding: 24px; border-radius: 8px; }
                    table { width: 100%%; border-collapse: collapse; }
                    th, td { padding: 12px; text-align: left; border-bottom: 1px solid #444; }
                    th { color: #ff9900; }
                    td a { color: #4caf50; font-weight: bold; text-decoration: none; }
                    td a:hover { text-decoration: underline; }
                    .empty { text-align: center; color: #666; }
                </style>
            </head>
            <body>
                <h1>📚 История периодов</h1>
                <div class="back"><a href="/">← К текущему периоду</a></div>
                <div class="card">
                    <table>
                        <thead>
                            <tr>
                                <th>Период</th>
                                <th>Собрано</th>
                                <th>Прогресс</th>
                                <th>Игроков</th>
                            </tr>
                        </thead>
                        <tbody>%s</tbody>
                    </table>
                </div>
            </body>
            </html>
            """, rows.toString());
    }

    private String renderReportHtml(PeriodReport report) {
        PeriodReport prev = historyStore.findPrevious(report.periodStart);
        PeriodReport next = historyStore.findNext(report.periodStart);

        String prevLink = prev != null
            ? String.format("<a href='/history?period=%s'>← Предыдущий</a>", PeriodReport.periodKey(prev.periodStart, prev.periodEnd))
            : "<span style='color:#666'>← Предыдущий</span>";
        String nextLink = next != null
            ? String.format("<a href='/history?period=%s'>Следующий →</a>", PeriodReport.periodKey(next.periodStart, next.periodEnd))
            : "<a href='/'>К текущему →</a>";

        int progress = report.targetCost > 0 ? (int) Math.min(100, (report.totalCollected * 100) / report.targetCost) : 0;

        StringBuilder activeRows = new StringBuilder();
        for (PeriodReport.PlayerEntry p : report.activePlayers) {
            String lastSeenStr = p.lastSeenMs > 0 ? formatDate(p.lastSeenMs) : "-";
            String statusCell = p.paid
                ? "<span class='paid-badge'>✔ Оплачено</span>"
                : "<span style='color:#ff6b6b'>Не отмечено</span>";
            activeRows.append(String.format(
                "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class='amount'>%d %s</td><td>%s</td></tr>",
                escapeHtml(p.name), lastSeenStr, p.getFormattedTime(),
                p.getFormattedShare(), p.recommendedAmount, escapeHtml(report.currency),
                statusCell));
        }
        if (activeRows.length() == 0) {
            activeRows.append("<tr><td colspan='6' class='empty'>Нет активных игроков</td></tr>");
        }

        StringBuilder newbieRows = new StringBuilder();
        for (PeriodReport.PlayerEntry p : report.newbies) {
            String lastSeenStr = p.lastSeenMs > 0 ? formatDate(p.lastSeenMs) : "-";
            newbieRows.append(String.format(
                "<tr><td>%s</td><td>%s</td><td>%s</td><td class='newbie-note'>Платить не обязательно</td></tr>",
                escapeHtml(p.name), lastSeenStr, p.getFormattedTime()));
        }
        if (newbieRows.length() == 0) {
            newbieRows.append("<tr><td colspan='4' class='empty'>Новичков нет</td></tr>");
        }

        return String.format("""
            <!DOCTYPE html>
            <html lang="ru">
            <head>
                <meta charset="UTF-8">
                <title>KeepLit - Отчёт %s — %s</title>
                <style>
                    body { background: #202020; color: #e8e8e8; font-family: monospace; padding: 48px; max-width: 900px; margin: 0 auto; }
                    h1 { color: #ff9900; text-align: center; }
                    h2 { color: #ff9900; border-bottom: 1px solid #444; padding-bottom: 5px; }
                    .nav { display: flex; justify-content: space-between; margin-bottom: 20px; }
                    .nav a { color: #ff9900; text-decoration: none; }
                    .nav a:hover { text-decoration: underline; }
                    .period { text-align: center; color: #aaa; margin-bottom: 20px; }
                    .archived-badge { text-align: center; background: #3a2f20; color: #ff9900; padding: 8px; border-radius: 4px; margin-bottom: 20px; }
                    .card { background: #2d2d2d; border: 1px solid #444; padding: 24px; margin-bottom: 30px; border-radius: 8px; }
                    .target { font-size: 28px; color: #4caf50; text-align: center; margin-bottom: 10px; }
                    .progress-bar { background: #444; border-radius: 4px; height: 20px; margin: 15px 0; overflow: hidden; }
                    .progress-fill { background: #4caf50; height: 100%%; text-align: center; color: #fff; font-weight: bold; line-height: 20px; }
                    table { width: 100%%; border-collapse: collapse; }
                    th, td { padding: 12px; text-align: left; border-bottom: 1px solid #444; }
                    th { color: #ff9900; }
                    .amount { font-weight: bold; color: #fff; text-align: right; }
                    .newbie-note { color: #888; font-style: italic; }
                    .paid-badge { color: #4caf50; font-weight: bold; }
                    .empty { text-align: center; color: #666; }
                    .meta { text-align: center; color: #666; font-size: 12px; margin-top: 30px; }
                </style>
            </head>
            <body>
                <h1>📜 Архивный отчёт</h1>
                <div class="archived-badge">⚠ Период закрыт. Данные заморожены.</div>
                <div class="nav">
                    <div>%s</div>
                    <div>%s</div>
                </div>
                <div class="period">Период: <b>%s — %s</b></div>
                <div class="card">
                    <div class="target">Цель: %d %s</div>
                    <div style="text-align:center; font-size: 18px;">Собрано: <b>%d %s</b></div>
                    <div class="progress-bar">
                        <div class="progress-fill" style="width: %d%%;">%d%%</div>
                    </div>
                </div>
                <div class="card">
                    <h2>Участники сбора</h2>
                    <table>
                        <thead>
                            <tr>
                                <th>Игрок</th>
                                <th>Последний вход</th>
                                <th>Время</th>
                                <th>Доля</th>
                                <th style="text-align:right;">Рекомендация</th>
                                <th>Статус</th>
                            </tr>
                        </thead>
                        <tbody>%s</tbody>
                    </table>
                </div>
                <div class="card" style="background: #252525;">
                    <h2 style="color: #aaa;">Новички</h2>
                    <table>
                        <thead>
                            <tr>
                                <th>Игрок</th>
                                <th>Последний вход</th>
                                <th>Время</th>
                                <th>Статус</th>
                            </tr>
                        </thead>
                        <tbody>%s</tbody>
                    </table>
                </div>
                <div class="meta">Отчёт создан: %s</div>
            </body>
            </html>
            """,
            report.periodStart, report.periodEnd,
            prevLink, nextLink,
            report.periodStart, report.periodEnd,
            report.targetCost, escapeHtml(report.currency),
            report.totalCollected, escapeHtml(report.currency),
            progress, progress,
            activeRows.toString(),
            newbieRows.toString(),
            escapeHtml(report.createdAt));
    }

    private void stopWebServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOGGER.info("[KeepLit] Веб-сервер остановлен.");
        }
    }

    // --- Утилиты ---

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String formatDate(long ms) {
        java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.of(config.timeZone));
        return String.format("%02d.%02d.%d %02d:%02d",
            zdt.getDayOfMonth(), zdt.getMonthValue(), zdt.getYear(),
            zdt.getHour(), zdt.getMinute());
    }
}
