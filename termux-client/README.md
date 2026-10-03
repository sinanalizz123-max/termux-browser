# browserctl (M2)

Termux CLI for the browser control API. Implemented in M2 once the Ktor
control plane exists. Planned commands:

- `browserctl status`
- `browserctl open <url>`
- `browserctl search <query>`
- `browserctl read --scope links`
- `browserctl log --last 20`
- `browserctl watch`
- `browserctl stop` / `browserctl resume`

All output JSON by default. Token from local config, never CLI args.
