import jakarta.mail.MessagingException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test fake implementing {@link ElfBusProducer}. Records every {@code enqueue}
 * call in-memory instead of writing to disk.
 *
 * <pre>
 *     var bus = new InMemoryBus();
 *     mailAction.execute(new MailAction.Context(email, sender, bus, config, pending));
 *
 *     var sent = bus.sentTo("github-worker");
 *     assertEquals(1, sent.size());
 *     assertEquals("github.issue.create", sent.get(0).kind());
 * </pre>
 *
 * For consumer-side tests, write fixture messages directly into a temp
 * Maildir and run {@code consumer.poll()}; no special fake needed because
 * the consumer already reads from a Path.
 */
public class InMemoryBus implements ElfBusProducer {

    public record Sent(
            String              targetElf,
            String              kind,
            EnvelopeOptions     opts,
            Map<String, Object> payload,
            String              messageId
    ) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public String enqueue(String targetElf, String kind,
                          Map<String, Object> payload, EnvelopeOptions opts)
            throws IOException, MessagingException {
        String mid = "<test-" + UUID.randomUUID() + "@elf-bus.local>";
        sent.add(new Sent(targetElf, kind, opts, Map.copyOf(payload), mid));
        return mid;
    }

    public List<Sent> sent()                    { return List.copyOf(sent); }
    public List<Sent> sentTo(String elf)        { return sent.stream().filter(s -> s.targetElf().equals(elf)).toList(); }
    public List<Sent> sentOfKind(String kind)   { return sent.stream().filter(s -> s.kind().equals(kind)).toList(); }
    public void       clear()                   { sent.clear(); }
}
