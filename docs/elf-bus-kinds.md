# elf-bus v1 kinds

Catalog of every `X-Elf-Kind` defined for elf-bus v1.

Companion to `docs/elf-bus.md`, which specifies the transport. This doc
specifies the **payloads** — what producers send, what consumers must
accept, and what results (if any) flow back.

This is the operational contract between elves. Adding a new kind:

1. Add an entry in this doc.
2. Implement a handler in the consuming elf (`ElfBusHandler.kind() ==`
   the kind string).
3. Document the result kind if callers want a callback, and implement
   the result handler in the calling elf.

## Conventions

- Kinds are `<scope>.<resource>.<action>`, lowercase, dotted.
- Result kinds append `.result` to the request kind.
- Schemas use prose tables; the bus does not validate, the handler does
  (per `docs/elf-bus.md` §6).
- **Required** payload fields are bold.
- Producers MAY omit `X-Elf-Reply-To` if they don't care about the result.
  A consumer MUST NOT enqueue a result when `X-Elf-Reply-To` is absent.

## v1 catalog

### github.issue.create

| | |
|---|---|
| Producer  | mail-worker (others permitted) |
| Consumer  | github-worker |
| Result    | `github.issue.create.result` |
| Retries   | Yes — 429s, 5xx, transient network. Auth (401/403), validation (422), not-found (404) are hard failures. |

Payload:

| Field | Type | Required | Description |
|---|---|---|---|
| **`repo`** | string | yes | `<owner>/<repo>` |
| **`title`** | string | yes | issue title |
| **`body`** | string | yes | issue body markdown |
| `labels` | string[] | no | applied at creation; default `[]` |
| `assignees` | string[] | no | github usernames; default `[]` |

Result payload (kind `github.issue.create.result`):

| Field | Type | Description |
|---|---|---|
| **`status`** | `"ok"` \| `"error"` | overall outcome |
| `issue.repo` | string | echo of input |
| `issue.number` | integer | issue number assigned by GitHub |
| `issue.url` | string | `html_url` |
| `error` | string | present iff `status == "error"` |

### github.issue.comment

| | |
|---|---|
| Producer  | mail-worker (others permitted) |
| Consumer  | github-worker |
| Result    | `github.issue.comment.result` (defined, but mail-worker does not subscribe in v1) |
| Retries   | Same as `github.issue.create` |

Payload:

| Field | Type | Required | Description |
|---|---|---|---|
| **`repo`** | string | yes | `<owner>/<repo>` |
| **`num`** | integer | yes | issue or PR number |
| **`body`** | string | yes | comment markdown |

Result payload (`github.issue.comment.result`):

| Field | Type | Description |
|---|---|---|
| **`status`** | `"ok"` \| `"error"` | |
| `comment.id` | integer | GitHub comment ID |
| `comment.url` | string | `html_url` |
| `error` | string | present iff `status == "error"` |

### github.issue.label

| | |
|---|---|
| Producer  | mail-worker (others permitted) |
| Consumer  | github-worker |
| Result    | `github.issue.label.result` (defined, mail-worker does not subscribe in v1) |
| Retries   | Same as `github.issue.create` |

Payload:

| Field | Type | Required | Description |
|---|---|---|---|
| **`repo`** | string | yes | `<owner>/<repo>` |
| **`num`** | integer | yes | issue or PR number |
| **`label`** | string | yes | label name (must already exist on the repo) |

Result payload (`github.issue.label.result`):

| Field | Type | Description |
|---|---|---|
| **`status`** | `"ok"` \| `"error"` | |
| `error` | string | present iff `status == "error"` |

### mail.send

| | |
|---|---|
| Producer  | any elf |
| Consumer  | mail-worker |
| Result    | `mail.send.result` |
| Retries   | Yes — SMTP 4xx, connection reset, transient DNS. Auth (5xx), recipient-not-allow-listed (validation), malformed addresses are hard failures. |

Send an outbound email on behalf of the producer elf. mail-worker is the sole holder of SMTP credentials in the House Elves system; this kind exists so other elves can reach the principal (or other pre-approved addresses) without each holding their own SMTP setup.

Payload:

| Field | Type | Required | Description |
|---|---|---|---|
| **`to`** | string | yes | Recipient address. MUST be present in mail-worker's `ALLOWED_RECIPIENTS` config — otherwise hard-failure. |
| **`subject`** | string | yes | Subject line. |
| **`body`** | string | yes | Plain-text body (UTF-8). |
| `in_reply_to` | string | no | Message-Id of an existing thread to reply into. Sets the `In-Reply-To` and `References` SMTP headers. |

Result payload (`mail.send.result`):

| Field | Type | Description |
|---|---|---|
| **`status`** | `"ok"` \| `"error"` | |
| `message_id` | string | SMTP Message-Id assigned to the sent mail; present on `status == "ok"`. Producers can use this as `in_reply_to` for follow-ups. |
| `error` | string | present iff `status == "error"` |

### session.run

| | |
|---|---|
| Producer  | mail-worker |
| Consumer  | session-worker |
| Result    | `session.run.result` |
| Retries   | No - a session run is not idempotent (it may have edited files or run commands before failing). Failures dead-letter and surface to the principal as an error reply. |

