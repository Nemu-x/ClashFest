# Privacy Policy

ClashFest is free and open-source software (GPL-3.0). It is published by Nemu-x at no cost and is provided as is.

## What the app does with your data

- **No analytics, no crash reporting, no advertising.** The app contains no Google Play Services, Firebase, AppCenter or any other tracking library, and the developer runs no server that the app talks to.
- **Everything stays on your device.** Subscriptions, profiles, settings and logs are stored in the app's private storage and are never uploaded anywhere by the app.
- **Traffic goes where you point it.** When the VPN is on, your network traffic is routed to the proxy servers listed in the subscription you imported. Those servers belong to the operator you chose, not to the developer; their handling of your traffic is governed by that operator's policy.
- **Subscription requests identify the device to your operator.** When the app downloads or refreshes a subscription it sends, together with the request, a hashed device identifier (`x-hwid`, a SHA-256 of a random per-install value and the Android ID) and basic device facts (`x-device-os`, `x-ver-os`, `x-device-model`, `x-app-version`) so that operators can enforce their device limits. These headers go only to the subscription URL you configured.
- **Geo databases** (GeoIP / GeoSite / ASN) are downloaded from the mirrors listed in the app (GitHub, jsDelivr) when a rule needs them.
- **Update check (GitHub releases only).** Builds distributed through GitHub may check `api.github.com` for a newer release and, if you accept, download the APK. Builds distributed through F-Droid contain no update check.
- **Permissions.** `QUERY_ALL_PACKAGES` is used to list installed apps for per-app routing; `CAMERA` only when you open the QR scanner; `POST_NOTIFICATIONS` for the VPN status notification.

## Changes

This policy may be updated together with the app. Changes are published in this repository.

## Contact

Open an issue at https://github.com/Nemu-x/ClashFest/issues.
