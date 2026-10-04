# Privacy Policy

**Mikan for Android** · effective October 4, 2026 · [Русская версия](PRIVACY_POLICY.ru.md)

Mikan is a free, open-source client for Clash Meta (mihomo) proxy profiles. It is published by the Mikan project (github.com/getmikan) under the GNU GPL v3. This policy explains what the app does with your data. In short: Mikan has no accounts, no analytics, no ads and no servers of its own that collect your data.

## What stays on your device

- The profiles and subscription links you add, your settings, routing rules and the list of apps you choose to route.
- Connection logs and statistics shown in the app. They are kept on the device and leave it only if you export or share them yourself.

We do not receive any of this. Uninstalling the app or clearing its data removes it.

## What the app sends, and to whom

Mikan connects only where you or your profiles tell it to:

- **Your VPN or proxy servers.** When you connect, your internet traffic goes through the servers in the profile you chose. Those servers are run by your provider or by you, not by us; their operator's privacy policy applies to that traffic.
- **Your subscription provider.** When the app downloads or updates a subscription, it sends the request to the address of that subscription, with the app's name and version and these device details so that the provider can count your devices: a device identifier (a SHA-256 hash of Android's per-app ID and the app's package name, not the raw ID), the Android version and the device model. The provider is chosen by you; we do not receive these details.
- **Rule and geodata files** (lists used for routing) are downloaded from the addresses written in your profiles or settings, such as GitHub or jsDelivr.
- **DNS servers** set in your profile or settings, for example 1.1.1.1 or dns.google, receive the domain names you look up.
- **Update check.** Versions of the app distributed outside Google Play may ask GitHub (api.github.com) whether a new release is out. GitHub sees your IP address, as with any website. The Google Play version is updated through Google Play.

Every service above sees your IP address when the app connects to it, as any internet service does.

## Permissions

- **VPN service** routes your device's traffic through the servers of your profile. This is the app's core function.
- **Notifications and foreground service** show that the connection is on and keep it running.
- **Query all packages** lets you choose which apps go through the proxy and which do not. The list of installed apps is used only on the device and is never sent anywhere.
- **Camera** is used only when you scan a QR code to add a profile or pair a device. Images are processed on the device and are not saved or sent.
- **Start at boot** restores the connection after a restart, if you turn that on.
- **Network state** lets the app react when your network changes.

## Data we collect

None. The Mikan project does not run analytics, crash reporting, advertising or tracking services, and it does not sell or share personal data.

## Children

Mikan is not directed at children under 13 and does not knowingly collect data from anyone.

## Security

Profiles and settings are stored in the app's private storage on your device. Protect access to your device: anyone who can open the app can see your profiles.

## Changes

If this policy changes, the new version is published at this address with a new effective date.

## Contact

Questions about this policy: open an issue at [github.com/getmikan/MikanApp/issues](https://github.com/getmikan/MikanApp/issues) or write to the [Mikan Telegram channel](https://t.me/mikanvpn).
