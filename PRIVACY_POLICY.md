# Privacy Policy

**ClashFest for Android** · effective October 6, 2026 · [Русская версия](PRIVACY_POLICY.ru.md)

ClashFest is free and open-source software (GPL-3.0), a client for Clash Meta (mihomo) proxy
subscriptions. It is published by Nemu-x at no cost and is provided as is. In short: the app has
no accounts, no analytics, no ads, and the developer runs no server that collects your data.

## What stays on your device

- The subscriptions and profiles you add, your settings, routing rules and the list of apps you
  choose to route.
- Connection logs and statistics shown in the app. They stay on the device and leave it only if
  you export or share them yourself.

The developer receives none of this. Uninstalling the app or clearing its data removes it.

## What the app sends, and to whom

- **No analytics, no crash reporting, no advertising.** The app contains no Google Play Services,
  Firebase, AppCenter or any other tracking library.
- **Your proxy servers.** When the VPN is on, your network traffic is routed to the proxy servers
  listed in the subscription you imported. Those servers belong to the operator you chose, not to
  the developer; their handling of your traffic is governed by that operator's policy.
- **Your subscription provider.** When the app downloads or refreshes a subscription it sends,
  together with the request, a hashed device identifier (`x-hwid`, a SHA-256 of the app's
  package name and Android's per-app ID — never the raw ID) and basic device facts (`x-device-os`,
  `x-ver-os`, `x-device-model`, `x-app-version`) so that operators can enforce their device
  limits. These headers go only to the subscription URL you configured. If the operator enables
  branding, the app also downloads the operator's logo from the address the operator provides.
- **Rule and geodata files** (GeoIP / GeoSite / ASN and rule lists) are downloaded from the
  addresses in your profile or from the mirrors listed in the app (GitHub, jsDelivr).
- **DNS servers** set in your profile or settings receive the domain names you look up.
- **Latency tests** connect to the test address of your profile (by default
  `www.gstatic.com/generate_204`) through each proxy, only when a test runs.
- **Remote control (optional).** If you pair two of your own devices to control one from the
  other, they talk to each other directly over your local network; nothing goes through the
  developer.
- **Update check.** Builds distributed through GitHub may check `api.github.com` for a newer
  release and, if you accept, download the APK. Builds from Google Play are updated by Google
  Play, and builds from F-Droid contain no update check.

Every service above sees your IP address when the app connects to it, as any internet service does.

## Permissions

- **VPN service** routes your device's traffic through the servers of your subscription. This is
  the app's core function.
- **Notifications and foreground service** show that the connection is on and keep it running.
- **Query all packages** lists installed apps for per-app routing. The list is used only on the
  device and is never sent anywhere.
- **Camera** is used only when you open the QR scanner to add a subscription or pair a device.
  Images are processed on the device and are neither saved nor sent.
- **Start at boot** restores the connection after a restart, if you turn that on.
- **Network state and Wi-Fi multicast** let the app react to network changes and find a paired
  device on your local network.
- **Install packages** (GitHub builds only) installs an update you chose to download.

## Data we collect

None. The developer does not run analytics, crash reporting, advertising or tracking services, and
does not sell or share personal data.

## Children

ClashFest is not directed at children under 13 and does not knowingly collect data from anyone.

## Security

Subscriptions and settings are stored in the app's private storage on your device. Protect access
to your device: anyone who can open the app can see your subscriptions.

## Changes

If this policy changes, the new version is published in this repository with a new effective date.

## Contact

Open an issue at https://github.com/Nemu-x/ClashFest/issues.
