import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Consumer side of the elf-bus. See docs/elf-bus.md §7 for the protocol.
 *
 * Construction:
 * <pre>
 *     var consumer = new ElfBusConsumer(
 *         cfg.busRoot(),                       // ~/.local/share/elf-bus
 *         "github-worker",                     // self elf-name
 *         cfg.busSeenIdsPath(),                // flat-file id store
 *         List.of(
 *             new GitHubIssueHandlers.Create(client),
 *             new GitHubIssueHandlers.Comment(client),
 *             new GitHubIssueHandlers.Label(client)
 *         ),
 *         new FileSystemBus(cfg.busRoot(), "github-worker")  // for emitting *.result
 *     );
 *     consumer.poll();  // call on each tick
 * </pre>
 *
 * Pass {@code null} for the reply producer when this elf only consumes
 * terminal messages (e.g. mail-worker reading {@code *.result} envelopes).
 */
public class ElfBusConsumer {

    private static final Session SESSION = Session.getInstance(new Properties());
    private static final String VERSION = "1";

    private final Path busRoot;
    private final String selfElf;
    private final Path seenIdsFile;
    private final Map<String, ElfBusHandler> handlers;
    private final ElfBusProducer replyProducer;  // nullable

    public ElfBusConsumer(Path busRoot, String selfElf, Path seenIdsFile,
                          List<ElfBusHandler> handlers,
                          ElfBusProducer replyProducer) {
        this.busRoot       = busRoot;
        this.selfElf       = selfElf;
        this.seenIdsFile   = seenIdsFile;
        this.handlers      = handlers.stream()
                                .collect(Collectors.toUnmodifiableMap(ElfBusHandler::kind, h -> h));
        this.replyProducer = replyProducer;
    }

    /** Single-pass over the inbox. Caller controls cadence. */
    public void poll() throws IOException {
        Path inbox = ElfBusInboxes.ensure(busRoot, selfElf);
        List<Path> entries;
        try (var stream = Files.list(inbox.resolve("new"))) {
            entries = stream.sorted().toList();
        }
        ElfBusSeenIds seen = ElfBusSeenIds.open(seenIdsFile);
        for (Path entry : entries) processOne(entry, inbox, seen);
    }

    private void processOne(Path entry, Path inbox, ElfBusSeenIds seen) {
        String name = entry.getFileName().toString();
        Path claimed = inbox.resolve("cur").resolve(name + ":2,S");

        try {
            Files.move(entry, claimed, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException | FileAlreadyExistsException race) {
            return;
        } catch (IOException io) {
            System.err.println("elf-bus: claim failed " + name + ": " + io.getMessage());
            return;
        }

        ElfBusEnvelope env;
        try { env = ElfBusEnvelope.read(claimed); }
        catch (Exception e) { deadLetter(claimed, inbox, "parse: " + e.getMessage()); return; }

        if (!VERSION.equals(env.busVersion())) {
            deadLetter(claimed, inbox, "unsupported bus version: " + env.busVersion()); return;
        }
        ElfBusHandler handler = handlers.get(env.kind());
        if (handler == null) {
            deadLetter(claimed, inbox, "unknown kind: " + env.kind()); return;
        }
        if (seen.contains(env.messageId())) {
            try { Files.delete(claimed); } catch (IOException ignored) {}
            return;
        }

        Map<String, Object> reply;
        try {
            reply = handler.execute(env);
        } catch (ElfBusHandler.RetryableException re) {
            retry(claimed, inbox, env, re.getMessage()); return;
        } catch (Exception hard) {
            deadLetter(claimed, inbox, "hard: " + hard.getMessage()); return;
        }

        try {
            if (reply != null && env.replyToElf() != null) {
                if (replyProducer == null) {
                    System.err.println("elf-bus: handler for " + env.kind()
                            + " returned a reply but no replyProducer configured; reply dropped");
                } else {
                    replyProducer.enqueue(
                            env.replyToElf(),
                            env.kind() + ".result",
                            reply,
                            new EnvelopeOptions(env.correlationId(), null, null, env.messageId(), null)
                    );
                    // §8 breadcrumb: mark R before final unlink.
                    Path replied = inbox.resolve("cur").resolve(name + ":2,RS");
                    try { Files.move(claimed, replied, StandardCopyOption.ATOMIC_MOVE); claimed = replied; }
                    catch (IOException ignored) {}
                }
            }
            seen.add(env.messageId());
            Files.delete(claimed);
        } catch (Exception postSuccess) {
            deadLetter(claimed, inbox, "post-success: " + postSuccess.getMessage());
        }
    }

    private void retry(Path claimed, Path inbox, ElfBusEnvelope env, String reason) {
        int next = env.attempt() + 1;
        if (next > env.maxAttempts()) {
            deadLetter(claimed, inbox,
                    "exhausted retries (" + env.attempt() + "/" + env.maxAttempts() + "): " + reason);
            return;
        }
        try {
            MimeMessage m;
            try (InputStream in = Files.newInputStream(claimed)) { m = new MimeMessage(SESSION, in); }
            m.setHeader("X-Elf-Attempt", String.valueOf(next));
            m.setHeader("X-Elf-Retry-Reason", reason);
            m.saveChanges();

            String name = ElfBusInboxes.generateFilename();
            Path tmp = inbox.resolve("tmp").resolve(name);
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW)) {
                m.writeTo(out);
            }
            try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.READ)) { fc.force(true); }
            Files.move(tmp, inbox.resolve("new").resolve(name), StandardCopyOption.ATOMIC_MOVE);
            Files.delete(claimed);
        } catch (Exception e) {
            System.err.println("elf-bus: retry rewrite failed: " + e.getMessage());
        }
    }

    private void deadLetter(Path claimed, Path inbox, String reason) {
        try {
            MimeMessage m;
            try (InputStream in = Files.newInputStream(claimed)) { m = new MimeMessage(SESSION, in); }
            m.setHeader("X-Elf-Dead-Reason", reason);
            m.saveChanges();
            Path tmp = inbox.resolve(".dead").resolve("tmp").resolve(claimed.getFileName().toString());
            Path dst = inbox.resolve(".dead").resolve("new").resolve(claimed.getFileName().toString());
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW)) {
                m.writeTo(out);
            }
            try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.READ)) { fc.force(true); }
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(claimed);
        } catch (Exception e) {
            System.err.println("elf-bus: dead-letter failed: " + e.getMessage());
        }
    }
}
