import java.util.Map;

/**
 * One handler per X-Elf-Kind this elf serves.
 *
 * Execute is allowed to:
 *   - return a Map → consumer enqueues it as a `.result` reply (only if
 *     the original envelope set X-Elf-Reply-To AND the consumer was
 *     configured with a reply producer).
 *   - return null → no reply.
 *   - throw {@link RetryableException} → consumer bumps X-Elf-Attempt
 *     and re-queues until max attempts exhausted.
 *   - throw anything else → consumer dead-letters with the message text.
 *
 * Handlers must be idempotent: the consumer dedupes on Message-Id, but
 * crashes mid-execution can re-deliver. See docs/elf-bus-kinds.md for the
 * per-kind idempotency story.
 */
public interface ElfBusHandler {

    /** The X-Elf-Kind string this handler serves. */
    String kind();

    /** Execute. Return reply payload or null. See class javadoc for error semantics. */
    Map<String, Object> execute(ElfBusEnvelope env) throws Exception;

    /** Thrown by a handler to ask the consumer for a bounded retry. */
    final class RetryableException extends Exception {
        public RetryableException(String message) { super(message); }
        public RetryableException(String message, Throwable cause) { super(message, cause); }
    }
}
