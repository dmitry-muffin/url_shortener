import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        try {
            Settings settings = Settings.load(Path.of(args.length == 0 ? "shortener.properties" : args[0]));
            Path dataFile = settings.dataFile();
            Files.createDirectories(dataFile.getParent());
            Path lockFile = dataFile.resolveSibling(dataFile.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("Файл данных уже используется другим экземпляром программы.");
                }
                run(settings);
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            System.err.println("Не удалось запустить сервис: " + e.getMessage());
        }
    }

    private static void run(Settings settings) throws IOException {
        Shortener service = new Shortener(settings, Clock.systemUTC(), new Storage(settings.dataFile()));
        service.cleanup();
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
             Scanner input = new Scanner(System.in)) {
            Console console = new Console(settings, service, Main::openInBrowser, System.out);
            scheduler.scheduleWithFixedDelay(console::backgroundCleanup, settings.cleanupSeconds(),
                    settings.cleanupSeconds(), TimeUnit.SECONDS);
            console.run(input);
        }
    }

    private static void openInBrowser(URI url) throws IOException {
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Открытие браузера недоступно в этой среде.");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Открытие браузера недоступно в этой среде.");
        }
        desktop.browse(url);
    }
}
