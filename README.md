# elf-bus-common

The shared transport library for [House Elves](https://github.com/House-elves) — a single-host, filesystem-backed message bus that lets cooperating elves hand work to each other without sharing credentials.

The bus is **Maildir-shaped**: every elf has an inbox under `~/.local/share/elf-bus/<elf-name>/Maildir/`, producers atomically write RFC 5322 envelopes into peers' `new/` directories, and consumers claim them via atomic rename. Replies come back the same way.

- **Transport spec:** [`docs/elf-bus.md`](docs/elf-bus.md)
- **v1 kind catalog:** [`docs/elf-bus-kinds.md`](docs/elf-bus-kinds.md)

## Using it from a JBang elf

Add these lines to the top of your elf's main script:

```
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS jakarta.mail:jakarta.mail-api:2.1.3
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/EnvelopeOptions.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusEnvelope.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusHandler.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusProducer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusInboxes.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusSeenIds.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/FileSystemBus.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusConsumer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/InMemoryBus.java
```

Pin to a version tag. `main` is unstable.

## Sending a message (producer)

```java
var bus = new FileSystemBus(busRoot, "my-elf");
bus.enqueue(
    "github-worker",
    "github.issue.create",
    Map.of("repo", "owner/repo", "title", "...", "body", "..."),
    new EnvelopeOptions(correlationId, /*replyToElf*/ "my-elf", "channel=email", null, null)
);
```

## Receiving messages (consumer)

```java
var consumer = new ElfBusConsumer(
    busRoot,
    "my-elf",
    seenIdsPath,
    List.of(myHandlers),
    /*replyProducer*/ bus      // null if this elf never emits results
);
consumer.poll();    // call on each cron tick
```

A handler implements `ElfBusHandler`, declares the `X-Elf-Kind` it serves, and returns a reply payload (or null for no reply).

## Testing

`InMemoryBus implements ElfBusProducer` records every `enqueue()` call without touching the filesystem:

```java
var bus = new InMemoryBus();
myAction.execute(bus);
assertEquals(1, bus.sentTo("github-worker").size());
assertEquals("github.issue.create", bus.sentOfKind("github.issue.create").get(0).kind());
```

For consumer-side tests, write fixture messages directly into a temp Maildir and call `consumer.poll()` — no special fake needed.

## Adding a new kind

1. Add a row in [`docs/elf-bus-kinds.md`](docs/elf-bus-kinds.md) with the payload schema.
2. Implement a handler in the consuming elf.
3. If callers want callbacks, document the `.result` kind alongside.

The bus library itself never needs to change — kinds are owned by the consuming elf.

## Non-goals (v1)

- Cross-host delivery (single-host only; add an MTA to lift this)
- Authentication / signing (all elves trust the shared UID)
- Pub/sub fanout (one message → one consumer)
- Schema validation at the bus layer (handlers validate their own payloads)

## License

Apache 2.0 — see [`LICENSE`](LICENSE).
