import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

final class Console {
    private final Settings settings;
    private final Shortener service;
    private final Shortener.Browser browser;
    private final PrintStream out;
    private UUID owner;

    Console(Settings settings, Shortener service, Shortener.Browser browser, PrintStream out) {
        this.settings = settings;
        this.service = service;
        this.browser = browser;
        this.out = out;
    }

    void run(Scanner input) {
        help();
        while (true) {
            out.print("> ");
            if (!input.hasNextLine()) {
                return;
            }
            String line = input.nextLine().strip();
            if (line.equals("exit")) {
                return;
            }
            handle(line);
        }
    }

    synchronized void backgroundCleanup() {
        try {
            service.cleanup();
            showNotices();
        } catch (UncheckedIOException e) {
            out.println("Ошибка: " + e.getMessage());
        }
    }

    private synchronized void handle(String line) {
        try {
            service.cleanup();
            showNotices();
            if (!line.isEmpty()) {
                execute(line.split("\\s+"));
            }
            showNotices();
        } catch (NumberFormatException e) {
            out.println("Ошибка: лимит должен быть целым числом.");
        } catch (IllegalArgumentException | IOException | UncheckedIOException
                 | UnsupportedOperationException | SecurityException e) {
            out.println("Ошибка: " + e.getMessage());
        }
    }

    private void execute(String[] parts) throws IOException {
        switch (parts[0]) {
            case "help" -> help();
            case "create" -> {
                if (parts.length < 2 || parts.length > 3) {
                    throw new IllegalArgumentException("Формат: create URL [лимит]");
                }
                int limit = parts.length == 3 ? Integer.parseInt(parts[2]) : settings.defaultLimit();
                Shortener.Link link = service.create(owner, parts[1], limit);
                if (owner == null) {
                    owner = link.owner();
                    out.println("Ваш UUID: " + owner + ". Сохраните его для команды user.");
                }
                out.println("Короткая ссылка: " + settings.prefix() + link.code());
            }
            case "user" -> {
                checkArgs(parts, 2, "user UUID");
                UUID selected;
                try {
                    selected = UUID.fromString(parts[1]);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Введите UUID, выданный при создании ссылки.");
                }
                service.requireUser(selected);
                owner = selected;
                out.println("Активный пользователь: " + owner);
            }
            case "new" -> {
                checkArgs(parts, 1, "new");
                owner = null;
                out.println("UUID нового пользователя будет выдан при создании ссылки.");
            }
            case "list" -> {
                checkArgs(parts, 1, "list");
                List<Shortener.Link> links = service.list(currentUser());
                if (links.isEmpty()) {
                    out.println("У вас нет ссылок.");
                }
                for (Shortener.Link link : links) {
                    out.printf("%s%s → %s | переходы %d/%d | действует до %s%n",
                            settings.prefix(), link.code(), link.url(), link.visits(), link.limit(), link.expires());
                }
            }
            case "limit" -> {
                checkArgs(parts, 3, "limit ссылка число");
                service.edit(currentUser(), code(parts[1]), null, Integer.parseInt(parts[2]));
                out.println("Лимит изменён.");
            }
            case "url" -> {
                checkArgs(parts, 3, "url ссылка новый_URL");
                service.edit(currentUser(), code(parts[1]), parts[2], null);
                out.println("Адрес изменён.");
            }
            case "delete" -> {
                checkArgs(parts, 2, "delete ссылка");
                service.delete(currentUser(), code(parts[1]));
                out.println("Ссылка удалена.");
            }
            case "open" -> {
                checkArgs(parts, 2, "open ссылка");
                open(parts[1]);
            }
            default -> {
                if (parts.length == 1 && parts[0].startsWith(settings.prefix())) {
                    open(parts[0]);
                } else {
                    out.println("Неизвестная команда. Введите help.");
                }
            }
        }
    }

    private UUID currentUser() {
        if (owner == null) {
            throw new IllegalArgumentException("Сначала создайте ссылку или выберите пользователя командой user.");
        }
        return owner;
    }

    private void showNotices() {
        for (String message : service.takeNotices(owner)) {
            out.println("Уведомление: " + message);
        }
    }

    private String code(String input) {
        String code = input.startsWith(settings.prefix()) ? input.substring(settings.prefix().length()) : input;
        if (!code.matches("[0-9a-z]+")) {
            throw new IllegalArgumentException("Введите короткую ссылку или её код.");
        }
        return code;
    }

    private void open(String input) throws IOException {
        service.open(code(input), browser);
        out.println("Ссылка передана браузеру.");
    }

    private void checkArgs(String[] parts, int count, String usage) {
        if (parts.length != count) {
            throw new IllegalArgumentException("Формат: " + usage);
        }
    }

    private void help() {
        out.println("""
                create https://example.com [лимит] — сократить URL
                open ссылка — открыть URL (можно просто вставить короткую ссылку)
                list — показать свои ссылки
                limit ссылка число — изменить общий лимит
                url ссылка новый_URL — изменить адрес назначения
                delete ссылка — удалить свою ссылку
                new — переключиться на нового пользователя
                user UUID — вернуться к существующему пользователю
                help — показать команды
                exit — выйти
                После перезапуска введите user UUID, чтобы вернуться к своим ссылкам.
                """);
    }
}
