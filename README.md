# SMS bridge public channel

Public docs + update feed for the Hotel at Gangnam SMS bridge. Do not put secrets here.

## Current: phone bridge (v3)

A small Android app on the hotel phone (AtGangnam SMS Bridge) syncs every inbound and outbound SMS/MMS to Slack `#lounge-호텔앳강남` and sends policy-based auto-replies, in the same format as the hotel mail handoff (`*[문자수신]*`, `:loading:`, `:완료:`). Release APKs are published on this repo's Releases (`android-v*`).

| Doc | |
| --- | --- |
| [INSTALL-ANDROID.md](docs/phone-bridge/INSTALL-ANDROID.md) | Install on the hotel phone (Korean) |
| [DESIGN.md](docs/phone-bridge/DESIGN.md) | Architecture, exactly-once sync, auth, state |
| [LOUNGE-FORMAT.md](docs/phone-bridge/LOUNGE-FORMAT.md) | Slack message format spec |
| [AUTO-REPLY.md](docs/phone-bridge/AUTO-REPLY.md) | Mail rule mapping, policy KB, guards |
| [ACCEPTANCE.md](docs/phone-bridge/ACCEPTANCE.md) | Acceptance criteria, real-phone QA, release gates |
| [HANDOFF.md](docs/phone-bridge/HANDOFF.md) | Implementation DO list + merge gate |

Android only. iOS does not allow third-party apps to read or send SMS.

## Deprecated: PC Phone Link / Setup.exe (1.x)

Superseded by the phone bridge (2026-10-07). No new 1.x versions will be published. Remove it from the front PC after go-live (`uninstall.ps1`, see the install doc).

The feed below stays live only so already-installed POS clients keep getting HTTP 200 instead of failing update checks. Do not bump it.

- Manifest: https://raw.githubusercontent.com/VIBEVENDIT/sms-bridge-channel/main/manifest.json
- CDN: https://cdn.jsdelivr.net/gh/VIBEVENDIT/sms-bridge-channel@main/manifest.json
- Commands: https://raw.githubusercontent.com/VIBEVENDIT/sms-bridge-channel/main/commands.json

The `v1.5.0` `sms-bridge-install.zip` Release referenced by `manifest.json` was never published (404), so installed clients stay on their current version.
