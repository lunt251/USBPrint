# USBPrint

Fast, ad-free printing to USB printers from Android (USB-OTG). Native Material 3 Expressive UI.

- Detects USB printers (USB printer class 7) and identifies them via the IEEE 1284 Device ID.
- Streams print data directly over USB bulk transfers; cancelling stops immediately and soft-resets the printer.
- Open-source drivers (HPLIP, SpliX, ESC/P-R, Gutenprint, foo2zjs) and built-in encoders (PCL, PostScript, URF, ESC/POS, ZPL, ...).

## Building

Every push is built by GitHub Actions (`.github/workflows/build.yml`). Download the APK from the
**USBPrint-debug-apk** artifact of the latest run and sideload it.

Requires Android 8.0 (API 26) or newer with USB host (OTG) support.

## License

GPL-3.0-or-later. See `LICENSE`.
