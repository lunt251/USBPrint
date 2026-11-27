#!/usr/bin/env bash
# Screenshot an isolated CI emulator. Never use a connected personal device.
set -euo pipefail
apk=${1:?debug APK path required}
output=${2:?screenshot directory required}
image='system-images;android-34;default;x86_64'
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
shopt -s nullglob
managers=("$sdk"/cmdline-tools/*/bin/sdkmanager)
shopt -u nullglob
if ((${#managers[@]} == 0)); then
  printf 'No Android SDK command-line tools under %s\n' "$sdk" >&2
  exit 1
fi
cli=$(dirname "${managers[-1]}")
work=$(mktemp -d)
export ANDROID_AVD_HOME="$work/avd"
mkdir -p "$output" "$ANDROID_AVD_HOME"
"$cli/sdkmanager" --sdk_root="$sdk" --install emulator "$image"
printf 'no\n' | "$cli/avdmanager" create avd --name usbprint-listing --package "$image"
sudo chown "$(id -u):$(id -g)" /dev/kvm
"$sdk/emulator/emulator" -avd usbprint-listing -port 5556 -no-window -no-snapshot \
  -no-boot-anim -no-audio -gpu swiftshader_indirect > "$work/emulator.log" 2>&1 &
emulator_pid=$!
cleanup() {
  if [[ $? -ne 0 ]]; then cat "$work/emulator.log" >&2; fi
  kill "$emulator_pid" 2>/dev/null || true
}
trap cleanup EXIT
export ANDROID_SERIAL=emulator-5556
timeout 240 bash -c 'adb wait-for-device; until [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r") == 1 ]]; do sleep 2; done'
adb shell wm size 1080x2160
adb shell wm density 440
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell cmd uimode night yes
adb shell input keyevent 82
adb install "$apk"
adb shell am start -W -n com.usbprint.app/.MainActivity
sleep 3
adb exec-out screencap -p > "$output/1.png"

# Generate a simple, original two-page PDF so the listing shows the real document preview.
python3 - "$work/USBPrint-demo.pdf" <<'PY'
import pathlib, sys

objects = [
    b'<< /Type /Catalog /Pages 2 0 R >>',
    b'<< /Type /Pages /Count 2 /Kids [4 0 R 6 0 R] >>',
    b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
]
for i, heading in enumerate(('Print layout sample', 'Ready to print')):
    objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents {5 + i * 2} 0 R >>'.encode())
    stream = (f'0.35 0.19 0.04 rg 50 766 495 8 re f BT /F1 38 Tf 50 700 Td ({heading}) Tj ET '
              '0 g BT /F1 18 Tf 50 647 Td (USBPrint demonstration document) Tj ET '
              '0.92 0.91 0.89 rg 50 450 495 125 re f 0 g '
              'BT /F1 18 Tf 68 531 Td (Paper size: A4) Tj 0 -32 Td (Orientation: Portrait) Tj ET').encode()
    objects.append(f'<< /Length {len(stream)} >>\nstream\n'.encode() + stream + b'\nendstream')
result = bytearray(b'%PDF-1.4\n')
offsets = [0]
for i, obj in enumerate(objects, 1):
    offsets.append(len(result))
    result += f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n'
xref = len(result)
result += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode()
for offset in offsets[1:]:
    result += f'{offset:010d} 00000 n \n'.encode()
result += f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
pathlib.Path(sys.argv[1]).write_bytes(result)
PY
adb push "$work/USBPrint-demo.pdf" /sdcard/Download/USBPrint-demo.pdf
adb shell appops set --uid com.usbprint.app MANAGE_EXTERNAL_STORAGE allow
adb shell am start -W -a android.intent.action.VIEW -t application/pdf \
  -d file:///sdcard/Download/USBPrint-demo.pdf -n com.usbprint.app/.MainActivity
sleep 4
adb shell uiautomator dump /sdcard/usbprint-listing.xml
adb pull /sdcard/usbprint-listing.xml "$work/window.xml"
if ! grep -Fq 'text="Edit"' "$work/window.xml"; then
  printf 'The demo document did not reach the preview\n' >&2
  exit 1
fi
adb exec-out screencap -p > "$output/2.png"