Run (or continue) an interactive Claude Code session on the host, driven by an email from the principal. This is REMOTE CODE EXECUTION BY DESIGN: mail-worker MUST only produce this kind for senders on its `SESSION_SENDERS` allow-list whose messages carry a passing DKIM authentication result. session-worker never sees the mailbox; mail-worker never runs a shell.

Payload:

| Field | Type | Required | Description |
|---|---|---|---|
| **`thread_key`** | string | yes | Stable key for the mail thread (the root Message-Id from `References`, else the message's own Message-Id). session-worker maps this to a Claude session id so replies continue the same session. |
| **`subject`** | string | yes | The email subject. |
| **`body`** | string | yes | The email's plain-text body: the instruction for the session. |
| **`from`** | string | yes | The (DKIM-verified) principal address that sent the mail. |
| `attachments` | string[] | no | Absolute paths of attachment files mail-worker spooled to local disk. |

Result payload (`session.run.result`):

| Field | Type | Description |
|---|---|---|
| **`status`** | `"ok"` \| `"error"` | |
| `reply_body` | string | The session's final answer, sent back to the principal as a threaded reply; present on `status == "ok"`. |
| `session_id` | string | The Claude session id (also persisted against `thread_key` by session-worker). |
| `error` | string | present iff `status == "error"` |

#### Note on the trust boundary

The pair of gates lives in mail-worker (sender allow-list + DKIM pass), enforced in code before anything is enqueued. session-worker additionally refuses envelopes whose `from` payload field is not in its own `SESSION_SENDERS` copy - defence in depth for a bus anyone on the host can write to.

#### Note on recipient ACLs

mail-worker enforces an **`ALLOWED_RECIPIENTS`** allow-list before sending. This is the symmetric protection to inbound `ALLOWED_SENDERS`:

- Without it, any producer (or any bug in a producer) can fire arbitrary mail. Spam vector.
- With it, producers can only reach addresses the operator has pre-approved — typically just the principal.
- Forbidden recipients dead-letter immediately (hard failure, not retryable).

Operators expand the allow-list deliberately; producers can't grow it.

Operators may *also* want a rate limit (max N outbound per hour) as a circuit breaker against runaway loops. Not part of the kind contract; it's a mail-worker-side defence and can be added without changing this spec.

#### Note on channel-level ACLs

mail-worker enforces a label allow-list on its own side (`approval:granted`
only). This is a *channel* restriction, not a github-side one — anyone
calling the GitHub API as `the-bin-chicken` could apply any label.

A future second producer of `github.issue.label` (e.g. a Slack-driven elf)
would need to make its own channel-level decision, OR github-worker would
grow per-producer ACLs via an `X-Elf-From`-based registry. v1 leaves this
to the producer because there is only one.

## Failure-mode conventions

A consumer SHOULD distinguish:

- **Retryable failure** — throws `RetryableException`. The bus bumps
  `X-Elf-Attempt` and re-queues until `X-Elf-Max-Attempts`. Typical:
  rate-limit, 5xx, connection reset, DNS hiccup.
- **Hard failure** — any other exception. The bus dead-letters with the
  exception message in `X-Elf-Dead-Reason`. Typical: 401/403/404/422,
  parse error, missing required field.

For request kinds with results, a consumer MAY *also* emit a result with
`status: "error"` for hard failures rather than dead-lettering, if the
producer benefits from getting notified. v1 elves dead-letter for all
hard failures; they do not emit error results. Revisit if a producer
needs structured failure reporting.

## Idempotency

All v1 kinds are idempotent at the bus layer (consumer dedupes on
`Message-Id`), but their *effects* on GitHub are not:

- `github.issue.create` — retrying after a successful create produces
  a duplicate issue. Consumer crash mid-write is the failure window.
  Acceptable for v1 because the window is small and duplicates are
  visible to the principal.
- `github.issue.comment` — same window, same accepted risk.
- `github.issue.label` — naturally idempotent (adding the same label
  twice is a no-op on GitHub's side).
- `mail.send` — retrying after a successful send produces a duplicate
  email. Same window as the github kinds; duplicates are user-visible
  in the recipient's inbox.

If duplicate-creates become a real problem, the fix is producer-side:
include an `X-Elf-Idempotency-Key` header, have github-worker store it
in a `KEY → issue-url` map, and short-circuit duplicates. Not v1.

## Candidate kinds (not yet implemented)

These have been mentioned in design discussions but are not part of v1.
Spec them here before implementing.

| Kind | Producer | Consumer | Notes |
|---|---|---|---|
| `github.pr.review-request` | mail-worker, others | github-worker | Ask the reviewer flow to look at a PR out-of-band. Overlaps with the existing label-driven flow. |
| `calendar.event.create` | mail-worker | calendar-worker | Implemented locally in mail-worker for v1; split out only if a second producer appears. |
| `slack.message.send` | any | slack-worker | If we ever build a slack elf. |

## Versioning

Schema changes within a major version MUST be backwards-compatible
additions (new optional fields). Breaking changes mint a new kind with
a `.v2` suffix or a new resource name, never a silent payload change
under the same kind.

`X-Elf-Bus-Version` is bus-level (envelope semantics), not kind-level
(payload schemas). Bus v1 + kind-payload-v2 is a valid combination.
