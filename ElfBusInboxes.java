import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Path and filename helpers for the elf-bus Maildir layout.
 * One source of truth so every elf agrees on inbox paths and filename format.
 */
public final class ElfBusInboxes {
    private ElfBusInboxes() {}

    public static Path inbox(Path busRoot, String elf) {
        return busRoot.resolve(elf).resolve("Maildir");
    }

    public static Path ensure(Path busRoot, String elf) throws IOException {
        Path inbox = inbox(busRoot, elf);
        for (String sub : List.of("tmp", "new", "cur", ".dead/tmp", ".dead/new", ".dead/cur")) {
            Files.createDirectories(inbox.resolve(sub));
        }
        return inbox;
    }

    /** Per spec §3.1: {@code <unix-time>.<unique>.<host>}. */
    public static String generateFilename() {
        return Instant.now().getEpochSecond()
             + "." + UUID.randomUUID().toString().replace("-", "")
             + ".elf-bus";
    }
}
