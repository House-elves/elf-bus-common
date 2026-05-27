import jakarta.mail.MessagingException;

import java.io.IOException;
import java.util.Map;

/**
 * Producer side of the elf-bus.
 *
 * Two implementations:
 *   - {@link FileSystemBus} — Maildir-backed, the production path.
 *   - {@link InMemoryBus}    — test fake that records calls without writing to disk.
 */
public interface ElfBusProducer {

    /**
     * Enqueue a message into the target elf's inbox.
     *
     * @param targetElf bare elf name (e.g. "github-worker")
     * @param kind      X-Elf-Kind value (e.g. "github.issue.create")
     * @param payload   JSON-serialisable body
     * @param opts      optional envelope fields (correlation id, reply-to, etc.)
     * @return the Message-Id assigned to the enqueued message
     */
    String enqueue(String targetElf,
                   String kind,
                   Map<String, Object> payload,
                   EnvelopeOptions opts) throws IOException, MessagingException;
}
