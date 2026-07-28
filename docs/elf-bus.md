# elf-bus — inter-elf message bus

**Status:** draft v1. Single-host, filesystem-backed. No MTA, no
network, no auth beyond Unix file permissions.

**Goal:** let House Elves hand work to each other without sharing
credentials, knowing each other's PIDs, or being up at the same time.

The transport is **Maildir**. Each elf has an inbox. Producers drop
messages in. Consumers process them on their existing schedule. Replies
are just messages back. The whole bus is browsable with `mutt` or `mu`.

This document is the contract between elves. An elf is conformant if it
implements §3, §4, §5, §6 and §7.

## 1. Topology

One Maildir per elf, located by convention at:

```
$XDG_DATA_HOME/elf-bus/<elf-name>/Maildir/
```

(`$XDG_DATA_HOME` defaults to `$HOME/.local/share`.)

Per-elf layout:

```
~/.local/share/elf-bus/<elf-name>/Maildir/
├── tmp/                # producer staging — never read by consumers
├── new/                # pending delivery
├── cur/                # claimed / in-flight
└── .dead/
    ├── tmp/
    ├── new/            # exceeded retries OR rejected; awaits operator
    └── cur/
```

`.dead/` is a regular Maildir under a hidden subdirectory (the
Maildir++ convention). It is browsed by the same tools and can be
emptied or re-queued by `mv`.

Elf names are short kebab-case (`github-worker`, `mail-worker`,
`calendar-worker`). The name appears in `From:` / `To:` headers
(§4) and in the inbox path; the two must agree.

## 2. Bootstrap

On first run, an elf creates its own inbox:

```bash
mkdir -p ~/.local/share/elf-bus/<self>/Maildir/{tmp,new,cur}
mkdir -p ~/.local/share/elf-bus/<self>/Maildir/.dead/{tmp,new,cur}
```

A producer wishing to send to `<peer>` MAY create `<peer>`'s inbox if
absent — this lets producers come up before consumers. The directories
are cheap and harmless if the peer never starts.

## 3. Atomic delivery

Producers MUST use the Maildir write protocol:

1. Compute a unique filename `N` (§3.1).
2. Open `tmp/N` write-exclusive; write the full RFC 5322 message; `fsync`; close.
3. `rename("tmp/N", "new/N")`. POSIX guarantees this is atomic on a
   single filesystem.
4. (Optional) `fsync` the `new/` directory for durability.

Consumers MUST claim a message by atomic rename before processing:

```
rename("new/N", "cur/N:2,S")
```

If the rename fails with `ENOENT`, another consumer won
the race — skip and move on. The `S` flag (§5) marks the
message as claimed.

### 3.1 Filename format

```
<unix-time>.<unique>.<host>
```

- `<unix-time>` — integer seconds since epoch at write time.
- `<unique>` — UUID v7 (preferred; sortable and collision-safe) or
  the Maildir-traditional `<pid>_<seq>` form.
- `<host>` — hostname, or the literal `elf-bus` if hostname is
  unstable.

Example:

```
1748256202.0190f4c8b35f7d4e9a8b1234567890ab.elf-bus
```

Listing `new/` and sorting lexicographically yields approximate FIFO
order. Strict ordering is not guaranteed — see §10.

## 4. Envelope

Messages are RFC 5322. **Required** headers:

| Header | Value |
|---|---|
| `Message-Id` | `<uuid@elf-bus.local>` — globally unique; basis for idempotency (§7). |
| `Date` | RFC 5322 date at enqueue time. |
| `From` | `<producer-elf>@elf-bus.local`. |
| `To` | `<consumer-elf>@elf-bus.local`. |
| `X-Elf-Bus-Version` | `1`. |
| `X-Elf-Kind` | Namespaced kind, lowercase, dotted (§6). |
| `Content-Type` | `application/json; charset=utf-8` (v1). |

**Optional** headers:

| Header | Value |
|---|---|
| `In-Reply-To` | `<message-id>` of the message this is a reply to. |
| `References` | Space-separated chain of message-ids, newest last. |
| `X-Elf-Reply-To` | Elf name to enqueue a callback into. Producer wants a result message. |
| `X-Elf-Correlation-Id` | Opaque tracking id propagated across hops. |
| `X-Elf-Attempt` | Integer ≥ 1. Defaults to 1 if absent. |
| `X-Elf-Max-Attempts` | Integer ≥ 1. Defaults to 5 if absent. |
| `X-Elf-Provenance` | Short free-text for "where did this originate" — surfaced in dashboards. |
| `X-Elf-Dead-Reason` | Set by consumers when moving to `.dead/`. |

The body is a UTF-8 JSON object whose schema is determined by
`X-Elf-Kind`. Schemas are owned by the consumer of that kind (§6).

