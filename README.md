# SMS bridge public channel

Public docs + update feed for the Hotel at Gangnam SMS bridge. Do not put secrets here.

## Current: phone bridge (v2)

The hotel Android phone runs SMS Gateway for Android (sms-gate.app) and talks to the Vercel ops app. Inbound SMS, staff replies, and guest auto-replies all show up in Slack `#lounge-호텔앳강남` as 「앳강남 막내」.

- Install on the hotel phone: [docs/phone-bridge/INSTALL-ANDROID.md](docs/phone-bridge/INSTALL-ANDROID.md)
- Design, auto-reply rules, acceptance criteria: [docs/phone-bridge/DESIGN.md](docs/phone-bridge/DESIGN.md)
- Implementation handoff + merge gate: [docs/phone-bridge/HANDOFF.md](docs/phone-bridge/HANDOFF.md)

Android only. iOS does not allow third-party apps to read or send SMS.

## Deprecated: PC Phone Link / Setup.exe (1.x)

Superseded by the phone bridge (2026-10-07). No new 1.x versions will be published. Remove it from the front PC once the phone bridge passes its smoke test (`uninstall.ps1`, see the install doc).

The feed below stays live only so already-installed POS clients keep getting HTTP 200 instead of failing update checks. Do not bump it.

- Manifest: https://raw.githubusercontent.com/VIBEVENDIT/sms-bridge-channel/main/manifest.json
- CDN: https://cdn.jsdelivr.net/gh/VIBEVENDIT/sms-bridge-channel@main/manifest.json
- Commands: https://raw.githubusercontent.com/VIBEVENDIT/sms-bridge-channel/main/commands.json

The `v1.5.0` `sms-bridge-install.zip` Release referenced by `manifest.json` was never published (404), so installed clients stay on their current version.
