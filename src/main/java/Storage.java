import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Storage {
    record Data(long nextId, Map<String, Shortener.Link> links, Map<UUID, List<String>> notices) {
    }

    private final Path path;
    private final Gson gson = new GsonBuilder()
            .setStrictness(Strictness.STRICT)
            .setPrettyPrinting()
            .create();

    Storage(Path path) {
        this.path = path.toAbsolutePath();
    }

    Data load() throws IOException {
        if (Files.notExists(path)) {
            return new Data(1_000_000, Map.of(), Map.of());
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            Data data = gson.fromJson(reader, Data.class);
            if (data == null || data.nextId() < 1_000_000 || data.links() == null || data.notices() == null) {
                throw new IOException("Неполный файл данных: " + path);
            }
            for (Map.Entry<String, Shortener.Link> entry : data.links().entrySet()) {
                Shortener.Link link = entry.getValue();
                if (link == null || !entry.getKey().equals(link.code())
                        || link.url() == null || !validExpiry(link.expires())
                        || !data.notices().containsKey(link.owner())
                        || link.visits() < 0 || link.limit() < 1 || link.visits() > link.limit()
                        || Long.parseLong(link.code(), 36) >= data.nextId()) {
                    throw new IOException("Некорректная ссылка в файле данных: " + path);
                }
            }
            for (Map.Entry<UUID, List<String>> entry : data.notices().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().contains(null)) {
                    throw new IOException("Некорректные данные пользователя: " + path);
                }
            }
            return data;
        } catch (JsonParseException | IllegalArgumentException | DateTimeException e) {
            throw new IOException("Некорректный JSON в файле данных: " + path, e);
        }
    }

    private boolean validExpiry(String expiry) {
        if (expiry == null) {
            return false;
        }
        ParsePosition position = new ParsePosition(0);
        return DateTimeFormatter.ISO_INSTANT.parse(expiry, position) != null
                && position.getIndex() == expiry.length();
    }

    void save(Data data) throws IOException {
        Path directory = path.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".shortener-", ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(data));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
