# Plugin backend

Install the base APK once. Behavior changes (adapters, selectors, health
rules, submit strategies) arrive as signed data files pushed from Termux.
No APK reinstall for behavior work.

## Explicit tradeoff

Core changes (API routes, permissions, control plane, manifest) still need
a base APK reinstall — rare by design, lossless via the persistent debug key.

Signing-key compromise also requires a new base APK carrying a new baked
root key. This is accepted deliberately: availability (platform RSA on
every API 26+ device, no bundled crypto) over theoretical rotation without
reinstall. The manifest carries a `keyId` field so a future hierarchy can
be introduced without changing the bundle shape.

## Bundle format (JSON only — never code)

`<id>-<version>.zip` containing exactly, at zip root:
- `manifest.json` — `{format:"tb-plugin/1", id, version:int>=1,
  minCore, keyId, hosts:[1..5], files:{name: sha256hex}}`
- `selectors.json` — `{prompt, submit, messages, stop}` (prompt+submit
  required, ≤30 total, ≤200 chars each)
- `health.json` — `{marker: selector|null, strategies: subset of
  click/enter}`
- `plan.json` — reserved object, unknown fields rejected
- `signature.sig` — raw RSA/SHA-256 over the exact manifest bytes

Unknown fields anywhere are rejected. Files over 64 KB, bundles over
512 KB, or any extra/absolute/traversal zip entry are rejected.

## Termux workflow

```
browserctl plugin pack ./deepseek-v3   # canonical manifest + sign
browserctl plugin push ./deepseek-v3.zip
browserctl plugin list
browserctl plugin rollback deepseek
```

The private key lives only in `~/.config/termux-browser/keys/`.
The public key is baked at `app/src/main/res/raw/plugin_root_pub.txt`.
