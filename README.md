# Termux Browser

Simple Android WebView browser controlled from Termux on the same phone.

- Visible shared session: the user watches every Termux search, page read,
  and AI chat in the same foreground WebView.
- Localhost control API with token auth (M2). Manual browsing shell (M1).
- Manual login only. Termux never handles passwords, cookies, or CAPTCHAs.

## Milestones (ChatGPT-approved order)

- M0: threat model + protocol (`docs/protocol.md`).
- M1: visible browser shell + manual browsing.
- M2: local control plane (Ktor CIO, auth, command IDs, queue, status).
- M3: state machine + takeover + generation IDs + cancellation.
- M4: generic extraction. M5: event stream. M6: notifications/lifecycle.
- M7+: AI adapter framework, then providers one at a time.

Heavy builds run on GitHub Actions. Termux does editing, git, polling,
downloads, and CLI testing.
