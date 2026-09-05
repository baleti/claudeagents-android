# Design notes

## Reading: transcript files, not terminal scraping

The obvious way to pull a Claude Code session's conversation into another
app is to attach to its `tmux` pane and parse the rendered text. That's
fragile — Claude Code's TUI reflows on resize, draws progress spinners and
partial lines, and generally isn't meant to be machine-read. Instead, the
daemon reads the same JSONL transcript files Claude Code itself writes to
`~/.claude*/projects/<cwd-encoded>/<session-id>.jsonl` — structured,
line-oriented, and already the source of truth the CLI's own `--resume`
picker uses. `parse_transcript_line()` extracts a tool-aware summary from
each entry (a `Bash` call renders as a fenced code block with the real
command, not an escaped one-liner; an `Edit` shows the file path; a
`tool_result` image block shows as `[image]` rather than silently
vanishing) so what reaches the phone reads like the CLI's own output, not
a raw JSON dump.

## Writing: `tmux send-keys`, because that part genuinely needs the terminal

Sending a new message does attach to the pane, via
`tmux send-keys -l -- <text>` followed by an `Enter` keypress. This is the
one place scraping-the-terminal's approach is actually correct, because
typing into a running interactive program is what `tmux send-keys` is for.
If a session doesn't currently have a live pane (closed terminal, not yet
started), the message is written to a per-session queue file and
delivered the next time that session is discovered live — nothing is
silently dropped.

## Discovering *which* pane belongs to which session

A session invoked with `--session-id <uuid>` or `--resume <uuid>` is easy
to match: `scan_tmux_panes()` walks every pane's process tree looking for
a `claude` process, and `/proc/<pid>/cmdline` gives the UUID directly
("exact" confidence). A bare `claude` invocation with no explicit ID has
no such marker, so the daemon falls back to matching by working directory:
if exactly one live pane's `cwd` matches a project directory with no other
candidate sharing it, that's treated as the pane for that session
("cwd-heuristic" confidence). With multiple sibling panes sharing a `cwd`,
there is no way to tell them apart from the outside, and the daemon
deliberately does not guess — an ambiguous match risks routing a reply
into the wrong conversation, which is worse than not routing it at all.

## Spawning a new session with a known ID up front

Starting a session via `tmux new-session ... claude --session-id <uuid>`
(rather than a bare `claude`) sidesteps the heuristic path entirely — the
UUID is known before the process even starts, so the very next `/stream`
call is guaranteed to find the transcript once it exists, rather than
waiting for the next scan tick to notice a heuristic match. The spawn
endpoint blocks briefly until the transcript file actually appears on
disk, so a caller's first read never races a session still booting.

## Offline-tolerant sync (the Android side)

The app keeps a local SQLite cache (`Db.kt`) and treats the daemon as
"reachable" based on one signal only: whether the conversation *list*
request succeeds. A single conversation's message fetch returning 404
(the session was renamed, compacted, or removed between the list scan and
this fetch — normal churn, not a connectivity problem) must not flip the
whole sync to "offline"; earlier versions conflated the two and a couple
of stale, no-longer-relevant conversation ids intermittently pinned the
"offline" banner even though every other request was succeeding.

Outgoing messages go through a small outbox table rather than a direct
network call from the compose box: `OutboxJobService` drains pending rows
periodically via `JobScheduler`, marking each `handed_off` as soon as the
daemon accepts it — whether delivered live (`tmux send-keys` succeeded) or
queued server-side for a pane that isn't there yet. From the app's
perspective the message has left the device either way; delivery
confirmation isn't visible to distinguish those two states further, and
doesn't need to be. On failure the row stays pending and the next
scheduled attempt retries it, so a message typed while offline is never
silently lost.

## Token storage

The pairing token is encrypted at rest with an AndroidKeyStore-backed
AES-GCM key (plain `javax.crypto`/`android.security.keystore` platform
APIs — not `androidx.security-crypto`, which needs a dependency resolver
this Gradle-less build doesn't have). Host and port aren't secret and are
stored in plain `SharedPreferences` alongside the pairing flag. See
`TokenStore.kt`.
