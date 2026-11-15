import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

record Settings(Duration ttl, int cleanupSeconds, int defaultLimit, int maxLimit, String prefix, Path dataFile) {
    static Settings load(Path path) throws IOException {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }

        int max = positive(properties, "limit.max");
        int defaultLimit = positive(properties, "limit.default");
        String prefix = properties.getProperty("short.prefix", "").strip();
        if (defaultLimit > max || !prefix.matches("[a-zA-Z0-9.-]+/")) {
            throw new IllegalArgumentException("Проверьте limit.default, limit.max и short.prefix.");
        }
        String dataFile = properties.getProperty("data.file", "data/links.json").strip();
        if (dataFile.isEmpty()) {
            throw new IllegalArgumentException("Укажите путь в data.file.");
        }
        Duration ttl = Duration.ofSeconds(positive(properties, "ttl.seconds"));
        int cleanupSeconds = positive(properties, "cleanup.seconds");
        Path dataPath = path.toAbsolutePath().resolveSibling(dataFile).normalize();
        return new Settings(ttl, cleanupSeconds, defaultLimit, max, prefix, dataPath);
    }

    private static int positive(Properties properties, String key) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, "").strip());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Сообщение ниже указывает имя параметра.
        }
        throw new IllegalArgumentException(key + " должен быть положительным целым числом.");
    }
}