## 5. Maildir flags

elf-bus uses two standard Maildir flags:

| Flag | Meaning in elf-bus |
|---|---|
| `S` | **Seen** — consumer has claimed the message. |
| `R` | **Replied** — consumer has written a callback message. |

All other flags are reserved.

The flag block is appended to the filename as `:2,<FLAGS>`. Flags
are uppercase ASCII, sorted alphabetically. Example:

```
cur/1748256202.<uuid>.elf-bus:2,RS
```

## 6. Kind namespace

`X-Elf-Kind` is `<scope>.<resource>.<action>`. Examples:

- `github.issue.create`
- `github.issue.comment`
- `github.issue.label`
- `github.pr.review-request`
- `mail.reply.send`
- `calendar.event.create`

Result kinds append `.result`:

- `github.issue.create.result`

Each elf publishes the list of kinds it consumes and the JSON schema
for each, in its own README. The bus does **not** validate schemas —
that's the consumer's job, and parse failure is grounds for dead-lettering
(§9).

## 7. Consumer protocol

On each tick (cron, timer, etc.) a consumer SHOULD:

```
1. acquire single-instance lock (per-elf flock)
2. for entry E in sorted(readdir("new/")):
   2a. try rename("new/E", "cur/E:2,S")  — on ENOENT/EEXIST, skip
   2b. read + parse RFC 5322 message
   2c. read X-Elf-Bus-Version. If unknown → dead-letter (§9)
   2d. read X-Elf-Kind. If not a kind we serve → dead-letter
   2e. dedupe on Message-Id (§7.1). If seen → unlink, continue
   2f. parse body JSON against kind schema. On parse error → dead-letter
   2g. execute the action
   2h. on success:
       - if X-Elf-Reply-To is set, write a callback (§8) into peer's new/
       - unlink the message from cur/  (success = gone from bus)
   2i. on retryable failure:
       - increment X-Elf-Attempt
       - if attempt > max-attempts → dead-letter
       - else rewrite the file with bumped attempt + rename cur/E:2,S → new/E
   2j. on hard failure → dead-letter (§9)
3. release lock
```

### 7.1 Idempotency

A consumer MUST ensure that the same `Message-Id` is processed
at-most-once. Implementations:

- Persist seen ids in a flat file or SQLite under
  `$XDG_STATE_HOME/elf-bus/<self>/processed-ids`.
- Or: rely on the fact that successful processing deletes the file
  from `cur/`, and treat "file still present" as "not yet processed".
  This is sufficient if the consumer never produces side effects
  before the unlink — most won't manage that, hence option 1 is
  recommended.

## 8. Replies

A reply is a normal message with:

- `In-Reply-To: <original Message-Id>`
- `References: <original Message-Id>` (plus any earlier ids if
  multi-hop)
- `X-Elf-Correlation-Id` propagated unchanged from the original
- `X-Elf-Kind` ending in `.result` (convention)
- `From: <consumer>@elf-bus.local`, `To: <original X-Elf-Reply-To>@elf-bus.local`

After writing the reply into the peer's `new/`, the consumer SHOULD
set the `R` flag on the original (i.e. rename `cur/E:2,S` to
`cur/E:2,RS`) **before** the final unlink. The flag is a debugging
breadcrumb — it tells an operator browsing the queue that a reply went
out. The unlink that follows removes the breadcrumb too; logs are
the durable record.

Replies are not themselves retried by the bus. A reply that the
peer fails to process becomes a dead-letter on the peer's side.

## 9. Dead-letter

A message is moved to `.dead/new/<original-filename>` (with the
existing flag suffix preserved) and an `X-Elf-Dead-Reason` header
is added describing why. Examples:

- `X-Elf-Dead-Reason: unsupported bus version: 2`
- `X-Elf-Dead-Reason: unknown kind: github.issue.frobnicate`
- `X-Elf-Dead-Reason: parse error: unexpected token at line 3`
- `X-Elf-Dead-Reason: exhausted retries (5/5)`

Operators handle dead-letters out-of-band:

```bash
# Inspect:
mu view ~/.local/share/elf-bus/github-worker/Maildir/.dead/new/*

# Re-queue:
mv ~/.local/share/elf-bus/github-worker/Maildir/.dead/new/<N> \
   ~/.local/share/elf-bus/github-worker/Maildir/new/<N>

# Discard:
rm ~/.local/share/elf-bus/github-worker/Maildir/.dead/new/<N>
```

