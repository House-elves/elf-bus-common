import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Idempotency store for processed Message-Ids. Flat-file: one id per line.
 *
 * Open(), then call {@link #contains(String)} and {@link #add(String)} as
 * messages are processed. The class buffers seen ids in memory; {@code add}
 * appends to the file synchronously so a crash mid-poll loses at most one
 * pending dedupe entry.
 *
 * Swap to SQLite by replacing this class entirely — the consumer's public
 * surface doesn't expose it.
 */
public final class ElfBusSeenIds {

    private final Path file;
    private final Set<String> seen;

    public static ElfBusSeenIds open(Path file) throws IOException {
        Set<String> s = Files.exists(file)
                ? new HashSet<>(Files.readAllLines(file))
                : new HashSet<>();
        return new ElfBusSeenIds(file, s);
    }

    private ElfBusSeenIds(Path file, Set<String> seen) {
        this.file = file;
        this.seen = seen;
    }

    public boolean contains(String messageId) {
        return seen.contains(messageId);
    }

    public void add(String messageId) throws IOException {
        if (seen.add(messageId)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, messageId + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
