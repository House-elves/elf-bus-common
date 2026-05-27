import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Maildir-backed producer. Atomic delivery: write to tmp/, fsync,
 * rename to new/. See docs/elf-bus.md §3.
 */
public class FileSystemBus implements ElfBusProducer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Session SESSION = Session.getInstance(new Properties());
    private static final String VERSION = "1";

    private final Path busRoot;
    private final String selfElf;

    public FileSystemBus(Path busRoot, String selfElf) {
        this.busRoot = busRoot;
        this.selfElf = selfElf;
    }

    @Override
    public String enqueue(String targetElf, String kind,
                          Map<String, Object> payload, EnvelopeOptions opts)
            throws IOException, MessagingException {
        Path inbox = ElfBusInboxes.ensure(busRoot, targetElf);
        String messageId = "<" + UUID.randomUUID() + "@elf-bus.local>";
        String filename = ElfBusInboxes.generateFilename();

        byte[] bytes = build(messageId, targetElf, kind, payload, opts);

        Path tmp    = inbox.resolve("tmp").resolve(filename);
        Path target = inbox.resolve("new").resolve(filename);

        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(bytes));
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        return messageId;
    }

    private byte[] build(String messageId, String targetElf, String kind,
                         Map<String, Object> payload, EnvelopeOptions opts)
            throws MessagingException, IOException {
        MimeMessage m = new MimeMessage(SESSION);
        m.setHeader("Message-Id", messageId);
        m.setFrom(new InternetAddress(selfElf + "@elf-bus.local"));
        m.setRecipients(Message.RecipientType.TO,
                new Address[]{ new InternetAddress(targetElf + "@elf-bus.local") });
        m.setSentDate(Date.from(Instant.now()));
        m.setHeader("X-Elf-Bus-Version", VERSION);
        m.setHeader("X-Elf-Kind", kind);
        if (opts.correlationId() != null) m.setHeader("X-Elf-Correlation-Id", opts.correlationId());
        if (opts.replyToElf()    != null) m.setHeader("X-Elf-Reply-To",       opts.replyToElf());
        if (opts.provenance()    != null) m.setHeader("X-Elf-Provenance",     opts.provenance());
        if (opts.inReplyTo()     != null) {
            m.setHeader("In-Reply-To", opts.inReplyTo());
            // v1 single-hop References; multi-hop chains TBD when needed.
            m.setHeader("References", opts.inReplyTo());
        }
        if (opts.maxAttempts() != null) {
            m.setHeader("X-Elf-Max-Attempts", String.valueOf(opts.maxAttempts()));
        }
        m.setContent(JSON.writeValueAsString(payload), "application/json; charset=utf-8");
        m.saveChanges();

        var baos = new ByteArrayOutputStream();
        m.writeTo(baos);
        return baos.toByteArray();
    }
}
