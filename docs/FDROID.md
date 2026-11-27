# F-Droid packaging

USBPrint is published as GPL-3.0-or-later source. The Android app uses AndroidX, Jetpack Compose, and Kotlin; it does not require Google Play Services, Firebase, a proprietary printer driver, or an IDE. USB printing works offline. The web browser is optional and loads the URLs the user opens; its address bar also offers DuckDuckGo search.

## Build

From the source root, use JDK 17, Gradle 8.14, and Android SDK Platform 36:

```sh
gradle -PfdroidBuild=true assembleRelease lintRelease
```

The unsigned APK is `app/build/outputs/apk/release/app-release-unsigned.apk`. The `fdroidBuild` property excludes `app/src/wordmarks` because the redistribution terms for the supplied manufacturer artwork are not documented. Printer names are shown as text instead. The app icon and the OFL-licensed Google Sans Flex and Roboto Flex fonts are retained. Upstream font notices are bundled with the app.

The GitHub Actions release check builds this variant and checks that its resource table contains no manufacturer wordmarks. The upstream Fastlane listing is under [`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US). The workflow's `listing_screenshots` option captures the UI from a blank Android emulator using a synthetic document, without accessing a personal device.

## Draft metadata for `fdroiddata`

After the `v1.0.0` tag exists, this is a starting point for `metadata/com.usbprint.app.yml` in a separate `fdroiddata` checkout:

```yaml
Categories:
  - System
License: GPL-3.0-or-later
SourceCode: https://github.com/lunt251/USBPrint
IssueTracker: https://github.com/lunt251/USBPrint/issues
Changelog: https://github.com/lunt251/USBPrint/releases

RepoType: git
Repo: https://github.com/lunt251/USBPrint.git

Builds:
  - versionName: 1.0.0
    versionCode: 2
    commit: v1.0.0
    subdir: app
    gradle:
      - yes
    gradleprops:
      - fdroidBuild=true
    scandelete:
      - app/src/wordmarks
      - app/debug.keystore

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.0
CurrentVersionCode: 2
```

Validate the recipe with `fdroid lint com.usbprint.app` and `fdroid build com.usbprint.app` on an F-Droid build host before submitting it. This repository is not yet listed on F-Droid, and the metadata is not a claim of acceptance or verified reproducibility. F-Droid can sign its own build. Because this variant omits the logos, the standard upstream APK is not a byte-for-byte reference for it. A reproducible upstream-signed F-Droid release would need a signed text-only APK and successful reproducibility checks in the F-Droid build environment.
