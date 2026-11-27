<p align="center"><img src="docs/assets/usbprint.svg" width="96" height="96" alt=""></p>

<h1 align="center">USBPrint</h1>

<p align="center">Print directly to USB printers from Android.<br>No Wi-Fi printer, computer, account, or ads required.</p>

<p align="center"><a href="https://github.com/lunt251/USBPrint/releases"><strong>Download from GitHub</strong></a> &nbsp;&middot;&nbsp; Android 8.0+ &nbsp;&middot;&nbsp; <a href="LICENSE">GPL-3.0-or-later</a></p>

| <img src="docs/assets/usb.svg" width="36" height="36" alt=""><br>Connect directly | <img src="docs/assets/preview.svg" width="36" height="36" alt=""><br>Preview and edit | <img src="docs/assets/privacy.svg" width="36" height="36" alt=""><br>On-device printing |
| :--- | :--- | :--- |
| Connect a printer over USB OTG and try a test page. | Select pages, set exact dimensions, crop, and clean up scans. | Edit and print without uploading files or signing in. |

## Quick start

1. Download the APK from [GitHub Releases](https://github.com/lunt251/USBPrint/releases) and open it on Android. If prompted, allow installs from the app you used to open the APK.
2. Plug a powered-on printer into your Android device using a USB host (OTG) adapter. Open USBPrint and allow USB access.
3. Tap **Test page** on the printer card. Then choose a file or web page in USBPrint, or use **Share → USBPrint** from another app.

> **Upgrading from a preview APK?** The public release uses a different signing key. Android won't install it over a debug build. Save anything you need before uninstalling the preview; uninstalling removes its settings and print history.

## What you can print

| Source | Support |
| :--- | :--- |
| **Local files** | PDFs, images, text, HTML, and EPUB. Open DOCX, ODT, and RTF as text, or print from their source app for the original layout. |
| **Web pages** | Open a page in the built-in browser, optionally turn on Reader mode, then preview it before printing. |
| **Other apps** | Turn on **Settings → Print from other apps** to use USBPrint in Android's system print dialog. |

The preview includes page selection, crop and perspective correction, scan cleanup, custom paper sizes, and PDF or image export. Material 3 Expressive styling follows Android's wallpaper colors on supported devices.

## Printer compatibility

USBPrint ships built-in drivers for **Canon CAPT**, **PCL raster**, **PostScript**, and **ESC/POS**. Your printer must support a compatible language; a logo on the printer card doesn't guarantee it. Wi-Fi, Bluetooth, IPP-over-USB, and downloadable driver packs are not included.

A **Canon LBP6000B** printed successfully during development. Other CAPT profiles and printer models may vary. If printing fails, expand the printer card to verify the chosen driver or try a manual override. Use **Share report** when filing a [compatibility issue](https://github.com/lunt251/USBPrint/issues/new); remove any serial number you don't want to share.

For a CAPT paper-out error, load paper and tap **Try again**. Cancelling stops the app from sending more data, but a printer may finish a page it already buffered.

## Privacy

USBPrint has no ads, accounts, or analytics. Editing, rendering, and USB printing work offline. The built-in browser loads the sites you open and uses DuckDuckGo for address-bar searches. Android's system picker is available if you prefer not to grant full file access.

## Build from source

Use JDK 17, Gradle 8.14, and Android SDK Platform 36:

```sh
gradle assembleDebug testDebugUnitTest lintDebug
```

Debug APKs are available as [GitHub Actions](https://github.com/lunt251/USBPrint/actions) artifacts. Release builds are built from source in CI and signed separately from the public debug key. To build the text-only variant designed for F-Droid, see the [packaging notes](docs/FDROID.md). USBPrint is not currently listed on F-Droid.

## Credits and license

- USBPrint is licensed under [GPL-3.0-or-later](LICENSE).
- Canon CAPT support is a Kotlin port of [captdriver](https://github.com/mounaiban/captdriver). CI checks the Kotlin compressor against the original C implementation.
- [Google Sans Flex](https://github.com/googlefonts/googlesans-flex) and [Roboto Flex](https://github.com/TypeNetwork/Roboto-Flex) are licensed under the SIL Open Font License; see the [bundled notices](app/src/main/assets/licenses).
- Manufacturer marks in the standard build identify connected devices. They belong to their respective owners and do not imply endorsement.
