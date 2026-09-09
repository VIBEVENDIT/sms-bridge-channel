# SMS bridge public update channel

Anonymous (no GitHub login) feed for DESKTOP-4CDEHLE.

## Live URLs

- Manifest: https://raw.githubusercontent.com/VIBEVENDIT/sms-bridge-channel/main/manifest.json
- Preferred hotel host: https://hotelat.com/sms-bridge/manifest.json
- Zip (after upload): https://hotelat.com/sms-bridge/sms-bridge-install.zip
- Zip (GitHub Release, after an org admin attaches the asset): https://github.com/VIBEVENDIT/sms-bridge-channel/releases/download/v1.3.0/sms-bridge-install.zip

The ops repo `hotel-at-gangnam-maknae` is private, so its Actions artifacts are not anonymous.

To finish publishing 1.3.0: create GitHub Release `v1.3.0` on this repo and attach `sms-bridge-install.zip` from the private repo workflow `sms-bridge-windows-release` (artifact `HotelAtGangnamSmsBridge-1.3.0-win-x64`). Then point `zipUrl` at that release asset. No secrets in this repo.