Dashboards (each elf's UI) SHOULD surface dead-letter counts and
contents.

## 10. Ordering and concurrency

- **Within one consumer:** processing order is the lexicographic
  order of filenames in `new/`, which is approximately enqueue order.
  Not strict — adjacent-timestamp messages from different producers
  may interleave.
- **Multiple consumers per elf:** allowed. The atomic claim-rename
  (§3) ensures each message is delivered to at-most-one consumer.
  Single-instance is still recommended for v1 to keep reasoning
  simple.
- **Multiple producers:** safe. The tmp→new rename guarantees readers
  never see partial messages.
- **No total order guarantee** across the bus.

## 11. Garbage collection

- Files in `tmp/` older than 36 hours MAY be deleted — they are
  crashed producer attempts (this matches the original Maildir spec).
- Files in `cur/` older than some elf-defined limit (default 24h)
  with the `S` flag set but no `R` flag indicate a crashed consumer
  mid-processing. The elf MAY rename them back to `new/` to retry,
  bumping `X-Elf-Attempt`.
- `.dead/new/` is never garbage-collected automatically. Operator
  action only.

## 12. Versioning

`X-Elf-Bus-Version: 1` is this spec. Future versions will be
backwards-incompatible only at major bumps. A consumer encountering
an unsupported version dead-letters (§9).

## 13. Non-goals (v1)

- **Cross-host delivery.** Maildirs are local. Cross-host arrives by
  adding a real MTA (postfix) that delivers into the same Maildir
  paths — elves don't change.
- **Authentication / signing.** All elves trust each other because
  they share a Unix UID. v2 may add detached signatures if cross-host
  or untrusted producers appear.
- **Encryption at rest.** Disk-level encryption is the operator's
  problem.
- **Fanout / pub-sub.** One message → one consumer. To fanout,
  enqueue N copies.
- **Strict ordering.** §10.
- **Schema validation at the bus.** §6.

## 14. Example: mail-worker files an issue via github-worker

Phillip emails `bin-chicken@greatsouthernsoftware.com.au` asking for a new issue.
mail-worker triages and enqueues:

**File:** `~/.local/share/elf-bus/github-worker/Maildir/new/1748256202.<uuid>.elf-bus`

```
Message-Id: <abc-123@elf-bus.local>
Date: Mon, 26 May 2026 11:43:22 +0000
From: mail-worker@elf-bus.local
To: github-worker@elf-bus.local
X-Elf-Bus-Version: 1
X-Elf-Kind: github.issue.create
X-Elf-Reply-To: mail-worker
X-Elf-Correlation-Id: <CAGv...@mail.gmail.com>
X-Elf-Provenance: email; principal=phillip.kruger@bin-space.app
Content-Type: application/json; charset=utf-8

{
  "repo": "Great-Southern-Software/bin-space-specifications",
  "title": "Reviewer should flag leftover TODO comments",
  "body": "Filed on behalf of phillip.kruger via email.\n\nUpdate the reviewer agent so it flags new TODO comments in the diff as blocking concerns.",
  "labels": ["origin:mail", "state:new"],
  "assignees": ["the-bin-chicken"]
}
```

github-worker, on its next tick, claims the message, calls the
GitHub API as `the-bin-chicken`, gets back issue #42, then enqueues
a reply:

**File:** `~/.local/share/elf-bus/mail-worker/Maildir/new/1748256260.<uuid>.elf-bus`

```
Message-Id: <def-456@elf-bus.local>
Date: Mon, 26 May 2026 11:44:20 +0000
From: github-worker@elf-bus.local
To: mail-worker@elf-bus.local
In-Reply-To: <abc-123@elf-bus.local>
References: <abc-123@elf-bus.local>
X-Elf-Bus-Version: 1
X-Elf-Kind: github.issue.create.result
X-Elf-Correlation-Id: <CAGv...@mail.gmail.com>
Content-Type: application/json; charset=utf-8

{
  "status": "ok",
  "issue": {
    "repo": "Great-Southern-Software/bin-space-specifications",
    "number": 42,
    "url": "https://github.com/Great-Southern-Software/bin-space-specifications/issues/42"
  }
}
```

mail-worker, on its next tick, claims the result, sends an SMTP reply
to Phillip with the issue URL, and deletes both messages from its
respective `cur/`.

Each elf stayed in its own auth domain throughout: mail-worker never
touched a GitHub PAT; github-worker never touched SMTP credentials.

## 15. Reference implementation hints

- **Java:** Jakarta Mail (`jakarta.mail.internet.MimeMessage`) for
  parsing/serialising RFC 5322. The Maildir delivery dance is
  ~30 lines: temp-file → fsync → atomic rename.
- **Shell:** `formail` + `mv` is enough for one-off producers. A
  pure-bash producer is ~15 lines.
- **Python:** stdlib `email` + `mailbox.Maildir` covers it; `Maildir`
  even does the atomic dance for you.

Each implementation MUST verify the rename is atomic on the target
filesystem (it is on ext4, xfs, btrfs, zfs, tmpfs; it is NOT on most
NFS mounts — don't put the bus on NFS).
