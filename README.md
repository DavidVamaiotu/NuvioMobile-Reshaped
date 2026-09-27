<div align="center">

  <img src="branding/nuvio-rs-logo.png" alt="Nuvio Reshaped" width="128" />

  <h1>Nuvio Reshaped</h1>

  <p>
    A community fork of <a href="https://github.com/NuvioMedia/NuvioMobile">Nuvio Mobile</a> for Android phones and tablets.
    <br />
    Everything you know from Nuvio, plus subtitles that sync themselves, instant seeking with previews, Live TV and a calmer, more cinematic player.
  </p>

  [Download](https://github.com/DavidVamaiotu/NuvioMobile-AutoSync/releases/latest) · [All releases](https://github.com/DavidVamaiotu/NuvioMobile-AutoSync/releases) · [TV version](https://github.com/DavidVamaiotu/NuvioTV-Reshaped) · [Official Nuvio](https://nuvio.tv)

</div>

## What's different from Nuvio

Nuvio Reshaped tracks official Nuvio closely and only adds on top. Every addition lives on its own page under **Settings > Nuvio Reshaped**, and the extras that change how Nuvio looks or behaves are off until you turn them on.

### Subtitles

- **Subtitle AutoSync.** When playback starts, your preferred add-on subtitle is compared against the stream's embedded subtitles and shifted into place automatically. Only confident matches are applied. Choose a Quick or Thorough search, set a tolerance, and tap *Try another reference* if the timing is still off.
- **Sync to audio.** When there's nothing to compare against, AutoSync can align the subtitle by listening to the dialogue. An optional on-device speech model (downloaded once, Wi-Fi recommended) makes this faster and more precise for English audio.
- **Secondary language.** AutoSync also works for a second subtitle language.
- **Bubble notifications.** Optional liquid-glass bubble that shows AutoSync progress and results at the bottom of the player instead of plain toasts.
- **Custom subtitle fonts.** Import any `.ttf` or `.otf` file and use it for subtitles.

### Player

- **Seek previews.** Thumbnails while you scrub, made on the device from the video you're already watching, with Seekr previews filling the gaps. No extra downloads, and no work while the film is playing.
- **Seek buffer.** Reads ahead to a temporary file on disk (256 MB, 512 MB or 1 GB) so jumping forward is instant. Nothing is kept once you close the player.
- **Volume boost.** The swipe volume bar goes up to 200% for quiet films, with a red tint above 100%. It resets when the player closes.

### Browsing

- **Pill navigation.** An optional floating liquid-glass pill for Home, Search, Library, Settings and your profile, which hides as you scroll.
- **Streams that fit your connection.** Learns your real playback speed and moves streams that are likely too heavy further down, keeping your add-on's order otherwise.
- **Live TV.** Add M3U, Xtream or Stalker playlists and watch live channels with a programme guide, in Nuvio's own player.

## Get it

Download the latest APK from [Releases](https://github.com/DavidVamaiotu/NuvioMobile-AutoSync/releases/latest). Nuvio Reshaped installs as **Nuvio RS**, next to the official app, so you can keep both.

Once installed, it checks this repository for updates and offers them in the app, on a stable or beta channel.

## Build from source

Android development requires Android Studio, a JDK and the Android SDK.

```bash
git clone -b subtitle-autosync https://github.com/DavidVamaiotu/NuvioMobile-AutoSync.git
cd NuvioMobile-AutoSync
./gradlew :androidApp:assembleFullDebug
```

`subtitle-autosync` is the fork's main branch. The app is built with Kotlin Multiplatform and Compose Multiplatform; Nuvio Reshaped targets Android only.

## Credits

Nuvio Reshaped is built on [Nuvio](https://nuvio.tv) by the [NuvioMedia](https://github.com/NuvioMedia) team, who do the real work on the app. It is not an official Nuvio release, so please report problems with this fork here rather than to Nuvio. If you enjoy it, consider [supporting Nuvio](https://nuvio.tv/support).

## License

[GNU General Public License v3.0](./LICENSE), the same as upstream Nuvio.
