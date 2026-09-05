# Claude Agents

A mobile front-end for [Claude Code](https://claude.com/claude-code)
sessions running on a remote machine: read the conversation, send new
messages, and get near-real-time updates, all from your phone — without
Claude Code itself needing any network-facing mode of its own.

> **Assumption:** you already have a private tunnel (WireGuard or
> equivalent) between your phone and the machine running your Claude Code
> sessions. This project does not set one up for you, and the daemon
> refuses to start without an explicit bind address and allowed subnet —
> see [Setup](#setup). Don't expose the daemon's port on an untrusted
> network; its token check is a backstop against what the tunnel
> structurally can't cover (browser-originated requests), not a substitute
> for the tunnel itself.

Two halves, one repo:

- **`server/`** — a small Python daemon (`claude-agents-daemon.py`, stdlib
  only) that runs on the same machine as your Claude Code sessions.
- **App** (`AndroidManifest.xml`, `src/`) — the Android client. No Gradle,
  no Play Services, no third-party dependencies; built with the plain
  Android SDK command-line tools.

## Why

Claude Code sessions in this setup run inside `tmux`, driven from a
terminal. Reading their output normally means SSHing in and scrolling a
pane — awkward from a phone, and actually *reading* a long agent
transcript in a terminal emulator is worse. The daemon instead reads
Claude Code's own JSONL transcript files directly (the same files the CLI
itself writes), which is far more reliable than trying to scrape rendered
terminal output, and exposes them as a small JSON API. Sending a new
message uses `tmux send-keys` into the session's pane — the one thing that
does need the terminal, because that's genuinely how you type into a
running interactive session. Full design rationale, including the
security model, is in [docs/design.md](docs/design.md).

## Setup

### Server

```sh
CLAUDE_AGENTS_BIND_IP=<your tunnel address> \
CLAUDE_AGENTS_ALLOWED_SUBNET=<your tunnel subnet, e.g. 10.0.0.0/24> \
python3 server/claude-agents-daemon.py
```

It generates and prints a pairing token on first run (also saved to
`~/.config/claude-agents/token`, mode 600). A systemd user-service template
is at [server/claude-agents.service.example](server/claude-agents.service.example).

### App

```sh
bash build.sh                              # produces build/claudeagents-signed.apk
adb install -r build/claudeagents-signed.apk
```

On first launch, paste the pairing token, host, and port the daemon
printed. Nothing is hardcoded — see [docs/design.md](docs/design.md) for
how the token is stored (AndroidKeyStore-backed AES-GCM, not plaintext).

## Requirements

- Claude Code running on the server machine, in `tmux` sessions (any
  session started with `claude`, with or without `--session-id`).
- Python 3.9+ on the server (stdlib only — no dependencies to install).
- Android 10+ (`minSdkVersion 29`) on the client.
- To build the app: `aapt2`, `kotlinc`, `d8`, `apksigner`, and an
  `android.jar` for a recent platform (see `build.sh` for the exact paths
  it expects — override via environment variables for your own layout).

## What it doesn't do

- Start or manage Claude Code sessions for you — it drives sessions you
  already started yourself.
- Work without `tmux` — the send path depends on it.
- Provide any web-facing control plane — everything is scoped to your own
  tunnel; see the security model in [docs/design.md](docs/design.md).

## License

MIT — see [LICENSE](LICENSE).
