# USBPrint

USBPrint is a free, ad-free Android app for printing directly to USB printers. It uses USB host (OTG), so the printer does not need Wi-Fi or a computer. USBPrint runs on Android 8.0 or newer.

**[Download the latest APK](https://github.com/lunt251/USBPrint/releases/latest)**

## Get started

1. Download the `.apk` from GitHub Releases and open it on your Android device. If Android asks, allow installs from the browser or file manager you used to open it.
2. Plug a powered-on printer into your phone or tablet using a USB host/OTG adapter. Open USBPrint and grant USB access when prompted.
3. Use **Test page** on the printer card to check compatibility. To print your own files, open **Documents**, **Photos & images**, or **Web pages**. You can also use **Share → USBPrint** from another app.

> **Upgrading from a preview APK?** The public release uses a different signing key. Android won't install it over a debug build. Save anything you need before uninstalling the preview; uninstalling removes its app settings and print history.

## What you can print

- **Files:** PDFs, images, text, HTML, and EPUB. Opening DOCX, ODT, or RTF directly imports the text only; for the original layout, print from the source app or export to PDF.
- **Web pages:** Open a page in the built-in browser, optionally switch to Reader mode, then preview it before printing.
- **From other apps:** Enable USBPrint via **Settings → Print from other apps**. Apps that support Android printing can then send jobs through the system print dialog.

The preview lets you select pages, move and scale content, set paper sizes and dimensions, crop or straighten scans, clean up shadows, and export edited pages to PDF or images. USBPrint uses Material 3 Expressive and follows Android's wallpaper colors on supported devices.

## Printer compatibility

USBPrint works with USB printers that understand one of its built-in drivers: **Canon CAPT**, **PCL raster**, **PostScript**, or **ESC/POS**. Compatibility depends on the model and printer language, not just the brand shown on the card. Wi-Fi and Bluetooth printing, IPP-over-USB, and downloadable driver packs are not included.

A Canon LBP6000B printed successfully during development. Other CAPT profiles and other printer models may vary. Expand the printer card to see the detected model and driver, choose a different driver if needed, or use **Share report** to attach the details to a [compatibility issue](https://github.com/lunt251/USBPrint/issues/new).

If a CAPT printer runs out of paper, reload the tray and tap **Try again**. Cancelling stops USBPrint from sending more data, but the printer may still finish a page it has already buffered.

## Privacy

USBPrint has no ads, accounts, or analytics. File editing, rendering, and USB printing run on-device and work offline. The web browser uses the internet to load pages. You can use Android's system picker instead of giving USBPrint full file access for its built-in browser.

## Build from source

Use JDK 17, Gradle 8.14, and Android SDK Platform 36. The [GitHub Actions workflow](.github/workflows/build.yml) builds debug APKs and runs unit tests and lint. Build a debug APK locally with:

```sh
gradle assembleDebug testDebugUnitTest lintDebug
```

The APK is written to `app/build/outputs/apk/debug/`. For public release builds, CI provides an unsigned release APK for signing with the private release key. The public debug key in the repository is for test builds only.

## Credits and license

- USBPrint is licensed under [GPL-3.0-or-later](LICENSE).
- Canon CAPT support is a Kotlin port of [captdriver](https://github.com/mounaiban/captdriver). CI checks the Kotlin compressor against the original C implementation.
- Google Sans Flex and Roboto Flex are licensed under the SIL Open Font License; see the [bundled font notices](app/src/main/assets/licenses).
- Printer brand marks identify connected devices. They belong to their respective owners and do not imply endorsement.
