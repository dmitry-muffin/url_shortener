import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Shortener {
    interface Browser {
        void open(URI url) throws IOException;
    }

    record Link(String code, URI url, UUID owner, String expires,
                int limit, int visits) {
        Instant expiryTime() {
            return Instant.parse(expires);
        }

        Link edited(URI newUrl, int newLimit) {
            return new Link(code, newUrl, owner, expires, newLimit, visits);
        }

        Link visited() {
            return new Link(code, url, owner, expires, limit, visits + 1);
        }
    }

    private final Settings settings;
    private final Clock clock;
    private final Map<String, Link> links = new LinkedHashMap<>();
    private final Map<UUID, List<String>> notices = new HashMap<>();
    private long nextId = 1_000_000;
    private Storage storage;
    private Storage.Data saved;

    Shortener(Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    Shortener(Settings settings, Clock clock, Storage storage) throws IOException {
        this(settings, clock);
        this.storage = storage;
        saved = storage.load();
        restore(saved);
    }

    private void restore(Storage.Data data) {
        nextId = data.nextId();
        links.clear();
        links.putAll(data.links());
        notices.clear();
        for (Map.Entry<UUID, List<String>> entry : data.notices().entrySet()) {
            notices.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
    }

    private void save() {
        if (storage == null) {
            return;
        }
        Map<UUID, List<String>> copiedNotices = new HashMap<>();
        for (Map.Entry<UUID, List<String>> entry : notices.entrySet()) {
            copiedNotices.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        Storage.Data snapshot = new Storage.Data(nextId, new LinkedHashMap<>(links), copiedNotices);
        try {
            storage.save(snapshot);
            saved = snapshot;
        } catch (IOException e) {
            restore(saved);
            throw new UncheckedIOException("Не удалось сохранить данные: " + e.getMessage(), e);
        }
    }

    synchronized Link create(UUID owner, String url, int limit) {
        URI target = parseUrl(url);
        checkLimit(limit);
        if (owner == null) {
            do {
                owner = UUID.randomUUID();
            } while (notices.containsKey(owner));
            notices.put(owner, new ArrayList<>());
        }
        requireUser(owner);
        String code = Long.toString(nextId++, 36);
        Instant expiry = clock.instant().plus(settings.ttl());
        Link link = new Link(code, target, owner, expiry.toString(), limit, 0);
        links.put(code, link);
        save();
        return link;
    }

    synchronized void requireUser(UUID owner) {
        if (!notices.containsKey(owner)) {
            throw new IllegalArgumentException("UUID не найден.");
        }
    }

    synchronized List<Link> list(UUID owner) {
        requireUser(owner);
        cleanup();
        List<Link> result = new ArrayList<>();
        for (Link link : links.values()) {
            if (link.owner().equals(owner)) {
                result.add(link);
            }
        }
        return result;
    }

    synchronized List<String> takeNotices(UUID owner) {
        List<String> pending = notices.get(owner);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        List<String> result = List.copyOf(pending);
        pending.clear();
        save();
        return result;
    }

    synchronized void open(String code, Browser browser) throws IOException {
        Link link = find(code);
        if (link.visits() >= link.limit()) {
            throw new IllegalArgumentException("Лимит переходов исчерпан.");
        }
        browser.open(link.url());
        Link updated = link.visited();
        links.put(code, updated);
        if (updated.visits() == updated.limit()) {
            notifyOwner(updated, "лимит переходов исчерпан.");
        }
        save();
    }

    synchronized void edit(UUID owner, String code, String url, Integer limit) {
        Link link = ownedLink(owner, code);
        URI target = url == null ? link.url() : parseUrl(url);
        int newLimit = limit == null ? link.limit() : limit;
        checkLimit(newLimit);
        if (newLimit < link.visits()) {
            throw new IllegalArgumentException("Лимит не может быть меньше числа переходов.");
        }
        Link updated = link.edited(target, newLimit);
        links.put(code, updated);
        if (link.visits() < link.limit() && updated.visits() == updated.limit()) {
            notifyOwner(updated, "лимит переходов исчерпан.");
        }
        save();
    }

    synchronized void delete(UUID owner, String code) {
        ownedLink(owner, code);
        links.remove(code);
        save();
    }

    synchronized void cleanup() {
        Instant now = clock.instant();
        List<String> expiredCodes = new ArrayList<>();
        for (Link link : links.values()) {
            if (!now.isBefore(link.expiryTime())) {
                expiredCodes.add(link.code());
                notifyOwner(link, "срок действия истёк, ссылка удалена.");
            }
        }
        for (String code : expiredCodes) {
            links.remove(code);
        }
        if (!expiredCodes.isEmpty()) {
            save();
        }
    }

    private Link find(String code) {
        cleanup();
        Link link = links.get(code);
        if (link == null) {
            throw new IllegalArgumentException("Ссылка не найдена или срок её действия истёк.");
        }
        return link;
    }

    private Link ownedLink(UUID owner, String code) {
        Link link = find(code);
        if (!link.owner().equals(owner)) {
            throw new IllegalArgumentException("Управлять ссылкой может только её владелец.");
        }
        return link;
    }

    private void notifyOwner(Link link, String message) {
        notices.get(link.owner()).add(settings.prefix() + link.code() + " — " + message);
    }

    private void checkLimit(int limit) {
        if (limit < 1 || limit > settings.maxLimit()) {
            throw new IllegalArgumentException("Введите лимит от 1 до " + settings.maxLimit() + ".");
        }
    }

    private static URI parseUrl(String text) {
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный URL.");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Введите полный HTTP(S) URL, например https://example.com.");
        }
        return uri;
    }
}
