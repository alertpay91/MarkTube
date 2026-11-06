# MarkTube

**MarkTube** is a lightweight Android app for watching YouTube without ads, with background
playback and SponsorBlock. It's a small, focused browser built on Mozilla's
[GeckoView](https://mozilla.github.io/geckoview/) (the Firefox engine) that opens the mobile website
`m.youtube.com`. It contains no YouTube code and no modified YouTube app.

Created by **Marko Stevanović** ([@alertpay91](https://github.com/alertpay91)).

> 🇷🇸 **Ukratko:** MarkTube je mala Android aplikacija za gledanje YouTube-a bez reklama, sa
> puštanjem u pozadini i preskakanjem sponzorisanih delova. Aplikacija i ekstenzije se ažuriraju
> same. Preuzmi `MarkTube.apk` iz [Releases](../../releases/latest) i instaliraj.

## Features

- **No ads:** [uBlock Origin](https://addons.mozilla.org/firefox/addon/ublock-origin/) is installed
  automatically, and sponsored Shorts are skipped.
- **SponsorBlock:** [SponsorBlock](https://addons.mozilla.org/firefox/addon/sponsorblock/) skips
  sponsor segments inside videos.
- **Background and lock-screen playback:** a media notification with play/pause, next, previous
  and a seek bar.
- **Native feel:**
  - The back button closes menus, comments and dialogs first, then steps back through history.
  - In Shorts, back goes to the previous short.
  - A tab bar (Home / Shorts / Subscriptions / You) stays visible on Shorts.
- **Fullscreen:** turns landscape automatically.
- **Opens YouTube links** shared from other apps.
- **Updates itself:**
  - Browser extensions update from addons.mozilla.org on every start.
  - The app checks this repository's latest release and installs it in the background, over
    Wi-Fi and only while nothing is playing.
  - Android asks once to allow installs from MarkTube. Later updates are silent.
- **Automatic builds:** GitHub Actions rebuilds the app every week with the newest GeckoView, so
  the engine's security fixes keep arriving.

## Install

1. Download `MarkTube.apk` from the [latest release](../../releases/latest).
2. Open it on your phone and allow installing from your browser or file manager.
3. Sign in to your Google account inside the app if you want your subscriptions.

Requires Android 8.0+ on a 64-bit ARM (arm64-v8a) phone.

## Build it yourself

```sh
./gradlew assembleRelease
```

- Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`), which is not part of this repository.
- CI gets the key from repository secrets: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS` and
  `KEY_PASSWORD`.

## Disclaimer

MarkTube is an independent, non-commercial project and is **not affiliated with, endorsed by,
or sponsored by YouTube or Google**. "YouTube" is a trademark of Google LLC. MarkTube does not
download or redistribute videos and does not include or modify any YouTube software. It displays
the public mobile website the way any web browser does. Use it in line with the terms of the
services you use.

## License

- MarkTube's own code is released under the [MIT License](LICENSE).
- GeckoView is © Mozilla and licensed under the [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/).
- uBlock Origin, SponsorBlock and Video Background Play Fix are
  downloaded at runtime from addons.mozilla.org. They belong to their authors and keep their own
  licenses.
