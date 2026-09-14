package ru.keeplit;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FileUtils {

    /**
     * Атомарная запись строки в файл.
     * Пишем во временный файл, потом переименовываем.
     */
    public static void writeAtomically(Path targetPath, String content) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Создаём временный файл в той же директории
        Path tempFile = Files.createTempFile(parent, targetPath.getFileName().toString(), ".tmp");

        try {
            // Пишем во временный файл
            try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
            }

            // Атомарно переименовываем временный файл в целевой
            try {
                Files.move(tempFile, targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Если атомарное перемещение не поддерживается (например, на некоторых ФС),
                // делаем обычное перемещение
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Если что-то пошло не так, удаляем временный файл
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Игнорируем ошибку удаления
            }
            throw e;
        }
    }
}
