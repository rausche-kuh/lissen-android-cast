# Lissen - Clean Audiobookshelf Player (UPnP cast fork)

Fork of [GrakovNe/lissen-android](https://github.com/GrakovNe/lissen-android).

> [!WARNING]
> **Fork disclaimer:** This fork adds basic UPnP streaming, available via a cast icon at the top right of the cover.
> It was mostly developed by AI and has only been tested with a WiiM device and Android 17.

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.rauschekuh.lauschen%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Frausche-kuh%2Flissen-android-cast%22%2C%22author%22%3A%22rausche-kuh%22%2C%22name%22%3A%22Lauschen%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%7D%22%7D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="89" align="middle"></a>
</p>

### Features

- Beautiful Interface: Intuitive design that makes browsing and listening to your audiobooks easy and enjoyable.
- Cloud Sync: Automatically syncs your audiobook progress across devices, keeping everything up to date no matter where you are.
- Streaming Support: Stream your audiobooks directly from the cloud without needing to download them first.
- Offline Listening: Download audiobooks to listen offline, ideal for those who want to access their collection without an internet connection.

### Screenshots

<p align="center">
  <img src="https://github.com/GrakovNe/lissen-android/raw/main/metadata/en-US/images/phoneScreenshots/2.png" alt="Screenshot 2" width="160">
  <img src="https://github.com/GrakovNe/lissen-android/raw/main/metadata/en-US/images/phoneScreenshots/3.png" alt="Screenshot 3" width="160">
  <img src="https://github.com/GrakovNe/lissen-android/raw/main/metadata/en-US/images/phoneScreenshots/4.png" alt="Screenshot 4" width="160">
  <img src="https://github.com/GrakovNe/lissen-android/raw/main/metadata/en-US/images/phoneScreenshots/5.png" alt="Screenshot 5" width="160">
  <img src="https://github.com/GrakovNe/lissen-android/raw/main/metadata/en-US/images/phoneScreenshots/6.png" alt="Screenshot 6" width="160">
</p>

### Building

1. Clone the repository:

```
git clone https://github.com/rausche-kuh/lissen-android-cast.git
```

2. Setup the SDK into your local.properties file

```
nano local.properties
```

3. Open the project in Android Studio or build it manually

```
./gradlew assembleDebug # Debug Build
./gradlew assembleRelease # Release Build
```

4. Build and run the app on an Android device or emulator.

### Demo Environment

You can connect to a demo [Audiobookshelf](https://github.com/advplyr/audiobookshelf) instance through the Lissen app:

URL: [https://demo.lissenapp.org/](https://demo.lissenapp.org/)

```
Username: demo
Password: demo
```

This instance is contains only Public Domain audiobooks from [LibriVox](https://librivox.org/)

## License

Lissen is open-source and licensed under the MIT License. See the LICENSE file for more details.
