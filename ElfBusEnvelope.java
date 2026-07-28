import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Parsed view of one elf-bus message (RFC 5322 + JSON body).
 *
 * Read-only; for the retry rewrite path the consumer re-parses the file
 * as a {@link MimeMessage} and mutates headers there, not on this record.
 */
public record ElfBusEnvelope(
        String   messageId,
        String   busVersion,
        String   kind,
        String   from,           // bare elf name, e.g. "mail-worker"
        String   replyToElf,     // null when absent
        String   correlationId,  // null when absent
        String   provenance,     // null when absent
        int      attempt,
        int      maxAttempts,
        JsonNode body
) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Session SESSION = Session.getInstance(new Properties());

    public static ElfBusEnvelope read(Path file) throws IOException, MessagingException {
        try (InputStream in = Files.newInputStream(file)) {
            MimeMessage m = new MimeMessage(SESSION, in);
            Object content = m.getContent();
            // jakarta.mail has no DataContentHandler for application/json, so
            // getContent() hands back a raw InputStream - toString()ing that
            // feeds "jakarta.mail.util.SharedByteArrayInputStream@..." to the
            // JSON parser. Read the bytes.
            String json;
            if (content instanceof String s) {
                json = s;
            } else if (content instanceof InputStream body) {
                json = new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                json = content.toString();
            }
            return new ElfBusEnvelope(
                    require(m, "Message-Id"),
                    require(m, "X-Elf-Bus-Version"),
                    require(m, "X-Elf-Kind"),
                    bareElf(require(m, "From")),
                    optional(m, "X-Elf-Reply-To"),
                    optional(m, "X-Elf-Correlation-Id"),
                    optional(m, "X-Elf-Provenance"),
                    intHeader(m, "X-Elf-Attempt",      1),
                    intHeader(m, "X-Elf-Max-Attempts", 5),
                    JSON.readTree(json)
            );
        }
    }

    private static String require(MimeMessage m, String name) throws MessagingException, IOException {
        String[] h = m.getHeader(name);
        if (h == null || h.length == 0 || h[0].isBlank())
            throw new IOException("missing required header: " + name);
        return h[0];
    }
    private static String optional(MimeMessage m, String name) throws MessagingException {
        String[] h = m.getHeader(name);
        return (h == null || h.length == 0) ? null : h[0];
    }
    private static int intHeader(MimeMessage m, String name, int dflt) throws MessagingException {
        String v = optional(m, name);
        if (v == null) return dflt;
        try { return Integer.parseInt(v.strip()); } catch (NumberFormatException e) { return dflt; }
    }
    private static String bareElf(String addr) {
        int lt = addr.indexOf('<'), gt = addr.indexOf('>');
        String inner = (lt >= 0 && gt > lt) ? addr.substring(lt + 1, gt) : addr;
        int at = inner.indexOf('@');
        return (at > 0) ? inner.substring(0, at) : inner;
    }
}
