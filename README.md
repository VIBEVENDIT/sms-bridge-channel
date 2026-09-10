# SMS bridge public update channel

Anonymous feed for the Hotel at Gangnam front POS.

## Live URLs (HTTP 200)

- Manifest: https://raw.githubusercontent.com/VIBEVENDIT/sms-bridge-channel/main/manifest.json
- CDN: https://cdn.jsdelivr.net/gh/VIBEVENDIT/sms-bridge-channel@main/manifest.json
- Commands: https://raw.githubusercontent.com/VIBEVENDIT/sms-bridge-channel/main/commands.json

`hotelat.com/sms-bridge/` and `hotel-at-gangnam-maknae.vercel.app` 404 until an org admin deploys those hosts. Do not put secrets here.

## Publish a zip

1. Download `sms-bridge-install.zip` from private repo Actions `sms-bridge-windows-release` (no secrets).
2. Create Release `vX.Y.Z` on this repo and attach that zip.
3. Bump `manifest.json` `version` + `zipUrl`.
