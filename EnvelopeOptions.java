/**
 * Optional envelope headers a producer may set per-message.
 *
 * All fields may be null. The bus version, kind, message-id, date, from
 * and to headers are always written by the producer implementation.
 *
 * @param correlationId  X-Elf-Correlation-Id — opaque tracking id propagated across hops.
 * @param replyToElf     X-Elf-Reply-To — bare elf name the consumer should send a result envelope to.
 * @param provenance     X-Elf-Provenance — short free-text for "where did this originate"; surfaced in dashboards.
 * @param inReplyTo      In-Reply-To / References — message-id of the message this one is replying to.
 * @param maxAttempts    X-Elf-Max-Attempts — override the default retry budget (5).
 */
public record EnvelopeOptions(
        String  correlationId,
        String  replyToElf,
        String  provenance,
        String  inReplyTo,
        Integer maxAttempts
) {
    public static EnvelopeOptions none() {
        return new EnvelopeOptions(null, null, null, null, null);
    }
}
