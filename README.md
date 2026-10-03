# Lauschen - Audiobookshelf Player with UPnP casting

Lauschen is a fork of [Lissen](https://github.com/GrakovNe/lissen-android) by GrakovNe.

> [!WARNING]
> **Fork disclaimer:** This fork adds basic UPnP streaming, available via a cast icon at the top right of the cover.
> It was mostly developed by AI and has only been tested with a WiiM device and Android 17.

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

You can connect to a demo [Audiobookshelf](https://github.com/advplyr/audiobookshelf) instance through Lauschen:

URL: [https://demo.lissenapp.org/](https://demo.lissenapp.org/)

```
Username: demo
Password: demo
```

This instance is contains only Public Domain audiobooks from [LibriVox](https://librivox.org/)

## License

Lauschen is open-source and licensed under the MIT License, like the original Lissen. See the LICENSE file for more details.
