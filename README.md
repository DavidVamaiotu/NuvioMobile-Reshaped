<div align="center">

  <img src="branding/nuvio-rs-logo.png" alt="NuvioTV Reshaped Mobile" width="128" />

  <h1>NuvioTV Reshaped — Mobile</h1>

  <p>
    The Android phone and tablet edition of <a href="https://github.com/DavidVamaiotu/NuvioTV-Reshaped">NuvioTV Reshaped</a>, a community fork of <a href="https://github.com/NuvioMedia/NuvioMobile">Nuvio Mobile</a>.
    <br />
    Adds subtitle AutoSync, seek previews and buffering, volume boost, pill navigation and Live TV.
  </p>

  [Download](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped/releases/latest) · [All releases](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped/releases) · [TV version](https://github.com/DavidVamaiotu/NuvioTV-Reshaped) · [Official Nuvio](https://nuvio.tv)

</div>

## About this fork

This repository contains the mobile edition of NuvioTV Reshaped, also known in the app as **Nuvio Reshaped** or **Nuvio RS**. For the Android TV app, use [NuvioTV Reshaped](https://github.com/DavidVamaiotu/NuvioTV-Reshaped). The features below apply to the mobile edition.

## Reshaped settings

Open **Settings > Nuvio Reshaped**. The options on this page are:

- **Sync with Google.** Sync Reshaped settings and Live TV with your phone and TVs. Choose settings sync and Live TV sync separately, or use **Sync now**. Buffer size and volume boost stay per device.
- **Live TV.** Enable the Live TV tab, where you can add M3U, Xtream or Stalker sources and browse channels with a programme guide.
- **Library calendar.** Enable a calendar of saved movies and episode releases with exact dates.
- **Subtitle AutoSync.** Align an add-on subtitle with embedded subtitles. Configure search thoroughness, timing tolerance and audio-based alignment, including the optional on-device English speech model.
- **AutoSync bubble notifications.** Show AutoSync progress and results in an optional bubble.
- **Custom subtitle font.** Import a `.ttf` or `.otf` font for subtitles, or reset to the default.
- **Volume boost.** Allow the player volume control to reach 200%. The boost resets when the player closes.
- **Seek buffer.** Choose a temporary disk buffer of 256 MB, 512 MB or 1 GB to reduce network waits when seeking within buffered video.
- **Seek previews.** Enable thumbnails while scrubbing through video.
- **Pill menu.** Enable the floating navigation menu.
- **Streams that fit your connection.** Use measured playback speed to move streams likely to exceed your connection speed further down the list.

## Get it

Download the latest APK from [Releases](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped/releases/latest). Nuvio Reshaped installs as **Nuvio RS**, next to the official app, so you can keep both.

Once installed, it checks this repository for updates and offers them in the app, on a stable or beta channel.

## Build from source

Android development requires Android Studio, a JDK and the Android SDK.

```bash
git clone -b subtitle-autosync https://github.com/DavidVamaiotu/NuvioMobile-Reshaped.git
cd NuvioMobile-Reshaped
./gradlew :androidApp:assembleFullDebug
```

`subtitle-autosync` is the fork's main branch. The app is built with Kotlin Multiplatform and Compose Multiplatform; Nuvio Reshaped targets Android only.

## Credits

Nuvio Reshaped is built on [Nuvio](https://nuvio.tv) by the [NuvioMedia](https://github.com/NuvioMedia) team, who do the real work on the app. It is not an official Nuvio release, so please report problems with this fork here rather than to Nuvio. If you enjoy it, consider [supporting Nuvio](https://nuvio.tv/support).

## License

[GNU General Public License v3.0](./LICENSE), the same as upstream Nuvio.

## Maintaining the fork README

`README.md` belongs to NuvioTV Reshaped. Keep its fork branding, platform details, downloads and upstream credits when updating it. The feature list must describe only controls present in **Settings > Nuvio Reshaped**. Do not add general Nuvio features or integrations from other settings pages. Do not replace it with upstream documentation during syncs, releases or branch merges.

Use `.github/scripts/merge-nuvio-upstream.sh <target> "<message>"` for upstream merges after fetching the target. It preserves this README and the fork workflows, including when upstream README changes would merge cleanly. The README branding workflow checks future pushes and pull requests for accidental reversions.
