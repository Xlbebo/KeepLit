package ru.keeplit;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebTemplateStore {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path webDir;

    public WebTemplateStore(Path keeplitDir) {
        this.webDir = keeplitDir.resolve("web");
        try {
            Files.createDirectories(webDir);
        } catch (IOException e) {
            LOGGER.error("[KeepLit] Не удалось создать папку web", e);
        }
        writeDefaultIfMissing("index.html", INDEX_DEFAULT);
        writeDefaultIfMissing("history.html", HISTORY_DEFAULT);
        writeDefaultIfMissing("report.html", REPORT_DEFAULT);
        // Распаковываем QR-библиотеку из jar, если её нет
        extractResourceIfMissing("qrcode.min.js");
    }

    public Path getWebDir() {
        return webDir;
    }

    /** Читает шаблон с диска (горячая перезагрузка дизайна без рестарта). */
    public String load(String name) {
        Path file = webDir.resolve(name);
        if (Files.exists(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("[KeepLit] Ошибка чтения шаблона {}", file, e);
            }
        }
        return getDefault(name);
    }

    public String getDefault(String name) {
        return switch (name) {
            case "index.html" -> INDEX_DEFAULT;
            case "history.html" -> HISTORY_DEFAULT;
            case "report.html" -> REPORT_DEFAULT;
            default -> "<h1>Шаблон не найден</h1>";
        };
    }

    private void writeDefaultIfMissing(String name, String content) {
        Path file = webDir.resolve(name);
        if (!Files.exists(file)) {
            try {
                FileUtils.writeAtomically(file, content);
                LOGGER.info("[KeepLit] Создан шаблон: {}", file);
            } catch (IOException e) {
                LOGGER.error("[KeepLit] Ошибка создания шаблона {}", file, e);
            }
        }
    }

    /** Достаёт файл из ресурсов jar (src/main/resources/keeplit/web/...) в папку web. */
    private void extractResourceIfMissing(String resourceName) {
        Path target = webDir.resolve(resourceName);
        if (Files.exists(target)) return;

        try (InputStream in = WebTemplateStore.class.getResourceAsStream("/keeplit/web/" + resourceName)) {
            if (in == null) {
                LOGGER.warn("[KeepLit] Ресурс {} не найден внутри jar", resourceName);
                return;
            }
            FileUtils.writeAtomically(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            LOGGER.info("[KeepLit] Извлечено из jar: {}", target);
        } catch (IOException e) {
            LOGGER.error("[KeepLit] Ошибка извлечения ресурса {}", resourceName, e);
        }
    }

    private static final String INDEX_DEFAULT = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>KeepLit - Биллинг</title>
            <style>
                body { background: #202020; color: #e8e8e8; font-family: monospace; padding: 48px; max-width: 900px; margin: 0 auto; }
                h1 { color: #ff9900; text-align: center; }
                h2 { color: #ff9900; border-bottom: 1px solid #444; padding-bottom: 5px; }
                .period { text-align: center; color: #aaa; margin-bottom: 20px; }
                .card { background: #2d2d2d; border: 1px solid #444; padding: 24px; margin-bottom: 30px; border-radius: 8px; }
                .target { font-size: 28px; color: #4caf50; text-align: center; margin-bottom: 10px; }
                .progress-bar { background: #444; border-radius: 4px; height: 20px; margin: 15px 0; overflow: hidden; }
                .progress-fill { background: #4caf50; height: 100%; text-align: center; color: #fff; font-weight: bold; line-height: 20px; transition: width 0.5s; }
                table { width: 100%; border-collapse: collapse; }
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
                .qr-box { background: #fff; padding: 12px; border-radius: 8px; display: inline-block; }
            </style>
        </head>
        <body>
            <h1>🔥 KeepLit</h1>
            <div style="text-align:center; margin-bottom:15px;">
                <a href="/history" style="color:#ff9900; text-decoration:none;">📚 История периодов</a>
            </div>
            <div class="period">Расчётный период: <b>{{PERIOD_RANGE}}</b></div>
            <div class="days-remaining {{DAYS_WARNING_CLASS}}">До конца периода: <b>{{DAYS_REMAINING}}</b> дн.</div>

            <div class="card">
                <div class="target">Цель: {{TARGET_COST}} {{CURRENCY}}</div>
                <div style="text-align:center; font-size: 18px;">Собрано: <b>{{COLLECTED_TOTAL}} {{CURRENCY}}</b></div>
                <div class="progress-bar">
                    <div class="progress-fill" style="width: {{PROGRESS_PERCENT}}%;">{{PROGRESS_PERCENT}}%</div>
                </div>
                <div class="pay-link">
                    <a href="{{PAYMENT_URL}}" target="_blank">💳 Перейти к оплате</a>
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
                        {{ACTIVE_ROWS}}
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
                        {{NEWBIE_ROWS}}
                    </tbody>
                </table>
            </div>

            <div class="card" style="text-align:center;">
                <h2>QR-код</h2>
                <div id="qr" class="qr-box"></div>
                <p style="color:#888; font-size:12px;">Отсканируй код телефоном, чтобы открыть страницу оплаты</p>
            </div>

            <script src="/qrcode.min.js"></script>
            <script>
                function markPaid(uuid, amount) {
                    if (!confirm('Вы действительно оплатили ' + amount + ' {{CURRENCY}}?')) return;
                    fetch('/api/mark-paid', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ uuid: uuid, amount: amount })
                    }).then(r => { if (r.ok) location.reload(); else alert('Ошибка'); });
                }

                if (typeof QRCode !== 'undefined') {
                    new QRCode(document.getElementById('qr'), {
                        text: '{{QR_TEXT}}',
                        width: 200,
                        height: 200,
                        colorDark: '#000000',
                        colorLight: '#ffffff',
                        correctLevel: QRCode.CorrectLevel.M
                    });
                } else {
                    document.getElementById('qr').outerHTML = '<p style="color:#888;">QR-библиотека не найдена.</p>';
                }
            </script>
        </body>
        </html>
        """;

    private static final String HISTORY_DEFAULT = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>KeepLit - История периодов</title>
            <style>
                body { background: #202020; color: #e8e8e8; font-family: monospace; padding: 48px; max-width: 900px; margin: 0 auto; }
                h1 { color: #ff9900; text-align: center; }
                .back { text-align: center; margin-bottom: 20px; }
                .back a { color: #ff9900; text-decoration: none; }
                .card { background: #2d2d2d; border: 1px solid #444; padding: 24px; border-radius: 8px; }
                table { width: 100%; border-collapse: collapse; }
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
                    <tbody>{{REPORT_ROWS}}</tbody>
                </table>
            </div>
        </body>
        </html>
        """;

    private static final String REPORT_DEFAULT = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>KeepLit - Отчёт {{PERIOD_START}} — {{PERIOD_END}}</title>
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
                .progress-fill { background: #4caf50; height: 100%; text-align: center; color: #fff; font-weight: bold; line-height: 20px; }
                table { width: 100%; border-collapse: collapse; }
                th, td { padding: 12px; text-align: left; border-bottom: 1px solid #444; }
                th { color: #ff9900; }
                .amount { font-weight: bold; color: #fff; text-align: right; }
                .newbie-note { color: #888; font-style: italic; }
                .paid-badge { color: #4caf50; font-weight: bold; }
                .unpaid { color: #ff6b6b; }
                .empty { text-align: center; color: #666; }
                .meta { text-align: center; color: #666; font-size: 12px; margin-top: 30px; }
            </style>
        </head>
        <body>
            <h1>📜 Архивный отчёт</h1>
            <div class="archived-badge">⚠ Период закрыт. Данные заморожены.</div>
            <div class="nav">
                <div>{{PREV_LINK}}</div>
                <div>{{NEXT_LINK}}</div>
            </div>
            <div class="period">Период: <b>{{PERIOD_START}} — {{PERIOD_END}}</b></div>

            <div class="card">
                <div class="target">Цель: {{TARGET_COST}} {{CURRENCY}}</div>
                <div style="text-align:center; font-size: 18px;">Собрано: <b>{{COLLECTED_TOTAL}} {{CURRENCY}}</b></div>
                <div class="progress-bar">
                    <div class="progress-fill" style="width: {{PROGRESS_PERCENT}}%;">{{PROGRESS_PERCENT}}%</div>
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
                    <tbody>{{ACTIVE_ROWS}}</tbody>
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
                    <tbody>{{NEWBIE_ROWS}}</tbody>
                </table>
            </div>

            <div class="meta">Отчёт создан: {{CREATED_AT}}</div>
        </body>
        </html>
        """;
}
