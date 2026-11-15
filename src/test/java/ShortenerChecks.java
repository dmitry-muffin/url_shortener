import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class ShortenerChecks {
    private static final String URL = "https://example.com";
    private static final Settings SETTINGS = new Settings(Duration.ofSeconds(2), 1, 2, 5, "short.local/", Path.of("data/links.json"));
    private final TestClock clock = new TestClock();
    private final Shortener service = new Shortener(SETTINGS, clock);
    private final List<URI> opened = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<Check> checks = List.of(
                () -> new ShortenerChecks().unique(),
                () -> new ShortenerChecks().multiple(),
                () -> new ShortenerChecks().openTarget(),
                () -> new ShortenerChecks().limitBlocks(),
                () -> new ShortenerChecks().limitNotice(),
                () -> new ShortenerChecks().expiry(),
                () -> new ShortenerChecks().expiryNotice(),
                () -> new ShortenerChecks().foreignEdit(),
                () -> new ShortenerChecks().foreignDelete(),
                () -> new ShortenerChecks().ownerDelete(),
                () -> new ShortenerChecks().editTarget(),
                () -> new ShortenerChecks().editLimit(),
                () -> new ShortenerChecks().invalidInput(),
                () -> new ShortenerChecks().failedBrowser(),
                () -> new ShortenerChecks().console(),
                ShortenerChecks::config,
                () -> new ShortenerChecks().restart());
        for (Check check : checks) {
            check.run();
        }
        System.out.println("Пройдено проверок: " + checks.size());
    }

    private void unique() {
        var first = service.create(null, URL, 1);
        var second = service.create(null, URL, 1);
        require(!first.code().equals(second.code()) && !first.owner().equals(second.owner()));
    }

    private void multiple() {
        var first = service.create(null, URL, 1);
        var second = service.create(first.owner(), URL, 1);
        var other = service.create(null, URL, 1);
        require(service.list(first.owner()).equals(List.of(first, second)));
        require(service.list(other.owner()).equals(List.of(other)));
    }

    private void openTarget() throws IOException {
        var link = service.create(null, URL, 2);
        service.open(link.code(), opened::add);
        require(opened.equals(List.of(URI.create(URL))));
    }

    private void limitBlocks() throws Exception {
        var link = service.create(null, URL, 2);
        service.open(link.code(), opened::add);
        service.open(link.code(), opened::add);
        rejected(() -> service.open(link.code(), opened::add));
        require(opened.size() == 2);
    }

    private void limitNotice() throws Exception {
        var link = service.create(null, URL, 1);
        var other = service.create(null, URL, 1);
        service.open(link.code(), opened::add);
        require(service.takeNotices(other.owner()).isEmpty());
        require(service.takeNotices(link.owner()).getFirst().contains("лимит переходов исчерпан"));
        require(service.takeNotices(link.owner()).isEmpty());
    }

    private void expiry() throws Exception {
        var link = service.create(null, URL, 2);
        clock.now = link.expiryTime().minusNanos(1);
        service.open(link.code(), opened::add);
        clock.now = link.expiryTime();
        rejected(() -> service.open(link.code(), opened::add));
        require(service.list(link.owner()).isEmpty());
    }

    private void expiryNotice() {
        var link = service.create(null, URL, 2);
        clock.now = link.expiryTime();
        service.cleanup();
        service.cleanup();

        var notices = service.takeNotices(link.owner());
        require(notices.size() == 1 && notices.getFirst().contains("срок действия истёк"));
    }

    private void foreignEdit() throws Exception {
        var link = service.create(null, URL, 1);
        var other = service.create(null, URL, 1);

        rejected(() -> service.edit(other.owner(), link.code(), null, 2));
        require(service.list(link.owner()).equals(List.of(link)));
    }

    private void foreignDelete() throws Exception {
        var link = service.create(null, URL, 1);

        rejected(() -> service.delete(null, link.code()));
        require(service.list(link.owner()).equals(List.of(link)));
    }

    private void ownerDelete() throws Exception {
        var link = service.create(null, URL, 1);

        service.delete(link.owner(), link.code());
        rejected(() -> service.open(link.code(), opened::add));
    }

    private void editTarget() throws Exception {
        var link = service.create(null, URL, 2);

        service.edit(link.owner(), link.code(), URL + "/new", null);
        service.open(link.code(), opened::add);
        require(opened.equals(List.of(URI.create(URL + "/new"))));
        require(service.list(link.owner()).getFirst().expiryTime().equals(link.expiryTime()));
    }

    private void editLimit() throws Exception {
        var link = service.create(null, URL, 1);

        service.open(link.code(), opened::add);
        service.edit(link.owner(), link.code(), null, 2);
        service.open(link.code(), opened::add);
        rejected(() -> service.edit(link.owner(), link.code(), null, 1));
        rejected(() -> service.open(link.code(), opened::add));
    }

    private void invalidInput() throws Exception {
        rejected(() -> service.create(null, "file:///tmp/example", 1));
        rejected(() -> service.create(null, "https:/example.com", 1));
        rejected(() -> service.create(null, URL, 0));
        rejected(() -> service.create(null, URL, 6));
    }

    private void failedBrowser() throws Exception {
        var link = service.create(null, URL, 1);

        try {
            service.open(link.code(), url -> {
                require(URI.create(URL).equals(url));
                throw new IOException("Браузер недоступен");
            });
            throw new AssertionError("Ожидалась ошибка браузера.");
        } catch (IOException expected) {
            require(service.list(link.owner()).getFirst().visits() == 0);
        }

        service.open(link.code(), opened::add);
        require(opened.size() == 1);
    }

    private void console() {
        String firstCode = "short.local/" + Long.toString(1_000_000, 36);
        String commands = "create " + URL + " 1\nnew\ncreate " + URL + "\n"
                + "delete " + firstCode + "\n" + firstCode + "\nopen " + firstCode + "\nexit\n";

        var output = new ByteArrayOutputStream();
        try (var input = new Scanner(commands); var writer = new PrintStream(output)) {
            new Console(SETTINGS, service, opened::add, writer).run(input);
        }

        require(opened.size() == 1);
        String messages = output.toString();
        require(messages.contains("Короткая ссылка: " + firstCode));
        require(messages.contains("только её владелец"));
        require(messages.contains("Лимит переходов исчерпан"));
    }

    private void restart() throws Exception {
        Path directory = Files.createTempDirectory("shortener-check");
        Path file = directory.resolve("links.json");
        try {
            var storage = new Storage(file);
            var firstRun = new Shortener(SETTINGS, clock, storage);
            var link = firstRun.create(null, URL, 1);
            var other = firstRun.create(null, URL, 2);
            firstRun.open(link.code(), opened::add);

            var restarted = new Shortener(SETTINGS, clock, storage);
            restarted.requireUser(link.owner());
            require(restarted.list(link.owner()).getFirst().visits() == 1);
            rejected(() -> restarted.open(link.code(), opened::add));
            rejected(() -> restarted.delete(other.owner(), link.code()));
            require(restarted.takeNotices(link.owner()).getFirst().contains("лимит переходов исчерпан"));
            restarted.delete(link.owner(), link.code());

            var finalRun = new Shortener(SETTINGS, clock, storage);
            require(finalRun.list(link.owner()).isEmpty());
            require(finalRun.takeNotices(link.owner()).isEmpty());
            var newLink = finalRun.create(link.owner(), URL, 1);
            require(!newLink.code().equals(link.code()) && !newLink.code().equals(other.code()));
            clock.now = newLink.expiryTime();
            finalRun.cleanup();
            var afterExpiry = new Shortener(SETTINGS, clock, storage);
            require(afterExpiry.list(link.owner()).isEmpty());
            require(afterExpiry.takeNotices(link.owner()).getFirst().contains("срок действия истёк"));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void config() throws IOException {
        Settings loaded = Settings.load(Path.of("shortener.properties"));
        require(loaded.ttl().isPositive() && loaded.defaultLimit() <= loaded.maxLimit());
    }

    private static void rejected(Check action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Ожидался отказ операции.");
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("Проверка не пройдена.");
        }
    }

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
