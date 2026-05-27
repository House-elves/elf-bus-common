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

If duplicate-creates become a real problem, the fix is producer-side:
include an `X-Elf-Idempotency-Key` header, have github-worker store it
in a `KEY → issue-url` map, and short-circuit duplicates. Not v1.

## Candidate kinds (not yet implemented)

These have been mentioned in design discussions but are not part of v1.
Spec them here before implementing.

| Kind | Producer | Consumer | Notes |
|---|---|---|---|
| `github.pr.review-request` | mail-worker, others | github-worker | Ask the reviewer flow to look at a PR out-of-band. Overlaps with the existing label-driven flow. |
| `mail.reply.send` | any | mail-worker | Let other elves send mail via the principal's mailbox. Requires careful auth thinking — every elf can speak as the principal? |
| `calendar.event.create` | mail-worker | calendar-worker | Implemented locally in mail-worker for v1; split out only if a second producer appears. |
| `slack.message.send` | any | slack-worker | If we ever build a slack elf. |

## Versioning

Schema changes within a major version MUST be backwards-compatible
additions (new optional fields). Breaking changes mint a new kind with
a `.v2` suffix or a new resource name, never a silent payload change
under the same kind.

`X-Elf-Bus-Version` is bus-level (envelope semantics), not kind-level
(payload schemas). Bus v1 + kind-payload-v2 is a valid combination.
